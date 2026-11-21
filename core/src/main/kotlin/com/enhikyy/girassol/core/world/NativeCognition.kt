package com.enhikyy.girassol.core.world

import com.enhikyy.girassol.core.memory.AssociativeMemoryNode
import com.enhikyy.girassol.core.memory.EpisodicIndex

/**
 * v3.8 — ponte JNI do núcleo cognitivo C++20 (`app/src/main/cpp/world/cognition.cpp`).
 *
 * Percepção, recuperação de memória em 2 estágios, energia livre esperada G(π), política com
 * histerese, energia livre variacional, conservação soma-zero do ledger e verificação da cadeia
 * SHA-256 rodam no kernel nativo, em LOTE (uma travessia JNI por estágio por tick). O Kotlin só
 * coleta as entradas do [WorldState] e aplica as saídas. Sem a biblioteca o mundo não roda.
 * A biblioteca é a mesma do [NativeWorldTick] (`libgirassol_core.so` / host de testes).
 */
object NativeCognition {
    const val NEEDS = 6
    const val WORKSPACE_CAP = 3
    const val CAND_D = 35
    const val CAND_I = 6
    const val CIT_D = 7

    const val SIGNAL_FOOD = 6
    const val SIGNAL_DANGER = 7
    const val SIGNAL_DROUGHT = 8

    const val FLAG_TARGET = 1
    const val FLAG_SOCIAL = 2
    const val FLAG_EXPLORE = 4
    const val FLAG_LORA = 8

    /** Assinatura dourada (mesma constante em native-tests/cognition_tests.cpp). */
    const val PARITY_GOLDEN: Long = -0x6b8532e5ca00199dL // 0x947acd1a35ffe663

    init {
        // garante que a biblioteca do WorldTick (que contém este kernel) esteja carregada
        NativeWorldTick.ALL_CITIZEN_BITS
    }

    @JvmStatic external fun nativePerceive(needs: DoubleArray, codes: IntArray, xs: IntArray, zs: IntArray,
                                           food: FloatArray?, danger: FloatArray?, width: Int, height: Int, cellSize: Float,
                                           drought: Boolean, salienceOut: DoubleArray): IntArray
    @JvmStatic external fun nativeCognitionBatch(candD: DoubleArray, candI: IntArray, offsets: IntArray, citizenD: DoubleArray,
                                                 currentOrdinal: IntArray, danger: FloatArray?, social: FloatArray?,
                                                 width: Int, height: Int, cellSize: Float,
                                                 preferred: Double, switchCost: Double, gamma: Double,
                                                 efeOut: DoubleArray, projectedOut: DoubleArray, orderOut: IntArray, vfeOut: DoubleArray): IntArray
    @JvmStatic external fun nativeRetrieve(packed: LongArray, scalars: DoubleArray, nQueries: Int, excitationOut: DoubleArray): IntArray
    @JvmStatic external fun nativeSimHash(featureHashes: LongArray): LongArray
    @JvmStatic external fun nativeLedgerZeroSum(balancesMicro: LongArray, resourceIndex: IntArray, nResources: Int): IntArray
    @JvmStatic external fun nativeVerifyChain(seq: LongArray, tick: LongArray, rules: IntArray, types: Array<String>,
                                              actors: Array<String?>, payloads: Array<String>, prevHashes: Array<String>,
                                              hashes: Array<String>, anchor: String?, genesis: String): LongArray
    @JvmStatic external fun nativeParitySignature(): Long

    // ------------------------------------------------------------------ percepção
    data class Perception(val codes: IntArray, val saliences: DoubleArray)

    // ------------------------------------------------------------------ memória
    /** Consulta de recuperação (uma por cidadão). */
    class MemoryQuery(
        val nodes: List<AssociativeMemoryNode>,
        val tick: Long,
        val queryText: String,
        val queryTags: Set<String>,
        val queryEntities: Set<String>,
        val topK: Int,
        val activeWorkingIds: Set<Long>
    )

    class Retrieval(val stage1: Int, val chosen: List<AssociativeMemoryNode>, val newExcitation: DoubleArray)

    private fun LongArrayBuilder.list(values: LongArray) { add(values.size.toLong()); addAll(values) }

    private fun hashes(values: Collection<String>): LongArray {
        val out = LongArray(values.size)
        var i = 0
        for (v in values) out[i++] = EpisodicIndex.fnv1a64(v)
        return out
    }

