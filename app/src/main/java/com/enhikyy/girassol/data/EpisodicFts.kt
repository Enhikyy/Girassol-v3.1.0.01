package com.enhikyy.girassol.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.enhikyy.girassol.core.memory.EpisodicIndex
import com.enhikyy.girassol.core.world.WorldState

data class FtsHit(val memoryId: Long, val owner: String, val layer: String, val content: String, val tick: Long, val score: Double)

/**
 * Índice textual das memórias episódicas no SQLite WAL (Estágio 2 lexical do RRF no
 * aparelho). Usa FTS5 com bm25() quando o SQLite do sistema foi compilado com FTS5;
 * caso contrário cai para FTS4 e reordena os candidatos com o BM25 do [EpisodicIndex]
 * (mesma fórmula k1 = 1,2, b = 0,75 usada na simulação).
 */
object EpisodicFts {
    const val TABLE = "episodic_memory_fts"
    private const val MAX_ROWS_PER_CITIZEN = 64

    /** Cria a tabela virtual; retorna "fts5" ou "fts4". */
    fun ensure(db: SQLiteDatabase): String {
        detect(db)?.let { return it }
        return try {
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS $TABLE USING fts5(owner, layer, content, tags, memory_id UNINDEXED, tick UNINDEXED, tokenize='unicode61 remove_diacritics 2')")
            "fts5"
        } catch (e: SQLiteException) {
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS $TABLE USING fts4(owner, layer, content, tags, memory_id, tick, notindexed=memory_id, notindexed=tick, tokenize=unicode61)")
            "fts4"
        }
    }

    private fun detect(db: SQLiteDatabase): String? =
        db.rawQuery("SELECT sql FROM sqlite_master WHERE name = ?", arrayOf(TABLE)).use { c ->
            if (!c.moveToFirst()) null else if (c.getString(0).contains("fts5", ignoreCase = true)) "fts5" else "fts4"
        }

    fun module(db: SQLiteDatabase): String = detect(db) ?: ensure(db)

    /** Linhas de projeção (substituídas a cada snapshot pelo escritor único). */
    fun rows(state: WorldState): List<ContentValues> = state.aliveCitizens().flatMap { c ->
        c.memories.sortedByDescending { it.createdTick }.take(MAX_ROWS_PER_CITIZEN).map { m ->
            ContentValues().apply {
                put("owner", c.id); put("layer", m.layer.name); put("content", m.content)
                put("tags", m.tags.sorted().joinToString(" ")); put("memory_id", m.id); put("tick", m.createdTick)
            }
        }
    }

    fun replaceAll(db: SQLiteDatabase, rows: List<ContentValues>) {
        ensure(db)
        db.delete(TABLE, null, null)
        for (r in rows) db.insert(TABLE, null, r)
    }

    /** Converte texto livre em consulta MATCH segura (termos OR, sem operadores do usuário). */
    fun matchQuery(text: String): String =
        EpisodicIndex.terms(text).filter { !it.startsWith("#") }.distinct().take(8)
            .joinToString(" OR ") { "\"" + it.replace("\"", "") + "\"" }

    fun search(db: SQLiteDatabase, text: String, owner: String? = null, limit: Int = 10): List<FtsHit> {
        val q = matchQuery(text)
        if (q.isEmpty()) return emptyList()
        val mod = module(db)
        val where = if (owner != null) " AND owner = ?" else ""
        val args = if (owner != null) arrayOf(q, owner) else arrayOf(q)
        return if (mod == "fts5") {
            db.rawQuery("SELECT memory_id, owner, layer, content, tick, bm25($TABLE) AS s FROM $TABLE WHERE $TABLE MATCH ?$where ORDER BY s LIMIT $limit", args).use { c ->
                buildList { while (c.moveToNext()) add(FtsHit(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), -c.getDouble(5))) }
            }
        } else {
            val cand = db.rawQuery("SELECT memory_id, owner, layer, content, tick FROM $TABLE WHERE $TABLE MATCH ?$where LIMIT 200", args).use { c ->
                buildList { while (c.moveToNext()) add(FtsHit(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), 0.0)) }
            }
            if (cand.isEmpty()) return emptyList()
            val docs = cand.map { EpisodicIndex.terms(it.content) }
            val scores = EpisodicIndex.bm25(EpisodicIndex.terms(text), docs, docs)
            cand.mapIndexed { i, h -> h.copy(score = scores[i]) }.sortedByDescending { it.score }.take(limit)
        }
    }
}
