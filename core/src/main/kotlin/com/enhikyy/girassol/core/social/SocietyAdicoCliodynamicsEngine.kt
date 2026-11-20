package com.enhikyy.girassol.core.social

import kotlin.math.abs
import kotlin.math.exp

enum class DunbarRing(val maxCapacity: Int) {
    INTIMATE_CLIQUE(5),
    SYMPATHY_GROUP(15),
    FUNCTIONAL_BAND(50),
    CLAN_COMMUNITY(150)
}

@kotlinx.serialization.Serializable
enum class DeonticOperator { OBRIGADO, PERMITIDO, PROIBIDO }

@kotlinx.serialization.Serializable
data class AdicoInstitutionRule(
    val ruleId: String,
    val attributes: Set<String>,
    val deontic: DeonticOperator,
    val aimAction: String,
    val conditions: String,
    val orElseSanctionUnits: Int,
    val maxQuotaPerWindow: Int = 5,
    val description: String = ""
) {
    fun evaluateActionCompliance(
        actorRoles: Set<String>,
        actionName: String,
        activeConditionTag: String,
        currentWindowUsage: Int
    ): Pair<Boolean, Int> {
        val appliesToActor = attributes.isEmpty() || attributes.any { it in actorRoles }
        val appliesToAction = aimAction == actionName
        val appliesToCondition = conditions.isBlank() || conditions == activeConditionTag
        if (!appliesToActor || !appliesToAction || !appliesToCondition) {
            return true to 0
        }
        return when (deontic) {
            DeonticOperator.PROIBIDO -> false to orElseSanctionUnits
            DeonticOperator.PERMITIDO -> {
                if (currentWindowUsage > maxQuotaPerWindow) false to orElseSanctionUnits else true to 0
            }
            DeonticOperator.OBRIGADO -> {
                if (currentWindowUsage < 1) false to orElseSanctionUnits else true to 0
            }
        }
    }
}

@kotlinx.serialization.Serializable
data class CliodynamicsSnapshot(
    val populationCount: Int,
    val carryingCapacity: Double,
    val giniIndex: Double,
    val massMobilizationPotential: Double,
    val eliteOverproductionIndex: Double,
    val stateFiscalHealth: Double,
    val politicalStressIndicator: Double
)

@kotlinx.serialization.Serializable
data class DirectedSocialTie(
    val fromAgentId: String,
    val toAgentId: String,
    var affinity: Double = 0.0,
    var familiarity: Double = 0.05,
    val domainAlpha: MutableMap<String, Double> = mutableMapOf(),
    val domainBeta: MutableMap<String, Double> = mutableMapOf(),
    var currentOpinion: Double = 0.0,
    val intrinsicOpinion: Double = 0.0,
    val susceptibility: Double = 0.65
) {
    fun domainTrust(domain: String): Double {
        val a = domainAlpha[domain] ?: 1.5
        val b = domainBeta[domain] ?: 1.5
        return a / (a + b)
    }

    fun recordTrustOutcome(domain: String, success: Boolean) {
        val a = domainAlpha[domain] ?: 1.5
        val b = domainBeta[domain] ?: 1.5
        if (success) {
            domainAlpha[domain] = a + 1.0
        } else {
            domainBeta[domain] = b + 2.2
        }
    }

    fun tieStrength(): Double =
        (0.40 * abs(affinity) + 0.35 * familiarity + 0.25 * domainTrust("general")).coerceIn(0.0, 1.0)

    fun decayFamiliarity(dtTicks: Long, lambda: Double = 0.0005) {
        familiarity *= exp(-lambda * dtTicks.coerceAtLeast(0L))
    }
}

