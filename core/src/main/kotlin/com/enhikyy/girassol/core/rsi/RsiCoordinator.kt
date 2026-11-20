package com.enhikyy.girassol.core.rsi

import com.enhikyy.girassol.core.cognition.Deliberator
import com.enhikyy.girassol.core.cognition.SymbolicLookaheadDeliberator
import com.enhikyy.girassol.core.util.StreamSeeds
import com.enhikyy.girassol.core.util.fmt
import com.enhikyy.girassol.core.world.RsiLedgerRecord
import com.enhikyy.girassol.core.world.WorldCore
import com.enhikyy.girassol.core.world.WorldSnapshotCodec
import com.enhikyy.girassol.core.world.WorldState

/**
 * RSI delimitado: um cidadão REFLECTOR propõe uma mutação tipada do
 * [CognitivePolicyGenome]; o candidato e a linha de base são executados em
 * Shadow-Replay a partir do MESMO snapshot e só são promovidos se passarem pelos
 * gates de invariantes, diversidade semântica (H_sem) e calibração (Brier).
 */
class RsiCoordinator(
    private val replayTicks: Int = 240,
    private val gate: BoundedRsiPromotionGate = BoundedRsiPromotionGate(),
    private val deliberatorFactory: () -> Deliberator = { SymbolicLookaheadDeliberator() }
) {
    fun isDue(state: WorldState): Boolean = state.tick > 0 && state.tick % WorldCore.RSI_PERIOD_TICKS == 0L

    fun propose(state: WorldState): RsiMutationProposal {
        val proposer = state.aliveCitizens().maxWithOrNull(
            compareBy<com.enhikyy.girassol.core.world.Citizen> { it.skills["pesquisa"]?.masteryProb ?: 0.0 }.thenBy { it.id }
        )
        val rng = StreamSeeds.rng(state.seed, proposer?.id ?: "WORLD", state.tick, "rsi-proposta")
        val m = state.metrics
        fun jitter(scale: Double) = (rng.nextDouble() - 0.5) * 2.0 * scale
        val rationale = StringBuilder()
        var dEpi = jitter(0.05)
        var dTie = jitter(0.01)
        var dHyst = jitter(0.02)
        var dAlpha = jitter(0.03)
        var dThermal = 0.0
        if (m.semanticEntropy < 0.45) { dEpi += 0.08; rationale.append("H_sem baixa (${"%.2f".fmt(m.semanticEntropy)}): ampliar exploração. ") }
        if (m.deliberationsPer100Ticks > 30) { dTie -= 0.01; rationale.append("deliberação excessiva: estreitar gate. ") }
        if (m.brierScore > 0.22) { dAlpha += 0.03; rationale.append("Brier alto: acelerar MemRL. ") }
        if (state.telemetry.thermalTier >= 2) { dThermal += 0.2; rationale.append("aquecimento: elevar custo neural. ") }
        if (rationale.isEmpty()) rationale.append("perturbação exploratória local do genoma cognitivo.")
        return RsiMutationProposal(
            proposalId = "rsi-${state.tick}", proposerAgentId = proposer?.id ?: "WORLD", tick = state.tick,
            rationale = rationale.toString().trim(),
            deltaEpistemicWeight = dEpi, deltaGateTieThreshold = dTie, deltaSwitchHysteresis = dHyst,
            deltaMemRlAlpha = dAlpha, deltaThermalSensitivity = dThermal,
            newVpoRule = if (m.semanticEntropy < 0.45) "verificar antes de agir sobre boatos" else null,
            declaredConfidence = 0.5 + rng.nextDouble() * 0.3
        )
    }

    fun shadowReplay(snapshot: WorldState, genome: CognitivePolicyGenome): ShadowReplayMetrics {
        val copy = WorldSnapshotCodec.deepCopy(snapshot)
        copy.policy = genome
        copy.forecastWindow.clear(); copy.outcomeWindow.clear()
        val core = WorldCore(copy, deliberatorFactory(), shadow = true)
        var fe = 0.0; var surv = 0.0; var div = 0.0; var delib = 0.0; var violations = 0
        for (i in 0 until replayTicks) {
            val r = core.step(copy.telemetry)
            violations += r.invariantViolations.size
            fe += copy.metrics.meanFreeEnergy
            surv += copy.metrics.survivalEfficiency
            div += copy.metrics.semanticEntropy
            delib += copy.metrics.deliberationsPer100Ticks
        }
        val n = replayTicks.toDouble()
        val brier = if (copy.forecastWindow.isEmpty()) 0.25 else copy.metrics.brierScore
        return ShadowReplayMetrics(fe / n, surv / n, brier, div / n, delib / n, violations)
    }

    /** Executa um ciclo completo e, se promovido, aplica o genoma ao mundo vivo. */
    fun runCycle(core: WorldCore): RsiLedgerRecord {
        val state = core.state
        val proposal = propose(state)
        val baseline = state.policy
        val candidate = proposal.applyTo(baseline)
        val snapshot = WorldSnapshotCodec.deepCopy(state)
        val base = shadowReplay(snapshot, baseline)
        val cand = shadowReplay(snapshot, candidate)
        val decision = gate.evaluatePromotion(baseline, candidate, base, cand)
        val record = RsiLedgerRecord(state.tick, proposal.proposerAgentId, proposal.rationale, decision,
            base.brierScore, cand.brierScore, base.behavioralDiversityScore, cand.behavioralDiversityScore)
        state.rsiLedger += record
        if (state.rsiLedger.size > 200) state.rsiLedger.removeAt(0)
        if (decision.promoted) state.policy = candidate
        core.emit("RSI", proposal.proposerAgentId,
            "promovido=${decision.promoted};ganho=${"%.4f".fmt(decision.netFitnessGain)};base=${decision.baselineHash.take(12)};cand=${decision.candidateHash.take(12)}")
        return record
    }
}
