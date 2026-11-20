package com.enhikyy.girassol.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enhikyy.girassol.nativebridge.GeaNativeBridge
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

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
        item {
            SectionCard("Diagnóstico nativo") {
                OutlinedButton(enabled = !diagRunning, onClick = {
                    diagRunning = true
                    scope.launch {
                        report = withContext(Dispatchers.Default) { NativeDiagnostics.run() }
                        diagRunning = false
                    }
                }) { Text(if (diagRunning) "Executando…" else "Executar autoteste, paridade e benchmark") }
                if (!GeaNativeBridge.isNativeLoaded) Text("Biblioteca nativa não carregada neste aparelho.", color = Lumon.Danger)
                report?.let { r ->
                    KeyValue("Autoteste C++", r.selfTest)
                    KeyValue("Paridade Kotlin↔C++", r.parity)
                    KeyValue("Tsetlin nativo", r.benchmark)
                    KeyValue("Tsetlin Kotlin", r.kotlinBenchmark)
                }
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
