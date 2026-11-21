package com.enhikyy.girassol.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enhikyy.girassol.sim.SimulationHub
import com.enhikyy.girassol.ui.components.EmptyState
import com.enhikyy.girassol.ui.components.KeyValue
import com.enhikyy.girassol.ui.components.MetricTile
import com.enhikyy.girassol.ui.components.SectionCard
import com.enhikyy.girassol.ui.components.f
import com.enhikyy.girassol.ui.theme.Lumon
import com.enhikyy.girassol.ui.theme.MonoStyle

/**
 * Painel de dados da sociedade sintética. Todas as séries vêm do DashboardSnapshot,
 * cuja origem é o WorldState publicado pelo SimulationHub.
 */
@Composable
fun DataScreen() {
    val dash by SimulationHub.dashboard.collectAsStateWithLifecycle()
    val status by SimulationHub.status.collectAsStateWithLifecycle()
    val d = dash ?: return EmptyState("Aguardando o primeiro snapshot do mundo…")
    val alive = d.citizens.filter { it.alive }
    val history = d.generation.history
    val meanWellbeing = alive.takeIf { it.isNotEmpty() }?.map { it.meanNeed }?.average()
    val generation = d.generation.lineage.maxOfOrNull { it.generation } ?: 0

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp).testTag("data_dashboard"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("VISÃO GERAL", style = MaterialTheme.typography.labelSmall, color = Lumon.Teal)
                Text("Dados da biosfera", style = MaterialTheme.typography.headlineSmall, color = Lumon.Cream)
                Text(
                    "Telemetria do mundo · tick ${d.tick} · ${if (status.running) "simulação ativa" else "simulação pausada"}",
                    style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile("CIDADÃOS VIVOS", alive.size.toString(), Modifier.weight(1f), Lumon.Sunflower)
                MetricTile("GERAÇÃO", "g$generation", Modifier.weight(1f), Lumon.Teal)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricTile("TICK ATUAL", d.tick.toString(), Modifier.weight(1f), Lumon.Cream)
                MetricTile("LANÇAMENTOS", d.society.totalLedgerEntries.toString(), Modifier.weight(1f), Lumon.Violet)
            }
        }
        item {
            SectionCard("Demografia") {
                TrendChart(
                    values = history.map { it.population.toDouble() },
                    color = Lumon.Sunflower,
                    emptyMessage = "A série demográfica será formada conforme o mundo acumular dias de simulação."
                )
                if (history.isNotEmpty()) {
                    val last = history.last()
                    KeyValue("População na última amostra", last.population.toString())
                    KeyValue("Geração máxima", "g${last.maxGeneration}")
                }
            }
        }
        item {
            SectionCard("Economia dos comuns") {
                TrendChart(
                    values = history.map { it.commonsFood },
                    color = Lumon.Ok,
                    emptyMessage = "Ainda não há amostras históricas de reservas comunitárias."
                )
                val commons = d.society.commons
                if (commons.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    commons.forEach { KeyValue(it.label, it.value.f(2)) }
                }
            }
        }
        item {
            SectionCard("Indicadores sociais") {
                TrendChart(
                    values = history.map { it.psi },
                    color = Lumon.Danger,
                    emptyMessage = "O indicador PSI aparecerá após as primeiras amostras diárias."
                )
                if (history.isNotEmpty()) {
                    KeyValue("PSI mais recente", history.last().psi.f(3))
                    KeyValue("Gini mais recente", history.last().gini.f(3))
                }
                KeyValue("Laços sociais ativos", d.society.totalTies.toString())
                KeyValue("Instituições", d.society.institutions.size.toString())
            }
        }
        item {
            SectionCard("Estado coletivo") {
                if (meanWellbeing != null) KeyValue("Bem-estar médio", meanWellbeing.f(3))
                KeyValue("Humor médio", d.mind.meanMood.f(3))
                KeyValue("Crenças em quarentena", d.mind.quarantinedBeliefs.toString())
                KeyValue("Crenças não verificadas", d.mind.unverifiedBeliefs.toString())
                KeyValue("Decisões neurais aplicadas", d.mind.neuralDecisionsApplied.toString())
            }
        }
        item {
            SectionCard("Evolução cognitiva") {
                TrendChart(
                    values = history.map { it.acceptorMismatch },
                    color = Lumon.Teal,
                    emptyMessage = "A série cognitiva será preenchida pelo motor ao fim dos dias simulados."
                )
                if (history.isNotEmpty()) {
                    KeyValue("Erro Anokhin", history.last().acceptorMismatch.f(4))
                    KeyValue("Brier", history.last().brier.f(4))
                    KeyValue("Promoções", history.last().promotions.toString())
                    KeyValue("Ciclos LoRA aceitos", history.last().loraAccepted.toString())
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun TrendChart(
    values: List<Double>,
    color: Color,
    emptyMessage: String
) {
    val valid = values.filter { it.isFinite() }
    if (valid.isEmpty()) {
        Text(emptyMessage, style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
        return
    }
    val min = valid.minOrNull() ?: 0.0
    val max = valid.maxOrNull() ?: min
    val span = (max - min).takeIf { it > 1e-9 } ?: 1.0
    Canvas(Modifier.fillMaxWidth().height(112.dp).testTag("data_trend_chart")) {
        val left = 2.dp.toPx()
        val right = size.width - 2.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - 8.dp.toPx()
        for (i in 0..3) {
            val y = top + (bottom - top) * i / 3f
            drawLine(Lumon.Outline.copy(alpha = 0.72f), Offset(left, y), Offset(right, y), 1.dp.toPx())
        }
        val path = Path()
        valid.forEachIndexed { index, value ->
            val x = if (valid.size == 1) (left + right) / 2f
                else left + (right - left) * index / (valid.size - 1).toFloat()
            val normalized = ((value - min) / span).toFloat().coerceIn(0f, 1f)
            val y = bottom - normalized * (bottom - top)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.5.dp.toPx()))
        val last = valid.last()
        val lastX = if (valid.size == 1) (left + right) / 2f else right
        val lastY = bottom - (((last - min) / span).toFloat().coerceIn(0f, 1f)) * (bottom - top)
        drawCircle(color.copy(alpha = 0.18f), radius = 7.dp.toPx(), center = Offset(lastX, lastY))
        drawCircle(color, radius = 3.dp.toPx(), center = Offset(lastX, lastY))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("mín. ${min.f(2)}", style = MonoStyle, color = Lumon.CreamDim)
        Text("atual ${valid.last().f(2)}", style = MonoStyle, color = color)
        Text("máx. ${max.f(2)}", style = MonoStyle, color = Lumon.CreamDim)
    }
}
