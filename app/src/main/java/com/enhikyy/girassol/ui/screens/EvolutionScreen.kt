package com.enhikyy.girassol.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import com.enhikyy.girassol.core.world.ActionType
import com.enhikyy.girassol.core.world.CitizenSummary
import com.enhikyy.girassol.core.world.DashboardSnapshot
import com.enhikyy.girassol.core.world.LexiconView
import com.enhikyy.girassol.core.world.LineageNode
import com.enhikyy.girassol.sim.LiveSample
import com.enhikyy.girassol.sim.SimClock
import com.enhikyy.girassol.ui.components.CitizenText
import com.enhikyy.girassol.ui.components.ReasoningChip
import com.enhikyy.girassol.ui.components.Shapes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enhikyy.girassol.core.world.EvolutionSample
import com.enhikyy.girassol.core.world.GenerationView
import com.enhikyy.girassol.core.world.ReproductionMerit
import com.enhikyy.girassol.sim.SimulationHub
import com.enhikyy.girassol.ui.components.Collapsible
import com.enhikyy.girassol.ui.components.CompactBar
import com.enhikyy.girassol.ui.components.EmptyState
import com.enhikyy.girassol.ui.components.KeyValue
import com.enhikyy.girassol.ui.components.Pill
import com.enhikyy.girassol.ui.components.SectionCard
import com.enhikyy.girassol.ui.components.f
import com.enhikyy.girassol.ui.theme.Lumon

/**
 * Aba Evolução (v3.9): ETA da próxima geração, gráficos vivos desde o tick 1, árvore geracional
 * sem sobreposição (grade responsiva de cartões com conectores vetoriais), cartões estilo The Sims
 * por cidadão e — recolhidos — sociedade e mente coletiva. A telemetria técnica (Anokhin, Brier,
 * IRT 2PL) fica dentro do chip "Raciocínio cognitivo", fechado por padrão.
 */
@Composable
fun EvolutionScreen(onOpenCitizen: (String) -> Unit) {
    val dash by SimulationHub.dashboard.collectAsStateWithLifecycle()
    val d = dash ?: return EmptyState("Carregando evolução…")
    val status by SimulationHub.status.collectAsStateWithLifecycle()
    val live by SimulationHub.live.collectAsStateWithLifecycle()
    val render by SimulationHub.render.collectAsStateWithLifecycle()
    val tps = status.effectiveTps.takeIf { it > 0.1 } ?: status.targetTps.toDouble()
    EvolutionContent(d, live, tps, onOpenCitizen, placeOf = { id ->
        render?.let { r -> r.agents.firstOrNull { it.id == id }?.let { CitizenText.nearestPlace(r.objects, it.fx, it.fz) } }
    }, memoriesOf = { id -> SimulationHub.citizenDetail(id)?.memories?.sortedByDescending { it.tick }?.take(3)?.map { it.content }.orEmpty() })
}

