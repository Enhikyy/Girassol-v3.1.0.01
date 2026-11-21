package com.enhikyy.girassol.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.graphicsLayer
import com.enhikyy.girassol.core.world.GenerationView
import com.enhikyy.girassol.core.world.ReproductionMerit
import com.enhikyy.girassol.llm.LlmStatus
import com.enhikyy.girassol.llm.LocalLlmManager
import com.enhikyy.girassol.ui.components.Shapes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.enhikyy.girassol.core.world.AgentView
import com.enhikyy.girassol.core.world.WorldRenderSnapshot
import com.enhikyy.girassol.render3d.Filament3DView
import com.enhikyy.girassol.render3d.EffectLevel
import com.enhikyy.girassol.render3d.GpuCalibration
import com.enhikyy.girassol.render3d.RenderMode
import com.enhikyy.girassol.render3d.RenderModePolicy
import com.enhikyy.girassol.render3d.WorldPickRegistry
import com.enhikyy.girassol.sim.SimulationHub
import com.enhikyy.girassol.ui.components.EmptyState
import com.enhikyy.girassol.ui.components.LabeledBar
import com.enhikyy.girassol.ui.components.f
import com.enhikyy.girassol.ui.theme.Lumon
import com.enhikyy.girassol.ui.theme.MonoStyle
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Janela (em ticks) durante a qual uma fala aparece em balão sobre o cidadão. */
const val SPEECH_BALLOON_TICKS = 90L
/** Máximo de balões simultâneos (v3.6: 2, com afastamento e fade-out). */
const val MAX_BALLOONS = 2

/**
 * Aba Biosfera: canvas 3D/2D em tela cheia (borda a borda). Por cima, só um HUD em cápsula
 * (menu, dia, população, modelos e ETA da próxima geração), o alternador 3D | 2D e — com
 * 0 cidadãos — o botão para importar o primeiro .gguf.
 */
@Composable
fun WorldScreen(onOpenCitizen: (String) -> Unit, onOpenMenu: () -> Unit = {}) {
    val snap by SimulationHub.render.collectAsStateWithLifecycle()
    val status by SimulationHub.status.collectAsStateWithLifecycle()
    val dash by SimulationHub.dashboard.collectAsStateWithLifecycle()
    val llm by LocalLlmManager.stats.collectAsStateWithLifecycle()
    val models by LocalLlmManager.models.collectAsStateWithLifecycle()
    val preferred by RenderModePolicy.preferred.collectAsStateWithLifecycle()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val context = LocalContext.current
    val importer = rememberFounderImporter()
    val s = snap ?: return EmptyState("Carregando o mundo…")
    var selected by remember { mutableStateOf<String?>(null) }
    var filamentFailure by remember { mutableStateOf<String?>(null) }
    var projected by remember { mutableStateOf<Map<String, Offset>>(emptyMap()) }
    val foreground = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val gpu by GpuCalibration.state.collectAsStateWithLifecycle()
    val eff = RenderModePolicy.effective(preferred, status.thermalStatus, filamentFailure, foreground, gpu)
    val fxLevel = gpu.active ?: EffectLevel.NONE

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (eff.mode) {
            RenderMode.MODE_3D -> Filament3DView(
                snapshot = s,
                onPick = { selected = it },
                onFailure = { filamentFailure = it },
                onProjected = { projected = it },
                modifier = Modifier.fillMaxSize(),
                level = fxLevel
            )
            RenderMode.ECO_2D -> Iso2DView(s, selected, showTrail = true, showFood = false, onPick = { selected = it }, onProjected = { projected = it })
        }
        SpeechBalloons(s, projected, selected)

        // HUD superior: uma cápsula compacta + ETA da próxima geração
        Column(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 12.dp, top = 8.dp, end = 120.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.clip(Shapes.Capsule).background(Lumon.Glass).border(1.dp, Lumon.Outline, Shapes.Capsule)
                    .padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp).testTag("biosfera_hud"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = onOpenMenu, modifier = Modifier.size(34.dp).testTag("open_drawer")) {
                    Icon(Icons.Outlined.Menu, contentDescription = "abrir menu", tint = Lumon.Cream)
                }
                val activeModels = models.count { it.active }
                val modelLine = when {
                    models.isEmpty() -> "sem modelos"
                    llm.status == LlmStatus.READY || llm.status == LlmStatus.BUSY -> "$activeModels .gguf · ${llm.tokensPerSecond.f(1)} tok/s"
                    else -> "$activeModels .gguf · ${llm.status.label}"
                }
                Text("Dia ${s.day} · ${s.population} ${if (s.population == 1) "cidadão" else "cidadãos"} · $modelLine",
                    style = MaterialTheme.typography.labelMedium, color = Lumon.Cream, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("hud_population"))
            }
            val g = dash?.generation
            if (g != null && s.population > 0) {
                Text(etaLabel(g, status.effectiveTps.takeIf { it > 0.1 } ?: status.targetTps.toDouble()),
                    style = MaterialTheme.typography.labelSmall, color = if (g.status == ReproductionMerit.Status.PRONTO) Lumon.Ok else Lumon.Sunflower,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clip(Shapes.Capsule).background(Lumon.Glass).padding(horizontal = 12.dp, vertical = 5.dp).testTag("hud_eta"))
            }
        }

        // alternador discreto 3D | 2D
        Row(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 12.dp, top = 10.dp)
                .clip(Shapes.Capsule).background(Lumon.Glass).border(1.dp, Lumon.Outline, Shapes.Capsule).padding(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SegmentPill("3D", eff.mode == RenderMode.MODE_3D, Modifier.testTag("world_mode_3d")) {
                filamentFailure = null; RenderModePolicy.set(context, RenderMode.MODE_3D)
            }
            SegmentPill("2D", eff.mode == RenderMode.ECO_2D, Modifier.testTag("world_mode_2d")) { RenderModePolicy.set(context, RenderMode.ECO_2D) }
            Box(Modifier.size(6.dp).testTag("world_mode_label").semantics { contentDescription = "modo:${eff.mode.name}" })
        }

        val agent = selected?.let { id -> s.agents.firstOrNull { it.id == id } }
        if (agent != null) AgentCard(agent, Modifier.align(Alignment.BottomCenter).padding(bottom = 92.dp)) { onOpenCitizen(agent.id) }
        else if (s.population == 0) {
            Button(
                onClick = { importer(null) }, shape = Shapes.Capsule,
                colors = ButtonDefaults.buttonColors(containerColor = Lumon.Glass, contentColor = Lumon.Sunflower),
                border = androidx.compose.foundation.BorderStroke(1.dp, Lumon.Sunflower.copy(alpha = 0.6f)),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp).testTag("import_first_gguf")
            ) { Text("Importar .gguf para criar o primeiro cidadão") }
        }
    }
}

