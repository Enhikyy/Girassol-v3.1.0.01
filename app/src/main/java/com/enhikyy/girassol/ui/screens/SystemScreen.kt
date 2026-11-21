package com.enhikyy.girassol.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import com.enhikyy.girassol.sim.SimClock
import com.enhikyy.girassol.ui.components.GlassPill
import com.enhikyy.girassol.ui.glass.GlassTone
import com.enhikyy.girassol.ui.glass.liquidGlass
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enhikyy.girassol.audio.AmbientAudioController
import com.enhikyy.girassol.llm.LocalLlmManager
import com.enhikyy.girassol.nativebridge.GeaNativeBridge
import com.enhikyy.girassol.nativebridge.NativeModules
import com.enhikyy.girassol.render3d.Render3dTelemetry
import com.enhikyy.girassol.nativebridge.NativeDiagnosticReport
import com.enhikyy.girassol.nativebridge.NativeDiagnostics
import com.enhikyy.girassol.service.GirassolContinuousService
import com.enhikyy.girassol.sim.SimulationHub
import com.enhikyy.girassol.ui.components.Collapsible
import com.enhikyy.girassol.ui.components.KeyValue
import com.enhikyy.girassol.ui.components.Shapes
import com.enhikyy.girassol.ui.components.SectionCard
import com.enhikyy.girassol.ui.components.f
import com.enhikyy.girassol.ui.theme.Lumon
import com.enhikyy.girassol.ui.theme.MonoStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun thermalLabel(s: Int) = when (s) {
    0 -> "Nenhum"; 1 -> "Leve"; 2 -> "Moderado"; 3 -> "Severo"; 4 -> "Crítico"; 5 -> "Emergência"; 6 -> "Desligamento"; else -> "?"
}

