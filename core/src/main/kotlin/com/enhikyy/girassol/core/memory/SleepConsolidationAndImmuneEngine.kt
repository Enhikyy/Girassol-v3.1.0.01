package com.enhikyy.girassol.core.memory

import com.enhikyy.girassol.core.util.fmt
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

@kotlinx.serialization.Serializable
enum class MemoryLayer { WORKING, EPISODIC, SEMANTIC, PROCEDURAL }

@kotlinx.serialization.Serializable
data class SubjectiveOpinion(
    val belief: Double,
    val disbelief: Double,
    val uncertainty: Double,
    val baseRate: Double = 0.5
) {
    init {
        val sum = belief + disbelief + uncertainty
        require(abs(sum - 1.0) < 1e-4) { "Opinião subjetiva inválida: b+d+u=$sum != 1.0" }
    }

    fun projectedProbability(): Double = (belief + baseRate * uncertainty).coerceIn(0.0, 1.0)

    fun discountByTrust(trustWeight: Double): SubjectiveOpinion {
        val c = trustWeight.coerceIn(0.0, 1.0)
        val bNew = c * belief
        val dNew = c * disbelief
        val uNew = (1.0 - bNew - dNew).coerceIn(0.0, 1.0)
        return SubjectiveOpinion(bNew, dNew, uNew, baseRate)
    }

    companion object {
        fun fromBeta(alpha: Double, beta: Double, baseRate: Double = 0.5, priorWeight: Double = 2.0): SubjectiveOpinion {
            val r = (alpha - 1.0).coerceAtLeast(0.0)
            val s = (beta - 1.0).coerceAtLeast(0.0)
            val denom = r + s + priorWeight
            return SubjectiveOpinion(r / denom, s / denom, priorWeight / denom, baseRate)
        }

        // Fusão Cumulativa: para evidências de fontes independentes
        fun cumulativeFuse(w1: SubjectiveOpinion, w2: SubjectiveOpinion): SubjectiveOpinion {
            val kappa = w1.uncertainty + w2.uncertainty - w1.uncertainty * w2.uncertainty
            if (kappa <= 1e-9) {
                return SubjectiveOpinion(
                    0.5 * (w1.belief + w2.belief),
                    0.5 * (w1.disbelief + w2.disbelief),
                    0.0,
                    0.5 * (w1.baseRate + w2.baseRate)
                )
            }
            val b = (w1.belief * w2.uncertainty + w2.belief * w1.uncertainty) / kappa
            val d = (w1.disbelief * w2.uncertainty + w2.disbelief * w1.uncertainty) / kappa
            val u = (w1.uncertainty * w2.uncertainty) / kappa
            val norm = b + d + u
            return SubjectiveOpinion(b / norm, d / norm, u / norm, w1.baseRate)
        }

        // Fusão por Média (Averaging Belief Fusion): para rumores correlacionados sem dupla contagem
        fun averagingFuse(w1: SubjectiveOpinion, w2: SubjectiveOpinion): SubjectiveOpinion {
            val kappa = w1.uncertainty + w2.uncertainty - 2.0 * w1.uncertainty * w2.uncertainty
            if (kappa <= 1e-9) {
                return SubjectiveOpinion(
                    0.5 * (w1.belief + w2.belief),
                    0.5 * (w1.disbelief + w2.disbelief),
                    0.0,
                    0.5 * (w1.baseRate + w2.baseRate)
                )
            }
            val b = (w1.belief * w2.uncertainty + w2.belief * w1.uncertainty) / kappa
            val d = (w1.disbelief * w2.uncertainty + w2.disbelief * w1.uncertainty) / kappa
            val u = (2.0 * w1.uncertainty * w2.uncertainty) / kappa
            val norm = b + d + u
            return SubjectiveOpinion(b / norm, d / norm, u / norm, 0.5 * (w1.baseRate + w2.baseRate))
        }
    }
}

@kotlinx.serialization.Serializable
data class AssociativeMemoryNode(
    val id: Long,
    val ownerAgentId: String,
    val layer: MemoryLayer,
    val content: String,
    val tags: Set<String>,
    val entityIds: Set<String>,
    val linkedMemoryIds: MutableSet<Long> = mutableSetOf(),
    val salience: Double,
    val confidence: Double,
    val sourceEventId: Long?,
    val sourceAgentId: String?,
    val createdTick: Long,
    val recentAccessTicks: MutableList<Long> = mutableListOf(),
    var qUtility: Double = 0.0,
    var tdPredictionError: Double = 0.0,
    var verifiedByWorldCore: Boolean = false
) {
    fun baseLevelActivation(currentTick: Long, decay: Double = 0.5): Double {
        val samples = if (recentAccessTicks.isEmpty()) listOf(createdTick) else recentAccessTicks
        var sum = 0.0
        for (t in samples) {
            val dt = (currentTick - t).coerceAtLeast(1L).toDouble()
            sum += dt.pow(-decay)
        }
        return ln(sum.coerceAtLeast(1e-9))
    }

    fun updateMemRlUtility(observedReward: Double, alpha: Double = 0.20) {
        val clampedR = observedReward.coerceIn(-1.0, 1.0)
        tdPredictionError = clampedR - qUtility
        qUtility = (qUtility + alpha * tdPredictionError).coerceIn(-1.0, 1.0)
    }
}

