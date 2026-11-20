package com.enhikyy.girassol.core.rsi

import com.enhikyy.girassol.core.util.fmt
import java.security.MessageDigest

@kotlinx.serialization.Serializable
data class CognitivePolicyGenome(
    val version: Int,
    val epistemicExplorationWeight: Double = 0.65,
    val cognitiveGateTieThreshold: Double = 0.05,
    val cognitiveGateMinUrgency: Double = 0.60,
    val intentionSwitchHysteresis: Double = 0.12,
    val memRlLearningRate: Double = 0.20,
    val actRDecayExponent: Double = 0.50,
    val thermalThrottleSensitivity: Double = 1.00,
    val activeVpoRules: List<String> = emptyList()
) {
    fun clamped(): CognitivePolicyGenome = copy(
        epistemicExplorationWeight = epistemicExplorationWeight.coerceIn(0.10, 1.50),
        cognitiveGateTieThreshold = cognitiveGateTieThreshold.coerceIn(0.01, 0.20),
        cognitiveGateMinUrgency = cognitiveGateMinUrgency.coerceIn(0.40, 0.90),
        intentionSwitchHysteresis = intentionSwitchHysteresis.coerceIn(0.02, 0.35),
        memRlLearningRate = memRlLearningRate.coerceIn(0.05, 0.50),
        actRDecayExponent = actRDecayExponent.coerceIn(0.20, 0.85),
        thermalThrottleSensitivity = thermalThrottleSensitivity.coerceIn(0.50, 3.00),
        activeVpoRules = activeVpoRules.take(8)
    )

    fun sha256Signature(): String {
        val payload = listOf(
            version,
            "%.6f".fmt(epistemicExplorationWeight),
            "%.6f".fmt(cognitiveGateTieThreshold),
            "%.6f".fmt(cognitiveGateMinUrgency),
            "%.6f".fmt(intentionSwitchHysteresis),
            "%.6f".fmt(memRlLearningRate),
            "%.6f".fmt(actRDecayExponent),
            "%.6f".fmt(thermalThrottleSensitivity),
            activeVpoRules.joinToString("|")
        ).joinToString(":")
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".fmt(it) }
    }
}

@kotlinx.serialization.Serializable
data class RsiMutationProposal(
    val proposalId: String,
    val proposerAgentId: String,
    val tick: Long,
    val rationale: String,
    val deltaEpistemicWeight: Double = 0.0,
    val deltaGateTieThreshold: Double = 0.0,
    val deltaSwitchHysteresis: Double = 0.0,
    val deltaMemRlAlpha: Double = 0.0,
    val deltaThermalSensitivity: Double = 0.0,
    val newVpoRule: String? = null,
    val declaredConfidence: Double = 0.5
) {
    fun applyTo(base: CognitivePolicyGenome): CognitivePolicyGenome {
        val updatedRules = if (!newVpoRule.isNullOrBlank()) {
            (base.activeVpoRules + newVpoRule.trim().take(140)).distinct().takeLast(8)
        } else {
            base.activeVpoRules
        }
        return base.copy(
            version = base.version + 1,
            epistemicExplorationWeight = base.epistemicExplorationWeight + deltaEpistemicWeight,
            cognitiveGateTieThreshold = base.cognitiveGateTieThreshold + deltaGateTieThreshold,
            intentionSwitchHysteresis = base.intentionSwitchHysteresis + deltaSwitchHysteresis,
            memRlLearningRate = base.memRlLearningRate + deltaMemRlAlpha,
            thermalThrottleSensitivity = base.thermalThrottleSensitivity + deltaThermalSensitivity,
            activeVpoRules = updatedRules
        ).clamped()
    }
}

@kotlinx.serialization.Serializable
data class ShadowReplayMetrics(
    val meanFreeEnergy: Double,
    val survivalEfficiency: Double,
    val brierScore: Double,
    val behavioralDiversityScore: Double,
    val llmCallsPer100Ticks: Double,
    val invariantsViolated: Int
)

@kotlinx.serialization.Serializable
data class RsiPromotionDecision(
    val promoted: Boolean,
    val reason: String,
    val baselineHash: String,
    val candidateHash: String,
    val netFitnessGain: Double
)

