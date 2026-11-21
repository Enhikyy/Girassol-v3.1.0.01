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
    /** CMA-ES: candidatos por geração e ticks de Shadow-Replay para ranqueá-los. */
    private val cmaLambda: Int = 4,
    private val cmaReplayTicks: Int = 60,
    private val gate: BoundedRsiPromotionGate = BoundedRsiPromotionGate(),
    private val deliberatorFactory: () -> Deliberator = { SymbolicLookaheadDeliberator() },
    private val portsFactory: () -> com.enhikyy.girassol.core.ports.CorePorts = { com.enhikyy.girassol.core.ports.CorePorts() }
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

    fun shadowReplay(snapshot: WorldState, genome: CognitivePolicyGenome, ticks: Int = replayTicks): ShadowReplayMetrics {
        val copy = WorldSnapshotCodec.deepCopy(snapshot)
        copy.policy = genome
        copy.forecastWindow.clear(); copy.outcomeWindow.clear()
        val core = WorldCore(copy, portsFactory().forShadow().copy(deliberator = deliberatorFactory()), shadow = true)
        var fe = 0.0; var surv = 0.0; var div = 0.0; var delib = 0.0; var violations = 0
        for (i in 0 until ticks) {
            val r = core.step(copy.telemetry)
            violations += r.invariantViolations.size
            fe += copy.metrics.meanFreeEnergy
            surv += copy.metrics.survivalEfficiency
            div += copy.metrics.semanticEntropy
            delib += copy.metrics.deliberationsPer100Ticks
        }
        val n = ticks.toDouble()
        val brier = if (copy.forecastWindow.isEmpty()) 0.25 else copy.metrics.brierScore
        return ShadowReplayMetrics(fe / n, surv / n, brier, div / n, delib / n, violations)
    }

    /** Executa um ciclo completo e, se promovido, aplica o genoma ao mundo vivo. */
    fun runCycle(core: WorldCore): RsiLedgerRecord {
        val state = core.state
        val proposal = propose(state)
        val baseline = state.policy
        val snapshot = WorldSnapshotCodec.deepCopy(state)
        // CMA-ES: uma geração por ciclo; os candidatos são ranqueados em Shadow-Replay curto e o
        // melhor (fundido com a proposta tipada do REFLECTOR) disputa o BoundedRsiPromotionGate.
        val cmaBest = cmaGeneration(state, snapshot, baseline)
        val proposed = proposal.applyTo(baseline)
        val candidate = cmaBest?.copy(activeVpoRules = proposed.activeVpoRules) ?: proposed
        val base = shadowReplay(snapshot, baseline)
        val cand = shadowReplay(snapshot, candidate)
        val decision = gate.evaluatePromotion(baseline, candidate, base, cand)
        val cmaNote = state.cmaEs?.let { " CMA-ES g=${it.generation} σ=${"%.4f".fmt(it.sigma)}." } ?: ""
        val record = RsiLedgerRecord(state.tick, proposal.proposerAgentId, proposal.rationale + cmaNote, decision,
            base.brierScore, cand.brierScore, base.behavioralDiversityScore, cand.behavioralDiversityScore)
        state.rsiLedger += record
        if (state.rsiLedger.size > 200) state.rsiLedger.removeAt(0)
        if (decision.promoted) state.policy = candidate
        state.cmaEs?.let { it.lastPromoted = decision.promoted }
        core.emit("RSI", proposal.proposerAgentId,
            "promovido=${decision.promoted};ganho=${"%.4f".fmt(decision.netFitnessGain)};base=${decision.baselineHash.take(12)};cand=${decision.candidateHash.take(12)}")
        return record
    }

    /** Custo minimizado pelo CMA-ES: −(mesmo escore do gate) + penalidades de invariantes e diversidade. */
    fun cmaCost(m: ShadowReplayMetrics): Double {
        val score = m.survivalEfficiency - 0.35 * m.meanFreeEnergy - 0.004 * m.llmCallsPer100Ticks
        val diversityPenalty = if (m.behavioralDiversityScore < 0.35) (0.35 - m.behavioralDiversityScore) * 4.0 else 0.0
        return -score + m.invariantsViolated.toDouble() + diversityPenalty + 2.0 * (m.brierScore - 0.25).coerceAtLeast(0.0)
    }

    /** Uma geração ask/tell do CMA-ES persistido no WorldState; retorna o melhor genoma da geração. */
    fun cmaGeneration(state: WorldState, snapshot: WorldState, baseline: CognitivePolicyGenome): CognitivePolicyGenome? {
        if (cmaLambda < 2) return null
        val es = state.cmaEs?.takeIf { it.n == PolicySearchSpace.DIM && it.lambda == cmaLambda }
            ?: CmaEsState.initial(PolicySearchSpace.encode(baseline), 0.12, cmaLambda)
        // se a última geração não foi promovida, recentra a média na política vigente (âncora segura)
        if (!es.lastPromoted && es.generation > 0) {
            val anchor = PolicySearchSpace.encode(baseline)
            for (i in anchor.indices) es.mean[i] = 0.5 * es.mean[i] + 0.5 * anchor[i]
        }
        val rng = StreamSeeds.rng(state.seed, "WORLD", state.tick, "cma-es")
        val xs = CmaEs.ask(es, rng)
        val genomes = xs.map { PolicySearchSpace.decode(it, baseline) }
        val costs = DoubleArray(xs.size) { cmaCost(shadowReplay(snapshot, genomes[it], cmaReplayTicks)) }
        val bestIdx = costs.indices.minWith(compareBy<Int> { costs[it] }.thenBy { it })
        CmaEs.tell(es, costs)
        state.cmaEs = es
        return genomes[bestIdx]
    }
}