/** Texto do ETA da próxima geração (ticks → dias simulados → tempo real pela taxa atual). */
fun etaLabel(g: GenerationView, ticksPerSecond: Double): String = when (g.status) {
    ReproductionMerit.Status.SEM_POPULACAO -> "Importe um .gguf para criar o primeiro cidadão"
    ReproductionMerit.Status.PRECISA_PAR -> "Importe o 2º modelo para formar par"
    ReproductionMerit.Status.PRONTO -> "Pronto para gerar g${g.nextGeneration}"
    ReproductionMerit.Status.AGUARDANDO ->
        if (g.etaTicks < 0) "g${g.nextGeneration}: aguardando evolução (${g.blockers.take(2).joinToString(", ")})"
        else "g${g.nextGeneration} em ~${g.etaTicks} ticks · ${g.etaDays.f(1)} dias · ${realTime(g.etaTicks, ticksPerSecond)}"
}

private fun realTime(ticks: Long, tps: Double): String {
    if (tps <= 0.0) return "—"
    val sec = (ticks / tps).toLong()
    return when {
        sec < 60 -> "${sec}s reais"
        sec < 3600 -> "${sec / 60} min ${sec % 60}s reais"
        else -> "${sec / 3600} h ${(sec % 3600) / 60} min reais"
    }
}

@Composable
private fun SegmentPill(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) Lumon.Background else Lumon.CreamDim,
        modifier = modifier.clip(Shapes.Capsule).background(if (on) Lumon.Sunflower else Color.Transparent)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp))
}