class BoundedRsiPromotionGate(
    private val minNetFitnessGain: Double = 0.025,
    private val maxBrierDegradation: Double = 0.010,
    private val minDiversityFloor: Double = 0.35
) {
    fun computeBrierScore(forecastOutcomePairs: List<Pair<Double, Boolean>>): Double {
        if (forecastOutcomePairs.isEmpty()) return 0.0
        var sumSq = 0.0
        for ((prob, outcome) in forecastOutcomePairs) {
            val p = prob.coerceIn(0.0, 1.0)
            val o = if (outcome) 1.0 else 0.0
            val diff = p - o
            sumSq += diff * diff
        }
        return sumSq / forecastOutcomePairs.size.toDouble()
    }

    fun evaluatePromotion(
        baseline: CognitivePolicyGenome,
        candidate: CognitivePolicyGenome,
        baseMetrics: ShadowReplayMetrics,
        candMetrics: ShadowReplayMetrics
    ): RsiPromotionDecision {
        val baseHash = baseline.sha256Signature()
        val candHash = candidate.sha256Signature()

        if (candMetrics.invariantsViolated > 0) {
            return RsiPromotionDecision(
                promoted = false,
                reason = "REJEITADO: Violação de ${candMetrics.invariantsViolated} invariante(s) física(s)/econômica(s).",
                baselineHash = baseHash,
                candidateHash = candHash,
                netFitnessGain = -1.0
            )
        }
        if (candMetrics.behavioralDiversityScore < minDiversityFloor) {
            return RsiPromotionDecision(
                promoted = false,
                reason = "REJEITADO: Risco de colapso semântico (diversidade=${"%.3f".fmt(candMetrics.behavioralDiversityScore)} < $minDiversityFloor).",
                baselineHash = baseHash,
                candidateHash = candHash,
                netFitnessGain = 0.0
            )
        }
        if (candMetrics.brierScore > baseMetrics.brierScore + maxBrierDegradation) {
            return RsiPromotionDecision(
                promoted = false,
                reason = "REJEITADO: Degradação de calibração Brier (${"%.4f".fmt(baseMetrics.brierScore)} -> ${"%.4f".fmt(candMetrics.brierScore)}).",
                baselineHash = baseHash,
                candidateHash = candHash,
                netFitnessGain = 0.0
            )
        }

        val baseScore = baseMetrics.survivalEfficiency - 0.35 * baseMetrics.meanFreeEnergy - 0.004 * baseMetrics.llmCallsPer100Ticks
        val candScore = candMetrics.survivalEfficiency - 0.35 * candMetrics.meanFreeEnergy - 0.004 * candMetrics.llmCallsPer100Ticks
        val gain = candScore - baseScore

        return if (gain >= minNetFitnessGain) {
            RsiPromotionDecision(
                promoted = true,
                reason = "PROMOVIDO: Ganho líquido (+${"%.4f".fmt(gain)}) comprovado em Shadow-Replay com 0 violações.",
                baselineHash = baseHash,
                candidateHash = candHash,
                netFitnessGain = gain
            )
        } else {
            RsiPromotionDecision(
                promoted = false,
                reason = "REJEITADO: Ganho líquido insuficiente (${"%.4f".fmt(gain)} < $minNetFitnessGain).",
                baselineHash = baseHash,
                candidateHash = candHash,
                netFitnessGain = gain
            )
        }
    }

    companion object {
        const val RSI_MUTATION_PROPOSAL_GBNF = """
root ::= "{" ws
  "\"rationale\":" ws string "," ws
  "\"delta_epistemic_weight\":" ws signed-float "," ws
  "\"delta_gate_tie_threshold\":" ws signed-float "," ws
  "\"delta_switch_hysteresis\":" ws signed-float "," ws
  "\"delta_memrl_alpha\":" ws signed-float "," ws
  "\"delta_thermal_sensitivity\":" ws signed-float "," ws
  "\"new_vpo_rule\":" ws (string | "null") "," ws
  "\"confidence\":" ws unit-float
ws "}"

signed-float ::= "-"? "0." [0-9] [0-9]? [0-9]?
unit-float ::= "0." [0-9] [0-9]? | "1.0"
string ::= "\"" ([^"\\\x7F\x00-\x1F] | "\\" ["\\/bfnrt])* "\""
ws ::= [ \t\n]*
"""
    }
}