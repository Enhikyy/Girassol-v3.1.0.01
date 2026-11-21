package com.enhikyy.girassol.audio

import android.content.Context
import android.util.Log
import com.enhikyy.girassol.llm.LlmQuantPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * v3.7 — backends de voz opcionais por perfil de RAM.
 *
 * Perfil 4 GB (orçamento de 3631 MB): SenseVoice (ASR) + Android TTS / Pocket-TTS embutidos;
 * nada é baixado. Perfis 8 GB e 12 GB: Piper VITS pt-BR (TTS) e Whisper tiny (ASR) podem ser
 * instalados sob demanda a partir das releases oficiais do Sherpa-ONNX. Cada pacote é verificado
 * em duas camadas: SHA-256 do arquivo `.tar.bz2` baixado e SHA-256 de cada arquivo de modelo
 * extraído (fixados abaixo). A verificação por arquivo é refeita antes de cada carregamento.
 */
object VoicePacks {
    private const val TAG = "GirassolVoicePacks"
    const val ROOT = "voice-packs"

    enum class Kind { TTS, ASR }

    data class Pack(
        val id: String,
        val label: String,
        val kind: Kind,
        val url: String,
        val archiveBytes: Long,
        val archiveSha256: String,
        /** Diretório raiz dentro do tar. */
        val topDir: String,
        /** Arquivos obrigatórios (relativos a [topDir]) → SHA-256 fixado. */
        val files: Map<String, String>,
        /** Prefixos extraídos (o resto do tar é ignorado para poupar armazenamento). */
        val keepPrefixes: List<String>
    )

    val PIPER_PT_BR = Pack(
        id = "piper-pt_BR-faber",
        label = "Piper VITS pt-BR (faber, int8)",
        kind = Kind.TTS,
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-pt_BR-faber-medium-int8.tar.bz2",
        archiveBytes = 21_336_772L,
        archiveSha256 = "dbc8b1d7d729fd417ea78a350ed35696c928770ac93513d3f507bd4e88eee3fd",
        topDir = "vits-piper-pt_BR-faber-medium-int8",
        files = mapOf(
            "pt_BR-faber-medium.onnx" to "ee093c2dff254412682496508241ff50ade0cd356288cce1ee1617532fa9fd63",
            "tokens.txt" to "2619c1a9de1bcf928162f40c583caf39368cfd6b2340c7bcad51dc634411ec36"
        ),
        keepPrefixes = listOf("pt_BR-faber-medium.onnx", "tokens.txt", "espeak-ng-data/")
    )

    val WHISPER_TINY = Pack(
        id = "whisper-tiny",
        label = "Whisper tiny (multilíngue, int8)",
        kind = Kind.ASR,
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2",
        archiveBytes = 116_204_861L,
        archiveSha256 = "c46116994e539aa165266d96b325252728429c12535eb9d8b6a2b10f129e66b1",
        topDir = "sherpa-onnx-whisper-tiny",
        files = mapOf(
            "tiny-encoder.int8.onnx" to "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434",
            "tiny-decoder.int8.onnx" to "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925",
            "tiny-tokens.txt" to "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"
        ),
        keepPrefixes = listOf("tiny-encoder.int8.onnx", "tiny-decoder.int8.onnx", "tiny-tokens.txt")
    )

    val ALL = listOf(PIPER_PT_BR, WHISPER_TINY)

    sealed class State {
        object Blocked : State()
        object Missing : State()
        data class Downloading(val fraction: Float) : State()
        object Verifying : State()
        data class Ready(val dir: String) : State()
        data class Failed(val message: String) : State()
    }