/** Conteúdo puro da aba (testável com snapshots sintéticos). */
@Composable
fun EvolutionContent(
    d: DashboardSnapshot,
    live: List<LiveSample>,
    tps: Double,
    onOpenCitizen: (String) -> Unit,
    placeOf: (String) -> String? = { null },
    memoriesOf: suspend (String) -> List<String> = { emptyList() }
) {
    val g = d.generation
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp).testTag("evolution_list"),
        contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("Próxima geração", Modifier.testTag("evo_eta")) {
                Text(etaLabel(g, tps), style = MaterialTheme.typography.titleMedium,
                    color = if (g.status == ReproductionMerit.Status.PRONTO) Lumon.Ok else Lumon.Sunflower)
                if (g.parentA != null) KeyValue("Par", "${g.parentA} + ${g.parentB ?: "—"}")
                if (g.blockers.isNotEmpty()) Text("Falta: " + g.blockers.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
                Text("Um bebê só nasce quando os dois adultos mostram que aprenderam desde que chegaram ao mundo, estão bem e a cidade tem comida sobrando.",
                    style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
                ReasoningChip(listOf(
                    "Critério" to "erro do aceitador Anokhin ≤ 90 % da linha de base",
                    "Calibração" to "Brier melhor que em t0",
                    "RSI" to "≥ 1 promoção aprovada (CMA-ES ou LoRA no sono)",
                    "Bem-estar" to "≥ 0,70 e excedente nos comuns"
                ), tag = "evo_eta_reasoning")
            }
        }
        item {
            SectionCard("Desempenho cognitivo", Modifier.testTag("evo_perf_chart")) {
                PerformanceChart(g.history, live)
            }
        }
        item {
            SectionCard("Árvore geracional", Modifier.testTag("evo_tree")) { LineageTree(g) }
        }
        item {
            SectionCard("Economia e cliodinâmica", Modifier.testTag("evo_econ_chart")) { EconomyChart(g.history, live) }
        }
        if (g.merit.isNotEmpty()) item {
            SectionCard("Mérito individual (vs t0)", Modifier.testTag("evo_merit")) {
                for (m in g.merit) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${m.name} · g${m.generation}", style = MaterialTheme.typography.titleSmall, color = Lumon.Cream, modifier = Modifier.weight(1f))
                        Pill(if (m.ready) "pronto" else "evoluindo", if (m.ready) Lumon.Ok else Lumon.Sunflower)
                    }
                    Text(meritSentence(m.acceptorBaseline, m.acceptorRecent, m.promotions), style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
                    CompactBar("bem-estar", m.wellbeing, if (m.wellbeing >= ReproductionMerit.WELLBEING_MIN) Lumon.Ok else Lumon.Sunflower)
                    if (m.blockers.isNotEmpty()) Text(m.blockers.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
                    ReasoningChip(listOf(
                        "Anokhin" to "${m.acceptorBaseline.f(3)} → ${m.acceptorRecent.f(3)}",
                        "Brier" to "${m.brierBaseline.f(3)} → ${m.brierRecent.f(3)}",
                        "IRT 2PL θ" to "${if (m.theta0.isNaN()) "—" else m.theta0.f(2)} → ${m.theta.f(2)} ± ${m.thetaSe.f(2)}",
                        "PER" to "${m.replaySize} exp. (prio. ${m.replayMeanPriority.f(3)})",
                        "Léxico" to "${m.lexiconSize} glifos (${m.lexiconInherited} herdados)"
                    ), tag = "merit_reasoning_${m.name}")
                }
                if (g.irtItems.isNotEmpty()) ReasoningChip(
                    listOf("Replays PER" to "${g.perReplays}") + g.irtItems.map { it.action to "a ${it.discrimination.f(2)} · b ${it.difficulty.f(2)}" },
                    title = "Calibração conjunta (IRT)", tag = "evo_irt")
            }
        }
        item {
            Collapsible("Cidadãos (${d.citizens.count { it.alive }} vivos)", "evo_citizens", initiallyOpen = true) {
                if (d.citizens.isEmpty()) Text("Nenhum cidadão ainda.", color = Lumon.CreamDim)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    d.citizens.filter { it.alive }.forEach { c ->
                        SimsCard(c, placeOf(c.id), d.mind.v35.lexicon.filter { it.coiner == c.name }.take(3), d.tick, memoriesOf) { onOpenCitizen(c.id) }
                    }
                    d.citizens.filter { !it.alive }.forEach { c -> CitizenRow(c) { onOpenCitizen(c.id) } }
                }
            }
        }
        item { Collapsible("Sociedade, economia e instituições", "evo_society") { SocietySections(d) } }
        item { Collapsible("Mente coletiva e RSI", "evo_mind") { MindSections(d) } }
    }
}

/** Frase natural do aprendizado (sem equações na superfície principal). */
fun meritSentence(baseline: Double, recent: Double, promotions: Int): String {
    val gain = if (baseline > 1e-9) (1.0 - recent / baseline) * 100.0 else 0.0
    val learn = when {
        baseline <= 1e-9 -> "Ainda medindo como prevê o mundo"
        gain >= 1.0 -> "Prevê o mundo ${gain.f(0)} % melhor que ao chegar"
        gain <= -1.0 -> "Anda errando mais as previsões (${(-gain).f(0)} % pior)"
        else -> "Previsões estáveis desde que chegou"
    }
    val promo = when (promotions) { 0 -> "nenhuma melhoria aprovada ainda"; 1 -> "1 melhoria aprovada"; else -> "$promotions melhorias aprovadas" }
    return "$learn · $promo"
}

