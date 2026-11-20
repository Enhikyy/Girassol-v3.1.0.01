package com.enhikyy.girassol.perf

import android.content.Context
import android.os.PerformanceHintManager
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AdpfStats(
    val supported: Boolean = false,
    val threads: Map<String, Int> = emptyMap(),
    val targetMs: Double = 0.0,
    val reports: Long = 0,
    val lastWorkMs: Double = 0.0,
    val message: String = "não iniciado"
)

/**
 * Sessão ADPF (PerformanceHintManager) cobrindo as threads de IA: o loop do mundo
 * (girassol-sim) e o motor llama.cpp (girassol-llm). Cada thread registra o próprio tid
 * na primeira execução; a sessão é recriada quando o conjunto de tids muda e recebe
 * reportActualWorkDuration a cada tick/geração para que o governador de CPU ajuste
 * frequência e afinidade sem acordar núcleos grandes à toa.
 */
object AdpfHints {
    private const val TAG = "GirassolADPF"
    private const val DEFAULT_TARGET_NS = 16_666_666L
    private var manager: PerformanceHintManager? = null
    private var session: PerformanceHintManager.Session? = null
    private val tids = LinkedHashMap<String, Int>()
    private var targetNs = DEFAULT_TARGET_NS
    private val _stats = MutableStateFlow(AdpfStats())
    val stats: StateFlow<AdpfStats> = _stats.asStateFlow()

    fun init(context: Context) {
        synchronized(this) {
            manager = context.getSystemService(PerformanceHintManager::class.java)
            val ok = manager != null && manager!!.preferredUpdateRateNanos > 0
            _stats.update { it.copy(supported = ok, message = if (ok) "aguardando threads" else "ADPF indisponível neste aparelho") }
        }
    }

    /** Registra a thread chamadora (idempotente). */
    fun registerCurrentThread(name: String) {
        val tid = Process.myTid()
        synchronized(this) {
            if (tids[name] == tid) return
            tids[name] = tid
            rebuild()
        }
    }

    /** Ajusta a duração-alvo (ex.: 1000 / ticks por segundo). */
    fun updateTarget(targetNanos: Long) {
        synchronized(this) {
            val t = targetNanos.coerceIn(1_000_000L, 2_000_000_000L)
            if (t == targetNs) return
            targetNs = t
            runCatching { session?.updateTargetWorkDuration(t) }
            _stats.update { it.copy(targetMs = t / 1e6) }
        }
    }

    fun reportWork(actualNanos: Long) {
        if (actualNanos <= 0) return
        synchronized(this) {
            val s = session ?: return
            runCatching { s.reportActualWorkDuration(actualNanos) }
                .onSuccess { _stats.update { st -> st.copy(reports = st.reports + 1, lastWorkMs = actualNanos / 1e6) } }
        }
    }

    private fun rebuild() {
        val m = manager ?: return
        runCatching { session?.close() }
        session = runCatching { m.createHintSession(tids.values.toIntArray(), targetNs) }
            .onFailure { Log.w(TAG, "createHintSession falhou", it) }.getOrNull()
        _stats.update {
            it.copy(threads = LinkedHashMap(tids), targetMs = targetNs / 1e6,
                message = if (session != null) "sessão ativa (${tids.size} threads)" else "sessão recusada pelo sistema")
        }
    }

    fun close() {
        synchronized(this) {
            runCatching { session?.close() }
            session = null
            tids.clear()
            _stats.update { it.copy(threads = emptyMap(), message = "encerrada") }
        }
    }
}