@Composable
fun SystemScreen() {
    val context = LocalContext.current
    val status by SimulationHub.status.collectAsStateWithLifecycle()
    val hw by SimulationHub.hardware.collectAsStateWithLifecycle()
    val dash by SimulationHub.dashboard.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<NativeDiagnosticReport?>(null) }
    var diagRunning by remember { mutableStateOf(false) }
    var roundTrip by remember { mutableStateOf("") }
    var eventCount by remember { mutableStateOf(-1L) }
    var confirmReset by remember { mutableStateOf(false) }
    val toggles by NativeModules.toggles.collectAsStateWithLifecycle()
    val parity by NativeModules.parity.collectAsStateWithLifecycle()
    val audio by AmbientAudioController.status.collectAsStateWithLifecycle()
    val llm by LocalLlmManager.stats.collectAsStateWithLifecycle()
    val r3d by Render3dTelemetry.stats.collectAsStateWithLifecycle()
    var bench by remember { mutableStateOf("") }
    var benchRunning by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp).testTag("system_list"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { WorldEngineCard() }
        item { TelemetryCard() }
        item { KnowledgeLibraryPanel(dash) }
        item { MeshSyncPanel(dash) }
        item {
            SectionCard("Execução contínua") {
                KeyValue("Estado térmico", thermalLabel(status.thermalStatus) + if (status.thermalCapped) " (limitado)" else "")
                KeyValue("Perfil do aparelho", LocalLlmManager.profile.label + " · " + LocalLlmManager.profile.description)
                KeyValue("Pool de modelos (llama_model)", "${llm.poolResident}/${llm.poolCapacity} residentes · ${llm.poolHits} trocas só de contexto · ${llm.poolColdLoads} cargas · ${llm.poolEvictions} despejos")
                if (llm.dynamicBudgetMb >= 0) KeyValue("Orçamento dinâmico de RAM",
                    "${llm.dynamicBudgetMb}/${llm.budgetMb} MB · reserva ${llm.budgetReserveMb} MB (Filament + gráfico + WAL + folga) · fonte ${llm.budgetSource}" +
                        if (llm.pressureEvictions > 0) " · ${llm.pressureEvictions} despejos por pressão" else "")
                if (llm.poolKeys.isNotEmpty()) KeyValue("Residentes", llm.poolKeys.joinToString(", "))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { GirassolContinuousService.start(context) }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Lumon.Sunflower, contentColor = Lumon.Background)) { Text("Iniciar serviço") }
                    OutlinedButton(onClick = { GirassolContinuousService.stop(context) }, modifier = Modifier.weight(1f)) { Text("Parar") }
                }
                Text("O serviço em primeiro plano mantém o mundo vivo com a tela desligada e é reiniciado após o boot.",
                    style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
            }
        }
        item { FounderModelsPanel() }
        item { SigningPanel() }
        item {
            SectionCard("Persistência e determinismo") {
                KeyValue("Semente do mundo", status.seed.toString())
                KeyValue("Restaurado de snapshot", if (status.restoredFromSnapshot) "sim" else "não")
                KeyValue("Último snapshot", if (status.lastSnapshotTick < 0) "-" else "tick ${status.lastSnapshotTick} · ${status.lastSnapshotBytes / 1024} KB")
                if (status.lastSnapshotSha.isNotEmpty()) Text("SHA-256 ${status.lastSnapshotSha.take(32)}…", style = MonoStyle, color = Lumon.CreamDim)
                dash?.mind?.recentEvents?.firstOrNull()?.let { Text("Cabeça da cadeia #${it.seq} ${it.hash.take(32)}…", style = MonoStyle, color = Lumon.CreamDim) }
                if (eventCount >= 0) KeyValue("Eventos canônicos no SQLite", eventCount.toString())
                if (roundTrip.isNotEmpty()) KeyValue("Verificação", roundTrip)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { SimulationHub.snapshotNow() }, modifier = Modifier.weight(1f)) { Text("Snapshot") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            roundTrip = SimulationHub.verifySnapshotRoundTrip()
                            eventCount = withContext(Dispatchers.IO) { SimulationHub.persistedEventCount() }
                        }
                    }, modifier = Modifier.weight(1f)) { Text("Verificar") }
                }
                if (status.lastMessage.isNotEmpty()) Text(status.lastMessage, style = MaterialTheme.typography.bodySmall, color = Lumon.Teal)
            }
        }
        item {
            Collapsible("Detalhes técnicos do hardware", "hw_details_toggle",
                subtitle = "CPU (avx2/dotprod/fp16), memória, GPU, energia, módulos C++ e diagnóstico") {
            run {
                SectionCard("Hardware (autodetectado)") {
                    val h = hw
                    if (h == null) Text("Detectando…", color = Lumon.CreamDim) else {
                        KeyValue("Aparelho", "${h.manufacturer} ${h.model}")
                        KeyValue("SoC", "${h.socManufacturer} ${h.socModel}")
                        KeyValue("ABIs", h.abis.joinToString())
                        KeyValue("Núcleos · RAM", "${h.cpuCores} · ${h.totalRamMb} MB")
                        KeyValue("Classe de desempenho", h.performanceClass.toString())
                        KeyValue("Nível · teto de ticks/s", "${h.tier.label} · ${h.maxTicksPerSecond}")
                        KeyValue("Núcleo nativo C++", if (h.nativeLoaded) "carregado" else "indisponível (fallback Kotlin)")
                        h.nativeCapabilities.toSortedMap().forEach { (k, v) -> KeyValue(k, v) }
                    }
                }
            }
            LlmCpuPanel()
            MemoryPanel()
            PowerPanel()
            GpuPanel()
            run {
                SectionCard("Módulos nativos C++") {
                    ModuleSwitch("Navegação A* nativa", "toggle_nav", toggles.nativeNavigation,
                        if (parity.navOk) "paridade OK ${parity.navSignature}" else "sem paridade: Kotlin") { v -> SimulationHub.updateModules(context) { it.copy(nativeNavigation = v) } }
                    ModuleSwitch("LoRA on-device no sono", "toggle_lora", toggles.nativeLora,
                        if (parity.loraOk) "paridade OK ${parity.loraSignature}" else "sem paridade: Kotlin") { v -> SimulationHub.updateModules(context) { it.copy(nativeLora = v) } }
                    ModuleSwitch("Áudio binaural (AAudio)", "toggle_audio", toggles.binauralAudio, audio) { v -> SimulationHub.updateModules(context) { it.copy(binauralAudio = v) } }
                    val voice by com.enhikyy.girassol.audio.CitizenVoice.status.collectAsStateWithLifecycle()
                    ModuleSwitch("Vozes TTS offline (pt-BR)", "toggle_tts", toggles.voiceTts,
                        "${voice.message} · ${voice.spoken} falas") { v -> SimulationHub.updateModules(context) { it.copy(voiceTts = v) } }
                    ModuleSwitch("LLM local (llama.cpp + GBNF)", "toggle_llm", toggles.localLlm,
                        "${llm.status.label}${if (!LocalLlmManager.engineCompiled) "" else " · orçamento ${llm.budgetMb} MB"}") { v -> SimulationHub.updateModules(context) { it.copy(localLlm = v) } }
                    KeyValue("Paridade dourada", parity.message, if (parity.navOk && parity.loraOk && parity.memoryOk) Lumon.Ok else Lumon.Sunflower)
                    KeyValue("Memória binária bit[256]", if (parity.memoryOk) "popcount C++ = Kotlin ${parity.memorySignature}" else "sem paridade: ${parity.memorySignature}")
                    KeyValue("Filament 3D", if (r3d.active) "${r3d.fps.f(0)} fps · ${r3d.vertices} vértices · ${r3d.citizens} cidadãos" else r3d.error.ifEmpty { "inativo (Modo Eco 2D)" })
                    Text("Backends C++ só substituem os Kotlin após reproduzirem as assinaturas douradas neste aparelho; trocar um módulo reconstrói o WorldCore sobre o mesmo estado.",
                        style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
                }
            }
            run {
                SectionCard("Diagnóstico nativo") {
                    OutlinedButton(enabled = !diagRunning, modifier = Modifier.testTag("system_diagnostics"), onClick = {
                        diagRunning = true
                        scope.launch {
                            report = withContext(Dispatchers.Default) { NativeDiagnostics.run() }
                            withContext(Dispatchers.Default) { NativeModules.verifyParity() }
                            diagRunning = false
                        }
                    }) { Text(if (diagRunning) "Executando…" else "Executar autoteste e paridade C++") }
                    if (!GeaNativeBridge.isNativeLoaded) Text("Biblioteca nativa não carregada neste aparelho.", color = Lumon.Danger)
                    report?.let { r ->
                        Column(Modifier.testTag("diag_result")) {
                            KeyValue("Autoteste C++", r.selfTest)
                            KeyValue("Paridade Tsetlin Kotlin↔C++", r.parity)
                            KeyValue("Tsetlin nativo", r.benchmark)
                            KeyValue("Tsetlin Kotlin", r.kotlinBenchmark)
                        }
                    }
                    OutlinedButton(enabled = !benchRunning, modifier = Modifier.testTag("system_benchmark"), onClick = {
                        benchRunning = true
                        scope.launch {
                            bench = withContext(Dispatchers.Default) { NativeDiagnostics.cpuBenchmark() }
                            benchRunning = false
                        }
                    }) { Text(if (benchRunning) "Medindo…" else "Benchmark de CPU") }
                    if (bench.isNotEmpty()) Text(bench, style = MonoStyle, color = Lumon.Cream, modifier = Modifier.testTag("benchmark_result"))
                }
            }
                HashChainPanel(dash)
                SectionCard("Voz dos cidadãos") { PocketTtsToggle(); VoiceBackendsPanel() }
            }
        }
        item {
            SectionCard("Zona de perigo") {
                Button(onClick = { confirmReset = true }, colors = ButtonDefaults.buttonColors(containerColor = Lumon.Danger)) { Text("Reiniciar mundo") }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reiniciar o mundo?") },
            text = { Text("Todos os cidadãos, instituições e memórias atuais serão substituídos por uma nova cidade com outra semente. Não é possível desfazer.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; SimulationHub.resetWorld(System.currentTimeMillis()) }) { Text("Reiniciar") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun ModuleSwitch(label: String, tag: String, checked: Boolean, detail: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Lumon.Cream)
            Text(detail, style = MonoStyle, color = Lumon.CreamDim, maxLines = 2)
        }
        Switch(
            checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag),
            colors = SwitchDefaults.colors(checkedThumbColor = Lumon.Background, checkedTrackColor = Lumon.Sunflower, uncheckedTrackColor = Lumon.SurfaceHigh)
        )
    }
}

/**
 * Cidadãos-Modelo: cada .gguf importado (registro `registered_models` no SQLite WAL) com o
 * fundador ligado a ele. Ativar/pausar troca o cidadão entre o próprio LLM e o deliberador
 * simbólico; remover apaga o arquivo do armazenamento privado (o cidadão segue vivo, simbólico).
 */
@Composable
fun FounderModelsPanel() {
    val context = LocalContext.current
    val models by LocalLlmManager.models.collectAsStateWithLifecycle()
    val dash by SimulationHub.dashboard.collectAsStateWithLifecycle()
    val llm by LocalLlmManager.stats.collectAsStateWithLifecycle()
    val importer = rememberFounderImporter()
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    val founders = dash?.citizens?.filter { it.alive && it.modelKey != null }?.associateBy { it.modelKey!! }.orEmpty()
    val profile = LocalLlmManager.profile
    SectionCard("Cidadãos-Modelo (${models.size} .gguf)", Modifier.testTag("founder_models")) {
        if (!LocalLlmManager.engineCompiled) Text("Motor llama.cpp não compilado neste build: os cidadãos usam o deliberador simbólico.",
            style = MaterialTheme.typography.bodySmall, color = Lumon.Sunflower)
        if (models.isEmpty()) Text("Nenhum modelo importado. Cada .gguf (F16, BF16, Q8_0, Q5_K, Q4_K_M, Q4_0…) vira exatamente um cidadão fundador.",
            style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
        for (m in models) {
            val f = founders[m.key]
            val ramMb = LlmQuantPolicyRam.estimateMb(m.bytes, profile)
            Column(Modifier.fillMaxWidth().testTag("model_row_${m.key}")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f?.let { "${it.name} · g${it.generation}" } ?: "sem cidadão", style = MaterialTheme.typography.titleSmall,
                            color = if (f != null) Lumon.Sunflower else Lumon.CreamDim)
                        Text("${m.displayName} · ${m.bytes / (1024 * 1024)} MB · ~$ramMb MB RAM ativa${if (llm.modelName == m.key) " · residente" else ""}",
                            style = MonoStyle, color = Lumon.CreamDim, maxLines = 2)
                    }
                    Switch(checked = m.active, onCheckedChange = { SimulationHub.setModelActive(context, m.key, it) },
                        modifier = Modifier.testTag("model_active_${m.key}"),
                        colors = SwitchDefaults.colors(checkedThumbColor = Lumon.Background, checkedTrackColor = Lumon.Sunflower, uncheckedTrackColor = Lumon.SurfaceHigh))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (f == null) OutlinedButton(onClick = { importer(m.key) }, shape = Shapes.Control) { Text("Dar vida") }
                    OutlinedButton(onClick = { confirmRemove = m.key }, shape = Shapes.Control) { Text("Remover") }
                }
            }
        }
        Button(enabled = LocalLlmManager.engineCompiled, onClick = { importer(null) }, shape = Shapes.Control,
            modifier = Modifier.fillMaxWidth().testTag("system_import_gguf"),
            colors = ButtonDefaults.buttonColors(containerColor = Lumon.Sunflower, contentColor = Lumon.Background)) { Text("Importar .gguf (novo fundador)") }
        Text("Perfil ${profile.label}: n_ctx ${profile.nCtx}; os pesos ficam em mmap (páginas de arquivo, não heap) e um modelo fica residente por vez — os demais entram por turno.",
            style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
    }
    confirmRemove?.let { key ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remover $key?") },
            text = { Text("O arquivo sai do armazenamento privado. O cidadão continua vivo com o deliberador simbólico (e a linhagem perde a mente herdada).") },
            confirmButton = { TextButton(onClick = { LocalLlmManager.removeModel(context, key); confirmRemove = null }) { Text("Remover") } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancelar") } }
        )
    }
}

