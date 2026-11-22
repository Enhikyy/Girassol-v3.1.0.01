package com.enhikyy.girassol.sim

import android.content.Context
import android.util.Log
import com.enhikyy.girassol.audio.AmbientAudioController
import com.enhikyy.girassol.core.ports.CorePorts
import com.enhikyy.girassol.core.rsi.RsiCoordinator
import com.enhikyy.girassol.llm.LocalLlmManager
import com.enhikyy.girassol.nativebridge.ModuleToggles
import com.enhikyy.girassol.nativebridge.NativeModules
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
import kotlinx.coroutines.Dispatchers
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
    val targetTps: Int = SimulationHub.DEFAULT_TPS,
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
    /** v3.9: 1x = 1 tick/s (1 hora simulada ≈ 20 s). O mundo abre sempre vivo em 1x. */
    val SPEEDS = listOf(1, 2, 8, 30)
    const val DEFAULT_TPS = 1
    private const val PREFS = "girassol_hub"
    private const val KEY_SPEED = "speed_tps"
    private const val MAX_WORLD_SPEECH = 400
    private const val MAX_LIVE_SAMPLES = 240

    private val simDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "girassol-worldcore").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + simDispatcher)

    private lateinit var core: WorldCore
    private lateinit var writer: PersistenceWriter
    @Volatile private var ports = CorePorts()
    private val rsi = RsiCoordinator(portsFactory = { ports })
    private var appContext: Context? = null
    private var loopJob: Job? = null

    private val _render = MutableStateFlow<WorldRenderSnapshot?>(null)
    val render: StateFlow<WorldRenderSnapshot?> = _render.asStateFlow()
    private val _dashboard = MutableStateFlow<DashboardSnapshot?>(null)
    val dashboard: StateFlow<DashboardSnapshot?> = _dashboard.asStateFlow()
    private val _status = MutableStateFlow(HubStatus())
    val status: StateFlow<HubStatus> = _status.asStateFlow()
    private val _chat = MutableStateFlow<List<com.enhikyy.girassol.core.world.ChatMessage>>(emptyList())
    /** Conversa permanente (WorldState.chat ⇄ tabela chat_messages). */
    val chat: StateFlow<List<com.enhikyy.girassol.core.world.ChatMessage>> = _chat.asStateFlow()
    private val _chatPending = MutableStateFlow<Set<String>>(emptySet())
    /** Cidadãos que estão pensando na resposta (indicador "digitando"). */
    val chatPending: StateFlow<Set<String>> = _chatPending.asStateFlow()
    private val _hardware = MutableStateFlow<HardwareProfile?>(null)
    val hardware: StateFlow<HardwareProfile?> = _hardware.asStateFlow()

    @Volatile private var telemetry = TelemetryInput()

    private val _worldSpeech = MutableStateFlow<List<WorldSpeech>>(emptyList())
    /** v3.9: falas AUTÔNOMAS dos cidadãos na cena (não persistidas), espelhadas no Chat "Canal aberto". */
    val worldSpeech: StateFlow<List<WorldSpeech>> = _worldSpeech.asStateFlow()
    private val lastSeenSpeech = HashMap<String, Long>()
    private var speechSerial = 0L
    private var speechPrimed = false

    private val _live = MutableStateFlow<List<LiveSample>>(emptyList())
    /** v3.9: série viva amostrada a cada publicação do painel (sparklines desde o tick 1). */
    val live: StateFlow<List<LiveSample>> = _live.asStateFlow()

    private val aborted = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Carrega (ou cria) o mundo uma única vez. Seguro para chamar de qualquer thread. */
    fun ensureLoaded(context: Context) {
        if (_status.value.loaded) return
        val app = context.applicationContext
        scope.launch {
            if (_status.value.loaded) return@launch
            appContext = app
            val saved = runCatching { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SPEED, DEFAULT_TPS) }.getOrDefault(DEFAULT_TPS)
            _status.update { it.copy(targetTps = saved.coerceAtLeast(1)) }
            _hardware.value = runCatching { HardwareAutoTuner.detect(app) }.getOrNull()
            safely("módulos nativos") { NativeModules.load(app) }
            safely("paridade") { Log.i(TAG, "paridade nativa: ${NativeModules.verifyParity().message}") }
            safely("ADPF") { com.enhikyy.girassol.perf.AdpfHints.init(app) }
            safely("LLM") {
                LocalLlmManager.init(app)
                LocalLlmManager.setEnabled(NativeModules.toggles.value.localLlm)
            }
            // v3.8: retoma (HTTP Range) downloads de Piper/Whisper interrompidos — só 8/12 GB
            safely("vozes") {
                com.enhikyy.girassol.audio.VoicePacks.refresh(app, LocalLlmManager.profile)
                com.enhikyy.girassol.audio.VoicePacks.resumeInterrupted(app, LocalLlmManager.profile).takeIf { it.isNotEmpty() }
                    ?.let { Log.i(TAG, "retomando downloads de voz: $it") }
            }
            // v3.6 "Adão e Eva": nenhum modelo de fábrica; o mundo nasce vazio e cada .gguf
            // importado pelo usuário cria exatamente 1 cidadão fundador (ver importFounder).
            ports = buildPorts(NativeModules.toggles.value)
            writer = PersistenceWriter(app)
            val restored = writer.loadLatestSnapshot()
            core = if (restored != null) WorldCore(restored, ports) else WorldCore.create(System.currentTimeMillis(), ports = ports)
            val cap = _hardware.value?.maxTicksPerSecond ?: 30
            _status.update {
                it.copy(loaded = true, restoredFromSnapshot = restored != null, seed = core.state.seed,
                    targetTps = it.targetTps.coerceAtMost(cap),
                    lastMessage = if (restored != null) "Mundo restaurado do snapshot (tick ${core.state.tick})" else "Novo mundo criado (semente ${core.state.seed})")
            }
            // conversas gravadas depois do último snapshot voltam do SQLite WAL
            val lastChat = core.state.chat.lastOrNull()?.id ?: 0L
            writer.loadChat(core.state.seed, lastChat).forEach { m ->
                core.state.chat += m
                if (m.id >= core.state.nextChatSerial) core.state.nextChatSerial = m.id + 1
            }
            safely("fundadores") { restoreFounders(app) }
            if (restored == null) persistSnapshot()
            publish(force = true)
            safely("áudio") {
                if (NativeModules.toggles.value.binauralAudio) AmbientAudioController.start()
                if (NativeModules.toggles.value.voiceTts) com.enhikyy.girassol.audio.CitizenVoice.setEnabled(app, true)
            }
            // v3.9: o mundo nasce VIVO — o relógio anda desde o segundo zero, sem depender do serviço.
            startLoopNow()
        }
    }

    /** Backends: C++ só quando o toggle está ligado E a paridade dourada passou; LLM opcional. */
    private fun buildPorts(t: ModuleToggles) = CorePorts(
        pathfinder = NativeModules.pathfinder(),
        lora = NativeModules.loraTrainer(),
        acceptor = NativeModules.actionAcceptor(),
        neural = if (t.localLlm) LocalLlmManager else null
    )

    /** Liga/desliga módulos nativos. O WorldCore é reconstruído sobre o MESMO estado na thread da simulação. */
    fun updateModules(context: Context, transform: (ModuleToggles) -> ModuleToggles) {
        NativeModules.update(context.applicationContext, transform)
        val t = NativeModules.toggles.value
        LocalLlmManager.setEnabled(t.localLlm)
        if (t.binauralAudio && !AmbientAudioController.running) AmbientAudioController.start()
        if (!t.binauralAudio && AmbientAudioController.running) AmbientAudioController.stop()
        com.enhikyy.girassol.audio.CitizenVoice.setEnabled(context, t.voiceTts)
        scope.launch {
            if (!_status.value.loaded) return@launch
            ports = buildPorts(t)
            core = WorldCore(core.state, ports)
            _status.update { it.copy(lastMessage = "Módulos: nav ${if (ports.pathfinder === com.enhikyy.girassol.core.ports.KotlinPathfinder) "Kotlin" else "C++"} · LoRA ${ports.lora.backend} · LLM ${if (ports.neural != null) "ligado" else "desligado"}") }
        }
    }

    /** O disjuntor (MemoryGuard) já descarrega o LLM; aqui só aliviamos caches do mundo. */
    @Suppress("UNUSED_PARAMETER")
    fun onTrimMemory(level: Int) = Unit

    /** Falhas de subsistemas opcionais (JNI, ADPF, vozes) nunca impedem o mundo de nascer. */
    private inline fun safely(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { Log.w(TAG, "$what indisponível: ${t.message}") }
    }

    fun start() {
        scope.launch { startLoopNow() }
    }

    /** Executa na thread do mundo. Idempotente. */
    private fun startLoopNow() {
        if (!_status.value.loaded || loopJob?.isActive == true) return
        _status.update { it.copy(running = true) }
        loopJob = scope.launch { runLoop() }
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
        val v = tps.coerceIn(1, cap)
        _status.update { it.copy(targetTps = v) }
        appContext?.let { ctx -> runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SPEED, v).apply() } }
    }

    /** Status térmico do PowerManager (0..6) e memória disponível, quantizados. */
    fun updateTelemetry(thermalStatus: Int, availableMemMb: Long, batteryPct: Int, charging: Boolean, socTempC: Double = Double.NaN) {
        val tier = when {
            thermalStatus >= 4 -> 3 // CRITICAL, EMERGENCY, SHUTDOWN
            thermalStatus == 3 -> 2 // SEVERE
            thermalStatus == 2 -> 1 // MODERATE
            else -> 0
        }
        // Temperatura quantizada a 1 °C para manter o replay determinístico por evento.
        telemetry = TelemetryInput(tier, (availableMemMb / 512) * 512, (batteryPct / 10) * 10, charging,
            socTempC = if (socTempC.isNaN()) 32.0 else Math.round(socTempC).toDouble())
        LocalLlmManager.setThermalTier(tier)
        _status.update { it.copy(thermalStatus = thermalStatus) }
    }

    private suspend fun runLoop() {
        var windowStart = System.nanoTime()
        var windowTicks = 0
        var lastPublish = 0L
        com.enhikyy.girassol.perf.AdpfHints.registerCurrentThread("girassol-sim")
        while (currentCoroutineContext().isActive) {
            val t0 = System.nanoTime()
            val s = _status.value
            // Acoplamento alostático: aquecimento severo reduz o relógio do mundo.
            val thermalCap = when (telemetry.thermalTier) { 3 -> 2; 2 -> 8; else -> Int.MAX_VALUE }
            val tps = minOf(s.targetTps, thermalCap)
            val report = core.step(telemetry, System.currentTimeMillis())
            com.enhikyy.girassol.perf.AdpfHints.updateTarget((1_000_000_000L / tps.coerceAtLeast(1)))
            com.enhikyy.girassol.perf.AdpfHints.reportWork(System.nanoTime() - t0)
            writer.submitTickBatch(report.events, report.ledgerEntries)
            // Sono (Sharp-Wave Ripple Replay) → treino LoRA real do modelo GGUF nas memórias
            // episódicas de maior utilidade; roda na thread do LLM e não bloqueia o mundo.
            if (report.events.any { it.type == "LORA_SONO" }) {
                val texts = core.topUtilityEpisodes(48).map { it.first }
                LocalLlmManager.trainLoraAsync(texts) { json ->
                    _status.update { it.copy(lastMessage = "LoRA GGUF no sono: " + json.take(160)) }
                }
            }
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
        if (_chat.value.size != core.state.chat.size || _chat.value.lastOrNull()?.id != core.state.chat.lastOrNull()?.id)
            _chat.value = core.state.chat.takeLast(500)
        collectWorldSpeech()
        val now = System.nanoTime()
        if (force || now - lastDashboardNs > 900_000_000L) {
            val d = DashboardSnapshot.of(core.state)
            _dashboard.value = d
            sampleLive(d)
            lastDashboardNs = now
            AmbientAudioController.update(_dashboard.value, _render.value)
            LocalLlmManager.maybeReload()
        }
    }

    /** Detecta falas novas (WorldCore.speak) e as publica no feed do Chat. */
    private fun collectWorldSpeech() {
        val st = core.state
        val fresh = ArrayList<WorldSpeech>()
        for (c in st.aliveCitizens()) {
            val t = c.lastSpeechTick
            if (t < 0 || c.lastSpeech.isBlank()) continue
            if (lastSeenSpeech[c.id] == t) continue
            lastSeenSpeech[c.id] = t
            if (!speechPrimed) continue // falas anteriores à abertura não viram mensagens novas
            fresh += WorldSpeech(
                key = ++speechSerial, tick = t, wallClockMs = System.currentTimeMillis(), citizenId = c.id, name = c.name,
                text = c.lastSpeech, neural = c.intention?.viaNeural == true, thought = c.lastThought,
                action = (c.intention?.action ?: c.lastAction)?.label
            )
        }
        speechPrimed = true
        if (fresh.isNotEmpty()) _worldSpeech.update { (it + fresh).takeLast(MAX_WORLD_SPEECH) }
    }

    private fun sampleLive(d: DashboardSnapshot) {
        val alive = d.citizens.filter { it.alive }
        val s = LiveSample(
            tick = d.tick,
            population = alive.size,
            meanMood = if (alive.isEmpty()) 0.0 else alive.map { it.mood }.average(),
            meanNeed = if (alive.isEmpty()) 0.0 else alive.map { it.meanNeed }.average(),
            meanEnergy = if (alive.isEmpty()) 0.0 else alive.map { it.energy }.average(),
            meanSocial = if (alive.isEmpty()) 0.0 else alive.map { it.social }.average()
        )
        _live.update { prev -> if (prev.lastOrNull()?.tick == s.tick) prev else (prev + s).takeLast(MAX_LIVE_SAMPLES) }
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
        core = WorldCore.create(newSeed, ports = ports)
        persistSnapshot()
        _status.update { it.copy(running = false, seed = newSeed, rsiCycles = 0, restoredFromSnapshot = false, lastMessage = "Mundo reiniciado (semente $newSeed)") }
        publish(force = true)
    }

    /**
     * Snapshot completo para exportar como .grs: mundo (cabeçalho GRS + SHA-256) seguido do bloco
     * GRSB (FNV-1a 64) do Orçamento Sináptico, no contêiner [com.enhikyy.girassol.data.GirassolRecordCodec].
     */
    suspend fun exportSnapshotBytes(): ByteArray? {
        val world = withContext(simDispatcher) { if (!_status.value.loaded) null else WorldSnapshotCodec.encode(core.state) } ?: return null
        val synaptic = com.enhikyy.girassol.synaptic.SynapticBudgetEngine.serialize()
        return com.enhikyy.girassol.data.GirassolRecordCodec.encode(world, synaptic)
    }

    /** Importa um .grs (valida cabeçalho e SHA-256) e substitui o mundo atual. */
    suspend fun importSnapshot(bytes: ByteArray): String {
        val container = runCatching { com.enhikyy.girassol.data.GirassolRecordCodec.decode(bytes) }
            .getOrElse { return "Contêiner .grs inválido: ${it.message}" }
        val worldMsg = importWorld(container.world)
        val sb = container.synaptic ?: return worldMsg
        return "$worldMsg · " + com.enhikyy.girassol.synaptic.SynapticBudgetEngine.restore(sb)
    }

    private suspend fun importWorld(bytes: ByteArray): String = withContext(simDispatcher) {
        if (!_status.value.loaded) return@withContext "Mundo não carregado"
        val state = runCatching { WorldSnapshotCodec.decode(bytes) }.getOrElse { return@withContext "Snapshot inválido: ${it.message}" }
        val wasRunning = loopJob?.isActive == true
        loopJob?.cancel(); loopJob = null
        core = WorldCore(state, ports)
        persistSnapshot()
        _status.update { it.copy(running = false, seed = state.seed, restoredFromSnapshot = true,
            lastMessage = "Snapshot .grs importado (tick ${state.tick}, semente ${state.seed})") }
        publish(force = true)
        if (wasRunning) start()
        "Importado: tick ${state.tick} · ${bytes.size / 1024} KB"
    }

    /** Busca FTS (bm25) nas memórias persistidas no SQLite WAL. */
    fun searchMemories(text: String, owner: String? = null): List<com.enhikyy.girassol.data.FtsHit> =
        if (::writer.isInitialized) writer.searchMemories(text, owner) else emptyList()

    fun ftsModule(): String = if (::writer.isInitialized) writer.ftsModule() else "-"

    /** Cadeia de auditoria SHA-256 no SQLite WAL (recalcula cada event_hash e o encadeamento). */
    suspend fun verifyHashChain(): String {
        if (!::writer.isInitialized || !_status.value.loaded) return "Mundo não carregado"
        val head = withContext(simDispatcher) { core.state.lastEventHash to core.state.eventSeq }
        writer.flushBlocking()
        val r = withContext(Dispatchers.IO) { writer.verifyHashChain(liveHeadHash = head.first, liveSeq = head.second) }
        return if (r.ok) "Cadeia SHA-256 íntegra: ${r.checked} eventos · cabeça ${r.headHash.take(12)}…"
        else "Cadeia QUEBRADA em #${r.firstBadSeq}: ${r.reason}"
    }

    // ------------------------------------------------------------- Biblioteca (Processo 19)

    /** Ingestão documental offline (TXT/EPUB/PDF/Parquet/áudio transcrito) → evento CONHECIMENTO. */
    suspend fun ingestDocument(doc: com.enhikyy.girassol.core.knowledge.ParsedDocument): String = withContext(simDispatcher) {
        if (!_status.value.loaded) return@withContext "Mundo não carregado"
        val rec = core.ingestKnowledge(doc) ?: return@withContext "Documento já está na biblioteca (ou vazio)"
        _status.update { it.copy(lastMessage = "Biblioteca: ${rec.title} (${rec.chunks} trechos)") }
        publish(force = true)
        "Ingerido: ${rec.title} · ${rec.format} · ${rec.chunks} trechos"
    }

    suspend fun topUtilityEpisodes(limit: Int): List<Pair<String, Double>> = withContext(simDispatcher) {
        if (!_status.value.loaded) emptyList() else core.topUtilityEpisodes(limit)
    }

    // ------------------------------------------------------------- MeshSync LAN

    suspend fun rawSnapshot(): Triple<ByteArray, Long, Long>? = withContext(simDispatcher) {
        if (!_status.value.loaded) null else Triple(WorldSnapshotCodec.encodeRaw(core.state), core.state.seed, core.state.tick)
    }

    suspend fun emigrate(citizenId: String, deviceId: String) = withContext(simDispatcher) {
        if (!_status.value.loaded) null else core.emigrate(citizenId, deviceId)?.also { publish(force = true) }
    }

    /** Desfaz uma emigração cujo envio falhou (o cidadão volta como imigrante do próprio aparelho). */
    suspend fun admitMigrant(packet: com.enhikyy.girassol.core.mesh.MeshSyncProtocol.MigrationPacket): String = withContext(simDispatcher) {
        if (!_status.value.loaded) return@withContext "Mundo não carregado"
        val c = core.admitMigrant(packet)
        _status.update { it.copy(lastMessage = "MeshSync: ${c.name} chegou de ${packet.originDevice}") }
        publish(force = true)
        "${c.name} admitido como ${c.id}"
    }

    /**
     * Cria o cidadão fundador vinculado a um modelo .gguf (1 modelo = 1 pessoa). Idempotente:
     * se o modelo já tem um fundador vivo, devolve-o.
     */
    suspend fun spawnFounder(modelKey: String, name: String? = null, sex: String? = null): String = withContext(simDispatcher) {
        if (!_status.value.loaded) return@withContext "Mundo não carregado"
        val c = core.spawnFounder(modelKey, name, sex)
        appContext?.let { LocalLlmManager.bindFounder(it, modelKey, c.id, c.name, c.sex) }
        persistSnapshot()
        _status.update { it.copy(lastMessage = "${c.name} nasceu do modelo $modelKey") }
        publish(force = true)
        "${c.name} vive agora com a mente de ${modelKey.removeSuffix(".gguf")}"
    }

    /** Importa um .gguf pelo SAF e cria o fundador correspondente. */
    suspend fun importFounder(context: Context, uri: android.net.Uri, name: String?, sex: String?): String {
        val r = withContext(Dispatchers.IO) {
            runCatching { LocalLlmManager.importModel(context.applicationContext, uri) }
                .getOrElse { LocalLlmManager.ImportResult(null, "Falha: ${it.message}") }
        }
        val key = r.key ?: return r.message
        return r.message + " · " + spawnFounder(key, name, sex)
    }

    /**
     * Ao reabrir o app: todo modelo do registro fixo (`registered_models`) tem o seu fundador.
     * Se o snapshot já o contém (caso normal), só confirma o vínculo; senão recria com o nome e
     * o sexo gravados no registro.
     */
    private fun restoreFounders(context: Context) {
        for (r in LocalLlmManager.registeredModels(context)) {
            if (LocalLlmManager.modelFor(r.key) == null) continue
            val existing = core.state.citizens.firstOrNull { it.alive && it.modelKey == r.key }
            val c = existing ?: core.spawnFounder(r.key, r.founderName, r.founderSex)
            if (r.founderId != c.id) LocalLlmManager.bindFounder(context, r.key, c.id, c.name, c.sex)
        }
    }

    /**
     * Envia uma mensagem do Chat para [citizenId] (null = canal aberto: respondem até 2 cidadãos).
     * Cada cidadão responde com o SEU .gguf (ou da linhagem); sem modelo disponível, responde o
     * deliberador simbólico com base no estado real dele. Tudo entra na cadeia de eventos,
     * no .grs (WorldState.chat) e na tabela chat_messages.
     */
    fun sendChat(citizenId: String?, text: String) = scope.launch {
        if (!_status.value.loaded || text.isBlank()) return@launch
        val msg = core.postUserChat(citizenId, text, System.currentTimeMillis())
        writer.submitChat(core.state.seed, listOf(msg))
        publish(force = true)
        val alive = core.state.aliveCitizens()
        val responders = if (citizenId != null) alive.filter { it.id == citizenId }
            else alive.sortedWith(compareBy<com.enhikyy.girassol.core.world.Citizen> { if (it.founder) 0 else 1 }
                .thenBy { it.needs[com.enhikyy.girassol.core.world.Need.SOCIAL] ?: 1.0 }.thenBy { it.id }).take(2)
        for (c in responders) {
            val prompt = core.chatPromptFor(c.id, text) ?: continue
            _chatPending.update { it + c.id }
            aborted.remove(c.id)
            LocalLlmManager.chatAsync(c.effectiveModelKey, c.id, prompt) { reply, engine ->
                scope.launch {
                    if (aborted.remove(c.id)) { _chatPending.update { it - c.id }; return@launch }
                    val finalText = reply ?: core.symbolicReply(c.id, text)
                    val finalEngine = if (reply != null) engine else "simbolico"
                    core.postCitizenReply(c.id, citizenId, finalText, finalEngine, System.currentTimeMillis())?.let { r ->
                        writer.submitChat(core.state.seed, listOf(r))
                        lastSeenSpeech[c.id] = core.state.tick // a resposta já é bolha de chat; não duplica no feed
                        if (NativeModules.toggles.value.voiceTts) com.enhikyy.girassol.audio.CitizenVoice.speakChat(c.id, c.sex, finalText)
                    }
                    _chatPending.update { it - c.id }
                    publish(force = true)
                }
            }
        }
    }

    /** v3.9: botão Parar da cápsula — interrompe a inferência em curso e descarta as respostas pendentes. */
    fun stopChat() {
        val pending = _chatPending.value
        aborted.addAll(pending)
        _chatPending.value = emptySet()
        runCatching { LocalLlmManager.abortGeneration() }
    }

    /** Pausa/ativa o modelo de um fundador (painel da aba Sistema). */
    fun setModelActive(context: Context, key: String, active: Boolean) {
        LocalLlmManager.setModelActive(context, key, active)
        _status.update { it.copy(lastMessage = if (active) "$key ativo" else "$key pausado (modo simbólico)") }
    }

    /** Modelos importados sem fundador vivo (ex.: depois de reiniciar o mundo). */
    fun modelsWithoutFounder(): List<String> {
        val alive = _dashboard.value?.citizens?.filter { it.alive }?.mapNotNull { it.modelKey }?.toSet() ?: emptySet()
        return LocalLlmManager.models.value.map { it.key }.filter { it !in alive }
    }

    fun aliveCitizenIds(): List<Pair<String, String>> = _dashboard.value?.citizens?.map { it.id to it.name } ?: emptyList()

    fun persistedEventCount(): Long = if (::writer.isInitialized) writer.countRows("canonical_events") else 0L

    fun flush() {
        if (::writer.isInitialized) writer.flushBlocking()
    }
}