    /** Recuperação em 2 estágios de várias consultas numa única travessia JNI. */
    fun retrieveBatch(queries: List<MemoryQuery>): List<Retrieval> {
        if (queries.isEmpty()) return emptyList()
        val l = LongArrayBuilder(1024)
        val d = DoubleArrayBuilder(256)
        var maxChosen = 0
        for (q in queries) {
            l.add(q.tick); l.add(q.topK.toLong()); l.add(q.nodes.size.toLong())
            val qFeatures = EpisodicIndex.features(q.queryText, q.queryTags, q.queryEntities)
            l.list(hashes(qFeatures))
            // termos BM25 na ordem do SortedSet da JVM (ordem da soma)
            l.list(hashes((EpisodicIndex.terms(q.queryText) + q.queryTags.map { it.lowercase() }).toSortedSet()))
            l.list(hashes(q.queryTags))
            l.list(hashes(q.queryEntities))
            for (n in q.nodes) {
                val cache = n.nativeCache()
                l.add(n.id)
                val bits = n.bits ?: nativeSimHash(cache.features).also { n.bits = it }
                for (w in 0 until EpisodicIndex.WORDS) l.add(bits[w])
                l.add(n.createdTick); l.add(n.hawkesLastTick)
                l.add(if (n.linkedMemoryIds.any { it in q.activeWorkingIds }) 1L else 0L)
                l.list(cache.features); l.list(cache.docTerms); l.list(cache.tags); l.list(cache.entities)
                l.add(n.recentAccessTicks.size.toLong())
                for (t in n.recentAccessTicks) l.add(t)
                d.add(n.salience); d.add(n.confidence); d.add(n.qUtility); d.add(n.hawkesExcitation)
            }
            maxChosen += q.topK.coerceAtLeast(0)
        }
        val exc = DoubleArray(maxChosen)
        val out = nativeRetrieve(l.toArray(), d.toArray(), queries.size, exc)
        val res = ArrayList<Retrieval>(queries.size)
        var p = 0
        var e = 0
        for (q in queries) {
            val stage1 = out[p++]
            val n = out[p++]
            val chosen = ArrayList<AssociativeMemoryNode>(n)
            val ex = DoubleArray(n)
            for (k in 0 until n) { chosen += q.nodes[out[p++]]; ex[k] = exc[e++] }
            res += Retrieval(stage1, chosen, ex)
        }
        return res
    }

    // ------------------------------------------------------------------ ledger e cadeia
    /** Recursos cuja soma em micro-unidades não é zero (ou estourou 64 bits) — verificado no kernel. */
    fun ledgerViolations(ledger: StockFlowDoubleEntryLedger): List<String> {
        val resources = ledger.resourceTypes().toList()
        if (resources.isEmpty()) return emptyList()
        val index = resources.withIndex().associate { it.value to it.index }
        val (balances, res) = ledger.packMicroBalances(index)
        val bad = nativeLedgerZeroSum(balances, res, resources.size)
        return resources.filterIndexed { i, _ -> bad[i] != 0 }
    }

    const val REASON_OK = 0
    const val REASON_EMPTY = 1
    const val REASON_ANCHOR = 2
    const val REASON_GAP = 3
    const val REASON_PREV = 4
    const val REASON_TICK = 5
    const val REASON_HASH = 6

    /** Verificação da cadeia prev_hash/event_hash em lote no kernel nativo (SHA-256 C++). */
    fun verifyChain(events: List<CanonicalEvent>, anchorPrevHash: String?): WorldStateHashChain.Report {
        if (events.isEmpty()) return WorldStateHashChain.Report(true, 0, null, "vazio", anchorPrevHash ?: GENESIS_HASH)
        val n = events.size
        val r = nativeVerifyChain(
            LongArray(n) { events[it].seq }, LongArray(n) { events[it].tick }, IntArray(n) { events[it].rulesVersion },
            Array(n) { events[it].type }, Array(n) { events[it].actorId }, Array(n) { events[it].payload },
            Array(n) { events[it].prevHash }, Array(n) { events[it].hash }, anchorPrevHash, GENESIS_HASH
        )
        val reason = r[0].toInt()
        val checked = r[1].toInt()
        val bad = r[2]
        val anchor = anchorPrevHash ?: if (events.first().seq == 1L) GENESIS_HASH else events.first().prevHash
        val head = when (r[3].toInt()) {
            0 -> anchor
            1 -> events[(checked - 1).coerceAtLeast(0)].hash
            2 -> events[checked.coerceIn(0, n - 1)].prevHash
            else -> events.last().hash
        }
        val prevSeq = if (checked > 0) events[checked - 1].seq else 0L
        return when (reason) {
            REASON_OK -> WorldStateHashChain.Report(true, checked, null, "íntegra", head)
            REASON_ANCHOR -> WorldStateHashChain.Report(false, 0, bad, "prev_hash do primeiro evento não confere com a âncora", head)
            REASON_GAP -> WorldStateHashChain.Report(false, checked, bad, "lacuna de sequência após $prevSeq", head)
            REASON_PREV -> WorldStateHashChain.Report(false, checked, bad, "prev_hash não encadeia com o evento $prevSeq", head)
            REASON_TICK -> WorldStateHashChain.Report(false, checked, bad, "tick regrediu", head)
            REASON_HASH -> WorldStateHashChain.Report(false, checked, bad, "event_hash adulterado", head)
            else -> WorldStateHashChain.Report(false, checked, bad, "código de verificação desconhecido $reason", head)
        }
    }
}

/** Vetor de Long crescente sem boxing (empacotamento dos lotes JNI). */
class LongArrayBuilder(capacity: Int = 64) {
    private var a = LongArray(capacity.coerceAtLeast(4))
    var size = 0
        private set
    fun add(v: Long) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    fun addAll(v: LongArray) {
        if (size + v.size > a.size) a = a.copyOf(maxOf(a.size * 2, size + v.size))
        System.arraycopy(v, 0, a, size, v.size); size += v.size
    }
    fun toArray(): LongArray = a.copyOf(size)
}

/** Vetor de Double crescente sem boxing. */
class DoubleArrayBuilder(capacity: Int = 64) {
    private var a = DoubleArray(capacity.coerceAtLeast(4))
    var size = 0
        private set
    fun add(v: Double) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    fun toArray(): DoubleArray = a.copyOf(size)
}