    /** Perfil 4 GB nunca recebe os backends opcionais (não estoura os 3631 MB). */
    fun allowedFor(profile: LlmQuantPolicy.DeviceProfile): Boolean = profile.poolCapacity > 1

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "girassol-voice-packs").apply { isDaemon = true } }
    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()
    @Volatile private var verified = HashSet<String>()

    fun root(context: Context) = File(context.filesDir, ROOT)
    fun dirOf(context: Context, p: Pack) = File(root(context), p.id)

    fun refresh(context: Context, profile: LlmQuantPolicy.DeviceProfile) {
        val m = ALL.associate { p ->
            p.id to when {
                !allowedFor(profile) -> State.Blocked
                isInstalled(dirOf(context, p), p) -> State.Ready(dirOf(context, p).absolutePath)
                else -> (_states.value[p.id] as? State.Downloading) ?: State.Missing
            }
        }
        _states.value = m
    }

    fun isInstalled(dir: File, p: Pack): Boolean =
        File(dir, ".sha256").let { it.exists() && it.readText().trim() == p.archiveSha256 } && p.files.keys.all { File(dir, it).isFile }

    /** Revalida os SHA-256 por arquivo (uma vez por processo) antes de carregar o modelo. */
    @Synchronized
    fun verifiedDir(context: Context, p: Pack, profile: LlmQuantPolicy.DeviceProfile): File? {
        if (!allowedFor(profile)) return null
        val dir = dirOf(context, p)
        if (!isInstalled(dir, p)) return null
        if (p.id in verified) return dir
        val bad = verifyFiles(dir, p)
        if (bad != null) {
            Log.w(TAG, "${p.id}: $bad — removendo pacote")
            dir.deleteRecursively()
            _states.update { it + (p.id to State.Failed(bad)) }
            return null
        }
        verified = HashSet(verified).apply { add(p.id) }
        return dir
    }

    fun install(context: Context, p: Pack, profile: LlmQuantPolicy.DeviceProfile) {
        if (!allowedFor(profile)) { _states.update { it + (p.id to State.Blocked) }; return }
        val app = context.applicationContext
        _states.update { it + (p.id to State.Downloading(0f)) }
        exec.execute {
            val r = runCatching {
                installFrom(root(app), p, { u ->
                    val c = URL(u).openConnection() as HttpURLConnection
                    c.connectTimeout = 20_000; c.readTimeout = 60_000; c.instanceFollowRedirects = true
                    if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                    c.inputStream
                }) { f -> _states.update { it + (p.id to (if (f >= 1f) State.Verifying else State.Downloading(f))) } }
            }
            _states.update { it + (p.id to r.fold({ d -> State.Ready(d.absolutePath) }, { e -> State.Failed(e.message ?: e.javaClass.simpleName) })) }
            if (r.isSuccess) verified = HashSet(verified).apply { add(p.id) }
        }
    }

    fun remove(context: Context, p: Pack) {
        exec.execute {
            dirOf(context, p).deleteRecursively()
            verified = HashSet(verified).apply { remove(p.id) }
            _states.update { it + (p.id to State.Missing) }
        }
    }

    // ----------------------------------------------------------------- núcleo puro (testável na JVM)

    fun sha256(f: File): String = f.inputStream().use { sha256(it) }

    fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** null = todos os arquivos conferem; caso contrário a descrição da divergência. */
    fun verifyFiles(dir: File, p: Pack): String? {
        for ((name, expected) in p.files) {
            val f = File(dir, name)
            if (!f.isFile) return "arquivo ausente: $name"
            val got = sha256(f)
            if (got != expected) return "SHA-256 divergente em $name ($got)"
        }
        return null
    }

    /**
     * Baixa (streaming + SHA-256 incremental), confere o hash do arquivo, extrai só os
     * [Pack.keepPrefixes] com proteção contra path traversal e confere o hash de cada modelo.
     * A instalação é atômica: extrai em `<id>.tmp` e renomeia ao final.
     */
    fun installFrom(root: File, p: Pack, open: (String) -> InputStream, progress: (Float) -> Unit = {}): File {
        root.mkdirs()
        val part = File(root, "${p.id}.tar.bz2.part")
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        open(p.url).use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    md.update(buf, 0, n); out.write(buf, 0, n); total += n
                    if (total > p.archiveBytes * 2) error("download maior que o esperado")
                    progress((total.toDouble() / p.archiveBytes).toFloat().coerceAtMost(0.99f))
                }
            }
        }
        progress(1f)
        val got = md.digest().joinToString("") { "%02x".format(it) }
        if (got != p.archiveSha256) { part.delete(); error("SHA-256 do pacote divergente ($got)") }
        val tmp = File(root, "${p.id}.tmp").apply { deleteRecursively(); mkdirs() }
        try {
            extractTarBz2(part, tmp, p.topDir, p.keepPrefixes)
            verifyFiles(tmp, p)?.let { error(it) }
            File(tmp, ".sha256").writeText(p.archiveSha256)
            val dest = File(root, p.id)
            dest.deleteRecursively()
            if (!tmp.renameTo(dest)) error("não foi possível instalar ${p.id}")
            return dest
        } catch (t: Throwable) {
            tmp.deleteRecursively(); throw t
        } finally {
            part.delete()
        }
    }

    fun extractTarBz2(archive: File, dest: File, topDir: String, keepPrefixes: List<String>) {
        val canon = dest.canonicalFile
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream(), 1 shl 16))).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                val name = e.name.removePrefix("./")
                if (!name.startsWith("$topDir/")) continue
                val rel = name.removePrefix("$topDir/")
                if (rel.isEmpty() || keepPrefixes.none { rel == it || rel.startsWith(it) }) continue
                val out = File(dest, rel).canonicalFile
                if (!out.path.startsWith(canon.path + File.separator)) error("entrada fora do destino: ${e.name}")
                if (e.isDirectory) { out.mkdirs(); continue }
                if (!e.isFile) continue
                out.parentFile?.mkdirs()
                out.outputStream().use { tar.copyTo(it, 1 shl 16) }
            }
        }
    }
}
