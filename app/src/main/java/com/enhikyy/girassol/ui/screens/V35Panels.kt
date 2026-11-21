package com.enhikyy.girassol.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enhikyy.girassol.audio.CitizenVoice
import com.enhikyy.girassol.audio.PocketTtsVoice
import com.enhikyy.girassol.core.ui.NativeThemes
import com.enhikyy.girassol.core.ui.Wcag
import com.enhikyy.girassol.core.world.DashboardSnapshot
import com.enhikyy.girassol.knowledge.AudioTranscriber
import com.enhikyy.girassol.knowledge.LibraryIngest
import com.enhikyy.girassol.llm.LocalLlmManager
import com.enhikyy.girassol.mesh.MeshSyncManager
import com.enhikyy.girassol.sim.SimulationHub
import com.enhikyy.girassol.ui.components.KeyValue
import com.enhikyy.girassol.ui.components.MetricRow
import com.enhikyy.girassol.ui.components.Pill
import com.enhikyy.girassol.ui.components.SectionCard
import com.enhikyy.girassol.ui.components.f
import com.enhikyy.girassol.ui.theme.Lumon
import com.enhikyy.girassol.ui.theme.MonoStyle
import com.enhikyy.girassol.ui.theme.ThemeController
import kotlinx.coroutines.launch

