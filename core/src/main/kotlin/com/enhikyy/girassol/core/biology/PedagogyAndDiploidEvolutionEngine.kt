package com.enhikyy.girassol.core.biology

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random

@kotlinx.serialization.Serializable
data class SkillMasteryState(
    val skillId: String,
    val masteryProb: Double = 0.10,
    val difficultyD: Double = 5.0,
    val stabilityS: Double = 24.0,
    val lastReviewedTick: Long = 0L,
    val verifiedExecutions: Int = 0
) {
    fun retrievability(currentTick: Long): Double {
        val elapsed = (currentTick - lastReviewedTick).coerceAtLeast(0L).toDouble()
        return (1.0 + (19.0 / 81.0) * (elapsed / stabilityS.coerceAtLeast(1.0))).pow(-0.5)
    }

    fun updateFromPractice(
        success: Boolean,
        currentTick: Long,
        geneticLearningAptitude: Double = 0.5,
        pSlip: Double = 0.08,
        pGuess: Double = 0.15
    ): SkillMasteryState {
        val pTransition = (0.10 + 0.18 * geneticLearningAptitude).coerceIn(0.05, 0.35)
        val posteriorL = if (success) {
            val num = masteryProb * (1.0 - pSlip)
            num / (num + (1.0 - masteryProb) * pGuess).coerceAtLeast(1e-9)
        } else {
            val num = masteryProb * pSlip
            num / (num + (1.0 - masteryProb) * (1.0 - pGuess)).coerceAtLeast(1e-9)
        }
        val newL = (posteriorL + (1.0 - posteriorL) * pTransition).coerceIn(0.001, 0.999)
        val r = retrievability(currentTick)
        val newDiff = (difficultyD + if (success) -0.25 else 0.45).coerceIn(1.0, 10.0)
        val newStab = if (success) {
            stabilityS * (1.0 + 0.48 * (11.0 - newDiff) * 0.1 * (2.0 - r))
        } else {
            (stabilityS * 0.45).coerceAtLeast(1.0)
        }
        return copy(
            masteryProb = newL,
            difficultyD = newDiff,
            stabilityS = newStab,
            lastReviewedTick = currentTick,
            verifiedExecutions = if (success) verifiedExecutions + 1 else verifiedExecutions
        )
    }

    fun executionSuccessProbability(
        taskDifficultyB: Double,
        discriminationA: Double = 4.2,
        guessC: Double = 0.05,
        geneticAptitude: Double = 0.5
    ): Double {
        val theta = masteryProb + 0.25 * (geneticAptitude - 0.5)
        return guessC + (1.0 - guessC) / (1.0 + exp(-discriminationA * (theta - taskDifficultyB)))
    }

    fun isVoyagerShareableSkill(): Boolean = verifiedExecutions >= 3 && masteryProb >= 0.75
}

@kotlinx.serialization.Serializable
data class VoyagerProceduralSkill(
    val skillId: String,
    val authorAgentId: String,
    val preconditions: List<String>,
    val actionSequence: List<String>,
    val verifiedSuccessRate: Double,
    val createdAtTick: Long
)

@kotlinx.serialization.Serializable
class VoyagerSkillLibrary(
    private val skills: MutableMap<String, VoyagerProceduralSkill> = mutableMapOf()
) {
    fun all(): List<VoyagerProceduralSkill> = skills.values.sortedBy { it.skillId }

    fun registerVerifiedSkill(skill: VoyagerProceduralSkill): Boolean {
        if (skill.verifiedSuccessRate < 0.75 || skill.actionSequence.isEmpty()) return false
        val existing = skills[skill.skillId]
        if (existing == null || skill.verifiedSuccessRate > existing.verifiedSuccessRate) {
            skills[skill.skillId] = skill
            return true
        }
        return false
    }

    fun teachApprentice(
        skillId: String,
        apprenticeCurrentState: SkillMasteryState,
        mentorTrust: Double,
        currentTick: Long
    ): SkillMasteryState {
        val proto = skills[skillId] ?: return apprenticeCurrentState
        val transferGain = (0.28 * mentorTrust * proto.verifiedSuccessRate).coerceIn(0.05, 0.45)
        val boostedMastery = (apprenticeCurrentState.masteryProb +
            (1.0 - apprenticeCurrentState.masteryProb) * transferGain).coerceIn(0.01, 0.95)
        return apprenticeCurrentState.copy(
            masteryProb = boostedMastery,
            stabilityS = apprenticeCurrentState.stabilityS * 1.25,
            lastReviewedTick = currentTick
        )
    }

    fun size(): Int = skills.size
    fun findSkill(skillId: String): VoyagerProceduralSkill? = skills[skillId]
}

