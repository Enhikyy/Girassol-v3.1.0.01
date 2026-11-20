package com.enhikyy.girassol.core.cognition

import kotlin.math.exp
import kotlin.math.ln

data class PerceptualSignal(
    val sourceModule: String,
    val summary: String,
    val entityId: String?,
    val salience: Double
)

data class CoreSelfAnchor(
    val agentId: String,
    val invariantName: String,
    val birthTick: Long,
    val coreValues: List<String>
) {
    fun renderIdentityHeader(): String =
        "P_SELF[id=$agentId, nome=$invariantName, nascimento=$birthTick, valores=${coreValues.joinToString(",")}]"
}

class GlobalWorkspaceStage(private val capacity: Int = 3) {
    fun broadcastWinningSignals(signals: List<PerceptualSignal>): List<PerceptualSignal> =
        signals.filter { it.salience > 0.15 }
            .sortedByDescending { it.salience }
            .take(capacity)
}

data class PianoCognitiveBusSnapshot(
    val agentId: String,
    val tick: Long,
    val coreSelf: CoreSelfAnchor,
    val activeNeedsSummary: String,
    val perceptionSummary: String,
    val memorySummary: String,
    val socialSummary: String,
    val workspaceBroadcast: List<PerceptualSignal>,
    val currentIntentionKey: String?,
    val variationalFreeEnergy: Double,
    val needsArray: DoubleArray = DoubleArray(6) { 1.0 }
)

object AllostaticThermalCoupling {
    enum class ThermalTier(val baseMetabolicMultiplier: Double) {
        NONE_OR_LIGHT(1.0),
        MODERATE(1.85),
        SEVERE_OR_CRITICAL(4.50)
    }

    fun computeNeuralDeliberationCost(
        tier: ThermalTier,
        thermalSensitivity: Double,
        agentEnergyRatio: Double
    ): Double {
        val scarcityFactor = if (agentEnergyRatio < 0.30) 2.0 else 1.0
        return 0.12 * tier.baseMetabolicMultiplier * thermalSensitivity * scarcityFactor
    }

    fun shouldInvokeLocalLlm(
        topActionUtility: Double,
        secondActionUtility: Double,
        tieThreshold: Double,
        minUrgency: Double,
        consecutiveHtnFailures: Int,
        socialNegotiationActive: Boolean,
        tier: ThermalTier,
        tsetlinReflexConfidence: Double
    ): Boolean {
        if (tsetlinReflexConfidence >= 0.88 && consecutiveHtnFailures == 0 && !socialNegotiationActive) {
            return false
        }
        if (tier == ThermalTier.SEVERE_OR_CRITICAL && consecutiveHtnFailures < 3) {
            return false
        }
        val closeTie = (topActionUtility >= minUrgency) && ((topActionUtility - secondActionUtility) < tieThreshold)
        return closeTie || consecutiveHtnFailures >= 2 || socialNegotiationActive
    }
}

object ActiveInferencePianoOrchestrator {
    fun computeVariationalFreeEnergy(
        posterior: DoubleArray,
        prior: DoubleArray,
        observationLikelihoods: DoubleArray
    ): Double {
        var kl = 0.0
        var expectedLogLik = 0.0
        val n = minOf(posterior.size, prior.size, observationLikelihoods.size)
        for (i in 0 until n) {
            val q = posterior[i].coerceAtLeast(1e-9)
            val p = prior[i].coerceAtLeast(1e-9)
            val lik = observationLikelihoods[i].coerceAtLeast(1e-9)
            kl += q * ln(q / p)
            expectedLogLik += q * ln(lik)
        }
        return kl - expectedLogLik
    }

    fun computeExpectedFreeEnergy(
        predictedPragmaticRisk: Double,
        priorEntropy: Double,
        posteriorExpectedEntropy: Double,
        epistemicWeight: Double,
        neuralMetabolicCost: Double
    ): Double {
        val epistemicGain = (priorEntropy - posteriorExpectedEntropy).coerceAtLeast(0.0)
        return predictedPragmaticRisk - epistemicWeight * epistemicGain + neuralMetabolicCost
    }

