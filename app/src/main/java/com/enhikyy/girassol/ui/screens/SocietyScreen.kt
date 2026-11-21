package com.enhikyy.girassol.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enhikyy.girassol.core.social.DeonticOperator
import com.enhikyy.girassol.sim.SimulationHub
import com.enhikyy.girassol.ui.components.EmptyState
import com.enhikyy.girassol.ui.components.KeyValue
import com.enhikyy.girassol.ui.components.LabeledBar
import com.enhikyy.girassol.ui.components.MetricRow
import com.enhikyy.girassol.ui.components.SectionCard
import com.enhikyy.girassol.ui.components.f
import com.enhikyy.girassol.ui.theme.Lumon
import com.enhikyy.girassol.ui.theme.MonoStyle

/** Cliodinâmica, opinião, ADICO, comuns e livro-razão como seções simples (aba Evolução). */
@Composable
fun SocietySections(d: com.enhikyy.girassol.core.world.DashboardSnapshot) {
    val s = d.society
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        run {
            val c = s.cliodynamics
            SectionCard("Cliodinâmica (Turchin)") {
                if (c == null) Text("Calculada ao fim do primeiro dia do mundo.", color = Lumon.CreamDim)
                else {
                    MetricRow("PSI" to c.politicalStressIndicator.f(3), "Gini" to c.giniIndex.f(3), "População" to c.populationCount.toString())
                    LabeledBar("Potencial de mobilização (MMP)", c.massMobilizationPotential.coerceIn(0.0, 1.0))
                    LabeledBar("Superprodução de elites (EMP)", c.eliteOverproductionIndex.coerceIn(0.0, 1.0), Lumon.Sunflower)
                    LabeledBar("Saúde fiscal do Estado (SFD)", c.stateFiscalHealth.coerceIn(0.0, 1.0), Lumon.Ok)
                    KeyValue("Capacidade de suporte", c.carryingCapacity.f(1))
                }
            }
        }
        run {
            SectionCard("Opinião pública (Friedkin–Johnsen)") {
                KeyValue("Opinião média sobre os comuns", s.meanOpinionCommons.f(3))
                KeyValue("Dispersão (polarização)", s.opinionSpread.f(3))
                KeyValue("Laços sociais ativos", s.totalTies.toString())
            }
        }
        run {
            SectionCard("Instituições ADICO") {
                s.institutions.forEach { r ->
                    val op = when (r.deontic) {
                        DeonticOperator.OBRIGADO -> "DEVE"
                        DeonticOperator.PERMITIDO -> "PODE (cota ${r.maxQuotaPerWindow})"
                        DeonticOperator.PROIBIDO -> "NÃO PODE"
                    }
                    val who = if (r.attributes.isEmpty()) "Todos" else r.attributes.sorted().joinToString("/")
                    Text("$who $op ${r.aimAction}${if (r.conditions.isNotBlank()) " se ${r.conditions}" else ""}, senão −${r.orElseSanctionUnits} crédito(s)",
                        style = MaterialTheme.typography.bodyMedium, color = Lumon.Cream)
                    if (r.description.isNotBlank()) Text(r.description, style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
                }
            }
        }
        run {
            SectionCard("Comuns (Ostrom)") { s.commons.forEach { KeyValue(it.label, it.value.f(2)) } }
        }
        run {
            SectionCard("Livro-razão de partidas dobradas · ${s.totalLedgerEntries} lançamentos") {
                s.recentLedger.forEach { e ->
                    Text("t${e.tick} ${e.fromAccount} → ${e.toAccount}  ${e.quantity.f(2)} ${e.resourceType}", style = MonoStyle, color = Lumon.Cream)
                }
            }
        }
    }
}
