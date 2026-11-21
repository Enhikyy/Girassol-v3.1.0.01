package com.enhikyy.girassol.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.enhikyy.girassol.ui.theme.MonoStyle

/**
 * Aba Evolução: ETA da próxima geração, gráficos Canvas unificados (desempenho cognitivo,
 * árvore geracional, economia + cliodinâmica), mérito individual e — recolhidos — cidadãos,
 * sociedade e mente coletiva.
 */
@Composable
fun EvolutionScreen(onOpenCitizen: (String) -> Unit) {
    val dash by SimulationHub.dashboard.collectAsStateWithLifecycle()
    val status by SimulationHub.status.collectAsStateWithLifecycle()
    val d = dash ?: return EmptyState("Carregando evolução…")
    val g = d.generation
    val tps = status.effectiveTps.takeIf { it > 0.1 } ?: status.targetTps.toDouble()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp).testTag("evolution_list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("Próxima geração", Modifier.testTag("evo_eta")) {
                Text(etaLabel(g, tps), style = MaterialTheme.typography.titleMedium,
                    color = if (g.status == ReproductionMerit.Status.PRONTO) Lumon.Ok else Lumon.Sunflower)
                if (g.parentA != null) KeyValue("Par", "${g.parentA} + ${g.parentB ?: "—"}")
                if (g.blockers.isNotEmpty()) Text("Falta: " + g.blockers.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
                Text("Nascimento exige que os dois adultos provem evolução desde t0: erro do aceitador Anokhin ≤ 90 % da linha de base, Brier melhor, ≥ 1 promoção aprovada no gate RSI (CMA-ES ou LoRA no sono), bem-estar ≥ 0,70 e excedente nos comuns.",
                    style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
            }
        }
        item {
            SectionCard("Desempenho cognitivo", Modifier.testTag("evo_perf_chart")) {
                PerformanceChart(g.history)
            }
        }
        item {
            SectionCard("Árvore geracional", Modifier.testTag("evo_tree")) { LineageTree(g) }
        }
        item {
            SectionCard("Economia e cliodinâmica", Modifier.testTag("evo_econ_chart")) { EconomyChart(g.history) }
        }
        if (g.merit.isNotEmpty()) item {
            SectionCard("Mérito individual (vs t0)", Modifier.testTag("evo_merit")) {
                for (m in g.merit) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${m.name} · g${m.generation}", style = MaterialTheme.typography.titleSmall, color = Lumon.Cream, modifier = Modifier.weight(1f))
                        Pill(if (m.ready) "pronto" else "evoluindo", if (m.ready) Lumon.Ok else Lumon.Sunflower)
                    }
                    Text("Anokhin ${m.acceptorBaseline.f(3)} → ${m.acceptorRecent.f(3)} · Brier ${m.brierBaseline.f(3)} → ${m.brierRecent.f(3)} · promoções ${m.promotions}",
                        style = MonoStyle, color = Lumon.CreamDim)
                    CompactBar("bem-estar", m.wellbeing, if (m.wellbeing >= ReproductionMerit.WELLBEING_MIN) Lumon.Ok else Lumon.Sunflower)
                    Text("IRT 2PL θ ${if (m.theta0.isNaN()) "—" else m.theta0.f(2)} → ${m.theta.f(2)} ± ${m.thetaSe.f(2)} · PER ${m.replaySize} exp. (prio. ${m.replayMeanPriority.f(3)}) · léxico ${m.lexiconSize} glifos (${m.lexiconInherited} herdados)",
                        style = MonoStyle, color = Lumon.CreamDim)
                    if (m.blockers.isNotEmpty()) Text(m.blockers.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
                }
            }
        }
        if (g.irtItems.isNotEmpty()) item {
            SectionCard("Itens IRT 2PL (calibração conjunta)", Modifier.testTag("evo_irt")) {
                Text("Replays PER aplicados: ${g.perReplays}", style = MonoStyle, color = Lumon.CreamDim)
                for (item in g.irtItems) Text("${item.action}: a ${item.discrimination.f(2)} · b ${item.difficulty.f(2)}", style = MonoStyle, color = Lumon.CreamDim)
            }
        }
        item {
            Collapsible("Cidadãos (${d.citizens.count { it.alive }} vivos)", "evo_citizens", initiallyOpen = true) {
                if (d.citizens.isEmpty()) Text("Nenhum cidadão ainda.", color = Lumon.CreamDim)
                d.citizens.forEach { c -> CitizenRow(c) { onOpenCitizen(c.id) } }
            }
        }
        item { Collapsible("Sociedade, economia e instituições", "evo_society") { SocietySections(d) } }
        item { Collapsible("Mente coletiva e RSI", "evo_mind") { MindSections(d) } }
        item { Box(Modifier.height(12.dp)) }
    }
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

