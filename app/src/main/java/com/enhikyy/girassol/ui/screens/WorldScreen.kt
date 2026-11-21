package com.enhikyy.girassol.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import com.enhikyy.girassol.render3d.FocusRequest
import com.enhikyy.girassol.render3d.WorldFocus
import com.enhikyy.girassol.sim.SimClock
import com.enhikyy.girassol.ui.components.CitizenText
import com.enhikyy.girassol.ui.components.CommandCapsule
import com.enhikyy.girassol.ui.components.GlassPill
import com.enhikyy.girassol.ui.components.Recipient
import com.enhikyy.girassol.ui.glass.GlassHost
import com.enhikyy.girassol.ui.glass.GlassMotion
import com.enhikyy.girassol.ui.glass.GlassTone
import com.enhikyy.girassol.ui.glass.liquidGlass
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

/** Janela máxima (em ticks) durante a qual uma fala aparece em balão sobre o cidadão. */
const val SPEECH_BALLOON_TICKS = 90L
/** Máximo de balões simultâneos (com afastamento e fade-out). */
const val MAX_BALLOONS = 2
/** v3.9: a 1 tick/s, 90 ticks = 90 s — o balão vive no máximo ~25 s reais. */
const val BALLOON_MAX_SECONDS = 25.0

/** Janela efetiva do balão (ticks) para a taxa atual. */
fun balloonWindow(tps: Double): Long =
    if (tps <= 0.0) SPEECH_BALLOON_TICKS else minOf(SPEECH_BALLOON_TICKS, (tps * BALLOON_MAX_SECONDS).toLong().coerceAtLeast(8L))

/**
 * Aba Biosfera (v3.9): cena 3D/2D em tela cheia como fundo de um [GlassHost]; por cima, HUD em
 * pílulas Liquid Glass (menu, relógio simulado, população/modelos, ETA), alternador 3D | 2D,
 * balões de fala com seta (toque = câmera voa até o cidadão e abre a conversa rápida) e a barra
 * rápida recolhível com a cápsula de comando.
 */
