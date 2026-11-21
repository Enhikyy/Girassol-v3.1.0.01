package com.enhikyy.girassol.system

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Debug
import android.os.PowerManager
import android.util.Log
import com.enhikyy.girassol.llm.LocalLlmManager
import com.enhikyy.girassol.nativebridge.GeaNativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class MemorySnapshot(
    val rssKb: Long = -1,
    val hwmKb: Long = -1,
    val rssAnonKb: Long = -1,
    val rssFileKb: Long = -1,
    val swapKb: Long = -1,
    val nativeSource: Boolean = false,
    val sysAvailMb: Long = 0,
    val sysTotalMb: Long = 0,
    val sysThresholdMb: Long = 0,
    val sysLowMemory: Boolean = false,
    val nativeHeapMb: Double = 0.0,
    val limitMb: Long = 0,
    val rearmMb: Long = 0,
    val breakerOpen: Boolean = false,
    val trips: Int = 0,
    val lastReason: String = "",
    val lastTrimLevel: Int = -1,
    val thermalStatus: Int = 0,
    val thermalHeadroom: Float = Float.NaN,
    val updatedAtMs: Long = 0
)

/**
 * Telemetria de RAM real + disjuntor de memória.
 *
 * - VmRSS/VmHWM vêm de /proc/self/status lidos em C++ (libgirassol_core), com fallback Kotlin.
 * - ActivityManager.MemoryInfo dá a RAM livre do sistema; ComponentCallbacks2 entrega onTrimMemory.
 * - ADPF: PowerManager.currentThermalStatus + getThermalHeadroom(10 s).
 * Ao disparar, descarrega o contexto do llama.cpp, libera caches do SQLite e temporários e
 * pede GC. O loop determinístico (thread girassol-worldcore) não é pausado.
 */
object MemoryGuard {
    private const val TAG = "GirassolMem"
    private const val POLL_MS = 2_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var appContext: Context? = null
    @Volatile private var breaker: MemoryBreaker? = null
    @Volatile private var lastTrim = -1
    private var samples = 0L

    private val _state = MutableStateFlow(MemorySnapshot())
    val state: StateFlow<MemorySnapshot> = _state.asStateFlow()

    /** Limite forçado (testes / depuração); null = automático pela RAM física. */
    @Volatile var limitOverrideBytes: Long? = null
        set(v) { field = v; breaker = null }

