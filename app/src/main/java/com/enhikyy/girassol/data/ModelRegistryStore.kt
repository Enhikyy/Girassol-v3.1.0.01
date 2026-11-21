package com.enhikyy.girassol.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log

/** Linha da tabela `registered_models` (SQLite WAL): o .gguf importado e o fundador vinculado. */
data class RegisteredModel(
    val key: String,
    val path: String,
    val bytes: Long,
    val sha256Head: String,
    val founderId: String?,
    val founderName: String?,
    val founderSex: String?,
    val active: Boolean
)

/**
 * Registro fixo dos modelos .gguf importados. Sobrevive ao fechamento do app: ao reabrir,
 * o LocalLlmManager recarrega a lista e o SimulationHub restaura (ou recria) os fundadores.
 */
object ModelRegistryStore {
    private const val TAG = "GirassolModels"

    private fun db(context: Context) = GirassolDatabase.get(context)

    fun list(context: Context): List<RegisteredModel> = runCatching {
        val out = ArrayList<RegisteredModel>()
        db(context).readableDatabase.rawQuery(
            "SELECT model_key, file_path, size_bytes, sha256_head, founder_id, founder_name, founder_sex, active FROM registered_models ORDER BY imported_utc, model_key",
            null
        ).use { c ->
            while (c.moveToNext()) out += RegisteredModel(c.getString(0), c.getString(1), c.getLong(2), c.getString(3),
                if (c.isNull(4)) null else c.getString(4), if (c.isNull(5)) null else c.getString(5),
                if (c.isNull(6)) null else c.getString(6), c.getInt(7) != 0)
        }
        out
    }.onFailure { Log.w(TAG, "falha ao ler registered_models", it) }.getOrDefault(emptyList())

    fun upsert(context: Context, m: RegisteredModel) {
        runCatching {
            db(context).writableDatabase.insertWithOnConflict("registered_models", null, ContentValues().apply {
                put("model_key", m.key); put("file_path", m.path); put("size_bytes", m.bytes); put("sha256_head", m.sha256Head)
                put("founder_id", m.founderId); put("founder_name", m.founderName); put("founder_sex", m.founderSex)
                put("active", if (m.active) 1 else 0); put("imported_utc", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }.onFailure { Log.w(TAG, "falha ao gravar modelo", it) }
    }

    fun setActive(context: Context, key: String, active: Boolean) {
        runCatching {
            db(context).writableDatabase.update("registered_models", ContentValues().apply { put("active", if (active) 1 else 0) },
                "model_key = ?", arrayOf(key))
        }
    }

    fun bindFounder(context: Context, key: String, founderId: String, name: String, sex: String) {
        runCatching {
            db(context).writableDatabase.update("registered_models", ContentValues().apply {
                put("founder_id", founderId); put("founder_name", name); put("founder_sex", sex)
            }, "model_key = ?", arrayOf(key))
        }
    }

    fun remove(context: Context, key: String) {
        runCatching { db(context).writableDatabase.delete("registered_models", "model_key = ?", arrayOf(key)) }
    }
}
