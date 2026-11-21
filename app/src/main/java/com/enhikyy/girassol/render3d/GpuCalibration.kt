package com.enhikyy.girassol.render3d

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.enhikyy.girassol.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

enum class GpuCalStatus { IDLE, RUNNING, DONE }

data class GpuCalibrationState(
    val status: GpuCalStatus = GpuCalStatus.IDLE,
    /** Nível calibrado (teto); null = nenhum nível passou → 3D indisponível (Modo Eco 2D). */
    val calibrated: EffectLevel? = null,
    /** Nível em uso agora (pode estar abaixo do calibrado por queda de FPS). */
    val active: EffectLevel? = null,
    val source: String = "",
    val driver: GlDriverInfo? = null,
    val probeLog: List<String> = emptyList(),
    val fpsDowngrades: Int = 0
)

data class ProbeResult(val level: EffectLevel, val ok: Boolean, val crashed: Boolean, val message: String, val millis: Long)

/**
 * Calibração de efeitos por GPU real.
 *
 * Uma falha de compilação de shader no Filament não é uma exceção Java: o driver/Filament
 * aborta o processo. Por isso cada nível é testado num processo separado (":gpuprobe")
 * que renderiza a cidade fora da tela com os mesmos .filamat e o mesmo pós-processamento.
 * Se a sonda morrer, o app principal sobrevive e desce um nível. O resultado fica em cache
 * por aparelho + build + driver. Em GL por software (SwiftShader do CI) vai direto para
 * [EffectLevel.NONE] sem sonda. Uma sentinela gravada enquanto o 3D roda detecta crash no
 * processo principal e rebaixa o nível na próxima abertura.
 */
object GpuCalibration {
    private const val TAG = "GirassolGpu"
    private const val PREFS = "girassol_gpu"
    const val PROBE_PROCESS_SUFFIX = ":gpuprobe"
    private const val PROBE_TIMEOUT_MS = 25_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(GpuCalibrationState())
    val state: StateFlow<GpuCalibrationState> = _state.asStateFlow()

    private fun cacheKey(d: GlDriverInfo?): String =
        "${Build.FINGERPRINT}|${BuildConfig.VERSION_CODE}|filament-${BuildConfig.FILAMENT_VERSION}|${d?.cacheKey() ?: "?"}"

    /** Chamado pela MainActivity (primeiro plano: startService da sonda é permitido). */
    fun ensure(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        if (!force && _state.value.status != GpuCalStatus.IDLE) return
        val driver = _state.value.driver ?: GlInfo.query()
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // v4.0: nível forçado (roteiro de demonstração/teste instrumentado) — ignora sonda e GL por software
        EffectLevel.fromId(prefs.getInt(FORCE_LEVEL, -1))?.let { lvl ->
            _state.value = GpuCalibrationState(GpuCalStatus.DONE, lvl, lvl, "nível forçado (${lvl.label})", driver)
            Log.i(TAG, "nível de efeitos forçado: ${lvl.label}")
            return
        }
        if (driver == null || driver.isSoftware || isSoftwareGl()) {
            val lvl = EffectLevel.NONE
            _state.value = GpuCalibrationState(GpuCalStatus.DONE, lvl, lvl, "GL por software (${driver?.renderer ?: "sem EGL"})", driver)
            return
        }
        val key = cacheKey(driver)
        // Sentinela: o processo principal morreu enquanto renderizava neste nível → rebaixa.
        val sentinel = prefs.getInt("sentinel_level", -1)
        if (sentinel >= 0) {
            prefs.edit().remove("sentinel_level").apply()
            val crashed = EffectLevel.fromId(sentinel)
            val next = crashed?.lower()
            Log.w(TAG, "sentinela: 3D encerrou de forma anormal em ${crashed?.label}; rebaixando para ${next?.label ?: "Eco 2D"}")
            prefs.edit().putString("key", key).putInt("level", next?.id ?: -1)
                .putString("source", "sentinela de crash em ${crashed?.label}").apply()
        }
        if (!force && prefs.getString("key", null) == key) {
            val lvl = EffectLevel.fromId(prefs.getInt("level", -1))
            _state.value = GpuCalibrationState(GpuCalStatus.DONE, lvl, lvl, prefs.getString("source", "cache") ?: "cache", driver,
                prefs.getString("log", "")!!.split('\n').filter { it.isNotBlank() })
            return
        }
        _state.value = GpuCalibrationState(GpuCalStatus.RUNNING, null, null, "calibrando shaders na GPU…", driver)
        scope.launch {
            val log = ArrayList<String>()
            var chosen: EffectLevel? = null
            for (lvl in EffectLevel.entries) { // FULL → NONE
                val r = runProbe(app, lvl)
                log += "${lvl.label}: ${if (r.ok) "ok" else if (r.crashed) "processo da sonda abortou" else "falhou"} (${r.millis} ms)${if (r.message.isNotEmpty()) " ${r.message}" else ""}"
                _state.update { it.copy(probeLog = log.toList()) }
                if (r.ok) { chosen = lvl; break }
            }
            val src = if (chosen != null) "sonda isolada" else "sonda: nenhum nível compilou"
            prefs.edit().putString("key", key).putInt("level", chosen?.id ?: -1).putString("source", src)
                .putString("log", log.joinToString("\n")).apply()
            _state.value = GpuCalibrationState(GpuCalStatus.DONE, chosen, chosen, src, driver, log)
            Log.i(TAG, "calibração: ${chosen?.label ?: "3D indisponível"} — $log")
        }
    }