@Composable
private fun Iso2DView(
    s: WorldRenderSnapshot, selected: String?, showTrail: Boolean, showFood: Boolean,
    onPick: (String?) -> Unit, onProjected: (Map<String, Offset>) -> Unit
) {
    val terrainKey = s.widthTiles * 1000 + s.districts.size
    val terrain = remember(terrainKey) { IsoRenderer.buildTerrain(s).asImageBitmap() }
    var zoom by remember { mutableFloatStateOf(0f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val w = s.widthTiles
    Canvas(
        Modifier.fillMaxSize().testTag("world_viewport_2d")
            .pointerInput(terrainKey) {
                detectTransformGestures { centroid, panChange, zoomChange, _ ->
                    val old = zoom
                    val nz = (zoom * zoomChange).coerceIn(0.2f, 8f)
                    pan = (pan - centroid) * (nz / old) + centroid + panChange
                    zoom = nz
                }
            }
            .pointerInput(terrainKey) {
                detectTapGestures { p ->
                    if (zoom <= 0f) return@detectTapGestures
                    val wx = (p.x - pan.x) / zoom
                    val wy = (p.y - pan.y) / zoom
                    val hit = SimulationHub.render.value?.agents?.minByOrNull { a ->
                        val (cx, cy) = IsoRenderer.pointCenter(a.fx, a.fz, w)
                        hypot(cx - wx, cy - 3f - wy)
                    }
                    onPick(hit?.takeIf { a ->
                        val (cx, cy) = IsoRenderer.pointCenter(a.fx, a.fz, w)
                        hypot(cx - wx, cy - 3f - wy) * zoom < 36f
                    }?.id)
                }
            }
    ) {
        if (zoom <= 0f) {
            zoom = minOf(size.width / terrain.width, size.height / terrain.height) * 0.98f
            pan = Offset((size.width - terrain.width * zoom) / 2f, (size.height - terrain.height * zoom) / 2f)
        }
        withTransform({ translate(pan.x, pan.y); scale(zoom, zoom, Offset.Zero) }) {
            drawImage(terrain)
            if (s.night) drawRect(Color(0x66000000), size = androidx.compose.ui.geometry.Size(terrain.width.toFloat(), terrain.height.toFloat()))
            if (showTrail) drawField(s.trail, w, Lumon.Sunflower)
            if (showFood) drawField(s.foodScent, w, Color(0xFF9BD36A))
            for (o in s.objects) {
                val (cx, cy) = IsoRenderer.tileCenter(o.x, o.z, w)
                drawCircle(IsoRenderer.objectColor(o.type), radius = 2.6f, center = Offset(cx, cy - 1f))
                if (o.contaminated) drawCircle(Lumon.Danger, radius = 4.5f, center = Offset(cx, cy - 1f), style = Stroke(1.2f))
                if (o.occupancy > 0) drawCircle(Color.White.copy(alpha = 0.5f), radius = 3.6f, center = Offset(cx, cy - 1f), style = Stroke(0.6f))
            }
            for (a in s.agents) {
                val (cx, cy) = IsoRenderer.pointCenter(a.fx, a.fz, w)
                val c = Offset(cx, cy - 3f)
                drawCircle(Color(0x66000000), radius = 2.2f, center = Offset(cx, cy + 0.5f))
                drawCircle(IsoRenderer.actionColor(a.action), radius = 2.4f, center = c)
                drawCircle(lerpMood(a.mood), radius = 2.4f, center = c, style = Stroke(0.7f))
                if (a.id == selected) drawCircle(Lumon.Sunflower, radius = 5.5f, center = c, style = Stroke(1.2f))
            }
        }
        val out = HashMap<String, Offset>(s.agents.size * 2)
        for (a in s.agents) {
            val (cx, cy) = IsoRenderer.pointCenter(a.fx, a.fz, w)
            val p = Offset(pan.x + cx * zoom, pan.y + (cy - 6f) * zoom)
            if (p.x in 0f..size.width && p.y in 0f..size.height) out[a.id] = p
        }
        WorldPickRegistry.mode = "2D"
        WorldPickRegistry.positions = out
        if (out.keys != lastKeys2d || s.tick != lastTick2d) { lastKeys2d = out.keys; lastTick2d = s.tick; onProjected(out) }
    }
}

private var lastKeys2d: Set<String> = emptySet()
private var lastTick2d = -1L

/**
 * Balões de diálogo: no máximo [MAX_BALLOONS] falas recentes, posicionados sem sobreposição
 * (afastamento vertical e, se preciso, horizontal) e com fade-out no último terço da janela.
 */
@Composable
private fun SpeechBalloons(s: WorldRenderSnapshot, projected: Map<String, Offset>, selected: String?) {
    val speaking = s.agents
        .filter { it.speech.isNotBlank() && it.speechAgeTicks <= SPEECH_BALLOON_TICKS && projected.containsKey(it.id) }
        .sortedBy { it.speechAgeTicks }
        .take(MAX_BALLOONS)
    val thinker = selected?.let { id -> s.agents.firstOrNull { it.id == id && projected.containsKey(id) && speaking.none { sp -> sp.id == id } } }
    val items = speaking.map { BalloonItem(it.name, it.speech, it.neural, false, projected.getValue(it.id), balloonAlpha(it.speechAgeTicks)) } +
        listOfNotNull(thinker?.takeIf { it.thought.isNotBlank() }?.let { BalloonItem(it.name, it.thought, it.neural, true, projected.getValue(it.id), 1f) })
    if (items.isEmpty()) return
    Layout(
        content = { for (b in items) BalloonBody(b) },
        modifier = Modifier.fillMaxSize()
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val gap = 6.dp.roundToPx()
        val topInset = 96.dp.roundToPx()
        val placed = ArrayList<androidx.compose.ui.unit.IntRect>()
        val positions = placeables.mapIndexed { i, p ->
            val a = items[i].anchor
            var x = (a.x - p.width / 2f).roundToInt().coerceIn(0, (constraints.maxWidth - p.width).coerceAtLeast(0))
            var y = (a.y - p.height - gap).roundToInt().coerceIn(topInset, (constraints.maxHeight - p.height).coerceAtLeast(topInset))
            var rect = androidx.compose.ui.unit.IntRect(x, y, x + p.width, y + p.height)
            var guard = 0
            while (guard++ < 8) {
                val hit = placed.firstOrNull { it.overlaps(rect) } ?: break
                // sobe acima do balão em conflito; sem espaço em cima, desloca para o lado
                val upY = hit.top - p.height - gap
                if (upY >= topInset) y = upY
                else x = if (hit.center.x > constraints.maxWidth / 2) (hit.left - p.width - gap).coerceAtLeast(0)
                    else (hit.right + gap).coerceAtMost((constraints.maxWidth - p.width).coerceAtLeast(0))
                rect = androidx.compose.ui.unit.IntRect(x, y, x + p.width, y + p.height)
            }
            placed += rect
            IntOffset(x, y)
        }
        layout(constraints.maxWidth, constraints.maxHeight) { placeables.forEachIndexed { i, p -> p.place(positions[i]) } }
    }
}

private data class BalloonItem(val name: String, val text: String, val neural: Boolean, val thought: Boolean, val anchor: Offset, val alpha: Float)

/** 1 até 60 % da janela; depois desvanece linearmente até 0. */
fun balloonAlpha(ageTicks: Long): Float {
    val fadeStart = SPEECH_BALLOON_TICKS * 0.6
    if (ageTicks <= fadeStart) return 1f
    return (1.0 - (ageTicks - fadeStart) / (SPEECH_BALLOON_TICKS - fadeStart)).toFloat().coerceIn(0f, 1f)
}

@Composable
private fun BalloonBody(b: BalloonItem) {
    Column(
        Modifier.graphicsLayer { alpha = b.alpha }.widthIn(max = 200.dp).clip(Shapes.Control).background(Lumon.Glass)
            .border(1.dp, if (b.neural) Lumon.Halation else if (b.thought) Lumon.Violet else Lumon.Outline, Shapes.Control)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(if (b.thought) "balloon_thought" else "balloon_speech")
    ) {
        Text((if (b.thought) "${b.name} pensa" else b.name), style = MaterialTheme.typography.labelSmall,
            color = if (b.neural) Lumon.Halation else Lumon.Sunflower, maxLines = 1)
        Text(b.text, style = MaterialTheme.typography.bodySmall, color = if (b.thought) Lumon.CreamDim else Lumon.Cream, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawField(field: FloatArray, w: Int, color: Color) {
    if (field.size < w * w) return
    var max = 0f
    for (v in field) if (v > max) max = v
    if (max <= 1e-4f) return
    val threshold = max * 0.06f
    for (z in 0 until w) for (x in 0 until w) {
        val v = field[z * w + x]
        if (v < threshold) continue
        val (cx, cy) = IsoRenderer.tileCenter(x, z, w)
        drawCircle(color.copy(alpha = (0.15f + 0.55f * (v / max)).coerceAtMost(0.7f)), radius = 3.2f, center = Offset(cx, cy))
    }
}

private fun lerpMood(m: Double): Color {
    val t = ((m + 1.0) / 2.0).toFloat().coerceIn(0f, 1f)
    return Color(red = 0.88f - 0.5f * t, green = 0.42f + 0.37f * t, blue = 0.37f + 0.38f * t)
}

@Composable
private fun AgentCard(a: AgentView, modifier: Modifier, onOpen: () -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(Shapes.Card).background(Lumon.Glass)
            .border(1.dp, Lumon.Outline, Shapes.Card).padding(18.dp).testTag("agent_card"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a.name, style = MaterialTheme.typography.titleMedium, color = Lumon.Sunflower, modifier = Modifier.weight(1f).testTag("agent_card_name"))
            Text("g${a.generation}", style = MonoStyle, color = Lumon.CreamDim)
        }
        Text((a.action?.label ?: "Ocioso") + if (a.neural) " · pensou com o próprio .gguf" else "", color = Lumon.Teal, style = MaterialTheme.typography.bodyMedium)
        Text(a.thought, color = Lumon.Cream, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (a.speech.isNotBlank()) Text("“${a.speech}”", color = Lumon.CreamDim, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        LabeledBar("Bem-estar médio", a.meanNeed)
        LabeledBar("Humor", (a.mood + 1) / 2, Lumon.Sunflower, a.mood.f(2))
        TextButton(onClick = onOpen) { Text("Abrir ficha") }
    }
}

