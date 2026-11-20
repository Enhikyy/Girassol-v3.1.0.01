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