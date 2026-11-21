package com.enhikyy.girassol.data

import android.content.ContentValues
import android.content.Context
import android.util.Log
import com.enhikyy.girassol.core.world.CanonicalEvent
import com.enhikyy.girassol.core.world.LedgerEntry
import com.enhikyy.girassol.core.world.RsiLedgerRecord
import com.enhikyy.girassol.core.world.StressEventRecord
import com.enhikyy.girassol.core.world.WorldSnapshotCodec
import com.enhikyy.girassol.core.world.WorldState
import com.enhikyy.girassol.core.world.WorldStateHashChain
import java.io.File
import java.util.concurrent.Executors

/**
 * Escritor único: todas as gravações em SQLite e no arquivo `world_snapshot.bin`
 * passam por uma única thread, em transações. A simulação nunca bloqueia em disco.
 */
class PersistenceWriter(private val context: Context) {
    private val db = GirassolDatabase.get(context)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "girassol-writer").apply { isDaemon = true } }

    val snapshotFile: File get() = File(context.filesDir, SNAPSHOT_FILE)
    private val backupFile: File get() = File(context.filesDir, "$SNAPSHOT_FILE.bak")

    fun submitTickBatch(events: List<CanonicalEvent>, ledger: List<LedgerEntry>) {
        if (events.isEmpty() && ledger.isEmpty()) return
        executor.execute {
            runCatching {
                val w = db.writableDatabase
                w.beginTransaction()
                try {
                    for (e in events) {
                        w.insertWithOnConflict("canonical_events", null, ContentValues().apply {
                            put("seq", e.seq); put("world_tick", e.tick); put("rules_version", e.rulesVersion)
                            put("event_type", e.type); put("actor_id", e.actorId); put("payload", e.payload)
                            put("prev_hash", e.prevHash); put("event_hash", e.hash)
                        }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
                    }
                    for (l in ledger) {
                        w.insert("double_entry_ledger", null, ContentValues().apply {
                            put("world_tick", l.tick); put("from_account", l.fromAccount); put("to_account", l.toAccount)
                            put("resource_type", l.resourceType); put("quantity", l.quantity); put("canonical_event_hash", l.canonicalEventHash)
                        })
                    }
                    w.setTransactionSuccessful()
                } finally {
                    w.endTransaction()
                }
            }.onFailure { Log.e(TAG, "falha ao gravar lote", it) }
        }
    }

    fun submitRsiRecord(r: RsiLedgerRecord) = executor.execute {
        runCatching {
            db.writableDatabase.insert("rsi_promotion_ledger", null, ContentValues().apply {
                put("world_tick", r.tick); put("proposer_agent_id", r.proposerAgentId)
                put("baseline_hash", r.decision.baselineHash); put("candidate_hash", r.decision.candidateHash)
                put("promoted", if (r.decision.promoted) 1 else 0); put("net_fitness_gain", r.decision.netFitnessGain)
                put("brier_before", r.brierBefore); put("brier_after", r.brierAfter)
                put("reason", r.decision.reason + " | " + r.rationale); put("created_utc", System.currentTimeMillis())
            })
        }.onFailure { Log.e(TAG, "falha ao gravar RSI", it) }
    }

    /** Conversa permanente (aba Chat): cada mensagem também está no .grs (WorldState.chat). */
    fun submitChat(seed: Long, messages: List<com.enhikyy.girassol.core.world.ChatMessage>) {
        if (messages.isEmpty()) return
        executor.execute {
            runCatching {
                val w = db.writableDatabase
                w.beginTransaction()
                try {
                    for (m in messages) w.insertWithOnConflict("chat_messages", null, ContentValues().apply {
                        put("id", m.id); put("world_seed", seed); put("world_tick", m.tick); put("wall_clock_ms", m.wallClockMs)
                        put("citizen_id", m.citizenId); put("author", m.author); put("author_name", m.authorName)
                        put("text", m.text); put("engine", m.engine)
                    }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
                    w.setTransactionSuccessful()
                } finally { w.endTransaction() }
            }.onFailure { Log.e(TAG, "falha ao gravar chat", it) }
        }
    }

    /** Mensagens gravadas de um mundo (usado para recompor o chat se o snapshot for anterior a elas). */
    fun loadChat(seed: Long, afterId: Long): List<com.enhikyy.girassol.core.world.ChatMessage> = runCatching {
        val out = ArrayList<com.enhikyy.girassol.core.world.ChatMessage>()
        db.readableDatabase.rawQuery(
            "SELECT id, world_tick, wall_clock_ms, citizen_id, author, author_name, text, engine FROM chat_messages " +
                "WHERE world_seed = ? AND id > ? ORDER BY id ASC LIMIT 2000", arrayOf(seed.toString(), afterId.toString())
        ).use { c ->
            while (c.moveToNext()) out += com.enhikyy.girassol.core.world.ChatMessage(c.getLong(0), c.getLong(1), c.getLong(2),
                if (c.isNull(3)) null else c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7))
        }
        out
    }.getOrDefault(emptyList())

    fun submitStressEvent(s: StressEventRecord) = executor.execute {
        runCatching {
            db.writableDatabase.insert("stress_events", null, ContentValues().apply {
                put("world_tick", s.tick); put("kind", s.kind); put("description", s.description); put("created_utc", System.currentTimeMillis())
            })
        }
    }

    /**
     * Grava o snapshot binário (já codificado na thread da simulação) de forma atômica
     * (arquivo temporário + rename, mantendo o anterior como .bak) e atualiza as
     * tabelas de projeção (crenças, autômatos, maestria, Voyager, ADICO).
     */
    fun submitSnapshot(bytes: ByteArray, meta: SnapshotMeta, projection: ProjectionRows) = executor.execute {
        runCatching {
            val tmp = File(context.filesDir, "$SNAPSHOT_FILE.tmp")
            tmp.outputStream().use { it.write(bytes); it.fd.sync() }
            if (snapshotFile.exists()) snapshotFile.copyTo(backupFile, overwrite = true)
            check(tmp.renameTo(snapshotFile)) { "rename do snapshot falhou" }
            val w = db.writableDatabase
            w.beginTransaction()
            try {
                w.insert("world_snapshots", null, ContentValues().apply {
                    put("world_tick", meta.tick); put("seed", meta.seed); put("rules_version", meta.rulesVersion)
                    put("state_sha256", meta.stateSha256); put("last_event_hash", meta.lastEventHash)
                    put("size_bytes", bytes.size); put("file_name", SNAPSHOT_FILE); put("created_utc", System.currentTimeMillis())
                })
                projection.writeTo(w)
                // retenção: mantém os últimos 200 mil eventos canônicos e 500 snapshots de metadados
                w.execSQL("DELETE FROM canonical_events WHERE seq < (SELECT MAX(seq) - 200000 FROM canonical_events)")
                w.execSQL("DELETE FROM double_entry_ledger WHERE entry_id < (SELECT MAX(entry_id) - 200000 FROM double_entry_ledger)")
                w.execSQL("DELETE FROM world_snapshots WHERE id < (SELECT MAX(id) - 500 FROM world_snapshots)")
                w.setTransactionSuccessful()
            } finally {
                w.endTransaction()
            }
        }.onFailure { Log.e(TAG, "falha ao gravar snapshot", it) }
    }

    /** Carrega o snapshot mais recente válido (tenta o .bak se o principal estiver corrompido). */
    fun loadLatestSnapshot(): WorldState? {
        for (f in listOf(snapshotFile, backupFile)) {
            if (!f.exists()) continue
            val state = runCatching { WorldSnapshotCodec.decode(f.readBytes()) }
                .onFailure { Log.w(TAG, "snapshot inválido em ${f.name}", it) }.getOrNull()
            if (state != null) return state
        }
        return null
    }

    fun deleteAllSnapshots() = executor.execute {
        snapshotFile.delete(); backupFile.delete()
        runCatching {
            val w = db.writableDatabase
            listOf("canonical_events", "double_entry_ledger", "world_snapshots", "rsi_promotion_ledger", "stress_events",
                "subjective_beliefs", "tsetlin_automata_blobs", "bkt_fsrs_mastery", "voyager_skill_library", "adico_institutions",
                "ecological_niche_structures", "chat_messages", EpisodicFts.TABLE).forEach { w.delete(it, null, null) }
        }
    }

    /** Busca textual (FTS5 bm25 ou FTS4 + BM25 Kotlin) nas memórias projetadas. */
    fun searchMemories(text: String, owner: String? = null, limit: Int = 10): List<FtsHit> = runCatching {
        EpisodicFts.search(db.readableDatabase, text, owner, limit)
    }.onFailure { Log.w(TAG, "busca FTS falhou", it) }.getOrDefault(emptyList())

    fun ftsModule(): String = runCatching { EpisodicFts.module(db.writableDatabase) }.getOrDefault("-")

    fun countRows(table: String): Long = runCatching {
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    }.getOrDefault(0L)

    /**
     * Verifica a cadeia SHA-256 gravada no SQLite WAL (últimos [limit] eventos): recalcula cada
     * `event_hash`, confere o encadeamento `prev_hash` e a cabeça contra o estado vivo.
     */
    fun verifyHashChain(limit: Int = 5000, liveHeadHash: String? = null, liveSeq: Long? = null): WorldStateHashChain.Report = runCatching {
        val rows = ArrayList<CanonicalEvent>()
        db.readableDatabase.rawQuery(
            "SELECT seq, world_tick, rules_version, event_type, actor_id, payload, prev_hash, event_hash FROM " +
                "(SELECT * FROM canonical_events ORDER BY seq DESC LIMIT ?) ORDER BY seq ASC", arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) rows += CanonicalEvent(c.getLong(0), c.getLong(1), c.getInt(2), c.getString(3),
                if (c.isNull(4)) null else c.getString(4), c.getString(5), c.getString(6), c.getString(7))
        }
        val r = WorldStateHashChain.verify(rows)
        if (r.ok && liveHeadHash != null && rows.isNotEmpty() && rows.last().seq == liveSeq && r.headHash != liveHeadHash)
            r.copy(ok = false, firstBadSeq = liveSeq, reason = "cabeça gravada ≠ lastEventHash vivo")
        else r
    }.getOrElse { WorldStateHashChain.Report(false, 0, null, "erro: ${it.message}", "") }

    fun flushBlocking() {
        val latch = java.util.concurrent.CountDownLatch(1)
        executor.execute { latch.countDown() }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    companion object {
        const val SNAPSHOT_FILE = "world_snapshot.bin"
        private const val TAG = "GirassolPersist"
    }
}

data class SnapshotMeta(val tick: Long, val seed: Long, val rulesVersion: Int, val stateSha256: String, val lastEventHash: String)
