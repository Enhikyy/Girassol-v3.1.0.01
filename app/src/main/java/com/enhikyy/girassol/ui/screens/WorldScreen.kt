package com.enhikyy.girassol.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
const val MAX_BALLOONS = 6

@Composable
fun WorldScreen(onOpenCitizen: (String) -> Unit) {
    val snap by SimulationHub.render.collectAsStateWithLifecycle()
    val status by SimulationHub.status.collectAsStateWithLifecycle()
    val preferred by RenderModePolicy.preferred.collectAsStateWithLifecycle()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val context = LocalContext.current
    val s = snap ?: return EmptyState("Carregando o mundo…")
    var selected by remember { mutableStateOf<String?>(null) }
    var showTrail by remember { mutableStateOf(true) }
    var showFood by remember { mutableStateOf(false) }
    var filamentFailure by remember { mutableStateOf<String?>(null) }
    var projected by remember { mutableStateOf<Map<String, Offset>>(emptyMap()) }
    val foreground = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val gpu by GpuCalibration.state.collectAsStateWithLifecycle()
    val eff = RenderModePolicy.effective(preferred, status.thermalStatus, filamentFailure, foreground, gpu)
    val fxLevel = gpu.active ?: EffectLevel.NONE

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            ModeChip("3D", preferred == RenderMode.MODE_3D, Modifier.testTag("world_mode_3d")) {
                filamentFailure = null; RenderModePolicy.set(context, RenderMode.MODE_3D)
            }
            ModeChip("Eco 2D", preferred == RenderMode.ECO_2D, Modifier.testTag("world_mode_2d")) { RenderModePolicy.set(context, RenderMode.ECO_2D) }
            if (eff.mode == RenderMode.ECO_2D) {
                OverlayChip("Trilhas", showTrail) { showTrail = it }
                OverlayChip("Comida", showFood) { showFood = it }
            }
        }
        Text(
            "${eff.mode.label} · ${eff.reason}" + if (eff.mode == RenderMode.MODE_3D)
                " · FX ${fxLevel.label} · ${gpu.driver?.renderer ?: "GPU ?"}" else "",
            style = MonoStyle, color = if (eff.mode == RenderMode.MODE_3D) Lumon.Teal else Lumon.Sunflower,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).testTag("world_mode_label")
                .semantics { contentDescription = "modo:${eff.mode.name}" }
        )
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            when (eff.mode) {
                RenderMode.MODE_3D -> Filament3DView(
                    snapshot = s,
                    onPick = { selected = it },
                    onFailure = { filamentFailure = it },
                    onProjected = { projected = it },
                    modifier = Modifier.fillMaxSize(),
                    level = fxLevel
                )
                RenderMode.ECO_2D -> Iso2DView(s, selected, showTrail, showFood, onPick = { selected = it }, onProjected = { projected = it })
            }
            SpeechBalloons(s, projected, selected)
            val agent = selected?.let { id -> s.agents.firstOrNull { it.id == id } }
            if (agent != null) AgentCard(agent, Modifier.align(Alignment.BottomCenter)) { onOpenCitizen(agent.id) }
            else Legend(eff.mode, Modifier.align(Alignment.BottomStart))
        }
    }
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
                        val (cx, cy) = IsoRenderer.tileCenter(a.x, a.z, w)
                        hypot(cx - wx, cy - 3f - wy)
                    }
                    onPick(hit?.takeIf { a ->
                        val (cx, cy) = IsoRenderer.tileCenter(a.x, a.z, w)
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
                val (cx, cy) = IsoRenderer.tileCenter(a.x, a.z, w)
                val c = Offset(cx, cy - 3f)
                drawCircle(Color(0x66000000), radius = 2.2f, center = Offset(cx, cy + 0.5f))
                drawCircle(IsoRenderer.actionColor(a.action), radius = 2.4f, center = c)
                drawCircle(lerpMood(a.mood), radius = 2.4f, center = c, style = Stroke(0.7f))
                if (a.id == selected) drawCircle(Lumon.Sunflower, radius = 5.5f, center = c, style = Stroke(1.2f))
            }
        }
        val out = HashMap<String, Offset>(s.agents.size * 2)
        for (a in s.agents) {
            val (cx, cy) = IsoRenderer.tileCenter(a.x, a.z, w)
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

/** Balões de diálogo autônomos: falas recentes (simbólicas ou do LLM local) sobre a cabeça do cidadão. */
@Composable
private fun SpeechBalloons(s: WorldRenderSnapshot, projected: Map<String, Offset>, selected: String?) {
    val speaking = s.agents
        .filter { it.speech.isNotBlank() && it.speechAgeTicks <= SPEECH_BALLOON_TICKS && projected.containsKey(it.id) }
        .sortedBy { it.speechAgeTicks }
        .take(MAX_BALLOONS)
    val thinker = selected?.let { id -> s.agents.firstOrNull { it.id == id && projected.containsKey(id) && speaking.none { sp -> sp.id == id } } }
    Box(Modifier.fillMaxSize()) {
        for (a in speaking) Balloon(a.name, a.speech, a.neural, false, projected.getValue(a.id))
        if (thinker != null && thinker.thought.isNotBlank()) Balloon(thinker.name, thinker.thought, thinker.neural, true, projected.getValue(thinker.id))
    }
}

@Composable
private fun Balloon(name: String, text: String, neural: Boolean, thought: Boolean, anchor: Offset) {
    Layout(
        content = {
            Column(
                Modifier.widthIn(max = 190.dp).clip(RoundedCornerShape(10.dp)).background(Lumon.Glass)
                    .border(1.dp, if (neural) Lumon.Halation else if (thought) Lumon.Violet else Lumon.Outline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
                    .testTag(if (thought) "balloon_thought" else "balloon_speech")
            ) {
                Text(
                    (if (thought) "$name pensa" else name) + if (neural) " · LLM" else "",
                    style = MaterialTheme.typography.labelSmall, color = if (neural) Lumon.Halation else Lumon.Sunflower, maxLines = 1
                )
                Text(text, style = MaterialTheme.typography.bodySmall, color = if (thought) Lumon.CreamDim else Lumon.Cream, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    ) { measurables, constraints ->
        val p = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val x = (anchor.x - p.width / 2f).roundToInt().coerceIn(0, (constraints.maxWidth - p.width).coerceAtLeast(0))
            val y = (anchor.y - p.height - 6f).roundToInt().coerceIn(0, (constraints.maxHeight - p.height).coerceAtLeast(0))
            p.place(IntOffset(x, y))
        }
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
private fun ModeChip(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FilterChip(
        selected = on, onClick = onClick, label = { Text(label) }, modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Lumon.Sunflower, selectedLabelColor = Color.Black, labelColor = Lumon.CreamDim, containerColor = Lumon.Surface)
    )
}

@Composable
private fun OverlayChip(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = on, onClick = { onChange(!on) }, label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Lumon.TealDim, selectedLabelColor = Lumon.Cream, labelColor = Lumon.CreamDim)
    )
}

@Composable
private fun Legend(mode: RenderMode, modifier: Modifier) {
    Column(modifier.padding(10.dp).clip(RoundedCornerShape(8.dp)).background(Lumon.Glass).padding(8.dp)) {
        if (mode == RenderMode.MODE_3D) {
            Text("1 dedo orbita · 2 dedos deslocam · pinça zoom · toque seleciona", style = MaterialTheme.typography.labelSmall, color = Lumon.CreamDim)
        } else {
            Text("Toque num cidadão · pinça para zoom", style = MaterialTheme.typography.labelSmall, color = Lumon.CreamDim)
        }
        Text("Cor = ação atual · balão laranja = fala gerada pelo LLM local", style = MaterialTheme.typography.labelSmall, color = Lumon.CreamDim)
    }
}

@Composable
private fun AgentCard(a: AgentView, modifier: Modifier, onOpen: () -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(12.dp)).background(Lumon.Glass)
            .border(1.dp, Lumon.Outline, RoundedCornerShape(12.dp)).padding(12.dp).testTag("agent_card"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a.name, style = MaterialTheme.typography.titleMedium, color = Lumon.Sunflower, modifier = Modifier.weight(1f).testTag("agent_card_name"))
            Text("ger. ${a.generation} · (${a.x},${a.z})", style = MonoStyle, color = Lumon.CreamDim)
        }
        Text((a.action?.label ?: "Ocioso") + if (a.neural) " · decisão do LLM local" else "", color = Lumon.Teal, style = MaterialTheme.typography.bodyMedium)
        Text(a.thought, color = Lumon.Cream, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (a.speech.isNotBlank()) Text("“${a.speech}”", color = Lumon.CreamDim, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        LabeledBar("Bem-estar médio", a.meanNeed)
        LabeledBar("Humor", (a.mood + 1) / 2, Lumon.Sunflower, a.mood.f(2))
        TextButton(onClick = onOpen) { Text("Abrir ficha do cidadão") }
    }
}