    const val FORCE_LEVEL = "force_level"

    /** Força um nível de efeitos (−1/null remove). Usado pelo roteiro de demonstração. */
    fun forceLevel(context: Context, level: EffectLevel?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(FORCE_LEVEL, level?.id ?: -1).commit()
    }

    fun isForced(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(FORCE_LEVEL, -1) >= 0

    fun recalibrate(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        _state.value = GpuCalibrationState(driver = _state.value.driver)
        ensure(context, force = true)
    }

    /** Executa a sonda num processo separado e espera o resultado (bloqueante; não chamar na main). */
    fun runProbe(context: Context, level: EffectLevel, timeoutMs: Long = PROBE_TIMEOUT_MS): ProbeResult {
        val t0 = SystemClock.elapsedRealtime()
        val nonce = UUID.randomUUID().toString().take(8)
        val dir = File(context.filesDir, "gpuprobe").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "result-$nonce.txt")
        val intent = Intent(context, GpuProbeService::class.java)
            .putExtra(GpuProbeService.EXTRA_LEVEL, level.id).putExtra(GpuProbeService.EXTRA_NONCE, nonce)
        val started = runCatching { context.startService(intent) }.onFailure { Log.w(TAG, "startService da sonda falhou", it) }.isSuccess
        if (!started) return ProbeResult(level, false, false, "não foi possível iniciar a sonda", 0)
        val am = context.getSystemService(ActivityManager::class.java)
        val procName = context.packageName + PROBE_PROCESS_SUFFIX
        var seenAlive = false
        var goneSince = -1L
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            if (out.exists()) {
                Thread.sleep(50)
                val kv = out.readLines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
                return ProbeResult(level, kv["status"] == "ok", false, kv["msg"] ?: "", SystemClock.elapsedRealtime() - t0)
            }
            val alive = am.runningAppProcesses?.any { it.processName == procName } == true
            if (alive) { seenAlive = true; goneSince = -1L }
            else if (seenAlive) {
                if (goneSince < 0) goneSince = SystemClock.elapsedRealtime()
                else if (SystemClock.elapsedRealtime() - goneSince > 1_500) // tolera a escrita tardia do arquivo
                    return ProbeResult(level, false, true, "", SystemClock.elapsedRealtime() - t0)
            }
            Thread.sleep(150)
        }
        am.runningAppProcesses?.firstOrNull { it.processName == procName }?.let { Process.killProcess(it.pid) }
        return ProbeResult(level, false, false, "timeout", SystemClock.elapsedRealtime() - t0)
    }

    // ------------------------------------------------------------- uso em runtime

    /** Nível a usar ao criar o renderer (null = 3D indisponível; só válido com status DONE). */
    fun levelForRenderer(): EffectLevel? = _state.value.active

    fun markRendering(context: Context, level: EffectLevel) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("sentinel_level", level.id).commit()
    }

    fun clearRendering(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("sentinel_level").apply()
    }

    /** Governador de FPS pediu para descer: vale para esta sessão (não altera o cache da sonda). */
    fun onFpsDowngrade(from: EffectLevel): EffectLevel? {
        val next = from.lower() ?: return null
        _state.update { it.copy(active = next, fpsDowngrades = it.fpsDowngrades + 1, source = it.source + " · FPS baixo → ${next.label}") }
        Log.w(TAG, "FPS abaixo do piso em ${from.label}: descendo para ${next.label}")
        return next
    }
}