    /** KL(Bern(q) ‖ Bern(p)) com proteção numérica. */
    fun bernoulliKl(q: Double, p: Double): Double {
        val qq = q.coerceIn(1e-6, 1 - 1e-6)
        val pp = p.coerceIn(1e-6, 1 - 1e-6)
        return qq * ln(qq / pp) + (1 - qq) * ln((1 - qq) / (1 - pp))
    }

    fun bernoulliEntropy(p: Double): Double {
        val q = p.coerceIn(1e-9, 1 - 1e-9)
        return -(q * ln(q) + (1 - q) * ln(1 - q))
    }

    /** Custo metabólico neural dependente da temperatura do SoC: sigmoide centrada em 40 °C. */
    fun neuralThermalCost(baseCost: Double, socTempC: Double): Double =
        baseCost * (1.0 + 2.0 / (1.0 + exp(-(socTempC - 40.0) / 2.5)))

    data class EfeTerms(val risk: Double, val ambiguity: Double, val epistemicGain: Double, val neuralCost: Double) {
        val total: Double get() = risk + ambiguity - epistemicGain + neuralCost
    }

    /**
     * Energia livre esperada na forma de risco + ambiguidade:
     *   G(π) = Σ_k w_k·D_KL[Q(o_k|π) ‖ P_pref(o_k)] + E_Q[H(P(o|s))] − ganho epistêmico + C_neural(T_SoC)
     * Cada necessidade projetada é tratada como resultado Bernoulli (satisfeita / não),
     * com preferência P_pref = [preferred]. A ambiguidade é a entropia esperada do resultado
     * dado o estado (incerteza sobre o alvo); o ganho epistêmico é a redução esperada de
     * entropia (o termo −E[H] da especificação, com sinal de informação).
     */
    fun computeExpectedFreeEnergyKl(
        projected: DoubleArray,
        weights: DoubleArray,
        preferred: Double,
        outcomeUncertainty: Double,
        priorEntropy: Double,
        posteriorExpectedEntropy: Double,
        epistemicWeight: Double,
        neuralBaseCost: Double,
        socTempC: Double,
        riskScale: Double = 0.35,
        ambiguityScale: Double = 0.04
    ): EfeTerms {
        var risk = 0.0
        for (k in projected.indices) risk += weights[k] * bernoulliKl(projected[k], preferred)
        val ambiguity = ambiguityScale * bernoulliEntropy(0.5 + 0.5 * (1.0 - outcomeUncertainty.coerceIn(0.0, 1.0)))
        val gain = epistemicWeight * (priorEntropy - posteriorExpectedEntropy).coerceAtLeast(0.0)
        return EfeTerms(riskScale * risk, ambiguity, gain, neuralThermalCost(neuralBaseCost, socTempC))
    }

    fun selectPolicyWithHysteresis(
        policyKeys: List<String>,
        expectedFreeEnergies: List<Double>,
        currentIntentionKey: String?,
        switchCost: Double,
        precisionGamma: Double,
        uniformSample: Double
    ): Int {
        if (policyKeys.isEmpty()) return -1
        if (policyKeys.size == 1) return 0

        val utilities = policyKeys.indices.map { i ->
            val penalty = if (currentIntentionKey != null && policyKeys[i] != currentIntentionKey) switchCost else 0.0
            -expectedFreeEnergies[i] - penalty
        }
        val maxU = utilities.max()
        val weights = utilities.map { exp((precisionGamma * (it - maxU)).coerceIn(-40.0, 0.0)) }
        val total = weights.sum()
        var acc = 0.0
        val target = uniformSample.coerceIn(0.0, 1.0) * total
        for (i in weights.indices) {
            acc += weights[i]
            if (target <= acc) return i
        }
        return weights.lastIndex
    }
}