@Composable
private fun PerformanceChart(h: List<EvolutionSample>) {
    if (h.size < 2) { Text("O gráfico começa ao fim do 2º dia do mundo.", style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim); return }
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
    Text("dia ${last.day} · Anokhin ${last.acceptorMismatch.f(3)} · Brier ${last.brier.f(3)} · ${last.promotions} promoções · ${last.loraAccepted} LoRA · CMA g${last.cmaGeneration}",
        style = MonoStyle, color = Lumon.CreamDim)
}

@Composable
private fun EconomyChart(h: List<EvolutionSample>) {
    if (h.size < 2) { Text("O gráfico começa ao fim do 2º dia do mundo.", style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim); return }
    val series = listOf(
        Series("Comuns (comida)", Lumon.Ok, h.map { it.commonsFood }),
        Series("PSI", Lumon.Danger, h.map { it.psi }),
        Series("Gini", Lumon.Violet, h.map { it.gini }, dashed = true),
        Series("População", Lumon.Cream, h.map { it.population.toDouble() }, dashed = true)
    )
    LineChart(series)
    Legend(series)
    val last = h.last()
    Text("comuns ${last.commonsFood.f(1)} · PSI ${last.psi.f(3)} · Gini ${last.gini.f(3)} · ${last.ledgerEntries} lançamentos",
        style = MonoStyle, color = Lumon.CreamDim)
}

/** g0 → g1 → g2…: uma linha por geração, arestas dos pais, nó fantasma da próxima geração. */
@Composable
private fun LineageTree(g: GenerationView) {
    val nodes = g.lineage
    if (nodes.isEmpty()) { Text("Importe um .gguf para criar o primeiro fundador (g0).", style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim); return }
    val measurer = rememberTextMeasurer()
    val byGen = nodes.groupBy { it.generation }.toSortedMap()
    val showGhost = g.status == ReproductionMerit.Status.AGUARDANDO || g.status == ReproductionMerit.Status.PRONTO
    val rows = byGen.size + if (showGhost) 1 else 0
    val alive = Lumon.Sunflower; val dead = Lumon.CreamDim; val edge = Lumon.Outline; val text = Lumon.Cream; val ghost = Lumon.Teal
    val label = TextStyle(fontSize = 10.sp, color = text)
    Canvas(Modifier.fillMaxWidth().height((rows * 64 + 8).dp)) {
        val pos = HashMap<String, Offset>()
        var row = 0
        for ((_, list) in byGen) {
            val shown = list.take(8)
            shown.forEachIndexed { i, n -> pos[n.id] = Offset(size.width * (i + 1) / (shown.size + 1), 24.dp.toPx() + row * 64.dp.toPx()) }
            row++
        }
        for (n in nodes) {
            val p = pos[n.id] ?: continue
            for (par in n.parents) pos[par]?.let { drawLine(edge, it, p, 2f) }
        }
        for (n in nodes) {
            val p = pos[n.id] ?: continue
            drawCircle(if (n.alive) alive else dead, radius = 9.dp.toPx(), center = p)
            val t = measurer.measure("${n.name} g${n.generation}", label)
            drawText(t, topLeft = Offset(p.x - t.size.width / 2f, p.y + 11.dp.toPx()))
        }
        if (showGhost) {
            val c = Offset(size.width / 2f, 24.dp.toPx() + row * 64.dp.toPx())
            val parents = nodes.filter { it.name == g.parentA || it.name == g.parentB }.mapNotNull { pos[it.id] }
            for (p in parents) drawLine(ghost.copy(alpha = 0.6f), p, c, 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            drawCircle(ghost, radius = 9.dp.toPx(), center = c, style = Stroke(2.5f))
            val t = measurer.measure(if (g.status == ReproductionMerit.Status.PRONTO) "g${g.nextGeneration} pronto"
                else "g${g.nextGeneration} · ${if (g.etaTicks >= 0) "~${g.etaDays.f(1)} dias" else "evoluindo"}", label.copy(color = ghost))
            drawText(t, topLeft = Offset(c.x - t.size.width / 2f, c.y + 11.dp.toPx()))
        }
    }
}
