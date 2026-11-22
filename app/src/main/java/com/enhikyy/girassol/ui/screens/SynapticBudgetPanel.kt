package com.enhikyy.girassol.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enhikyy.girassol.nativebridge.SynapticBudgetNative
import com.enhikyy.girassol.synaptic.SpectrumRegime
import com.enhikyy.girassol.synaptic.SynapticBudgetEngine
import com.enhikyy.girassol.ui.components.KeyValue
import com.enhikyy.girassol.ui.components.SectionCard
import com.enhikyy.girassol.ui.components.f
import com.enhikyy.girassol.ui.theme.Lumon
import com.enhikyy.girassol.ui.theme.MonoStyle
import kotlinx.coroutines.launch

/**
 * v4.1 — Orçamento Sináptico (P5/P6): subespaço protegido do LoRA on-device, cota cumulativa de
 * esquecimento e regime espectral. Alimentado por um StateFlow imutável com coleta ciente do ciclo
 * de vida; nenhum estado mutável compartilhado com a thread do motor.
 */
@Composable
fun SynapticBudgetPanel() {
    val t by SynapticBudgetEngine.telemetry.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    SectionCard("Orçamento Sináptico", Modifier.testTag("synaptic_budget")) {
        if (!t.ready) {
            Text(t.message.ifEmpty { "Inicializando o núcleo C++…" }, style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
            return@SectionCard
        }
        KeyValue("Núcleo", "${t.abi} · D=${t.dim} (escolhido pela RAM)")
        KeyValue("Subespaço protegido", "posto ${t.protectedRank}/${t.dim} · ${t.tasks} tarefas · livre ${(t.freeFraction * 100).f(1)}%")
        KeyValue("Cota cumulativa (P5)", "ε atual ${t.epsilon.f(4)} · limite F ≤ ${t.forgettingBound.f(4)} (${t.epsilonHistory.size} ε registrados)")
        val regime = when (t.regime) {
            SpectrumRegime.EXPONENCIAL -> "exponencial · τ=${t.tau.f(2)}"
            SpectrumRegime.POTENCIA -> "lei de potência · α=${t.alpha.f(2)}"
            SpectrumRegime.DESCONHECIDO -> "indefinido (espectro curto)"
        }
        KeyValue("Espectro de A", "$regime · R² exp ${t.r2Exp.f(3)} / pot ${t.r2Pow.f(3)}")
        KeyValue("LoRA on-device", "${t.loraProjectedBlocks} blocos projetados · ${t.stagedRows} ativações pendentes · ponte ${if (t.bridgeActive) "ativa" else "inativa"}")
        KeyValue("LoRA do mundo", "${t.worldObservations} amostras observadas")
        KeyValue("Fidelidade de treino", t.trainingFidelity)
        KeyValue("Persistência", if (t.serializedBytes > 0) "GRSB ${t.serializedBytes / 1024} KB · FNV ${t.checksumHex} · ${if (t.persisted) "SQLite WAL" else "pendente"}" else if (t.restoredFromDisk) "restaurado do SQLite WAL" else "sem gravação ainda")
        if (t.epsilonHistory.size >= 2) EpsilonSparkline(t.epsilonHistory)
        if (t.selfTest.isNotEmpty()) Text(t.selfTest, style = MonoStyle,
            color = if (t.selfTest.contains(SynapticBudgetNative.CANONICAL_T13_HASH)) Lumon.Ok else Lumon.Danger,
            modifier = Modifier.testTag("synaptic_selftest"))
        OutlinedButton(enabled = !running, modifier = Modifier.testTag("synaptic_run_tests"), onClick = {
            running = true
            scope.launch {
                runCatching { SynapticBudgetEngine.runSelfTests() }
                running = false
            }
        }) { Text(if (running) "Executando T1–T18…" else "Autoteste T1–T18") }
        Text(t.message, style = MaterialTheme.typography.bodySmall, color = Lumon.Teal)
    }
}

@Composable
private fun EpsilonSparkline(values: List<Double>) {
    val color = Lumon.Sunflower
    Canvas(Modifier.fillMaxWidth().height(36.dp).testTag("synaptic_eps_chart")) {
        val maxV = values.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
        val stepX = size.width / (values.size - 1).coerceAtLeast(1)
        for (i in 1 until values.size) {
            val y0 = size.height * (1f - (values[i - 1] / maxV).toFloat())
            val y1 = size.height * (1f - (values[i] / maxV).toFloat())
            drawLine(color, Offset(stepX * (i - 1), y0), Offset(stepX * i, y1), strokeWidth = 2f)
        }
    }
}
