package com.enhikyy.girassol.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enhikyy.girassol.sim.SimulationHub
import com.enhikyy.girassol.ui.components.EmptyState
import com.enhikyy.girassol.ui.components.KeyValue
import com.enhikyy.girassol.ui.components.LabeledBar
import com.enhikyy.girassol.ui.components.MetricRow
import com.enhikyy.girassol.ui.components.Pill
import com.enhikyy.girassol.ui.components.SectionCard
import com.enhikyy.girassol.ui.components.f
import com.enhikyy.girassol.ui.theme.Lumon
import com.enhikyy.girassol.ui.theme.MonoStyle

@Composable
fun MindScreen() {
    val dash by SimulationHub.dashboard.collectAsStateWithLifecycle()
    val status by SimulationHub.status.collectAsStateWithLifecycle()
    val d = dash ?: return EmptyState("Carregando mente coletiva…")
    val m = d.mind
    val mt = m.metrics
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SectionCard("Métricas cognitivas") {
                MetricRow("H_sem" to mt.semanticEntropy.f(2), "Brier" to mt.brierScore.f(3), "Energia livre" to mt.meanFreeEnergy.f(2))
                MetricRow("Deliber./100t" to mt.deliberationsPer100Ticks.f(0), "Reflexos/100t" to mt.reflexBypassesPer100Ticks.f(0), "Invariantes" to mt.invariantsViolated.toString())
                LabeledBar("Diversidade semântica (piso 0,35)", mt.semanticEntropy, if (mt.semanticEntropy < 0.35) Lumon.Danger else Lumon.Teal)
                LabeledBar("Eficiência de sobrevivência", mt.survivalEfficiency, Lumon.Ok)
                LabeledBar("Cobertura MAP-Elites (${mt.mapElitesNiches} nichos)", mt.mapElitesCoverage, Lumon.Violet)
                KeyValue("Nascimentos / óbitos", "${mt.births} / ${mt.deaths}")
            }
        }
        item {
            SectionCard("Teste de estresse epistêmico (Emergence World)") {
                Text("Planta um boato num cidadão bem conectado ou contamina de verdade uma horta. A imunidade epistêmica deve colocar boatos de fontes pouco confiáveis em quarentena até a verificação no WorldCore.",
                    style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { SimulationHub.injectMisinformation() }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Lumon.Sunflower, contentColor = Lumon.Background)) { Text("Boato") }
                    OutlinedButton(onClick = { SimulationHub.injectRealContamination() }, modifier = Modifier.weight(1f)) { Text("Contaminação real") }
                }
                KeyValue("Crenças em quarentena (população)", m.quarantinedBeliefs.toString())
                KeyValue("Crenças não verificadas", m.unverifiedBeliefs.toString())
                m.stressEvents.forEach { e ->
                    Text("t${e.tick} · ${e.kind}: ${e.description}", style = MaterialTheme.typography.bodySmall, color = Lumon.Cream)
                    Text("agiu sem verificar ${e.actedBeforeVerification} · verificou antes ${e.verifiedBeforeAction} · quarentena ${e.quarantinedBy}${if (e.resolvedTick >= 0) " · encerrado t${e.resolvedTick}" else ""}",
                        style = MonoStyle, color = Lumon.CreamDim)
                }
            }
        }
        item {
            SectionCard("Genoma de política cognitiva v${m.policy.version}") {
                KeyValue("Peso epistêmico (exploração)", m.policy.epistemicExplorationWeight.f(3))
                KeyValue("Limiar de empate do gate", m.policy.cognitiveGateTieThreshold.f(3))
                KeyValue("Urgência mínima p/ deliberar", m.policy.cognitiveGateMinUrgency.f(3))
                KeyValue("Histerese de intenção", m.policy.intentionSwitchHysteresis.f(3))
                KeyValue("Taxa MemRL", m.policy.memRlLearningRate.f(3))
                KeyValue("Decaimento ACT-R", m.policy.actRDecayExponent.f(3))
                KeyValue("Sensibilidade térmica", m.policy.thermalThrottleSensitivity.f(3))
                m.policy.activeVpoRules.forEach { Text("VPO: $it", style = MonoStyle, color = Lumon.Sunflower) }
            }
        }
        item {
            SectionCard("RSI delimitado · ${status.rsiCycles} ciclos nesta sessão") {
                OutlinedButton(onClick = { SimulationHub.forceRsiCycle() }) { Text("Executar ciclo RSI agora (Shadow-Replay)") }
                if (m.rsiLedger.isEmpty()) Text("Primeiro ciclo a cada 3 dias do mundo.", color = Lumon.CreamDim)
                m.rsiLedger.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("t${r.tick} · ${r.proposerAgentId}", style = MonoStyle, color = Lumon.Cream, modifier = Modifier.weight(1f))
                        Pill(if (r.decision.promoted) "promovido" else "rejeitado", if (r.decision.promoted) Lumon.Ok else Lumon.CreamDim)
                    }
                    Text(r.rationale, style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
                    Text("ganho ${r.decision.netFitnessGain.f(4)} · Brier ${r.brierBefore.f(3)}→${r.brierAfter.f(3)} · H ${r.diversityBefore.f(2)}→${r.diversityAfter.f(2)} · ${r.decision.reason}",
                        style = MonoStyle, color = Lumon.CreamDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item {
            SectionCard("Biblioteca Voyager (${m.skillLibrary.size} habilidades)") {
                if (m.skillLibrary.isEmpty()) Text("Habilidades entram aqui quando um mestre atinge maestria verificada.", color = Lumon.CreamDim)
                m.skillLibrary.forEach { s ->
                    KeyValue("${s.skillId} · ${s.authorAgentId}", "${(s.verifiedSuccessRate * 100).f(0)}%")
                    Text(s.actionSequence.joinToString(" → "), style = MonoStyle, color = Lumon.CreamDim)
                }
            }
        }
        item {
            SectionCard("Cadeia de eventos canônicos (SHA-256)") {
                m.recentEvents.forEach { e ->
                    Text("#${e.seq} t${e.tick} ${e.type} ${e.actorId ?: ""} ${e.payload}", style = MonoStyle, color = Lumon.Cream, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("  ${e.hash.take(16)}… ← ${e.prevHash.take(8)}…", style = MonoStyle, color = Lumon.CreamDim)
                }
            }
        }
        item { Spacer(Modifier.padding(8.dp)) }
    }
}