private object LlmQuantPolicyRam {
    fun estimateMb(bytes: Long, p: com.enhikyy.girassol.llm.LlmQuantPolicy.DeviceProfile): Long =
        com.enhikyy.girassol.llm.LlmQuantPolicy.activeRamEstimate(bytes, p.nCtx, p.kvType) / (1024 * 1024)
}

/** v3.9 — Motor do mundo: relógio simulado, estado e pílulas de velocidade (1x | 2x | 8x | 30x). */
@Composable
fun WorldEngineCard() {
    val status by SimulationHub.status.collectAsStateWithLifecycle()
    val snap by SimulationHub.render.collectAsStateWithLifecycle()
    val hw by SimulationHub.hardware.collectAsStateWithLifecycle()
    SectionCard("Motor do mundo", Modifier.testTag("world_engine")) {
        val tick = snap?.tick ?: 0L
        Text("${SimClock.label(tick)} · ${SimClock.period(tick)}", style = MaterialTheme.typography.titleMedium, color = Lumon.Sunflower,
            modifier = Modifier.testTag("engine_clock"))
        Text((if (status.running) "Rodando" else "Pausado") + " · ${status.effectiveTps.f(1)} ticks/s (alvo ${status.targetTps}) · tick $tick",
            style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim, modifier = Modifier.testTag("engine_status"))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SimulationHub.SPEEDS.forEach { s ->
                GlassPill("${s}x", status.targetTps == s, "speed_$s", Modifier.weight(1f)) { SimulationHub.setSpeed(s); if (!status.running) SimulationHub.start() }
            }
        }
        val cap = hw?.maxTicksPerSecond
        if (cap != null && cap < SimulationHub.SPEEDS.last()) Text("Este aparelho limita o mundo a $cap ticks/s.", style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
        OutlinedButton(onClick = { if (status.running) SimulationHub.pause() else SimulationHub.start() }, shape = Shapes.Capsule,
            modifier = Modifier.fillMaxWidth().testTag("engine_toggle")) { Text(if (status.running) "Pausar o mundo" else "Retomar o mundo") }
    }
}