@kotlinx.serialization.Serializable
data class Allele(val value: Double, val dominanceWeight: Double)

@kotlinx.serialization.Serializable
data class DiploidLocus(val maternal: Allele, val paternal: Allele) {
    fun express(): Double {
        val wm = maternal.dominanceWeight.coerceAtLeast(1e-4)
        val wp = paternal.dominanceWeight.coerceAtLeast(1e-4)
        return ((wm * maternal.value + wp * paternal.value) / (wm + wp)).coerceIn(0.0, 1.0)
    }

    fun applyBaldwinAssimilation(acquiredPhenotypeTarget: Double, assimilationRate: Double = 0.04): DiploidLocus {
        val r = assimilationRate.coerceIn(0.0, 0.15)
        val newMat = maternal.copy(value = (maternal.value + r * (acquiredPhenotypeTarget - maternal.value)).coerceIn(0.0, 1.0))
        val newPat = paternal.copy(value = (paternal.value + r * (acquiredPhenotypeTarget - paternal.value)).coerceIn(0.0, 1.0))
        return DiploidLocus(newMat, newPat)
    }
}

@kotlinx.serialization.Serializable
data class DiploidGenome(
    val loci: Map<String, DiploidLocus>
) {
    fun expressTrait(traitName: String, fallback: Double = 0.5): Double =
        loci[traitName]?.express() ?: fallback

    fun recombineWith(partner: DiploidGenome, rng: Random, mutationSigma: Double = 0.03): DiploidGenome {
        val allKeys = (loci.keys + partner.loci.keys).distinct().sorted()
        val childLoci = mutableMapOf<String, DiploidLocus>()
        for (k in allKeys) {
            val matSource = loci[k] ?: DiploidLocus(Allele(0.5, 0.5), Allele(0.5, 0.5))
            val patSource = partner.loci[k] ?: DiploidLocus(Allele(0.5, 0.5), Allele(0.5, 0.5))
            val chosenMat = if (rng.nextBoolean()) matSource.maternal else matSource.paternal
            val chosenPat = if (rng.nextBoolean()) patSource.maternal else patSource.paternal
            val mutMat = chosenMat.copy(
                value = (chosenMat.value + (rng.nextDouble() - 0.5) * 2.0 * mutationSigma).coerceIn(0.0, 1.0)
            )
            val mutPat = chosenPat.copy(
                value = (chosenPat.value + (rng.nextDouble() - 0.5) * 2.0 * mutationSigma).coerceIn(0.0, 1.0)
            )
            childLoci[k] = DiploidLocus(mutMat, mutPat)
        }
        return DiploidGenome(childLoci)
    }

    fun symbiogeneticMerge(donor: DiploidGenome, traitName: String): DiploidGenome {
        val donorLocus = donor.loci[traitName] ?: return this
        val current = loci[traitName] ?: return copy(loci = loci + (traitName to donorLocus))
        val merged = DiploidLocus(
            maternal = current.maternal,
            paternal = if (donorLocus.express() > current.express()) donorLocus.maternal else current.paternal
        )
        return copy(loci = loci + (traitName to merged))
    }
}

object SemanticCollapseGuard {
    fun normalizedPopulationEntropy(actionCounts: Map<String, Int>): Double {
        val total = actionCounts.values.sum().toDouble()
        if (total <= 0.0 || actionCounts.size <= 1) return 0.0
        var entropy = 0.0
        for (count in actionCounts.values) {
            if (count > 0) {
                val p = count / total
                entropy -= p * ln(p)
            }
        }
        return (entropy / ln(actionCounts.size.toDouble())).coerceIn(0.0, 1.0)
    }

    fun noveltyEpistemicBonus(actionType: String, actionCounts: Map<String, Int>, threshold: Double = 0.55): Double {
        val hSem = normalizedPopulationEntropy(actionCounts)
        if (hSem >= threshold) return 0.0
        val total = actionCounts.values.sum().coerceAtLeast(1).toDouble()
        val freq = (actionCounts[actionType] ?: 0) / total
        val scarcity = (1.0 - freq).coerceIn(0.0, 1.0)
        val urgency = ((threshold - hSem) / threshold).coerceIn(0.0, 1.0)
        return 0.30 * urgency * scarcity
    }
}