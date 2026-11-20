package com.enhikyy.girassol.sim

import android.content.Context
import android.util.Log
import com.enhikyy.girassol.core.rsi.RsiCoordinator
import com.enhikyy.girassol.core.world.CitizenDetail
import com.enhikyy.girassol.core.world.DashboardSnapshot
import com.enhikyy.girassol.core.world.TelemetryInput
import com.enhikyy.girassol.core.world.WorldCore
import com.enhikyy.girassol.core.world.WorldRenderSnapshot
import com.enhikyy.girassol.core.world.WorldSnapshotCodec
import com.enhikyy.girassol.data.PersistenceWriter
import com.enhikyy.girassol.data.ProjectionRows
import com.enhikyy.girassol.data.SnapshotMeta
import com.enhikyy.girassol.hardware.HardwareAutoTuner
import com.enhikyy.girassol.hardware.HardwareProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

data class HubStatus(
    val loaded: Boolean = false,
    val running: Boolean = false,
    val targetTps: Int = 8,
    val effectiveTps: Double = 0.0,
    val thermalStatus: Int = 0,
    val thermalCapped: Boolean = false,
    val lastSnapshotTick: Long = -1,
    val lastSnapshotBytes: Int = 0,
    val lastSnapshotSha: String = "",
    val restoredFromSnapshot: Boolean = false,
    val seed: Long = 0,
    val rsiCycles: Int = 0,
    val lastMessage: String = ""
)

/**
 * Hub único da simulação. O [WorldCore] vive exclusivamente na thread
 * "girassol-worldcore"; a UI recebe apenas snapshots imutáveis via StateFlow e
 * envia comandos que são executados nessa mesma thread.
 */
object SimulationHub {
    private const val TAG = "GirassolHub"
    const val SNAPSHOT_EVERY_TICKS = 240L
    val SPEEDS = listOf(2, 8, 30, 60, 120)