    fun start(context: Context) {
        appContext = context.applicationContext
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                runCatching { sample() }.onFailure { Log.w(TAG, "amostra falhou", it) }
                delay(POLL_MS)
            }
        }
    }

    private fun ensureBreaker(totalBytes: Long): MemoryBreaker {
        breaker?.let { return it }
        val limit = limitOverrideBytes ?: MemoryBreaker.limitForTotalRam(totalBytes)
        return MemoryBreaker(limit, rearmHoldMs = 30_000L).also { breaker = it }
    }

    /** Lê memória do processo: C++ (/proc/self/status) ou fallback Kotlin. */
    fun readProcMemory(): Pair<LongArray, Boolean> {
        if (GeaNativeBridge.isNativeLoaded) {
            val v = runCatching { GeaNativeBridge.nativeProcMemory() }.getOrNull()
            if (v != null && v.size >= 6 && v[0] > 0) return v to true
        }
        val out = LongArray(6) { -1 }
        runCatching {
            File("/proc/self/status").readLines().forEach { l ->
                val idx = when { l.startsWith("VmRSS:") -> 0; l.startsWith("VmHWM:") -> 1; l.startsWith("VmSize:") -> 2
                    l.startsWith("RssAnon:") -> 3; l.startsWith("RssFile:") -> 4; l.startsWith("VmSwap:") -> 5; else -> -1 }
                if (idx >= 0) out[idx] = l.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: -1
            }
        }
        return out to false
    }

    fun sample(): MemorySnapshot {
        val ctx = appContext ?: return _state.value
        val (pm, native) = readProcMemory()
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val b = ensureBreaker(mi.totalMem)
        val now = android.os.SystemClock.elapsedRealtime()
        // RAM anônima (RssAnon + swap): o .gguf mapeado por mmap não é heap exclusivo
        val pressure = MemoryBreaker.pressureBytes(pm)
        if (mi.lowMemory) LocalLlmManager.evictUnderPressure("lowMemory")
        when (b.onSample(pressure, now, mi.lowMemory)) {
            MemoryBreaker.Action.TRIP -> trip(b.lastReason)
            MemoryBreaker.Action.REARM -> rearm()
            MemoryBreaker.Action.NONE -> Unit
        }
        val power = ctx.getSystemService(PowerManager::class.java)
        val thermal = runCatching { power.currentThermalStatus }.getOrDefault(0)
        val headroom = runCatching { power.getThermalHeadroom(10) }.getOrDefault(Float.NaN)
        val snap = MemorySnapshot(
            rssKb = pm[0], hwmKb = pm[1], rssAnonKb = pm[3], rssFileKb = pm[4], swapKb = pm[5], nativeSource = native,
            sysAvailMb = mi.availMem / MemoryBreaker.MB, sysTotalMb = mi.totalMem / MemoryBreaker.MB,
            sysThresholdMb = mi.threshold / MemoryBreaker.MB, sysLowMemory = mi.lowMemory,
            nativeHeapMb = Debug.getNativeHeapAllocatedSize() / 1048576.0,
            limitMb = b.limitBytes / MemoryBreaker.MB, rearmMb = b.rearmBytes / MemoryBreaker.MB,
            breakerOpen = b.open, trips = b.trips, lastReason = b.lastReason, lastTrimLevel = lastTrim,
            thermalStatus = thermal, thermalHeadroom = headroom, updatedAtMs = System.currentTimeMillis()
        )
        _state.value = snap
        if (++samples % 5 == 0L) LocalLlmManager.refreshBudget(ctx)
        return snap
    }

    /** Encaminhado por GirassolApplication.onTrimMemory. */
    fun onTrimMemory(level: Int) {
        lastTrim = level
        val b = breaker ?: appContext?.let { ctx ->
            val mi = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
            ensureBreaker(mi.totalMem)
        } ?: return
        val low = appContext?.let { ctx ->
            ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.lowMemory
        } ?: false
        // v3.8: perfil 8/12 GB — qualquer pressão real despeja já os modelos estacionados do pool
        if (LocalLlmManager.isPressureTrim(level, low)) {
            LocalLlmManager.evictUnderPressure("onTrimMemory($level)")
        }
        val critical = MemoryBreaker.isCriticalTrim(level, low)
        if (critical && b.onCriticalSignal("onTrimMemory($level)", android.os.SystemClock.elapsedRealtime()) == MemoryBreaker.Action.TRIP) {
            trip(b.lastReason)
        }
        scope.launch { runCatching { sample() } }
    }

    fun onLowMemory() {
        val b = breaker ?: return
        val ctx = appContext ?: return
        val mi = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        if (!mi.lowMemory) return // callback legado; só age com lowMemory real
        LocalLlmManager.evictUnderPressure("onLowMemory")
        if (b.onCriticalSignal("onLowMemory", android.os.SystemClock.elapsedRealtime()) == MemoryBreaker.Action.TRIP) trip(b.lastReason)
    }

    /** Dispara manualmente (botão de teste na aba Sistema e testes instrumentados). */
    fun tripForTest(reason: String = "teste manual") {
        val b = breaker ?: return
        if (b.onCriticalSignal(reason, android.os.SystemClock.elapsedRealtime()) == MemoryBreaker.Action.TRIP) trip(b.lastReason)
        scope.launch { runCatching { sample() } }
    }

    /** Rearme manual: recarrega o LLM se a memória já permitir (o próximo excesso dispara de novo). */
    fun rearmManually() {
        val b = breaker ?: return
        if (b.forceRearm() == MemoryBreaker.Action.REARM) rearm()
        scope.launch { runCatching { sample() } }
    }

    private fun trip(reason: String) {
        Log.w(TAG, "DISJUNTOR ABERTO: $reason")
        LocalLlmManager.unloadForMemory(reason)
        runCatching { SQLiteDatabase.releaseMemory() }
        appContext?.let { ctx ->
            runCatching { ctx.cacheDir.listFiles()?.forEach { if (it.isFile) it.delete() } }
        }
        System.gc()
    }

    private fun rearm() {
        Log.i(TAG, "disjuntor rearmado")
        LocalLlmManager.rearmMemoryBreaker()
    }
}