@Composable
fun WorldScreen(onOpenCitizen: (String) -> Unit, onOpenMenu: () -> Unit = {}) {
    val snap by SimulationHub.render.collectAsStateWithLifecycle()
    val status by SimulationHub.status.collectAsStateWithLifecycle()
    val dash by SimulationHub.dashboard.collectAsStateWithLifecycle()
    val llm by LocalLlmManager.stats.collectAsStateWithLifecycle()
    val models by LocalLlmManager.models.collectAsStateWithLifecycle()
    val pending by SimulationHub.chatPending.collectAsStateWithLifecycle()
    val preferred by RenderModePolicy.preferred.collectAsStateWithLifecycle()
    val focusReq by WorldFocus.request.collectAsStateWithLifecycle()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val context = LocalContext.current
    val importer = rememberFounderImporter()
    var libraryMsg by remember { mutableStateOf("") }
    val ingest = rememberDocumentIngester { libraryMsg = it }
    val s = snap ?: return EmptyState("Carregando o mundo…")
    var selected by remember { mutableStateOf<String?>(null) }
    var filamentFailure by remember { mutableStateOf<String?>(null) }
    var projected by remember { mutableStateOf<Map<String, Offset>>(emptyMap()) }
    var quickOpen by rememberSaveable { mutableStateOf(false) }
    var quickTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    val foreground = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val gpu by GpuCalibration.state.collectAsStateWithLifecycle()
    val eff = RenderModePolicy.effective(preferred, status.thermalStatus, filamentFailure, foreground, gpu)
    val fxLevel = gpu.active ?: EffectLevel.NONE
    val tps = status.effectiveTps.takeIf { it > 0.1 } ?: status.targetTps.toDouble()

    LaunchedEffect(focusReq) { focusReq?.let { selected = it.citizenId } }
    val focusCitizen: (String) -> Unit = { id -> WorldFocus.focus(id); selected = id; quickTarget = id; quickOpen = true }

    GlassHost(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        background = {
            when (eff.mode) {
                RenderMode.MODE_3D -> Filament3DView(
                    snapshot = s,
                    onPick = { selected = it },
                    onFailure = { filamentFailure = it },
                    onProjected = { projected = it },
                    modifier = Modifier.fillMaxSize(),
                    level = fxLevel,
                    focus = focusReq
                )
                RenderMode.ECO_2D -> Iso2DView(s, selected, showTrail = true, showFood = false, focus = focusReq,
                    onPick = { selected = it }, onProjected = { projected = it })
            }
        }
    ) {
        SpeechBalloons(s, projected, selected, balloonWindow(tps), onTap = focusCitizen)

        // HUD superior: pílulas de vidro (menu + relógio/população) e ETA da próxima geração
        Column(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 12.dp, top = 8.dp, end = 124.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.liquidGlass(Shapes.Capsule, tone = GlassTone.REGULAR, elevation = 12.dp)
                    .padding(start = 4.dp, end = 14.dp, top = 3.dp, bottom = 3.dp).testTag("biosfera_hud"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconButton(onClick = onOpenMenu, modifier = Modifier.size(36.dp).testTag("open_drawer")) {
                    Icon(Icons.Outlined.Menu, contentDescription = "abrir menu", tint = Lumon.Cream)
                }
                Column {
                    Text("${SimClock.label(s.tick)} · ${SimClock.period(s.tick)}" + if (!status.running) " · pausado" else "",
                        style = MaterialTheme.typography.labelMedium, color = Lumon.Sunflower, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("hud_clock"))
                    val activeModels = models.count { it.active }
                    val modelLine = when {
                        models.isEmpty() -> "sem modelos"
                        llm.status == LlmStatus.READY || llm.status == LlmStatus.BUSY -> "$activeModels .gguf · ${llm.tokensPerSecond.f(1)} tok/s"
                        else -> "$activeModels .gguf · ${llm.status.label}"
                    }
                    Text("${s.population} ${if (s.population == 1) "cidadão" else "cidadãos"} · $modelLine",
                        style = MaterialTheme.typography.labelSmall, color = Lumon.Cream, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("hud_population"))
                }
            }
            val g = dash?.generation
            if (g != null && s.population > 0) {
                Text(etaLabel(g, tps),
                    style = MaterialTheme.typography.labelSmall, color = if (g.status == ReproductionMerit.Status.PRONTO) Lumon.Ok else Lumon.Sunflower,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.liquidGlass(Shapes.Capsule, tone = GlassTone.CLEAR, elevation = 8.dp)
                        .padding(horizontal = 12.dp, vertical = 5.dp).testTag("hud_eta"))
            }
        }

        // alternador 3D | 2D em vidro
        Row(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 12.dp, top = 10.dp)
                .liquidGlass(Shapes.Capsule, tone = GlassTone.REGULAR, elevation = 12.dp).padding(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SegmentPill("3D", eff.mode == RenderMode.MODE_3D, Modifier.testTag("world_mode_3d")) {
                filamentFailure = null; RenderModePolicy.set(context, RenderMode.MODE_3D)
            }
            SegmentPill("2D", eff.mode == RenderMode.ECO_2D, Modifier.testTag("world_mode_2d")) { RenderModePolicy.set(context, RenderMode.ECO_2D) }
            Box(Modifier.size(6.dp).testTag("world_mode_label").semantics { contentDescription = "modo:${eff.mode.name}" })
        }

        // rodapé: cartão do cidadão + barra rápida (fica acima da navegação flutuante e do teclado)
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding()
                .padding(start = 12.dp, end = 12.dp, bottom = 92.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val agent = selected?.let { id -> s.agents.firstOrNull { it.id == id } }
            val summary = agent?.let { a -> dash?.citizens?.firstOrNull { it.id == a.id } }
            if (agent != null) AgentCard(agent, summary?.energy, summary?.social, CitizenText.nearestPlace(s.objects, agent.fx, agent.fz),
                onClose = { selected = null }) { onOpenCitizen(agent.id) }
            else if (s.population == 0) {
                Button(
                    onClick = { importer(null) }, shape = Shapes.Capsule,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Lumon.Sunflower),
                    modifier = Modifier.align(Alignment.CenterHorizontally).liquidGlass(Shapes.Capsule, tone = GlassTone.REGULAR).testTag("import_first_gguf")
                ) { Text("Importar .gguf para criar o primeiro cidadão") }
            }
            if (libraryMsg.isNotBlank()) Text(libraryMsg, style = MaterialTheme.typography.labelSmall, color = Lumon.CreamDim,
                modifier = Modifier.liquidGlass(Shapes.Capsule, tone = GlassTone.CLEAR, elevation = 6.dp).padding(horizontal = 12.dp, vertical = 5.dp))
            val alive = s.agents
            AnimatedVisibility(quickOpen && alive.isNotEmpty(), enter = fadeIn(GlassMotion.spec()) + expandVertically(GlassMotion.spec()),
                exit = fadeOut(GlassMotion.spec()) + shrinkVertically(GlassMotion.spec())) {
                CommandCapsule(
                    draft = draft, onDraft = { draft = it },
                    recipients = listOf(Recipient(null, "Todos")) + alive.map { Recipient(it.id, it.name) },
                    selected = quickTarget, onSelect = { quickTarget = it; it?.let(WorldFocus::focus) },
                    busy = pending.isNotEmpty(),
                    onSend = { val t = draft.trim(); if (t.isNotEmpty()) { SimulationHub.sendChat(quickTarget, t); draft = "" } },
                    onStop = { SimulationHub.stopChat() },
                    onFocus = { quickTarget?.let(WorldFocus::focus) },
                    onAttachDocument = ingest,
                    onImportCitizen = { importer(null) },
                    modifier = Modifier.testTag("world_quick_capsule")
                )
            }
            if (alive.isNotEmpty()) Row(Modifier.align(Alignment.End)) {
                GlassPill(if (quickOpen) "Recolher" else "Conversar", quickOpen, "world_quick_toggle", backdrop = true) { quickOpen = !quickOpen }
            }
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
    focus: FocusRequest?,
    onPick: (String?) -> Unit, onProjected: (Map<String, Offset>) -> Unit
) {
    val terrainKey = s.widthTiles * 1000 + s.districts.size
    val terrain = remember(terrainKey) { IsoRenderer.buildTerrain(s).asImageBitmap() }
    var zoom by remember { mutableFloatStateOf(0f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = Lumon.Cream)
    val w = s.widthTiles
    // v3.9: "cover" — o mapa sempre preenche 100 % do viewport (sem faixas pretas)
    fun cover(): Float = if (viewport.width <= 0f) 1f else maxOf(viewport.width / terrain.width, viewport.height / terrain.height)
    fun clampPan(p: Offset, z: Float): Offset {
        val tw = terrain.width * z; val th = terrain.height * z
        val x = if (tw <= viewport.width) (viewport.width - tw) / 2f else p.x.coerceIn(viewport.width - tw, 0f)
        val y = if (th <= viewport.height) (viewport.height - th) / 2f else p.y.coerceIn(viewport.height - th, 0f)
        return Offset(x, y)
    }
    LaunchedEffect(focus, viewport) {
        val f = focus ?: return@LaunchedEffect
        if (viewport.width <= 0f) return@LaunchedEffect
        val a = s.agents.firstOrNull { it.id == f.citizenId } ?: return@LaunchedEffect
        val (cx, cy) = IsoRenderer.pointCenter(a.fx, a.fz, w)
        val targetZoom = maxOf(zoom, cover() * 2.4f).coerceAtMost(cover() * 8f)
        val startPan = pan; val startZoom = zoom
        val endPan = clampPan(Offset(viewport.width / 2f - cx * targetZoom, viewport.height * 0.42f - cy * targetZoom), targetZoom)
        val anim = androidx.compose.animation.core.Animatable(0f)
        anim.animateTo(1f, GlassMotion.spec()) {
            zoom = startZoom + (targetZoom - startZoom) * value
            pan = startPan + (endPan - startPan) * value
        }
    }
    Canvas(
        Modifier.fillMaxSize().background(Color(0xFF1A302A)).testTag("world_viewport_2d")
            .pointerInput(terrainKey) {
                detectTransformGestures { centroid, panChange, zoomChange, _ ->
                    val old = zoom
                    val c = cover()
                    val nz = (zoom * zoomChange).coerceIn(c, c * 8f)
                    zoom = nz
                    pan = clampPan((pan - centroid) * (nz / old) + centroid + panChange, nz)
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
                        hypot(cx - wx, cy - 3f - wy) * zoom < 40f
                    }?.id)
                }
            }
    ) {
        if (viewport != size) viewport = size
        if (zoom <= 0f) {
            zoom = maxOf(size.width / terrain.width, size.height / terrain.height)
            // enquadra o centro da população (o "cover" corta as pontas do losango em telas retrato)
            val pts = s.agents.map { IsoRenderer.pointCenter(it.fx, it.fz, w) }
            val ccx = if (pts.isEmpty()) terrain.width / 2f else pts.map { it.first }.average().toFloat()
            val ccy = if (pts.isEmpty()) terrain.height / 2f else pts.map { it.second }.average().toFloat()
            viewport = size
            pan = clampPan(Offset(size.width / 2f - ccx * zoom, size.height * 0.48f - ccy * zoom), zoom)
        }
        withTransform({ translate(pan.x, pan.y); scale(zoom, zoom, Offset.Zero) }) {
            drawImage(terrain, filterQuality = FilterQuality.High)
            if (s.night) drawRect(Color(0x66000000), size = androidx.compose.ui.geometry.Size(terrain.width.toFloat(), terrain.height.toFloat()))
            if (showTrail) drawField(s.trail, w, Lumon.Sunflower)
            if (showFood) drawField(s.foodScent, w, Color(0xFF9BD36A))
            for (o in s.objects) {
                val (cx, cy) = IsoRenderer.tileCenter(o.x, o.z, w)
                drawCircle(IsoRenderer.objectColor(o.type), radius = 2.6f, center = Offset(cx, cy - 1f))
                if (o.contaminated) drawCircle(Lumon.Danger, radius = 4.5f, center = Offset(cx, cy - 1f), style = Stroke(1.2f))
                if (o.occupancy > 0) drawCircle(Color.White.copy(alpha = 0.5f), radius = 3.6f, center = Offset(cx, cy - 1f), style = Stroke(0.6f))
            }
        }
        // avatares em espaço de tela: legíveis em qualquer zoom (raio mínimo de 7 dp + nome)
        val minR = with(density) { 7.dp.toPx() }
        val out = HashMap<String, Offset>(s.agents.size * 2)
        val pickable = HashMap<String, Offset>(s.agents.size * 2)
        for (a in s.agents) {
            val (cx, cy) = IsoRenderer.pointCenter(a.fx, a.fz, w)
            val c = Offset(pan.x + cx * zoom, pan.y + (cy - 3f) * zoom)
            val r = maxOf(2.4f * zoom, minR)
            drawCircle(Color(0x88000000), radius = r * 0.9f, center = c + Offset(0f, r * 0.55f))
            drawCircle(IsoRenderer.actionColor(a.action), radius = r, center = c)
            drawCircle(lerpMood(a.mood), radius = r, center = c, style = Stroke(r * 0.28f))
            if (a.id == selected) drawCircle(Lumon.Sunflower, radius = r * 1.8f, center = c, style = Stroke(2.2f))
            val layout = measurer.measure(a.name.substringBefore(' '), labelStyle)
            val tl = Offset(c.x - layout.size.width / 2f, c.y + r + 2f)
            drawRoundRect(Color(0xAA0B0D0E), topLeft = tl - Offset(5f, 1f),
                size = androidx.compose.ui.geometry.Size(layout.size.width + 10f, layout.size.height + 2f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(layout.size.height / 2f))
            drawText(layout, topLeft = tl)
            val p = Offset(c.x, c.y - r)
            // só registra como tocável o que não fica sob o HUD (topo) nem sob a cápsula (base)
            if (p.x in 0f..size.width && p.y in 0f..size.height) out[a.id] = p
            if (p.x in 0f..size.width && p.y in size.height * 0.16f..size.height * 0.78f) pickable[a.id] = p
        }
        WorldPickRegistry.mode = "2D"
        WorldPickRegistry.positions = pickable
        if (out != lastOut2d) { lastOut2d = out; onProjected(out) }
    }
}

private var lastOut2d: Map<String, Offset> = emptyMap()

/**
 * Balões de diálogo Liquid Glass com seta apontando para o cidadão: no máximo [MAX_BALLOONS]
 * falas recentes, posicionados sem sobreposição e com fade-out. Toque = foco + conversa rápida.
 */
@Composable
private fun SpeechBalloons(s: WorldRenderSnapshot, projected: Map<String, Offset>, selected: String?, window: Long, onTap: (String) -> Unit) {
    val speaking = s.agents
        .filter { it.speech.isNotBlank() && it.speechAgeTicks <= window && projected.containsKey(it.id) }
        .sortedBy { it.speechAgeTicks }
        .take(MAX_BALLOONS)
    val thinker = selected?.let { id -> s.agents.firstOrNull { it.id == id && projected.containsKey(id) && speaking.none { sp -> sp.id == id } } }
    val items = speaking.map { BalloonItem(it.id, it.name, it.speech, it.neural, false, projected.getValue(it.id), balloonAlpha(it.speechAgeTicks, window)) } +
        listOfNotNull(thinker?.takeIf { it.thought.isNotBlank() }?.let { BalloonItem(it.id, it.name, it.thought, it.neural, true, projected.getValue(it.id), 1f) })
    if (items.isEmpty()) return
    val tails = remember { BalloonTails() }
    Layout(
        content = { for (b in items) BalloonBody(b) { onTap(b.id) } },
        modifier = Modifier.fillMaxSize().drawBehind {
            val list = tails.list
            for (t in list) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(t.baseX - 9f, t.baseY - 1f); lineTo(t.baseX + 9f, t.baseY - 1f); lineTo(t.tipX, t.tipY); close()
                }
                drawPath(path, Lumon.Surface.copy(alpha = 0.78f * t.alpha))
                drawPath(path, Color.White.copy(alpha = 0.28f * t.alpha), style = Stroke(1f))
                drawCircle(if (t.thought) Lumon.Violet else Lumon.Sunflower, radius = 3.5f, center = Offset(t.tipX, t.tipY), alpha = t.alpha)
            }
        }
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val gap = 14.dp.roundToPx()
        val topInset = 96.dp.roundToPx()
        val placed = ArrayList<androidx.compose.ui.unit.IntRect>()
        val newTails = ArrayList<Tail>()
        val positions = placeables.mapIndexed { i, p ->
            val a = items[i].anchor
            var x = (a.x - p.width / 2f).roundToInt().coerceIn(0, (constraints.maxWidth - p.width).coerceAtLeast(0))
            var y = (a.y - p.height - gap).roundToInt().coerceIn(topInset, (constraints.maxHeight - p.height).coerceAtLeast(topInset))
            var rect = androidx.compose.ui.unit.IntRect(x, y, x + p.width, y + p.height)
            var guard = 0
            while (guard++ < 8) {
                val hit = placed.firstOrNull { it.overlaps(rect) } ?: break
                val upY = hit.top - p.height - gap
                if (upY >= topInset) y = upY
                else x = if (hit.center.x > constraints.maxWidth / 2) (hit.left - p.width - gap).coerceAtLeast(0)
                    else (hit.right + gap).coerceAtMost((constraints.maxWidth - p.width).coerceAtLeast(0))
                rect = androidx.compose.ui.unit.IntRect(x, y, x + p.width, y + p.height)
            }
            placed += rect
            val baseX = a.x.coerceIn(rect.left + 18f, rect.right - 18f)
            newTails += Tail(baseX, rect.bottom.toFloat(), a.x, a.y, items[i].alpha, items[i].thought)
            IntOffset(x, y)
        }
        tails.list = newTails
        layout(constraints.maxWidth, constraints.maxHeight) { placeables.forEachIndexed { i, p -> p.place(positions[i]) } }
    }
}

