package com.enhikyy.girassol

import com.enhikyy.girassol.core.biology.Allele
import com.enhikyy.girassol.core.biology.DiploidGenome
import com.enhikyy.girassol.core.biology.DiploidLocus
import com.enhikyy.girassol.core.biology.SemanticCollapseGuard
import com.enhikyy.girassol.core.biology.SkillMasteryState
import com.enhikyy.girassol.core.biology.VoyagerProceduralSkill
import com.enhikyy.girassol.core.biology.VoyagerSkillLibrary
import com.enhikyy.girassol.core.cognition.ActiveInferencePianoOrchestrator
import com.enhikyy.girassol.core.cognition.AllostaticThermalCoupling
import com.enhikyy.girassol.core.memory.SubjectiveOpinion
import com.enhikyy.girassol.core.rsi.BoundedRsiPromotionGate
import com.enhikyy.girassol.core.rsi.CognitivePolicyGenome
import com.enhikyy.girassol.core.rsi.RsiMutationProposal
import com.enhikyy.girassol.core.rsi.RsiCoordinator
import com.enhikyy.girassol.core.rsi.ShadowReplayMetrics
import com.enhikyy.girassol.core.social.AdicoInstitutionRule
import com.enhikyy.girassol.core.social.DeonticOperator
import com.enhikyy.girassol.core.social.DirectedSocialTie
import com.enhikyy.girassol.core.social.DunbarRing
import com.enhikyy.girassol.core.social.DunbarSocialNetwork
import com.enhikyy.girassol.core.social.SocialDynamicsAndCliodynamics
import com.enhikyy.girassol.core.world.AffordanceAdvertisement
import com.enhikyy.girassol.core.world.AtomicToil
import com.enhikyy.girassol.core.world.PhysicalJobPipeline
import com.enhikyy.girassol.core.world.SpatialStigmergyAndReservationBoard
import com.enhikyy.girassol.core.world.StockFlowDoubleEntryLedger
import com.enhikyy.girassol.core.world.ToilStatus
import com.enhikyy.girassol.core.world.WorldCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GeaDeterministicEnginesTest {

    @Test
    fun `subjective logic josang preserves simplex and neutralizes untrusted poisoning`() {
        val rumor = SubjectiveOpinion(belief = 0.90, disbelief = 0.05, uncertainty = 0.05, baseRate = 0.5)
        val discounted = rumor.discountByTrust(trustWeight = 0.10)
        assertEquals(1.0, discounted.belief + discounted.disbelief + discounted.uncertainty, 1e-6)
        assertTrue(discounted.uncertainty > 0.85)
        assertTrue(discounted.projectedProbability() < 0.60)

        val fused = SubjectiveOpinion.cumulativeFuse(discounted, discounted)
        assertEquals(1.0, fused.belief + fused.disbelief + fused.uncertainty, 1e-6)
    }

    @Test
    fun `bounded rsi gate rejects invariant violation and promotes valid gain`() {
        val gate = BoundedRsiPromotionGate()
        val baseGenome = CognitivePolicyGenome(version = 1)
        val proposal = RsiMutationProposal(
            proposalId = "rsi-01",
            proposerAgentId = "citizen-1",
            tick = 100L,
            rationale = "Aumentar peso epistêmico em ambiente incerto",
            deltaEpistemicWeight = 0.15
        )
        val candGenome = proposal.applyTo(baseGenome)

        val baseMetrics = ShadowReplayMetrics(1.4, 0.72, 0.18, 0.68, 12.0, 0)
        val unsafeMetrics = ShadowReplayMetrics(1.1, 0.85, 0.17, 0.70, 10.0, invariantsViolated = 1)
        assertFalse(gate.evaluatePromotion(baseGenome, candGenome, baseMetrics, unsafeMetrics).promoted)

        val validMetrics = ShadowReplayMetrics(1.15, 0.80, 0.175, 0.72, 11.0, invariantsViolated = 0)
        val decision = gate.evaluatePromotion(baseGenome, candGenome, baseMetrics, validMetrics)
        assertTrue(decision.promoted)
        assertTrue(decision.netFitnessGain >= 0.025)
    }

    @Test
    fun `rsi shadow replay without forecasts cannot promote a policy`() {
        val core = WorldCore.create(seed = 505L)
        val coordinator = RsiCoordinator(replayTicks = 8, cmaLambda = 2, cmaReplayTicks = 4)

        val metrics = coordinator.shadowReplay(core.state, core.state.policy, ticks = 8)
        assertTrue(metrics.brierScore.isNaN())
        assertFalse(metrics.hasValidMeasurements())

        val record = coordinator.runCycle(core)
        assertFalse(record.decision.promoted)
        assertTrue(record.decision.reason.contains("ausentes"))
    }

    @Test
    fun `active inference and allostatic thermal coupling gate llm properly`() {
        val gExplore = ActiveInferencePianoOrchestrator.computeExpectedFreeEnergy(
            predictedPragmaticRisk = 0.40,
            priorEntropy = 1.20,
            posteriorExpectedEntropy = 0.45,
            epistemicWeight = 0.70,
            neuralMetabolicCost = 0.12
        )
        assertTrue(gExplore < 0.05)

        val bypassByTsetlin = AllostaticThermalCoupling.shouldInvokeLocalLlm(
            topActionUtility = 0.85,
            secondActionUtility = 0.84,
            tieThreshold = 0.05,
            minUrgency = 0.60,
            consecutiveHtnFailures = 0,
            socialNegotiationActive = false,
            tier = AllostaticThermalCoupling.ThermalTier.NONE_OR_LIGHT,
            tsetlinReflexConfidence = 0.92
        )
        assertFalse(bypassByTsetlin)
    }

    @Test
    fun `rsi gate fails closed on missing or non finite evaluation evidence`() {
        val gate = BoundedRsiPromotionGate()
        val baseline = CognitivePolicyGenome(version = 1)
        val candidate = baseline.copy(version = 2, epistemicExplorationWeight = 0.90)
        val baseMetrics = ShadowReplayMetrics(1.2, 0.70, 0.20, 0.70, 8.0, 0)

        assertTrue(gate.computeBrierScore(emptyList()).isNaN())

        val invalidMetrics = baseMetrics.copy(meanFreeEnergy = Double.POSITIVE_INFINITY)
        val decision = gate.evaluatePromotion(baseline, candidate, baseMetrics, invalidMetrics)
        assertFalse(decision.promoted)
        assertTrue(decision.reason.contains("não finitas"))
        assertEquals(-1.0, decision.netFitnessGain, 0.0)

        val invalidCalibration = baseMetrics.copy(brierScore = Double.NaN)
        val calibrationDecision = gate.evaluatePromotion(baseline, candidate, baseMetrics, invalidCalibration)
        assertFalse(calibrationDecision.promoted)
    }

    @Test
    fun `pedagogy bkt fsrs irt voyager and diploid dominance work deterministically`() {
        var skill = SkillMasteryState(skillId = "forjar_ferramenta")
        repeat(4) { i ->
            skill = skill.updateFromPractice(success = true, currentTick = (i + 1) * 10L, geneticLearningAptitude = 0.8)
        }
        assertTrue(skill.isVoyagerShareableSkill())
        assertTrue(skill.executionSuccessProbability(taskDifficultyB = 0.5) > 0.75)

        val library = VoyagerSkillLibrary()
        assertTrue(
            library.registerVerifiedSkill(
                VoyagerProceduralSkill(
                    skillId = "forjar_ferramenta",
                    authorAgentId = "mestre-1",
                    preconditions = listOf("bancada_livre"),
                    actionSequence = listOf("coletar_minerio", "aquecer_forja", "moldar"),
                    verifiedSuccessRate = 0.92,
                    createdAtTick = 50L
                )
            )
        )
        val apprentice = library.teachApprentice("forjar_ferramenta", SkillMasteryState("forjar_ferramenta"), 0.9, 60L)
        assertTrue(apprentice.masteryProb > 0.25)

        val locus = DiploidLocus(
            maternal = Allele(value = 0.90, dominanceWeight = 0.80),
            paternal = Allele(value = 0.20, dominanceWeight = 0.20)
        )
        assertEquals(0.76, locus.express(), 1e-6)
        val genomeA = DiploidGenome(mapOf("curiosidade" to locus))
        val child = genomeA.recombineWith(genomeA, Random(42L), mutationSigma = 0.0)
        val twin = genomeA.recombineWith(genomeA, Random(42L), mutationSigma = 0.0)
        // cada gameta herda um alelo aleatório: a expressão fica entre os alelos parentais e é reprodutível
        assertTrue(child.expressTrait("curiosidade") in 0.2..0.9)
        assertEquals(child.expressTrait("curiosidade"), twin.expressTrait("curiosidade"), 0.0)
    }

    @Test
    fun `society dunbar adico cliodynamics and double entry ledger preserve invariants`() {
        val net = DunbarSocialNetwork("citizen-1")
        for (i in 1..160) {
            val tie = net.getOrCreateTie("peer-$i")
            tie.affinity = (i % 10) / 10.0
            tie.familiarity = 0.5
        }
        assertEquals(DunbarRing.CLAN_COMMUNITY.maxCapacity, net.allTies().size)

        val rule = AdicoInstitutionRule(
            ruleId = "cota-agua",
            attributes = setOf("COLETOR"),
            deontic = DeonticOperator.PERMITIDO,
            aimAction = "EXTRAIR_AGUA",
            conditions = "SECA",
            orElseSanctionUnits = 5,
            maxQuotaPerWindow = 2
        )
        assertEquals(true to 0, rule.evaluateActionCompliance(setOf("COLETOR"), "EXTRAIR_AGUA", "SECA", 2))
        assertEquals(false to 5, rule.evaluateActionCompliance(setOf("COLETOR"), "EXTRAIR_AGUA", "SECA", 3))

        val ledger = StockFlowDoubleEntryLedger()
        ledger.mintInitialEndowment("citizen-1", "ALIMENTO", 100.0, 1L, "hash-1")
        assertTrue(ledger.contributeToCommunalPool("citizen-1", "ALIMENTO", 40.0, 2L, "hash-2"))
        assertTrue(ledger.withdrawFromCommunalPool("citizen-2", "ALIMENTO", 15.0, 3L, "hash-3"))
        assertTrue(ledger.verifyZeroSumConservation("ALIMENTO"))

        val board = SpatialStigmergyAndReservationBoard(16, 16)
        board.publishAdvertisement(AffordanceAdvertisement("forja-1", "FORJAR", 4f, 4f, 20f, maxConcurrentUsers = 1))
        assertTrue(board.tryReserve("forja-1", "citizen-1", currentTick = 10L, ttlTicks = 5L))
        assertFalse(board.tryReserve("forja-1", "citizen-2", currentTick = 12L, ttlTicks = 5L))
        assertTrue(board.tryReserve("forja-1", "citizen-2", currentTick = 16L, ttlTicks = 5L))

        val job = PhysicalJobPipeline(
            jobId = "job-1",
            targetEntityId = "forja-1",
            toils = listOf(object : AtomicToil {
                override val name = "executar"
                override fun tick(agentId: String, currentTick: Long) = ToilStatus.SUCCEEDED
            }),
            board = board
        )
        assertEquals(ToilStatus.SUCCEEDED, job.step("citizen-2", 17L))
        assertEquals(0.0, SemanticCollapseGuard.normalizedPopulationEntropy(mapOf("IDLE" to 50)), 1e-6)
        assertTrue(SemanticCollapseGuard.noveltyEpistemicBonus("EXPLORAR", mapOf("IDLE" to 50, "EXPLORAR" to 0)) > 0.25)

        val tieOpinion = DirectedSocialTie("a", "b", currentOpinion = 0.1, intrinsicOpinion = 0.0, susceptibility = 0.7)
        SocialDynamicsAndCliodynamics.stepHybridOpinion(tieOpinion, peerOpinion = 0.4)
        val psi = SocialDynamicsAndCliodynamics.computeCliodynamics(120, 100.0, listOf(10.0, 20.0, 90.0), 15, 5, 400.0)
        assertTrue(psi.politicalStressIndicator > 1.0)
    }
}