    private val simDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "girassol-worldcore").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + simDispatcher)

    private lateinit var core: WorldCore
    private lateinit var writer: PersistenceWriter
    private val rsi = RsiCoordinator()
    private var loopJob: Job? = null

    private val _render = MutableStateFlow<WorldRenderSnapshot?>(null)
    val render: StateFlow<WorldRenderSnapshot?> = _render.asStateFlow()
    private val _dashboard = MutableStateFlow<DashboardSnapshot?>(null)
    val dashboard: StateFlow<DashboardSnapshot?> = _dashboard.asStateFlow()
    private val _status = MutableStateFlow(HubStatus())
    val status: StateFlow<HubStatus> = _status.asStateFlow()
    private val _hardware = MutableStateFlow<HardwareProfile?>(null)
    val hardware: StateFlow<HardwareProfile?> = _hardware.asStateFlow()

    @Volatile private var telemetry = TelemetryInput()

    /** Carrega (ou cria) o mundo uma única vez. Seguro para chamar de qualquer thread. */
    fun ensureLoaded(context: Context) {
        if (_status.value.loaded) return
        val app = context.applicationContext
        scope.launch {
            if (_status.value.loaded) return@launch
            _hardware.value = runCatching { HardwareAutoTuner.detect(app) }.getOrNull()
            writer = PersistenceWriter(app)
            val restored = writer.loadLatestSnapshot()
            core = if (restored != null) WorldCore(restored) else WorldCore.create(System.currentTimeMillis())
            val cap = _hardware.value?.maxTicksPerSecond ?: 30
            _status.update {
                it.copy(loaded = true, restoredFromSnapshot = restored != null, seed = core.state.seed,
                    targetTps = it.targetTps.coerceAtMost(cap),
                    lastMessage = if (restored != null) "Mundo restaurado do snapshot (tick ${core.state.tick})" else "Novo mundo criado (semente ${core.state.seed})")
            }
            if (restored == null) persistSnapshot()
            publish(force = true)
        }
    }

    fun start() {
        scope.launch {
            if (!_status.value.loaded || loopJob?.isActive == true) return@launch
            _status.update { it.copy(running = true) }
            loopJob = scope.launch { runLoop() }
        }
    }

    fun pause() {
        scope.launch {
            loopJob?.cancel()
            loopJob = null
            _status.update { it.copy(running = false, effectiveTps = 0.0) }
            if (::core.isInitialized) persistSnapshot()
        }
    }

    fun setSpeed(tps: Int) {
        val cap = _hardware.value?.maxTicksPerSecond ?: 120
        _status.update { it.copy(targetTps = tps.coerceIn(1, cap)) }
    }

    /** Status térmico do PowerManager (0..6) e memória disponível, quantizados. */
    fun updateTelemetry(thermalStatus: Int, availableMemMb: Long, batteryPct: Int, charging: Boolean) {
        val tier = when {
            thermalStatus >= 4 -> 3 // CRITICAL, EMERGENCY, SHUTDOWN
            thermalStatus == 3 -> 2 // SEVERE
            thermalStatus == 2 -> 1 // MODERATE
            else -> 0
        }
        telemetry = TelemetryInput(tier, (availableMemMb / 512) * 512, (batteryPct / 10) * 10, charging)
        _status.update { it.copy(thermalStatus = thermalStatus) }
    }

    private suspend fun runLoop() {
        var windowStart = System.nanoTime()
        var windowTicks = 0
        var lastPublish = 0L
        while (currentCoroutineContext().isActive) {
            val t0 = System.nanoTime()
            val s = _status.value
            // Acoplamento alostático: aquecimento severo reduz o relógio do mundo.
            val thermalCap = when (telemetry.thermalTier) { 3 -> 2; 2 -> 8; else -> Int.MAX_VALUE }
            val tps = minOf(s.targetTps, thermalCap)
            val report = core.step(telemetry, System.currentTimeMillis())
            writer.submitTickBatch(report.events, report.ledgerEntries)
            if (report.invariantViolations.isNotEmpty()) Log.w(TAG, "invariantes: ${report.invariantViolations}")
            if (rsi.isDue(core.state)) {
                val rec = rsi.runCycle(core)
                writer.submitRsiRecord(rec)
                _status.update { it.copy(rsiCycles = it.rsiCycles + 1, lastMessage = "Ciclo RSI no tick ${rec.tick}: ${if (rec.decision.promoted) "promovido" else "rejeitado"}") }
            }
            if (core.state.tick % SNAPSHOT_EVERY_TICKS == 0L) persistSnapshot()
            windowTicks++
            val now = System.nanoTime()
            if (now - lastPublish > 90_000_000L) { publish(force = false); lastPublish = now }
            if (now - windowStart > 1_000_000_000L) {
                val eff = windowTicks / ((now - windowStart) / 1e9)
                _status.update { it.copy(effectiveTps = eff, thermalCapped = thermalCap < s.targetTps) }
                windowStart = now; windowTicks = 0
            }
            val budgetMs = 1000.0 / tps
            val elapsedMs = (System.nanoTime() - t0) / 1e6
            if (elapsedMs < budgetMs) delay((budgetMs - elapsedMs).toLong().coerceAtLeast(1)) else yield()
        }
    }

    private var lastDashboardNs = 0L
    private fun publish(force: Boolean) {
        _render.value = WorldRenderSnapshot.of(core.state)
        val now = System.nanoTime()
        if (force || now - lastDashboardNs > 900_000_000L) {
            _dashboard.value = DashboardSnapshot.of(core.state)
            lastDashboardNs = now
        }
    }

    private fun persistSnapshot() {
        val state = core.state
        val bytes = WorldSnapshotCodec.encode(state)
        val sha = WorldSnapshotCodec.stateHash(state)
        writer.submitSnapshot(bytes, SnapshotMeta(state.tick, state.seed, state.rulesVersion, sha, state.lastEventHash), ProjectionRows.of(state))
        _status.update { it.copy(lastSnapshotTick = state.tick, lastSnapshotBytes = bytes.size, lastSnapshotSha = sha) }
    }

    // ------------------------------------------------------------- comandos da UI

    suspend fun citizenDetail(id: String): CitizenDetail? = withContext(simDispatcher) {
        if (!_status.value.loaded) null else DashboardSnapshot.detailOf(core.state, id)
    }

    fun injectMisinformation() = scope.launch {
        if (!_status.value.loaded) return@launch
        val rec = core.injectMisinformation()
        rec?.let { writer.submitStressEvent(it) }
        _status.update { it.copy(lastMessage = rec?.description ?: "Sem alvo para o boato") }
        publish(force = true)
    }

    fun injectRealContamination() = scope.launch {
        if (!_status.value.loaded) return@launch
        val rec = core.injectRealContamination()
        rec?.let { writer.submitStressEvent(it) }
        _status.update { it.copy(lastMessage = rec?.description ?: "Nenhuma horta disponível") }
        publish(force = true)
    }

    fun forceRsiCycle() = scope.launch {
        if (!_status.value.loaded) return@launch
        val rec = rsi.runCycle(core)
        writer.submitRsiRecord(rec)
        _status.update { it.copy(rsiCycles = it.rsiCycles + 1, lastMessage = "RSI manual: ${rec.decision.reason}") }
        publish(force = true)
    }

    fun snapshotNow() = scope.launch {
        if (!_status.value.loaded) return@launch
        persistSnapshot()
        _status.update { it.copy(lastMessage = "Snapshot gravado no tick ${core.state.tick}") }
    }

    /** Verifica a restauração: decodifica o snapshot recém-gerado e compara o SHA-256 do estado. */
    suspend fun verifySnapshotRoundTrip(): String = withContext(simDispatcher) {
        if (!_status.value.loaded) return@withContext "Mundo não carregado"
        val bytes = WorldSnapshotCodec.encode(core.state)
        val restored = WorldSnapshotCodec.decode(bytes)
        val a = WorldSnapshotCodec.stateHash(core.state)
        val b = WorldSnapshotCodec.stateHash(restored)
        if (a == b) "Roundtrip OK (${bytes.size / 1024} KB, SHA ${a.take(12)}…)" else "DIVERGÊNCIA no roundtrip"
    }

    fun resetWorld(newSeed: Long) = scope.launch {
        if (!_status.value.loaded) return@launch
        loopJob?.cancel(); loopJob = null
        writer.deleteAllSnapshots()
        core = WorldCore.create(newSeed)
        persistSnapshot()
        _status.update { it.copy(running = false, seed = newSeed, rsiCycles = 0, restoredFromSnapshot = false, lastMessage = "Mundo reiniciado (semente $newSeed)") }
        publish(force = true)
    }

    fun persistedEventCount(): Long = if (::writer.isInitialized) writer.countRows("canonical_events") else 0L

    fun flush() {
        if (::writer.isInitialized) writer.flushBlocking()
    }
}