private class Tail(val baseX: Float, val baseY: Float, val tipX: Float, val tipY: Float, val alpha: Float, val thought: Boolean)
private class BalloonTails { var list by mutableStateOf<List<Tail>>(emptyList()) }

private data class BalloonItem(val id: String, val name: String, val text: String, val neural: Boolean, val thought: Boolean, val anchor: Offset, val alpha: Float)

/** 1 até 60 % da janela; depois desvanece linearmente até 0. */
fun balloonAlpha(ageTicks: Long, window: Long = SPEECH_BALLOON_TICKS): Float {
    val fadeStart = window * 0.6
    if (ageTicks <= fadeStart) return 1f
    return (1.0 - (ageTicks - fadeStart) / (window - fadeStart)).toFloat().coerceIn(0f, 1f)
}

@Composable
private fun BalloonBody(b: BalloonItem, onTap: () -> Unit) {
    Column(
        Modifier.graphicsLayer { alpha = b.alpha }.widthIn(max = 220.dp)
            .liquidGlass(Shapes.Control, tone = GlassTone.REGULAR, elevation = 10.dp)
            .border(1.dp, if (b.neural) Lumon.Halation.copy(alpha = 0.7f) else if (b.thought) Lumon.Violet.copy(alpha = 0.6f) else Color.Transparent, Shapes.Control)
            .clip(Shapes.Control)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "focar em ${b.name}", onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .testTag(if (b.thought) "balloon_thought" else "balloon_speech")
    ) {
        Text((if (b.thought) "${b.name} pensa" else b.name) + if (b.neural) " · .gguf" else "", style = MaterialTheme.typography.labelSmall,
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
private fun AgentCard(a: AgentView, energy: Double?, social: Double?, place: String, onClose: () -> Unit, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().liquidGlass(Shapes.Card, tone = GlassTone.DENSE, elevation = 18.dp)
            .padding(horizontal = 18.dp, vertical = 14.dp).testTag("agent_card"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a.name, style = MaterialTheme.typography.titleMedium, color = Lumon.Sunflower, modifier = Modifier.weight(1f).testTag("agent_card_name"))
            Text("g${a.generation}", style = MonoStyle, color = Lumon.CreamDim)
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp).testTag("agent_card_close")) {
                Icon(Icons.Outlined.Close, contentDescription = "fechar", tint = Lumon.CreamDim)
            }
        }
        Text("${CitizenText.action(a.action)} · $place" + if (a.neural) " · pensou com o próprio .gguf" else "",
            color = Lumon.Teal, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("agent_card_action"))
        if (a.thought.isNotBlank()) Text(a.thought, color = Lumon.Cream, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (a.speech.isNotBlank()) Text("“${a.speech}”", color = Lumon.CreamDim, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        LabeledBar("Bem-estar", a.meanNeed)
        if (energy != null) LabeledBar("Energia", energy, Lumon.Sunflower)
        if (social != null) LabeledBar("Social", social, Lumon.Violet)
        TextButton(onClick = onOpen) { Text("Abrir ficha") }
    }
}
