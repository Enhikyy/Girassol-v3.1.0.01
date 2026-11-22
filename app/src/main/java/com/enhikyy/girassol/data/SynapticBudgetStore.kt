package com.enhikyy.girassol.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * Persistência do Orçamento Sináptico: tabela `synaptic_budget_state` no SQLite WAL (fonte
 * primária) + arquivo `synaptic/budget_D.grsb` gravado atomicamente (tmp + rename) como cópia
 * de segurança. Todo bloco é validado por [GirassolRecordCodec.inspectGrsb] antes de ser aceito.
 */
class SynapticBudgetStore(private val context: Context) {
    private val dir: File get() = File(context.filesDir, "synaptic").apply { mkdirs() }
    private fun file(dim: Int) = File(dir, "budget_$dim.grsb")

    fun save(dim: Int, blob: ByteArray, tasks: Int, rank: Int): Boolean = runCatching {
        val info = GirassolRecordCodec.inspectGrsb(blob)
        require(info.dim == dim) { "bloco D=${info.dim} gravado como D=$dim" }
        val w = GirassolDatabase.get(context).writableDatabase
        w.beginTransaction()
        try {
            w.insertWithOnConflict("synaptic_budget_state", null, ContentValues().apply {
                put("dim", dim); put("blob", blob); put("checksum", info.checksumHex)
                put("tasks", tasks); put("protected_rank", rank); put("updated_utc", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        val tmp = File(dir, "budget_$dim.grsb.tmp")
        tmp.outputStream().use { it.write(blob); it.fd.sync() }
        check(tmp.renameTo(file(dim))) { "rename do .grsb falhou" }
        true
    }.onFailure { Log.w(TAG, "falha ao persistir o Orçamento Sináptico", it) }.getOrDefault(false)

    /** Último bloco válido para D (SQLite primeiro; arquivo como reserva). */
    fun load(dim: Int): ByteArray? {
        val fromDb = runCatching {
            GirassolDatabase.get(context).readableDatabase
                .rawQuery("SELECT blob FROM synaptic_budget_state WHERE dim = ?", arrayOf(dim.toString())).use { c ->
                    if (c.moveToFirst()) c.getBlob(0) else null
                }
        }.getOrNull()
        for (candidate in listOf(fromDb, runCatching { file(dim).takeIf { it.exists() }?.readBytes() }.getOrNull())) {
            if (candidate == null) continue
            val ok = runCatching { GirassolRecordCodec.inspectGrsb(candidate).dim == dim }.getOrDefault(false)
            if (ok) return candidate
        }
        return null
    }

    fun clear(dim: Int) {
        runCatching { GirassolDatabase.get(context).writableDatabase.delete("synaptic_budget_state", "dim = ?", arrayOf(dim.toString())) }
        file(dim).delete()
    }

    private companion object { const val TAG = "SynapticBudgetStore" }
}
