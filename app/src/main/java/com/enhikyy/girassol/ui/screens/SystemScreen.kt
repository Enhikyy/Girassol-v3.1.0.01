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
import androidx.compose.material3.FilterChip
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
import com.enhikyy.girassol.ui.components.KeyValue
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

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp).testTag("system_list"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SectionCard("Execução contínua") {
                KeyValue("Estado", if (status.running) "rodando" else "pausado")
                KeyValue("Ticks/s alvo · efetivo", "${status.targetTps} · ${status.effectiveTps.f(1)}")
                KeyValue("Estado térmico", thermalLabel(status.thermalStatus) + if (status.thermalCapped) " (limitado)" else "")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SimulationHub.SPEEDS.filter { hw == null || it <= hw!!.maxTicksPerSecond }.forEach { s ->
                        FilterChip(selected = status.targetTps == s, onClick = { SimulationHub.setSpeed(s) }, label = { Text("$s/s") })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { GirassolContinuousService.start(context) }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Lumon.Sunflower, contentColor = Lumon.Background)) { Text("Iniciar serviço") }
                    OutlinedButton(onClick = { GirassolContinuousService.stop(context) }, modifier = Modifier.weight(1f)) { Text("Parar") }
                }
                Text("O serviço em primeiro plano mantém o mundo vivo com a tela desligada e é reiniciado após o boot.",
                    style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
            }
        }
        item {
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
        item { LlmCpuPanel() }
        item { MemoryPanel() }
        item { PowerPanel() }
        item { GpuPanel() }
        item { SigningPanel() }
        item {
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
        item {
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
        item { HashChainPanel(dash) }
        item { MeshSyncPanel(dash) }
        item { ThemesPanel() }
        item { SectionCard("Voz dos cidadãos") { PocketTtsToggle() } }
        item {
            SectionCard("Zona de perigo") {
                Button(onClick = { confirmReset = true }, colors = ButtonDefaults.buttonColors(containerColor = Lumon.Danger)) { Text("Reiniciar mundo") }
            }
        }
        item { Spacer(Modifier.padding(8.dp)) }
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