/** v3.9 — Telemetria em tempo real com barras de vidro: RAM, Filament, SQLite WAL e mmap por .gguf. */
@Composable
fun TelemetryCard() {
    val context = LocalContext.current
    val mem by com.enhikyy.girassol.system.MemoryGuard.state.collectAsStateWithLifecycle()
    val models by LocalLlmManager.models.collectAsStateWithLifecycle()
    var walBytes by remember { mutableStateOf(0L) }
    var filamentBytes by remember { mutableStateOf(0L) }
    var resident by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    androidx.compose.runtime.LaunchedEffect(models) {
        while (true) {
            withContext(Dispatchers.IO) {
                walBytes = runCatching { java.io.File(context.getDatabasePath(com.enhikyy.girassol.data.GirassolDatabase.DB_NAME).path + "-wal").length() }.getOrDefault(0L)
                filamentBytes = com.enhikyy.girassol.render3d.FilamentMemory.nativeBytes()
                resident = com.enhikyy.girassol.system.MmapResidency.residentKb(models.map { it.path })
            }
            kotlinx.coroutines.delay(2_000)
        }
    }
    SectionCard("Telemetria em tempo real", Modifier.testTag("telemetry_card")) {
        val total = mem.sysTotalMb.coerceAtLeast(1)
        val used = (mem.sysTotalMb - mem.sysAvailMb).coerceAtLeast(0)
        GlassBar("RAM do aparelho", used.toDouble() / total, "$used / $total MB", "tele_ram")
        val rssMb = if (mem.rssKb > 0) mem.rssKb / 1024 else 0
        GlassBar("Este app (VmRSS)", rssMb.toDouble() / total, "$rssMb MB", "tele_rss")
        val filMb = filamentBytes / (1024.0 * 1024.0)
        GlassBar("Filament 3D (nativo)", (filMb / 256.0).coerceIn(0.0, 1.0), "${filMb.f(1)} MB", "tele_filament")
        val walMb = walBytes / (1024.0 * 1024.0)
        GlassBar("SQLite WAL", (walMb / 64.0).coerceIn(0.0, 1.0), "${walMb.f(2)} MB", "tele_wal")
        if (models.isEmpty()) Text("Nenhum .gguf importado: sem páginas mmap de modelos.", style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
        for (m in models) {
            val resMb = (resident[m.path] ?: 0L) / 1024.0
            val fileMb = m.bytes / (1024.0 * 1024.0)
            GlassBar("mmap · ${m.displayName}", if (fileMb > 0) (resMb / fileMb).coerceIn(0.0, 1.0) else 0.0,
                "${resMb.f(0)} / ${fileMb.f(0)} MB", "tele_mmap_${m.key}")
        }
    }
}

@Composable
private fun GlassBar(label: String, value: Double, trailing: String, tag: String) {
    Column(Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(trailing, style = MonoStyle, color = Lumon.Cream, maxLines = 1)
        }
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(10.dp)
            .liquidGlass(Shapes.Capsule, tone = GlassTone.CLEAR, elevation = 0.dp, backdrop = false)) {
            val v = value.toFloat().coerceIn(0f, 1f)
            val color = when { v > 0.85f -> Lumon.Danger; v > 0.65f -> Lumon.Sunflower; else -> Lumon.Teal }
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(v.coerceAtLeast(0.02f)).height(10.dp)
                .clip(Shapes.Capsule).background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(color.copy(alpha = 0.55f), color))))
        }
    }
}