/** Cartão estilo The Sims: barras, ação atual, memórias recentes e léxico em português claro. */
@Composable
private fun SimsCard(
    c: CitizenSummary, place: String?, lexicon: List<LexiconView>, tick: Long,
    memoriesOf: suspend (String) -> List<String>, onOpen: () -> Unit
) {
    var memories by remember(c.id) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(c.id, tick / 60) { memories = runCatching { memoriesOf(c.id) }.getOrDefault(emptyList()) }
    Column(
        Modifier.fillMaxWidth().clip(Shapes.Card).background(Lumon.SurfaceHigh).clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp).testTag("sims_card_${c.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CitizenAvatar(c.id, c.name, 36)
            Column(Modifier.weight(1f)) {
                Text(c.name, style = MaterialTheme.typography.titleSmall, color = Lumon.Sunflower, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("g${c.generation} · ${c.ageDays.f(1)} dias" + (c.modelKey?.let { " · .gguf próprio" } ?: ""),
                    style = MaterialTheme.typography.labelSmall, color = Lumon.CreamDim, maxLines = 1)
            }
        }
        Text("Ação atual: ${friendlyActionLabel(c.action)}" + (place?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = Lumon.Teal, modifier = Modifier.testTag("sims_action_${c.id}"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CompactBar("Bem-estar", c.meanNeed, Lumon.Ok, Modifier.weight(1f))
            CompactBar("Energia", c.energy, Lumon.Sunflower, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CompactBar("Social", c.social, Lumon.Violet, Modifier.weight(1f))
            CompactBar("Humor", ((c.mood + 1) / 2).coerceIn(0.0, 1.0), Lumon.Teal, Modifier.weight(1f))
        }
        if (c.lastThought.isNotBlank()) Text("Pensando: ${c.lastThought}", style = MaterialTheme.typography.bodySmall, color = Lumon.Cream,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (memories.isNotEmpty()) {
            Text("Memórias", style = MaterialTheme.typography.labelSmall, color = Lumon.CreamDim)
            for (m in memories) Text("• $m", style = MaterialTheme.typography.bodySmall, color = Lumon.Cream, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (lexicon.isNotEmpty()) {
            Text("Palavras que criou", style = MaterialTheme.typography.labelSmall, color = Lumon.CreamDim)
            Text(lexicon.joinToString(" · ") { "${it.glyph} = “${it.phrase}”" }, style = MaterialTheme.typography.bodySmall, color = Lumon.Cream,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** CitizenSummary.action vem como rótulo do núcleo ("Ocioso", "Comer"…). */
fun friendlyActionLabel(label: String): String {
    val t = ActionType.entries.firstOrNull { it.label.equals(label, true) || it.name.equals(label, true) }
    return if (t != null || label.isBlank()) CitizenText.action(t) else label
}

private data class Series(val label: String, val color: Color, val values: List<Double>, val dashed: Boolean = false)

@Composable
private fun Legend(series: List<Series>) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        for (s in series) Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(s.color))
            Text(" ${s.label}", style = MaterialTheme.typography.labelSmall, color = Lumon.CreamDim)
        }
    }
}

/** Gráfico de linhas normalizadas por série (cada série na própria escala 0..máx). */
@Composable
private fun LineChart(series: List<Series>, markers: List<Int> = emptyList(), height: Int = 150) {
    val grid = Lumon.Outline
    val markColor = Lumon.Violet
    Canvas(Modifier.fillMaxWidth().height(height.dp)) {
        val w = size.width; val h = size.height
        for (i in 0..3) drawLine(grid, Offset(0f, h * i / 3f), Offset(w, h * i / 3f), 1f)
        val n = series.maxOf { it.values.size }
        if (n < 2) return@Canvas
        val dx = w / (n - 1)
        for (m in markers) drawLine(markColor.copy(alpha = 0.55f), Offset(m * dx, 0f), Offset(m * dx, h), 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        for (s in series) {
            if (s.values.size < 2) continue
            val lo = s.values.min(); val hi = s.values.max()
            val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
            val path = Path()
            s.values.forEachIndexed { i, v ->
                val x = i * dx; val y = (h - ((v - lo) / span * h * 0.9 + h * 0.05)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, s.color, style = Stroke(width = 3f, pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null))
        }
    }
}

/** v3.9: antes do 2º dia, o gráfico mostra a amostragem ao vivo (desde o tick 1). */
@Composable
private fun LiveChart(live: List<LiveSample>, population: Boolean) {
    if (live.size < 2) {
        Text("Coletando as primeiras amostras do mundo…", style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim,
            modifier = Modifier.testTag("live_chart_waiting"))
        return
    }
    val series = if (population) listOf(
        Series("População", Lumon.Cream, live.map { it.population.toDouble() }),
        Series("Bem-estar", Lumon.Ok, live.map { it.meanNeed })
    ) else listOf(
        Series("Humor", Lumon.Teal, live.map { (it.meanMood + 1) / 2 }),
        Series("Bem-estar", Lumon.Ok, live.map { it.meanNeed }),
        Series("Energia", Lumon.Sunflower, live.map { it.meanEnergy }),
        Series("Social", Lumon.Violet, live.map { it.meanSocial }, dashed = true)
    )
    Box(Modifier.testTag("live_chart")) { LineChart(series, height = 110) }
    Legend(series)
    val last = live.last()
    Text("ao vivo · ${SimClock.label(last.tick)} · ${last.population} cidadãos · bem-estar ${(last.meanNeed * 100).f(0)} %",
        style = MaterialTheme.typography.labelSmall, color = Lumon.CreamDim)
}

@Composable
private fun PerformanceChart(h: List<EvolutionSample>, live: List<LiveSample>) {
    if (h.size < 2) { LiveChart(live, population = false); return }
    val series = listOf(
        Series("Anokhin", Lumon.Teal, h.map { it.acceptorMismatch }),
        Series("Brier", Lumon.Sunflower, h.map { it.brier }),
        Series("CMA-ES", Lumon.CreamDim, h.map { it.cmaGeneration.toDouble() }, dashed = true)
    )
    // marcadores verticais: dias com nova promoção RSI ou ciclo LoRA aceito
    val markers = (1 until h.size).filter { h[it].promotions > h[it - 1].promotions || h[it].loraAccepted > h[it - 1].loraAccepted }
    LineChart(series, markers)
    Legend(series + Series("promoção/LoRA", Lumon.Violet, emptyList()))
    val last = h.last()
    Text("Dia ${last.day}: ${last.promotions} melhorias aprovadas e ${last.loraAccepted} ciclos de sono com aprendizado.",
        style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
    ReasoningChip(listOf("Anokhin" to last.acceptorMismatch.f(3), "Brier" to last.brier.f(3), "CMA-ES" to "g${last.cmaGeneration}"), tag = "perf_reasoning")
}

@Composable
private fun EconomyChart(h: List<EvolutionSample>, live: List<LiveSample>) {
    if (h.size < 2) { LiveChart(live, population = true); return }
    val series = listOf(
        Series("Comuns (comida)", Lumon.Ok, h.map { it.commonsFood }),
        Series("PSI", Lumon.Danger, h.map { it.psi }),
        Series("Gini", Lumon.Violet, h.map { it.gini }, dashed = true),
        Series("População", Lumon.Cream, h.map { it.population.toDouble() }, dashed = true)
    )
    LineChart(series)
    Legend(series)
    val last = h.last()
    Text("Comida nos comuns: ${last.commonsFood.f(1)} · ${last.ledgerEntries} lançamentos no livro-caixa",
        style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
    ReasoningChip(listOf("PSI (Turchin)" to last.psi.f(3), "Gini" to last.gini.f(3)), title = "Indicadores sociais", tag = "econ_reasoning")
}

/** g0 → g1 → g2…: grade responsiva de cartões por geração com conectores vetoriais por trás. */
@Composable
private fun LineageTree(g: GenerationView) {
    val nodes = g.lineage
    if (nodes.isEmpty()) { Text("Importe um .gguf para criar o primeiro fundador (g0).", style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim); return }
    val showGhost = g.status == ReproductionMerit.Status.AGUARDANDO || g.status == ReproductionMerit.Status.PRONTO
    val ghostLabel = if (g.status == ReproductionMerit.Status.PRONTO) "g${g.nextGeneration} pronto"
        else "g${g.nextGeneration} · ${if (g.etaTicks >= 0) "~${g.etaDays.f(1)} dias" else "evoluindo"}"
    val edge = Lumon.Outline; val ghostC = Lumon.Teal
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("lineage_tree")) {
        val lay = remember(nodes, maxWidth, showGhost) { LineageLayout.compute(nodes, maxWidth.value, showGhost) }
        Box(Modifier.fillMaxWidth().height(lay.heightDp.dp)) {
            Canvas(Modifier.matchParentSize()) {
                val k = density
                fun bottom(r: LineageLayout.Box) = Offset((r.x + r.w / 2) * k, (r.y + r.h) * k)
                fun top(r: LineageLayout.Box) = Offset((r.x + r.w / 2) * k, r.y * k)
                fun curve(a: Offset, b: Offset, color: Color, dashed: Boolean) {
                    val midY = (a.y + b.y) / 2f
                    val p = Path().apply { moveTo(a.x, a.y); cubicTo(a.x, midY, b.x, midY, b.x, b.y) }
                    drawPath(p, color, style = Stroke(2.dp.toPx(), pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(8f, 8f)) else null))
                }
                for (n in nodes) {
                    val child = lay.boxes[n.id] ?: continue
                    for (par in n.parents) lay.boxes[par]?.let { curve(bottom(it), top(child), edge, false) }
                }
                lay.ghost?.let { gh ->
                    nodes.filter { it.name == g.parentA || it.name == g.parentB }.mapNotNull { lay.boxes[it.id] }
                        .forEach { curve(bottom(it), top(gh), ghostC.copy(alpha = 0.7f), true) }
                }
            }
            for (n in nodes) {
                val r = lay.boxes[n.id] ?: continue
                LineageCard(n.name, "g${n.generation}" + if (n.modelKey != null) " · .gguf" else "", n.alive, false,
                    Modifier.offset(r.x.dp, r.y.dp).size(r.w.dp, r.h.dp).testTag("lineage_node_${n.id}"))
            }
            lay.ghost?.let { r ->
                LineageCard("Próxima geração", ghostLabel, true, true, Modifier.offset(r.x.dp, r.y.dp).size(r.w.dp, r.h.dp).testTag("lineage_node_ghost"))
            }
        }
    }
}

@Composable
private fun LineageCard(title: String, subtitle: String, alive: Boolean, ghost: Boolean, modifier: Modifier) {
    val accent = when { ghost -> Lumon.Teal; alive -> Lumon.Sunflower; else -> Lumon.CreamDim }
    Column(
        modifier.clip(Shapes.Control).background(if (ghost) Color.Transparent else Lumon.SurfaceHigh)
            .border(1.dp, accent.copy(alpha = if (ghost) 0.8f else 0.45f), Shapes.Control)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle + if (!alive && !ghost) " · falecido" else "", style = MaterialTheme.typography.labelSmall, color = Lumon.CreamDim,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Layout puro da árvore (dp): cada geração ocupa linhas de [cols] cartões (2 colunas < 600 dp,
 * 4 a partir disso), largura mínima de [MIN_NODE_W]; linhas centralizadas e espaçadas para os
 * conectores. Sem sobreposição por construção (verificado no teste de JVM).
 */
object LineageLayout {
    const val NODE_H = 52f
    const val MIN_NODE_W = 132f
    const val H_GAP = 12f
    const val ROW_GAP = 14f
    const val GEN_GAP = 34f

    data class Box(val x: Float, val y: Float, val w: Float, val h: Float) {
        fun overlaps(o: Box): Boolean = x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h
    }
    data class Result(val boxes: Map<String, Box>, val ghost: Box?, val heightDp: Float, val cols: Int)

    fun compute(nodes: List<LineageNode>, widthDp: Float, showGhost: Boolean): Result {
        val wanted = if (widthDp < 600f) 2 else 4
        val cols = maxOf(1, minOf(wanted, ((widthDp + H_GAP) / (MIN_NODE_W + H_GAP)).toInt()))
        val nodeW = ((widthDp - H_GAP * (cols - 1)) / cols).coerceAtLeast(1f)
        val out = LinkedHashMap<String, Box>()
        var y = 0f
        val gens = nodes.groupBy { it.generation }.toSortedMap()
        var first = true
        for ((_, list) in gens) {
            if (!first) y += GEN_GAP - ROW_GAP
            first = false
            for (row in list.chunked(cols)) {
                val rowW = row.size * nodeW + (row.size - 1) * H_GAP
                var x = (widthDp - rowW) / 2f
                for (n in row) { out[n.id] = Box(x, y, nodeW, NODE_H); x += nodeW + H_GAP }
                y += NODE_H + ROW_GAP
            }
        }
        var ghost: Box? = null
        if (showGhost) {
            y += GEN_GAP - ROW_GAP
            ghost = Box((widthDp - nodeW) / 2f, y, nodeW, NODE_H)
            y += NODE_H + ROW_GAP
        }
        return Result(out, ghost, (y - ROW_GAP).coerceAtLeast(NODE_H), cols)
    }
}
