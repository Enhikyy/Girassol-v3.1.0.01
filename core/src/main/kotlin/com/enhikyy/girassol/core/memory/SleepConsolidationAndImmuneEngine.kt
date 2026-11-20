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
    var verifiedByWorldCore: Boolean = false,
    /** Embedding binário bit[256] (SimHash) — calculado na primeira indexação e persistido. */
    var bits: LongArray? = null,
    /** Estado recursivo do processo de Hawkes (excitação acumulada e tick do último evento). */
    var hawkesExcitation: Double = 0.0,
    var hawkesLastTick: Long = 0L
) {
    fun features(): List<String> = EpisodicIndex.features(content, tags, entityIds)

    fun binaryEmbedding(): LongArray = bits ?: EpisodicIndex.simHash(features()).also { bits = it }

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

data class RetrievalResult(val memories: List<AssociativeMemoryNode>, val stage1Candidates: Int, val corpusSize: Int)

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

    /**
     * Recuperação em 2 estágios (ver [EpisodicIndex]): Hamming bit[256] seleciona os
     * [EpisodicIndex.STAGE1_K] candidatos; RRF funde cosseno denso, BM25, ativação composta
     * ACT-R/MemRL/A-MEM e intensidade de Hawkes. As memórias recuperadas entram na memória de
     * trabalho (capacidade 7±2) e recebem um evento de excitação de Hawkes.
     */
    fun retrieveTwoStage(
        nodes: List<AssociativeMemoryNode>,
        currentTick: Long,
        queryText: String,
        queryTags: Set<String>,
        queryEntities: Set<String>,
        topK: Int = 6,
        workingCapacity: Int = workingMemoryCapacity
    ): RetrievalResult {
        if (nodes.isEmpty()) return RetrievalResult(emptyList(), 0, 0)
        val qFeatures = EpisodicIndex.features(queryText, queryTags, queryEntities)
        val qBits = EpisodicIndex.simHash(qFeatures)
        val stage1 = nodes.indices.sortedWith(compareBy<Int> { EpisodicIndex.hamming(qBits, nodes[it].binaryEmbedding()) }.thenByDescending { nodes[it].id })
            .take(EpisodicIndex.STAGE1_K).map { nodes[it] }
        val qDense = EpisodicIndex.dense(qFeatures)
        val activeWm = activeWorkingIds()
        val cos = DoubleArray(stage1.size) { EpisodicIndex.cosine(qDense, EpisodicIndex.dense(stage1[it].features())).toDouble() }
        val corpus = nodes.map { EpisodicIndex.terms(it.content) + it.tags.map { t -> t.lowercase() } }
        val bm25 = EpisodicIndex.bm25(EpisodicIndex.terms(queryText) + queryTags.map { it.lowercase() },
            stage1.map { EpisodicIndex.terms(it.content) + it.tags.map { t -> t.lowercase() } }, corpus)
        val composite = DoubleArray(stage1.size) { i ->
            val node = stage1[i]
            val normAct = 1.0 / (1.0 + exp(-node.baseLevelActivation(currentTick)))
            val zettelkastenBoost = if (node.linkedMemoryIds.any { it in activeWm }) 0.25 else 0.0
            val tagScore = if (queryTags.isEmpty()) 0.0 else node.tags.intersect(queryTags).size.toDouble() / queryTags.size
            val entScore = if (queryEntities.isEmpty()) 0.0 else node.entityIds.intersect(queryEntities).size.toDouble() / queryEntities.size
            0.30 * normAct + 0.25 * (node.salience * node.confidence) + 0.25 * (0.55 * tagScore + 0.45 * entScore).coerceIn(0.0, 1.0) +
                0.20 * (node.qUtility + 1.0) * 0.5 + zettelkastenBoost
        }
        val hawkes = DoubleArray(stage1.size) { EpisodicIndex.hawkesIntensity(stage1[it].hawkesExcitation, stage1[it].hawkesLastTick, currentTick) }
        val fused = EpisodicIndex.rrf(listOf(cos, bm25, composite, hawkes), stage1.size)
        val chosen = stage1.indices.sortedWith(compareByDescending<Int> { fused[it] }.thenByDescending { stage1[it].id }).take(topK).map { stage1[it] }
        val cap = workingCapacity.coerceIn(5, 9)
        for (node in chosen) {
            if (node.recentAccessTicks.size >= 16) node.recentAccessTicks.removeAt(0)
            node.recentAccessTicks.add(currentTick)
            EpisodicIndex.hawkesExcite(node, currentTick, node.salience)
            workingSlots.removeAll { it.id == node.id }
            while (workingSlots.size >= cap) workingSlots.removeFirst()
            workingSlots.addLast(node)
        }
        return RetrievalResult(chosen, stage1.size, nodes.size)
    }

    fun retrieveHybrid(
        nodes: List<AssociativeMemoryNode>,
        currentTick: Long,
        queryTags: Set<String>,
        queryEntities: Set<String>,
        topK: Int = 6
    ): List<AssociativeMemoryNode> = retrieveTwoStage(nodes, currentTick, queryTags.joinToString(" "), queryTags, queryEntities, topK).memories

    fun workingMemorySize(): Int = workingSlots.size

    /**
     * Sharp-Wave Ripple Replay: reproduz as sequências encadeadas (A-MEM) em ordem reversa
     * durante o sono, propagando o valor TD do episódio final para os anteriores
     * (Q_prev ← Q_prev + α·(γ·Q_next − Q_prev)). Devolve o número de atualizações.
     */
    fun sharpWaveRippleReplay(memories: List<AssociativeMemoryNode>, alpha: Double = 0.15, gamma: Double = 0.9): Int {
        val byId = memories.associateBy { it.id }
        val episodic = memories.filter { it.layer == MemoryLayer.EPISODIC }.sortedByDescending { it.id }.take(replayBatchSize)
        var updates = 0
        for (end in episodic) {
            var next = end
            val visited = HashSet<Long>()
            visited += next.id
            while (true) {
                val prev = next.linkedMemoryIds.filter { it < next.id && it !in visited }.maxOrNull()?.let { byId[it] } ?: break
                prev.qUtility = (prev.qUtility + alpha * (gamma * next.qUtility - prev.qUtility)).coerceIn(-1.0, 1.0)
                visited += prev.id
                updates++
                next = prev
                if (visited.size > 8) break
            }
        }
        return updates
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