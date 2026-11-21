package com.enhikyy.girassol.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.enhikyy.girassol.llm.LlmStatus
import com.enhikyy.girassol.llm.LocalLlmManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val llm by LocalLlmManager.stats.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importMsg by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                importMsg = withContext(Dispatchers.IO) { runCatching { LocalLlmManager.importModel(context, uri) }.getOrElse { "Falha: ${it.message}" } }
                importing = false
            }
        }
    }
    val d = dash ?: return EmptyState("Carregando mente coletiva…")
    val m = d.mind
    val mt = m.metrics
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SectionCard("Motor cognitivo híbrido · llama.cpp + ${llm.grammarBackend.ifEmpty { "GBNF" }}") {
                val stColor = when (llm.status) { LlmStatus.READY, LlmStatus.BUSY -> Lumon.Teal; LlmStatus.ERROR -> Lumon.Danger; else -> Lumon.Sunflower }
                KeyValue("Estado", llm.status.label, stColor)
                KeyValue("Modelo", llm.modelName.ifEmpty { "nenhum (modo econômico: SymbolicLookahead)" })
                if (llm.description.isNotEmpty()) Text(llm.description, style = MonoStyle, color = Lumon.CreamDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                MetricRow("tok/s" to llm.tokensPerSecond.f(1), "Latência" to "${llm.lastLatencyMs.f(0)} ms", "RAM nativa" to "${llm.nativeHeapMb.f(0)} MB")
                KeyValue("Orçamento de RAM do modelo", "${llm.budgetMb} MB (mmap, n_ctx 1024)")
                KeyValue("Decisões aceitas / rejeitadas", "${llm.decisions} / ${llm.rejected}")
                KeyValue("Aplicadas no mundo · falas", "${m.neuralDecisionsApplied} · ${m.speechesEmitted}")
                if (llm.lastOutput.isNotEmpty()) Text(llm.lastOutput, style = MonoStyle, color = Lumon.Cream, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (llm.lastError.isNotEmpty()) Text(llm.lastError, style = MaterialTheme.typography.bodySmall, color = Lumon.Danger)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = LocalLlmManager.engineCompiled && !importing,
                        onClick = { picker.launch(arrayOf("application/octet-stream", "*/*")) },
                        modifier = Modifier.weight(1f).testTag("mind_import_gguf"),
                        colors = ButtonDefaults.buttonColors(containerColor = Lumon.Sunflower, contentColor = Lumon.Background)
                    ) { Text(if (importing) "Copiando…" else "Importar .gguf") }
                    OutlinedButton(enabled = llm.modelName.isNotEmpty(), onClick = { LocalLlmManager.removeModel(context) }, modifier = Modifier.weight(1f)) { Text("Remover") }
                }
                if (importMsg.isNotEmpty()) Text(importMsg, style = MaterialTheme.typography.bodySmall, color = Lumon.Teal)
                Text("Saída restrita por gramática (llguidance/Lark quando compilado, senão GBNF estrita) a JSON {acao, pensamento, fala}; decisões entram no log de eventos e são aplicadas no estágio 1 do tick seguinte. Sem modelo, sob calor ou pressão de memória, o deliberador simbólico assume.",
                    style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
            }
        }
        item { LlmV35Panel() }
        item { KnowledgeLibraryPanel(d) }
        item { FunctionalSystemsPanel(d) }
        item {
            val v = m.v34
            SectionCard("Memória episódica em 2 estágios", Modifier.testTag("v34_memory")) {
                MetricRow("Consultas" to v.retrievalQueries.toString(), "Estágio 1" to v.meanStage1.f(1), "Corpus" to v.meanCorpus.f(1))
                KeyValue("Embeddings bit[256] (32 B cada)", "${v.binaryIndexBytes / 1024} KB · Hamming XOR+popcount")
                KeyValue("Estágio 2", "RRF (cosseno + BM25) × Hawkes λ(t)")
                KeyValue("Replay SWR no sono (atualizações Q)", v.swrReplayUpdates.toString())
                var query by remember { mutableStateOf("") }
                var hits by remember { mutableStateOf<List<com.enhikyy.girassol.data.FtsHit>>(emptyList()) }
                var module by remember { mutableStateOf("") }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("fts_query"), singleLine = true,
                    label = { Text("Buscar nas memórias (SQLite FTS)") })
                OutlinedButton(enabled = query.isNotBlank(), modifier = Modifier.testTag("fts_search"), onClick = {
                    scope.launch {
                        val (h, mod) = withContext(Dispatchers.IO) { SimulationHub.searchMemories(query) to SimulationHub.ftsModule() }
                        hits = h; module = mod
                    }
                }) { Text("Buscar") }
                if (module.isNotEmpty()) Text("${hits.size} resultados · ${module.uppercase()}${if (module == "fts5") " bm25()" else " + BM25 Kotlin"}",
                    style = MonoStyle, color = Lumon.Teal, modifier = Modifier.testTag("fts_result"))
                hits.take(6).forEach { h ->
                    Text("${h.owner} · ${h.layer} · t${h.tick} · ${h.score.f(2)}", style = MonoStyle, color = Lumon.CreamDim)
                    Text(h.content, style = MaterialTheme.typography.bodySmall, color = Lumon.Cream, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item {
            val v = m.v34
            SectionCard("Corpo, movimento e genética", Modifier.testTag("v34_body")) {
                MetricRow("Cortisol" to v.meanCortisol.f(2), "Ocitocina" to v.meanOxytocin.f(2), "Dopamina" to v.meanDopamine.f(2))
                KeyValue("Locomoção CfC (48 neurônios)", "× ${v.meanLocomotion.f(2)}")
                KeyValue("ORCA: passos ajustados", v.orcaAdjustedSteps.toString())
                KeyValue("Colisões resolvidas (círculo × AABB)", v.aabbCorrections.toString())
                KeyValue("TIES-Merging de LoRA no nascimento", v.tiesMerges.toString())
                KeyValue("Superações (≥ +15% sobre os pais)", v.superacoes.toString())
            }
        }
        item {
            SectionCard("LoRA on-device (ciclos no sono)") {
                MetricRow("Aceitos" to m.loraAcceptedCycles.toString(), "Versão" to m.loraVersion.toString(), "‖ΔW‖" to m.loraDeltaNorm.toDouble().f(3))
                KeyValue("Amostras de memória pendentes", m.loraPendingSamples.toString())
                if (m.loraLedger.isEmpty()) Text("O primeiro ciclo ocorre no sono noturno após memórias consolidadas suficientes.", color = Lumon.CreamDim)
                m.loraLedger.take(8).forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("t${r.tick} · ${r.samples} amostras · ${r.backend}", style = MonoStyle, color = Lumon.Cream, modifier = Modifier.weight(1f))
                        Pill(if (r.accepted) "aplicado" else "rejeitado", if (r.accepted) Lumon.Ok else Lumon.CreamDim)
                    }
                    Text("MSE ${r.mseBefore.toDouble().f(4)}→${r.mseAfter.toDouble().f(4)} · sandbox ${r.baselineScore.f(3)}→${r.candidateScore.f(3)}", style = MonoStyle, color = Lumon.CreamDim)
                }
            }
        }
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
