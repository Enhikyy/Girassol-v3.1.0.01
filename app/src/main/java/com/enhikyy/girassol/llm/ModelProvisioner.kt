package com.enhikyy.girassol.llm

import android.content.Context
import android.util.Log
import com.enhikyy.girassol.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/** Um modelo GGUF empacotado (APK direto, asset pack install-time do .aab ou importado via SAF). */
data class PackagedModel(
    val role: String,               // "rapido" | "profundo"
    val file: String,
    val sha256: String,
    val bytes: Long,
    val parts: List<String>,
    val installed: File?            // cópia verificada em filesDir/models (null se ainda não provisionado)
)

data class ProvisionState(
    val busy: Boolean = false,
    val progress: Float = 0f,
    val message: String = "",
    val models: List<PackagedModel> = emptyList()
)

/**
 * Provisionamento dos modelos embutidos.
 *
 *  - `models/rapido.json` (APK direto ou pack_rapido): modelo rápido Q4_0 (~0,43 GB).
 *  - `models/profundo.json` (pack_profundo_a; partes em pack_profundo_a/b, install-time,
 *    só no .aab): modelo profundo (~2,2 GB) dividido em partes < 1,5 GB (limite por asset pack
 *    do Google Play). As partes são concatenadas em `filesDir/models` e o SHA-256 completo é
 *    conferido contra o manifesto e contra o `BuildConfig` antes do modelo ser oferecido.
 *
 * Asset packs install-time são entregues como splits do próprio app e aparecem no
 * `AssetManager` normal, por isso o mesmo código serve ao APK direto e ao .aab.
 * llama.cpp precisa de um caminho real (mmap), daí a cópia para o armazenamento interno.
 */
object ModelProvisioner {
    private const val TAG = "GirassolModels"
    private val _state = MutableStateFlow(ProvisionState())
    val state: StateFlow<ProvisionState> = _state.asStateFlow()

    fun modelsDir(context: Context) = File(context.filesDir, "models").apply { mkdirs() }

    private fun manifest(context: Context, role: String): PackagedModel? = runCatching {
        val j = JSONObject(context.assets.open("models/$role.json").bufferedReader().use { it.readText() })
        val parts = j.getJSONArray("parts").let { a -> (0 until a.length()).map { a.getString(it) } }
        val file = j.getString("file")
        val target = File(modelsDir(context), file)
        val ok = File(target.path + ".ok")
        PackagedModel(role, file, j.getString("sha256"), j.optLong("bytes", -1L), parts,
            target.takeIf { it.exists() && ok.exists() && ok.readText() == j.getString("sha256") })
    }.onFailure { if (it !is FileNotFoundException) Log.w(TAG, "manifesto $role", it) }.getOrNull()

    /** Lê os manifestos disponíveis neste build (rápido e/ou profundo). */
    fun refresh(context: Context): List<PackagedModel> {
        val list = listOfNotNull(manifest(context, "rapido"), manifest(context, "profundo"))
        _state.update { it.copy(models = list) }
        return list
    }

    private fun expectedSha(m: PackagedModel) = when (m.role) {
        "rapido" -> BuildConfig.FAST_MODEL_SHA256
        "profundo" -> BuildConfig.DEEP_MODEL_SHA256
        else -> m.sha256
    }

    /** Copia (e remonta, se dividido) o modelo para filesDir/models, verificando o SHA-256. */
    suspend fun provision(context: Context, role: String): Result<File> = withContext(Dispatchers.IO) {
        val m = refresh(context).firstOrNull { it.role == role }
            ?: return@withContext Result.failure(IllegalStateException("modelo '$role' não está empacotado neste build"))
        m.installed?.let { return@withContext Result.success(it) }
        if (!m.sha256.equals(expectedSha(m), ignoreCase = true))
            return@withContext Result.failure(SecurityException("manifesto de '$role' não confere com o BuildConfig"))
        val dir = modelsDir(context)
        val target = File(dir, m.file)
        val tmp = File(dir, m.file + ".tmp")
        val need = if (m.bytes > 0) m.bytes else m.parts.sumOf { p -> runCatching { context.assets.openFd("models/$p").use { it.length } }.getOrDefault(0L) }
        if (dir.usableSpace < need + (64L shl 20))
            return@withContext Result.failure(IllegalStateException("espaço insuficiente: ${need / 1048576} MB necessários"))
        _state.update { it.copy(busy = true, progress = 0f, message = "Provisionando ${m.file}…") }
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        var done = 0L
        try {
            tmp.outputStream().buffered(1 shl 20).use { out ->
                for (p in m.parts) context.assets.open("models/$p").use { ins ->
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        md.update(buf, 0, n); out.write(buf, 0, n); done += n
                        if (need > 0) _state.update { it.copy(progress = (done.toFloat() / need).coerceIn(0f, 1f)) }
                    }
                }
            }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (!sha.equals(m.sha256, ignoreCase = true)) {
                tmp.delete()
                error("SHA-256 do modelo remontado não confere ($sha)")
            }
            if (!tmp.renameTo(target)) error("não foi possível mover ${tmp.name}")
            File(target.path + ".ok").writeText(m.sha256)
            _state.update { it.copy(busy = false, progress = 1f, message = "${m.file}: ${done / 1048576} MB verificados (SHA-256)") }
            refresh(context)
            Result.success(target)
        } catch (t: Throwable) {
            tmp.delete()
            _state.update { it.copy(busy = false, message = "Falha: ${t.message}") }
            Result.failure(t)
        }
    }

    /** Provisiona e seleciona o modelo no llama.cpp (sem filtro de nome: qualquer GGUF válido). */
    suspend fun provisionAndSelect(context: Context, role: String): String {
        val f = provision(context, role).getOrElse { return it.message ?: "falha" }
        return LocalLlmManager.selectModel(context, f)
    }

    /** No 1º uso, se nada foi escolhido ainda, provisiona o modelo rápido embutido. */
    suspend fun autoProvision(context: Context) {
        if (!BuildConfig.EMBEDDED_MODELS) return
        if (LocalLlmManager.currentModelPath(context)?.let { File(it).exists() } == true) return
        if (refresh(context).any { it.role == "rapido" }) provisionAndSelect(context, "rapido")
    }
}
