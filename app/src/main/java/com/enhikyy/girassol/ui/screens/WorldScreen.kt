package com.enhikyy.girassol.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enhikyy.girassol.core.world.AgentView
import com.enhikyy.girassol.sim.SimulationHub
import com.enhikyy.girassol.ui.components.EmptyState
import com.enhikyy.girassol.ui.components.LabeledBar
import com.enhikyy.girassol.ui.components.f
import com.enhikyy.girassol.ui.theme.Lumon
import com.enhikyy.girassol.ui.theme.MonoStyle
import kotlin.math.hypot

@Composable
fun WorldScreen(onOpenCitizen: (String) -> Unit) {
    val snap by SimulationHub.render.collectAsStateWithLifecycle()
    val s = snap ?: return EmptyState("Carregando o mundo…")
    val terrainKey = s.widthTiles * 1000 + s.districts.size
    val terrain = remember(terrainKey) { IsoRenderer.buildTerrain(s).asImageBitmap() }
    var zoom by remember { mutableFloatStateOf(0f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var selected by remember { mutableStateOf<String?>(null) }
    var showTrail by remember { mutableStateOf(true) }
    var showFood by remember { mutableStateOf(false) }
    val w = s.widthTiles

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverlayChip("Trilhas (feromônio)", showTrail) { showTrail = it }
            OverlayChip("Cheiro de comida", showFood) { showFood = it }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(terrainKey) {
                        detectTransformGestures { centroid, panChange, zoomChange, _ ->
                            val old = zoom
                            val nz = (zoom * zoomChange).coerceIn(0.2f, 8f)
                            pan = (pan - centroid) * (nz / old) + centroid + panChange
                            zoom = nz
                        }
                    }
                    .pointerInput(s.tick / 50, terrainKey) {
                        detectTapGestures { p ->
                            if (zoom <= 0f) return@detectTapGestures
                            val wx = (p.x - pan.x) / zoom
                            val wy = (p.y - pan.y) / zoom
                            val hit = SimulationHub.render.value?.agents?.minByOrNull { a ->
                                val (cx, cy) = IsoRenderer.tileCenter(a.x, a.z, w)
                                hypot(cx - wx, cy - 3f - wy)
                            }
                            selected = hit?.takeIf { a ->
                                val (cx, cy) = IsoRenderer.tileCenter(a.x, a.z, w)
                                hypot(cx - wx, cy - 3f - wy) * zoom < 36f
                            }?.id
                        }
                    }
            ) {
                if (zoom <= 0f) {
                    zoom = minOf(size.width / terrain.width, size.height / terrain.height) * 0.98f
                    pan = Offset((size.width - terrain.width * zoom) / 2f, (size.height - terrain.height * zoom) / 2f)
                }
                withTransform({ translate(pan.x, pan.y); scale(zoom, zoom, Offset.Zero) }) {
                    drawImage(terrain)
                    if (s.night) drawRect(Color(0x55061018), size = androidx.compose.ui.geometry.Size(terrain.width.toFloat(), terrain.height.toFloat()))
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
            }
            val agent = selected?.let { id -> s.agents.firstOrNull { it.id == id } }
            if (agent != null) AgentCard(agent, Modifier.align(Alignment.BottomCenter)) { onOpenCitizen(agent.id) }
            else Legend(Modifier.align(Alignment.BottomStart))
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
private fun OverlayChip(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = on, onClick = { onChange(!on) }, label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Lumon.TealDim, selectedLabelColor = Lumon.Cream, labelColor = Lumon.CreamDim)
    )
}

@Composable
private fun Legend(modifier: Modifier) {
    Column(
        modifier.padding(10.dp).clip(RoundedCornerShape(8.dp)).background(Lumon.Surface.copy(alpha = 0.9f)).padding(8.dp)
    ) {
        Text("Toque num cidadão · pinça para zoom", style = MaterialTheme.typography.labelSmall, color = Lumon.CreamDim)
        Text("Cor = ação atual · anel = humor · vermelho = horta contaminada", style = MaterialTheme.typography.labelSmall, color = Lumon.CreamDim)
    }
}

@Composable
private fun AgentCard(a: AgentView, modifier: Modifier, onOpen: () -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(10.dp)).background(Lumon.Surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a.name, style = MaterialTheme.typography.titleMedium, color = Lumon.Sunflower, modifier = Modifier.weight(1f))
            Text("ger. ${a.generation} · (${a.x},${a.z})", style = MonoStyle, color = Lumon.CreamDim)
        }
        Text(a.action?.label ?: "Ocioso", color = Lumon.Teal, style = MaterialTheme.typography.bodyMedium)
        Text(a.thought, color = Lumon.Cream, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        LabeledBar("Bem-estar médio", a.meanNeed)
        LabeledBar("Humor", (a.mood + 1) / 2, Lumon.Sunflower, a.mood.f(2))
        TextButton(onClick = onOpen) { Text("Abrir ficha do cidadão") }
    }
}
