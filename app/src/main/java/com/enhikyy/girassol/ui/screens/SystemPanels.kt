package com.enhikyy.girassol.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enhikyy.girassol.llm.LocalLlmManager
import com.enhikyy.girassol.nativebridge.LlmNativeBridge
import com.enhikyy.girassol.render3d.GpuCalStatus
import com.enhikyy.girassol.render3d.GpuCalibration
import com.enhikyy.girassol.render3d.Render3dTelemetry
import com.enhikyy.girassol.sim.SimulationHub
import com.enhikyy.girassol.system.MemoryGuard
import com.enhikyy.girassol.system.SigningInfo
import com.enhikyy.girassol.system.SigningKind
import com.enhikyy.girassol.ui.components.KeyValue
import com.enhikyy.girassol.ui.components.SectionCard
import com.enhikyy.girassol.ui.components.f
import com.enhikyy.girassol.ui.theme.Lumon
import com.enhikyy.girassol.ui.theme.MonoStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun mb(kb: Long) = if (kb < 0) "-" else "${kb / 1024} MB"

/** CPU SIMD em runtime + variante do llama.cpp carregada + tokens/s. */
@Composable
fun LlmCpuPanel() {
    val llm by LocalLlmManager.stats.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var bench by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    SectionCard("CPU ARM64 e motor LLM", Modifier.testTag("llm_cpu_panel")) {
        Text("SIMD em runtime: ${LlmNativeBridge.cpuFlags.label()}", style = MonoStyle, color = Lumon.Cream, modifier = Modifier.testTag("simd_flags"))
        Text("Variante: ${LlmNativeBridge.loadedVariant ?: "nenhuma"} · ${LlmNativeBridge.buildVariant.substringAfter('|')}",
            style = MonoStyle, color = Lumon.Teal, modifier = Modifier.testTag("llm_variant"))
        Text("Carga: ${LlmNativeBridge.loadLog}", style = MonoStyle, color = Lumon.CreamDim)
        if (LlmNativeBridge.systemInfo.isNotEmpty()) Text("ggml: ${LlmNativeBridge.systemInfo}", style = MonoStyle, color = Lumon.CreamDim, maxLines = 4)
        KeyValue("tokens/s (última geração)", if (llm.genTokensPerSecond > 0) "${llm.genTokensPerSecond.f(1)} tg · ${llm.tokensPerSecond.f(1)} total" else "-")
        KeyValue("tokens/s (benchmark)", if (llm.benchGenTps > 0) "pp ${llm.benchPromptTps.f(1)} · tg ${llm.benchGenTps.f(1)}" else "-")
        OutlinedButton(enabled = !running, modifier = Modifier.testTag("llm_bench"), onClick = {
            running = true
            scope.launch { bench = withContext(Dispatchers.Default) { LocalLlmManager.benchmark() }; running = false }
        }) { Text(if (running) "Medindo…" else "Medir tokens/s") }
        if (bench.isNotEmpty()) Text(bench, style = MonoStyle, color = Lumon.Cream, modifier = Modifier.testTag("llm_bench_result"))
        Text("i8mm só é usado quando o HWCAP2 o confirma; Snapdragon 695 e Helio G99 recebem a variante dotprod (sem SIGILL).",
            style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
    }
}

/** VmRSS/VmHWM (C++), RAM livre do sistema, ADPF e estado do disjuntor. */
@Composable
fun MemoryPanel() {
    val m by MemoryGuard.state.collectAsStateWithLifecycle()
    SectionCard("Memória real e disjuntor", Modifier.testTag("memory_panel")) {
        Text("VmRSS ${mb(m.rssKb)} · pico VmHWM ${mb(m.hwmKb)}", style = MonoStyle, color = Lumon.Cream, modifier = Modifier.testTag("mem_rss"))
        KeyValue("Anônima · arquivos (mmap)", "${mb(m.rssAnonKb)} · ${mb(m.rssFileKb)}")
        KeyValue("Fonte", if (m.nativeSource) "/proc/self/status (C++)" else "/proc/self/status (Kotlin)")
        KeyValue("RAM livre do sistema", "${m.sysAvailMb} / ${m.sysTotalMb} MB${if (m.sysLowMemory) " · BAIXA" else ""}",
            if (m.sysLowMemory) Lumon.Danger else Lumon.Cream)
        KeyValue("Limiar lowMemory do Android", "${m.sysThresholdMb} MB")
        KeyValue("Heap nativo", "${m.nativeHeapMb.f(0)} MB")
        KeyValue("Térmico ADPF · folga 10 s", "${m.thermalStatus} · ${if (m.thermalHeadroom.isNaN()) "-" else m.thermalHeadroom.toDouble().f(2)}")
        KeyValue("Limite VmRSS · rearme", "${m.limitMb} MB · < ${m.rearmMb} MB por 60 s")
        Text(if (m.breakerOpen) "Disjuntor ABERTO: LLM descarregado (${m.lastReason})" else "Disjuntor armado (${m.trips} disparos)",
            style = MonoStyle, color = if (m.breakerOpen) Lumon.Danger else Lumon.Ok, modifier = Modifier.testTag("breaker_state"))
        if (m.lastTrimLevel >= 0) KeyValue("Último onTrimMemory", m.lastTrimLevel.toString())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(modifier = Modifier.testTag("breaker_test"), onClick = { MemoryGuard.tripForTest() }) { Text("Testar disjuntor") }
            if (m.breakerOpen) OutlinedButton(modifier = Modifier.testTag("breaker_rearm"), onClick = { MemoryGuard.rearmManually() }) { Text("Rearmar") }
        }
        Text("O disjuntor descarrega o contexto do llama.cpp e libera caches; o loop determinístico do mundo continua no deliberador simbólico.",
            style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
    }
}

/** Driver GL real e nível de efeito Kodak/Dehancer ativo. */
@Composable
fun GpuPanel() {
    val g by GpuCalibration.state.collectAsStateWithLifecycle()
    val r3d by Render3dTelemetry.stats.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SectionCard("GPU e efeitos visuais", Modifier.testTag("gpu_panel")) {
        val d = g.driver
        Text("GL_RENDERER: ${d?.renderer ?: "?"}", style = MonoStyle, color = Lumon.Cream, modifier = Modifier.testTag("gl_renderer"))
        Text("GL_VENDOR: ${d?.vendor ?: "?"} · ${d?.family ?: ""}", style = MonoStyle, color = Lumon.CreamDim)
        Text("GL_VERSION: ${d?.version ?: "?"}", style = MonoStyle, color = Lumon.CreamDim, maxLines = 2)
        KeyValue("Backend Filament", "OpenGL ES (materiais -a opengl)")
        val lvl = g.active
        Text(when (g.status) {
            GpuCalStatus.DONE -> "Efeitos: ${lvl?.label ?: "3D indisponível"} (calibrado ${g.calibrated?.label ?: "-"})"
            GpuCalStatus.RUNNING -> "Efeitos: calibrando…"
            GpuCalStatus.IDLE -> "Efeitos: não calibrado"
        }, style = MonoStyle, color = if (lvl == null) Lumon.Sunflower else Lumon.Teal, modifier = Modifier.testTag("gpu_level"))
        lvl?.let { Text(it.describe(), style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim) }
        KeyValue("Origem", g.source)
        if (r3d.active) KeyValue("Viewport agora", "${r3d.fps.f(0)} fps · FX ${r3d.effectLevel}")
        if (g.fpsDowngrades > 0) KeyValue("Quedas por FPS", g.fpsDowngrades.toString())
        g.probeLog.forEach { Text("sonda · $it", style = MonoStyle, color = Lumon.CreamDim) }
        OutlinedButton(enabled = g.status != GpuCalStatus.RUNNING, modifier = Modifier.testTag("gpu_recalibrate"),
            onClick = { GpuCalibration.recalibrate(context) }) { Text("Recalibrar shaders") }
    }
}

/** Chave de assinatura do APK + exportação/importação do snapshot .grs. */
@Composable
fun SigningPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sig = remember { SigningInfo.read(context) }
    var msg by remember { mutableStateOf("") }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = SimulationHub.exportSnapshotBytes()
            msg = if (bytes == null) "Mundo não carregado" else withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }; "Exportado: ${bytes.size / 1024} KB" }
                    .getOrElse { "Falha ao exportar: ${it.message}" }
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }
            msg = if (bytes == null) "Não foi possível ler o arquivo" else SimulationHub.importSnapshot(bytes)
        }
    }
    SectionCard("Assinatura do APK", Modifier.testTag("signing_panel")) {
        Text("Assinado com: ${sig.kind.label}", style = MonoStyle,
            color = if (sig.kind == SigningKind.RELEASE) Lumon.Ok else Lumon.Sunflower, modifier = Modifier.testTag("signing_kind"))
        Text(sig.subject, style = MonoStyle, color = Lumon.CreamDim, maxLines = 2)
        if (sig.sha256.isNotEmpty()) Text("SHA-256 ${sig.sha256}", style = MonoStyle, color = Lumon.CreamDim, maxLines = 3)
        Text(
            (if (sig.kind == SigningKind.DEBUG) "Este APK usa a chave de debug (apenas testes). " else "") +
                "Antes de migrar de assinatura (debug ↔ release) exporte o snapshot .grs: o Android exige desinstalar o app, o que apaga o mundo salvo.",
            style = MaterialTheme.typography.bodySmall, color = Lumon.Sunflower, modifier = Modifier.testTag("signing_warning")
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(modifier = Modifier.weight(1f).testTag("grs_export"), onClick = {
                exporter.launch("girassol-${System.currentTimeMillis() / 1000}.grs")
            }) { Text("Exportar .grs") }
            OutlinedButton(modifier = Modifier.weight(1f).testTag("grs_import"), onClick = { importer.launch(arrayOf("*/*")) }) { Text("Importar .grs") }
        }
        if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall, color = Lumon.Teal, modifier = Modifier.testTag("grs_message"))
    }
}