/** Processo 19 — Biblioteca de Conhecimento + léxico comprimido emergente. */
@Composable
fun KnowledgeLibraryPanel(dash: DashboardSnapshot?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val v = dash?.mind?.v35
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            msg = LibraryIngest.ingest(context, uri) { p -> msg = p }
            busy = false
        }
    }
    SectionCard("Biblioteca de Conhecimento (Processo 19)", Modifier.testTag("v35_library")) {
        MetricRow("Documentos" to (v?.documents?.size ?: 0).toString(), "Trechos" to (v?.knowledgeChunks ?: 0).toString(),
            "Compressão" to ((v?.lexiconCompression ?: 0.0) * 100).f(0) + "%")
        Text("TXT · EPUB · PDF · Parquet · áudio (SenseVoice-Small${if (AudioTranscriber.modelAvailable(context)) "" else " — ausente neste build"}). Tudo offline: os trechos entram na memória semântica e os cidadãos cunham glifos para as frases mais repetidas.",
            style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
        Button(enabled = !busy, onClick = { picker.launch(LibraryIngest.MIME_TYPES) }, modifier = Modifier.fillMaxWidth().testTag("library_ingest"),
            colors = ButtonDefaults.buttonColors(containerColor = Lumon.Sunflower, contentColor = Lumon.Background)) {
            Text(if (busy) "Processando…" else "Adicionar documento ou áudio")
        }
        if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall, color = Lumon.Teal)
        v?.documents?.take(8)?.forEach { d ->
            KeyValue("${d.title} · ${d.format}", "${d.chunks} trechos · ${d.reads} leituras")
        }
        if (!v?.lexicon.isNullOrEmpty()) Text("Léxico emergente", style = MaterialTheme.typography.labelSmall, color = Lumon.Sunflower)
        v?.lexicon?.take(10)?.forEach { e ->
            Text("${e.glyph} = \"${e.phrase}\" · ${e.uses} usos${e.coiner?.let { " · cunhado por $it" } ?: ""}", style = MonoStyle, color = Lumon.Cream,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        v?.compressedSpeechSample?.take(3)?.forEach { Text("› $it", style = MonoStyle, color = Lumon.CreamDim, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

/** Processo 19 — MeshSync LAN (NSD/UDP + HMAC-SHA256 + WifiLock temporizado). */
@Composable
fun MeshSyncPanel(dash: DashboardSnapshot?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mesh by MeshSyncManager.state.collectAsStateWithLifecycle()
    var pin by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var migrantIdx by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { MeshSyncManager.refreshInbox(context) }
    SectionCard("MeshSync LAN (sem internet)", Modifier.testTag("v35_mesh")) {
        KeyValue("Este aparelho", MeshSyncManager.deviceId(context))
        KeyValue("Migrantes recebidos · enviados", "${dash?.mind?.v35?.migrantsIn ?: 0} · ${dash?.mind?.v35?.migrantsOut ?: 0}")
        if (!mesh.running) {
            OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(12) }, Modifier.fillMaxWidth().testTag("mesh_pin"), singleLine = true,
                label = { Text("PIN de pareamento (mesmo nos dois aparelhos, ≥ 6 dígitos)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            Button(enabled = pin.length >= 6, onClick = { msg = runCatching { MeshSyncManager.start(context, pin); "" }.getOrElse { it.message ?: "falha" } },
                modifier = Modifier.fillMaxWidth().testTag("mesh_start")) { Text("Ativar malha local") }
        } else {
            val left = ((mesh.wifiLockUntilMs - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
            KeyValue("TCP · WifiLock", "porta ${mesh.port} · expira em ~$left min")
            OutlinedButton(onClick = { MeshSyncManager.stop() }, modifier = Modifier.fillMaxWidth()) { Text("Encerrar malha") }
            if (mesh.peers.isEmpty()) Text("Procurando pares via NSD (_girassol._tcp) e beacon UDP 47653…", style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
            val citizens = SimulationHub.aliveCitizenIds()
            mesh.peers.forEach { p ->
                Text("${p.name} · ${p.host}:${p.port} · ${p.via}", style = MonoStyle, color = Lumon.Cream)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { scope.launch { msg = MeshSyncManager.pushSnapshot(p) } }, modifier = Modifier.weight(1f)) { Text("Enviar .grs") }
                    OutlinedButton(enabled = citizens.isNotEmpty(), onClick = {
                        val c = citizens[migrantIdx % citizens.size]
                        scope.launch { msg = MeshSyncManager.migrate(p, c.first) }
                    }, modifier = Modifier.weight(1f)) { Text("Migrar ${citizens.getOrNull(migrantIdx % citizens.size.coerceAtLeast(1))?.second ?: "-"}") }
                }
            }
            if (citizens.size > 1) Text("Tocar para escolher outro cidadão", style = MaterialTheme.typography.bodySmall, color = Lumon.Teal,
                modifier = Modifier.clickable { migrantIdx++ })
            if (mesh.lastBytesSent > 0) KeyValue("Último envio diferencial", "${mesh.lastBytesSent / 1024} KB (economia ${mesh.lastBytesSaved / 1024} KB)")
        }
        mesh.received.takeLast(3).forEach { name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MonoStyle, color = Lumon.CreamDim, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                OutlinedButton(onClick = { scope.launch { msg = MeshSyncManager.importReceived(name) } }) { Text("Importar") }
            }
        }
        if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall, color = Lumon.Teal)
        mesh.log.take(4).forEach { Text(it, style = MonoStyle, color = Lumon.CreamDim, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

/** Anokhin (aceitador de resultados) + CMA-ES (locomoção/decisão). */
@Composable
fun FunctionalSystemsPanel(dash: DashboardSnapshot?) {
    val v = dash?.mind?.v35 ?: return
    SectionCard("Sistemas funcionais (Anokhin) e CMA-ES", Modifier.testTag("v35_anokhin")) {
        MetricRow("Confirmações" to v.acceptorConfirmations.toString(), "Violações" to v.acceptorViolations.toString(), "Desvio médio" to v.acceptorMeanMismatch.f(3))
        Text("Síntese aferente → decisão → aceitador de resultados: o resultado sensorial esperado é comparado ao obtido antes de atualizar plasticidade e surpresa.",
            style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
        MetricRow("Geração CMA-ES" to v.cmaGeneration.toString(), "σ" to v.cmaSigma.f(3), "Melhor custo" to v.cmaBestCost.f(3))
        KeyValue("Escala de locomoção (promovida pelo gate RSI)", "× ${v.locomotionSpeedScale.f(3)}")
    }
}

/** Cadeia de auditoria SHA-256 do SQLite WAL. */
@Composable
fun HashChainPanel(dash: DashboardSnapshot?) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    SectionCard("Cadeia de auditoria SHA-256 (WorldState Hash Chain)", Modifier.testTag("v35_hashchain")) {
        KeyValue("Eventos encadeados", (dash?.mind?.v35?.eventSeq ?: 0).toString())
        Text("cabeça ${dash?.mind?.v35?.lastEventHash?.take(32) ?: "-"}…", style = MonoStyle, color = Lumon.CreamDim)
        OutlinedButton(enabled = !running, onClick = {
            scope.launch { running = true; result = SimulationHub.verifyHashChain(); running = false }
        }, modifier = Modifier.fillMaxWidth().testTag("verify_hash_chain")) { Text(if (running) "Verificando…" else "Verificar cadeia SHA-256") }
        if (result.isNotEmpty()) Text(result, style = MonoStyle, color = if (result.contains("QUEBRADA")) Lumon.Danger else Lumon.Ok,
            modifier = Modifier.testTag("hash_chain_result"))
    }
}

/** 15 Temas Nativos com o contraste WCAG mínimo medido ao vivo. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemesPanel() {
    val context = LocalContext.current
    val current = ThemeController.palette
    val minRatio = remember(current) { Wcag.audit(current).minOf { it.ratio } }
    SectionCard("Temas nativos (${NativeThemes.ALL.size}) · WCAG AA", Modifier.testTag("v35_themes")) {
        KeyValue("Tema ativo", current.label)
        KeyValue("Menor contraste texto/vidro", "${minRatio.f(2)}:1 ${if (minRatio >= Wcag.AA_NORMAL) "(AA)" else "(abaixo de AA)"}",
            if (minRatio >= Wcag.AA_NORMAL) Lumon.Ok else Lumon.Danger)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NativeThemes.ALL.forEach { t ->
                val sel = t.key == current.key
                Box(Modifier.size(width = 96.dp, height = 40.dp).clip(RoundedCornerShape(10.dp)).background(Color(t.background.toInt()))
                    .border(if (sel) 2.dp else 1.dp, Color(t.primary.toInt()), RoundedCornerShape(10.dp))
                    .clickable { ThemeController.select(context, t) }.padding(6.dp), contentAlignment = Alignment.Center) {
                    Text(t.label, style = MaterialTheme.typography.labelSmall, color = Color(t.text.toInt()), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Voz neural Pocket-TTS opcional (Sherpa-ONNX). */
@Composable
fun PocketTtsToggle() {
    val context = LocalContext.current
    val available = remember { PocketTtsVoice.available(context) }
    var on by remember { mutableStateOf(CitizenVoice.usePocketTts) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Pocket-TTS neural (Sherpa-ONNX)", style = MaterialTheme.typography.bodyMedium, color = Lumon.Cream)
            Text(if (available) "voz clonada offline · centrado em inglês" else "ausente neste build", style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
        }
        Switch(checked = on, enabled = available, onCheckedChange = { on = it; CitizenVoice.usePocketTts = it })
    }
}