data class ConsolidationReport(
    val replayedNodeIds: List<Long>,
    val promotedSemanticSummaries: List<String>,
    val distilledProceduralMacros: List<String>,
    val prunedNodeIds: List<Long>
)

class FourLayerMemoryAndSleepEngine(
    private val workingMemoryCapacity: Int = 7,
    private val maxNodesPerAgent: Int = 512,
    private val replayBatchSize: Int = 12
) {
    private val workingSlots = ArrayDeque<AssociativeMemoryNode>()

    fun clearWorkingMemory() = workingSlots.clear()

    fun pushWorkingMemory(node: AssociativeMemoryNode) {
        workingSlots.removeAll { it.id == node.id }
        if (workingSlots.size >= workingMemoryCapacity) {
            workingSlots.removeFirst()
        }
        workingSlots.addLast(node)
    }

    fun activeWorkingIds(): Set<Long> = workingSlots.map { it.id }.toSet()

    fun retrieveHybrid(
        nodes: List<AssociativeMemoryNode>,
        currentTick: Long,
        queryTags: Set<String>,
        queryEntities: Set<String>,
        topK: Int = 6
    ): List<AssociativeMemoryNode> {
        val activeWm = activeWorkingIds()
        return nodes.map { node ->
            val normAct = 1.0 / (1.0 + exp(-node.baseLevelActivation(currentTick)))
            val zettelkastenBoost = if (node.linkedMemoryIds.any { it in activeWm }) 0.25 else 0.0
            val tagScore = if (queryTags.isEmpty()) 0.0 else node.tags.intersect(queryTags).size.toDouble() / queryTags.size
            val entScore = if (queryEntities.isEmpty()) 0.0 else node.entityIds.intersect(queryEntities).size.toDouble() / queryEntities.size
            val contextRel = (0.55 * tagScore + 0.45 * entScore).coerceIn(0.0, 1.0)
            val memRlTerm = (node.qUtility + 1.0) * 0.5
            val score = 0.30 * normAct +
                0.25 * (node.salience * node.confidence) +
                0.25 * contextRel +
                0.20 * memRlTerm +
                zettelkastenBoost
            node to score
        }
            .sortedByDescending { it.second }
            .take(topK)
            .map { (node, _) ->
                if (node.recentAccessTicks.size >= 16) node.recentAccessTicks.removeAt(0)
                node.recentAccessTicks.add(currentTick)
                pushWorkingMemory(node)
                node
            }
    }

    fun runHippocampalSleepConsolidation(
        memories: List<AssociativeMemoryNode>,
        currentTick: Long,
        actRDecay: Double = 0.5
    ): ConsolidationReport {
        if (memories.isEmpty()) {
            return ConsolidationReport(emptyList(), emptyList(), emptyList(), emptyList())
        }

        val replayCandidates = memories
            .sortedByDescending { abs(it.tdPredictionError) * 0.65 + abs(it.qUtility) * 0.35 }
            .take(replayBatchSize)

        val promotedSemantic = memories
            .filter { it.layer == MemoryLayer.EPISODIC && it.verifiedByWorldCore && it.qUtility > 0.35 }
            .flatMap { mem -> mem.tags.map { tag -> tag to mem } }
            .groupBy({ it.first }, { it.second })
            .filter { (_, group) -> group.size >= 3 }
            .map { (tag, group) ->
                val avgQ = group.map { it.qUtility }.average()
                "SCHEMA[$tag]: ${group.size} episódios verificados consolidados (Q_m=${"%.2f".fmt(avgQ)})."
            }

        val distilledProcedural = memories
            .filter { it.layer == MemoryLayer.EPISODIC && it.verifiedByWorldCore && it.qUtility >= 0.65 }
            .sortedByDescending { it.qUtility }
            .take(3)
            .map { ep ->
                "SKILL_MACRO[src=${ep.id}]: ${ep.content.take(96)} (Q_m=${"%.2f".fmt(ep.qUtility)})"
            }

        val prunedIds = if (memories.size > maxNodesPerAgent) {
            val excess = memories.size - maxNodesPerAgent
            memories
                .filter { it.layer == MemoryLayer.EPISODIC && !it.verifiedByWorldCore }
                .sortedBy { it.baseLevelActivation(currentTick, actRDecay) + 1.5 * abs(it.qUtility) }
                .take(excess)
                .map { it.id }
        } else {
            emptyList()
        }

        return ConsolidationReport(
            replayedNodeIds = replayCandidates.map { it.id },
            promotedSemanticSummaries = promotedSemantic,
            distilledProceduralMacros = distilledProcedural,
            prunedNodeIds = prunedIds
        )
    }
}