@kotlinx.serialization.Serializable
class DunbarSocialNetwork(
    private val ownerAgentId: String,
    private val ties: MutableMap<String, DirectedSocialTie> = mutableMapOf()
) {
    fun tieOrNull(targetAgentId: String): DirectedSocialTie? = ties[targetAgentId]
    fun removeTie(targetAgentId: String) { ties.remove(targetAgentId) }

    fun getOrCreateTie(targetAgentId: String, intrinsicOpinion: Double = 0.0): DirectedSocialTie {
        ties[targetAgentId]?.let { return it }
        if (ties.size >= DunbarRing.CLAN_COMMUNITY.maxCapacity) {
            val weakest = ties.values.minWithOrNull(compareBy<DirectedSocialTie> { it.tieStrength() }.thenBy { it.toAgentId })
            if (weakest != null) ties.remove(weakest.toAgentId)
        }
        val created = DirectedSocialTie(
            fromAgentId = ownerAgentId,
            toAgentId = targetAgentId,
            intrinsicOpinion = intrinsicOpinion.coerceIn(-1.0, 1.0)
        )
        ties[targetAgentId] = created
        return created
    }

    fun classifyDunbarRing(targetAgentId: String): DunbarRing? {
        val ranked = ties.values.sortedWith(compareByDescending<DirectedSocialTie> { it.tieStrength() }.thenBy { it.toAgentId })
        val idx = ranked.indexOfFirst { it.toAgentId == targetAgentId }
        if (idx < 0) return null
        return when {
            idx < DunbarRing.INTIMATE_CLIQUE.maxCapacity -> DunbarRing.INTIMATE_CLIQUE
            idx < DunbarRing.SYMPATHY_GROUP.maxCapacity -> DunbarRing.SYMPATHY_GROUP
            idx < DunbarRing.FUNCTIONAL_BAND.maxCapacity -> DunbarRing.FUNCTIONAL_BAND
            else -> DunbarRing.CLAN_COMMUNITY
        }
    }

    fun allTies(): Collection<DirectedSocialTie> = ties.values
    fun sortedTies(): List<DirectedSocialTie> = ties.values.sortedBy { it.toAgentId }
}

object SocialDynamicsAndCliodynamics {
    fun stepHybridOpinion(
        tie: DirectedSocialTie,
        peerOpinion: Double,
        confidenceBoundEpsilon: Double = 0.45,
        convergenceMu: Double = 0.25
    ): Double {
        val diff = peerOpinion - tie.currentOpinion
        val dwUpdated = if (abs(diff) < confidenceBoundEpsilon) {
            tie.currentOpinion + convergenceMu * tie.domainTrust("general") * diff
        } else {
            tie.currentOpinion
        }
        tie.currentOpinion = (tie.susceptibility * dwUpdated + (1.0 - tie.susceptibility) * tie.intrinsicOpinion)
            .coerceIn(-1.0, 1.0)
        return tie.currentOpinion
    }

    fun applyHeiderTriadBalance(
        tieIJ: DirectedSocialTie,
        tieJK: DirectedSocialTie,
        tieIK: DirectedSocialTie,
        step: Double = 0.03
    ) {
        val product = tieIJ.affinity * tieJK.affinity * tieIK.affinity
        if (product < 0.0) {
            val nudge = if (tieIJ.affinity * tieJK.affinity > 0.0) step else -step
            tieIK.affinity = (tieIK.affinity + nudge).coerceIn(-1.0, 1.0)
        }
    }

    fun computeCliodynamics(
        population: Int,
        carryingCapacity: Double,
        agentWealths: List<Double>,
        aspiringLeadersCount: Int,
        leadershipSlots: Int,
        communalTreasuryUnits: Double
    ): CliodynamicsSnapshot {
        val gini = computeGini(agentWealths)
        val mmp = (population.toDouble() / carryingCapacity.coerceAtLeast(1.0)).coerceAtLeast(0.0)
        val emp = (aspiringLeadersCount.toDouble() / leadershipSlots.coerceAtLeast(1)).coerceAtLeast(0.0)
        val sfd = (communalTreasuryUnits / (population * 10.0).coerceAtLeast(1.0)).coerceIn(0.05, 2.0)
        val psi = (mmp * (0.35 + gini) * emp) / sfd
        return CliodynamicsSnapshot(population, carryingCapacity, gini, mmp, emp, sfd, psi)
    }

    private fun computeGini(values: List<Double>): Double {
        if (values.size <= 1) return 0.0
        val sorted = values.map { it.coerceAtLeast(0.0) }.sorted()
        val sum = sorted.sum()
        if (sum <= 1e-9) return 0.0
        var weightedSum = 0.0
        for (i in sorted.indices) {
            weightedSum += (2.0 * (i + 1) - sorted.size - 1) * sorted[i]
        }
        return (weightedSum / (sorted.size * sum)).coerceIn(0.0, 1.0)
    }
}