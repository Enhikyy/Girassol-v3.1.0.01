package com.enhikyy.girassol

import com.enhikyy.girassol.core.cognition.ActiveInferencePianoOrchestrator
import com.enhikyy.girassol.core.memory.AssociativeMemoryNode
import com.enhikyy.girassol.core.memory.EpisodicIndex
import com.enhikyy.girassol.core.memory.FourLayerMemoryAndSleepEngine
import com.enhikyy.girassol.core.memory.MemoryLayer
import com.enhikyy.girassol.core.util.SplitMix64
import com.enhikyy.girassol.core.util.StreamSeeds
import com.enhikyy.girassol.core.world.CanonicalEvent
import com.enhikyy.girassol.core.world.GENESIS_HASH
import com.enhikyy.girassol.core.world.NativeCognition
import com.enhikyy.girassol.core.world.NativeWorldTick
import com.enhikyy.girassol.core.world.StockFlowDoubleEntryLedger
import com.enhikyy.girassol.core.world.WorldCore
import com.enhikyy.girassol.core.world.WorldStateHashChain
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * v3.8 — o núcleo cognitivo C++20 (percepção, memória 2 estágios, G(π), política, F, ledger e
 * cadeia SHA-256) contra implementações de referência em Kotlin (as fórmulas do v3.7), bit a bit.
 */
class NativeCognitionTest {

    @Test
    fun paritySignatureMatchesNativeTests() {
        assertEquals(NativeCognition.PARITY_GOLDEN, NativeCognition.nativeParitySignature())
    }

    @Test
    fun streamSeedParityWithEmojiAndNul() {
        // v3.8: o jstr do WorldTick usava UTF-8 modificado (emoji em 6 bytes, U+0000 em C0 80)
        for (id in listOf("C1", "C🌻7", "Ana\u0000B", "ção-ü", "\uD83D\uDE00\uD83C\uDF3B")) {
            assertEquals("semente de '$id'", StreamSeeds.derive(99L, id, 1234L, "decisão☀"),
                NativeWorldTick.nativeStreamSeed(99L, id, 1234L, "decisão☀"))
        }
    }

    // ------------------------------------------------------------- percepção
    @Test
    fun perceptionMatchesReference() {
        val rng = SplitMix64(7L)
        val w = 20; val h = 20
        val food = FloatArray(w * h) { rng.nextFloat() * 14f }
        val danger = FloatArray(w * h) { rng.nextFloat() * 7f }
        val n = 50
        val needs = DoubleArray(n * 6) { rng.nextDouble() }
        val codes = IntArray(n * 6) { it % 6 }
        val xs = IntArray(n) { rng.nextInt(45) }
        val zs = IntArray(n) { rng.nextInt(45) }
        for (drought in listOf(false, true)) {
            val sal = DoubleArray(n * 3)
            val out = NativeCognition.nativePerceive(needs, codes, xs, zs, food, danger, w, h, 2f, drought, sal)
            for (i in 0 until n) {
                fun read(g: FloatArray, x: Int, z: Int): Float {
                    val gx = (x.toFloat() / 2f).toInt().coerceIn(0, w - 1)
                    val gz = (z.toFloat() / 2f).toInt().coerceIn(0, h - 1)
                    return g[gz * w + gx]
                }
                val ref = mutableListOf<Pair<Int, Double>>()
                for (k in 0 until 6) ref += k to (1.0 - needs[i * 6 + k]) * 1.1
                val f = read(food, xs[i], zs[i]); val d = read(danger, xs[i], zs[i])
                if (f > 0.5f) ref += 6 to (f / 10f).toDouble().coerceAtMost(0.9)
                if (d > 0.5f) ref += 7 to (d / 5f).toDouble().coerceAtMost(1.0)
                if (drought) ref += 8 to 0.5
                val win = ref.filter { it.second > 0.15 }.sortedByDescending { it.second }.take(3)
                assertEquals(win.size, out[i * 4])
                for ((k, p) in win.withIndex()) {
                    assertEquals(p.first, out[i * 4 + 1 + k])
                    assertEquals(p.second.toRawBits(), sal[i * 3 + k].toRawBits())
                }
            }
        }
    }

    // ------------------------------------------------------------- inferência ativa
    private fun entropyOf(u: Double): Double {
        val p = (0.5 * u).coerceIn(1e-6, 0.5)
        return -(p * ln(p) + (1 - p) * ln(1 - p))
    }

    /** Fórmula Kotlin do v3.7 (WorldCore.evaluate) para G(π). */
    private fun referenceEfe(d: DoubleArray, i: IntArray, danger: FloatArray, social: FloatArray, w: Int, h: Int): Pair<Double, DoubleArray> {
        val projected = DoubleArray(6) { k ->
            val g = d[12 + k]
            val gain = g * (if (g > 0) d[18] * (0.4 + 0.6 * d[19] / 0.6).coerceAtMost(1.4) else 1.0)
            (d[k] - d[6 + k] * (i[0] + i[1]) + gain).coerceIn(0.0, 1.0)
        }
        val stress = 0.8 + 0.4 * d[21]
        val weights = DoubleArray(6) { k ->
            val deficit = 1.0 - projected[k]
            val wk = if (k == 0 || k == 1) 1.6 else 1.0
            wk * stress * (if (deficit > 0.7) 1.8 else 1.0)
        }
        var ew = d[24] * (0.5 + d[23])
        if (i[5] and NativeCognition.FLAG_EXPLORE != 0) ew *= 1.2
        val terms = ActiveInferencePianoOrchestrator.computeExpectedFreeEnergyKl(
            projected = projected, weights = weights, preferred = 0.95, outcomeUncertainty = d[20],
            priorEntropy = entropyOf(d[20]), posteriorExpectedEntropy = entropyOf(d[20] * 0.5),
            epistemicWeight = ew * 0.15 * (0.8 + 0.4 * d[22]), neuralBaseCost = d[25] * 0.05, socTempC = d[26])
        var efe = terms.total - d[27] - 0.12 * d[28] - d[29] + 0.002 * i[0]
        fun read(g: FloatArray, x: Int, z: Int): Float {
            val gx = (x.toFloat() / 2f).toInt().coerceIn(0, w - 1)
            val gz = (z.toFloat() / 2f).toInt().coerceIn(0, h - 1)
            return g[gz * w + gx]
        }
        val hasObj = i[5] and NativeCognition.FLAG_TARGET != 0
        val social0 = i[5] and NativeCognition.FLAG_SOCIAL != 0
        if (hasObj && read(danger, i[2], i[3]) > 2f) efe += 0.05
        if (social0 && hasObj) efe -= 0.02 * read(social, i[2], i[3]).coerceAtMost(5f)
        if (social0) efe -= 0.04 * d[30]
        efe -= d[33] * d[31].coerceIn(-1.0, 1.0)
        if (i[5] and NativeCognition.FLAG_LORA != 0) efe -= d[34] * d[32].coerceIn(-1.0, 1.0)
        return efe to projected
    }

    @Test
    fun activeInferenceBatchMatchesReferenceBitForBit() {
        val rng = SplitMix64(2026L)
        val w = 16; val h = 16
        val danger = FloatArray(w * h) { rng.nextFloat() * 5f }
        val social = FloatArray(w * h) { rng.nextFloat() * 9f }
        val nCit = 30
        val counts = IntArray(nCit) { if (it % 7 == 3) 0 else 1 + rng.nextInt(12) }
        val total = counts.sum()
        val candD = DoubleArray(total * NativeCognition.CAND_D)
        val candI = IntArray(total * NativeCognition.CAND_I)
        val offsets = IntArray(nCit + 1)
        val citD = DoubleArray(nCit * NativeCognition.CIT_D)
        val current = IntArray(nCit) { if (it % 3 == 0) -1 else rng.nextInt(14) }
        var j = 0
        for (c in 0 until nCit) {
            offsets[c] = j
            val needs = DoubleArray(6) { rng.nextDouble() }
            for (k in 0 until 6) citD[c * 7 + k] = needs[k]
            citD[c * 7 + 6] = rng.nextDouble()
            repeat(counts[c]) {
                val o = j * NativeCognition.CAND_D
                for (k in 0 until 6) { candD[o + k] = needs[k]; candD[o + 6 + k] = rng.nextDouble() * 0.003; candD[o + 12 + k] = rng.nextDouble() * 0.6 - 0.2 }
                for (k in 18 until 35) candD[o + k] = rng.nextDouble() * (if (k == 26) 60.0 else 1.0)
                candD[o + 31] = rng.nextDouble() * 4 - 2; candD[o + 32] = rng.nextDouble() * 4 - 2
                candD[o + 33] = 0.05; candD[o + 34] = 0.08
                val p = j * NativeCognition.CAND_I
                candI[p] = rng.nextInt(40); candI[p + 1] = 10 + rng.nextInt(80)
                candI[p + 2] = rng.nextInt(40); candI[p + 3] = rng.nextInt(40)
                candI[p + 4] = rng.nextInt(14); candI[p + 5] = rng.nextInt(16)
                j++
            }
        }
        offsets[nCit] = j
        val efe = DoubleArray(total); val proj = DoubleArray(total * 6); val order = IntArray(total); val vfe = DoubleArray(nCit)
        val chosen = NativeCognition.nativeCognitionBatch(candD, candI, offsets, citD, current, danger, social, w, h, 2f,
            0.95, 0.05, 6.0, efe, proj, order, vfe)
        for (c in 0 until nCit) {
            val a = offsets[c]; val b = offsets[c + 1]
            val refs = (a until b).map { k ->
                referenceEfe(candD.copyOfRange(k * 35, k * 35 + 35), candI.copyOfRange(k * 6, k * 6 + 6), danger, social, w, h)
            }
            for (k in a until b) {
                assertEquals("G candidato $k", refs[k - a].first.toRawBits(), efe[k].toRawBits())
                for (n in 0 until 6) assertEquals(refs[k - a].second[n].toRawBits(), proj[k * 6 + n].toRawBits())
            }
            val sortedRef = (0 until b - a).sortedWith(compareBy<Int> { refs[it].first }.thenBy { candI[(a + it) * 6 + 4] })
            assertArrayEquals(sortedRef.toIntArray(), order.copyOfRange(a, b))
            val keys = sortedRef.map { "A" + candI[(a + it) * 6 + 4] }
            val idxRef = if (a == b) -1 else ActiveInferencePianoOrchestrator.selectPolicyWithHysteresis(
                keys, sortedRef.map { refs[it].first }, if (current[c] >= 0) "A${current[c]}" else null, 0.05, 6.0, citD[c * 7 + 6])
            assertEquals("política do cidadão $c", idxRef, chosen[c])
            val needs = DoubleArray(6) { citD[c * 7 + it] }
            val deficits = needs.map { (1.0 - it) + 1e-3 }
            val sum = deficits.sum()
            val refVfe = ActiveInferencePianoOrchestrator.computeVariationalFreeEnergy(
                deficits.map { it / sum }.toDoubleArray(), DoubleArray(6) { 1.0 / 6 }, needs.map { it.coerceAtLeast(0.02) }.toDoubleArray())
            assertEquals(refVfe.toRawBits(), vfe[c].toRawBits())
        }
    }

    // ------------------------------------------------------------- memória
    private fun node(rng: SplitMix64, id: Long, words: List<String>): AssociativeMemoryNode {
        val content = List(4) { words[rng.nextInt(words.size)] }.joinToString(" ") + " ${rng.nextInt(9)}${rng.nextInt(9)}"
        val tags = setOf(listOf("COMER", "COLHER", "FOME", "ENERGIA", "SOCIAL")[rng.nextInt(5)])
        val ents = if (rng.nextInt(3) == 0) setOf("H${rng.nextInt(3)}") else emptySet()
        return AssociativeMemoryNode(
            id = id, ownerAgentId = "C1", layer = MemoryLayer.EPISODIC, content = content, tags = tags, entityIds = ents,
            salience = rng.nextDouble(), confidence = rng.nextDouble(), sourceEventId = null, sourceAgentId = null,
            createdTick = rng.nextInt(2000).toLong(), qUtility = rng.nextDouble() * 2 - 1,
            hawkesExcitation = rng.nextDouble(), hawkesLastTick = rng.nextInt(2000).toLong()
        ).also { n -> if (rng.nextInt(2) == 0) repeat(1 + rng.nextInt(5)) { n.recentAccessTicks += n.createdTick + rng.nextInt(300) } }
    }

    /** Recuperação Kotlin do v3.7 (referência), sem efeitos colaterais. */
    private fun referenceRetrieve(nodes: List<AssociativeMemoryNode>, now: Long, text: String, qTags: Set<String>, qEnts: Set<String>, topK: Int): Pair<List<Long>, List<Double>> {
        val qf = EpisodicIndex.features(text, qTags, qEnts)
        val qBits = EpisodicIndex.simHash(qf)
        val stage1 = nodes.indices.sortedWith(compareBy<Int> { EpisodicIndex.hamming(qBits, nodes[it].binaryEmbedding()) }.thenByDescending { nodes[it].id })
            .take(EpisodicIndex.STAGE1_K).map { nodes[it] }
        val qDense = EpisodicIndex.dense(qf)
        val cos = DoubleArray(stage1.size) { EpisodicIndex.cosine(qDense, EpisodicIndex.dense(stage1[it].features())).toDouble() }
        val corpus = nodes.map { EpisodicIndex.terms(it.content) + it.tags.map { t -> t.lowercase() } }
        val bm25 = EpisodicIndex.bm25(EpisodicIndex.terms(text) + qTags.map { it.lowercase() },
            stage1.map { EpisodicIndex.terms(it.content) + it.tags.map { t -> t.lowercase() } }, corpus)
        val composite = DoubleArray(stage1.size) { i ->
            val node = stage1[i]
            val normAct = 1.0 / (1.0 + exp(-node.baseLevelActivation(now)))
            val tagScore = if (qTags.isEmpty()) 0.0 else node.tags.intersect(qTags).size.toDouble() / qTags.size
            val entScore = if (qEnts.isEmpty()) 0.0 else node.entityIds.intersect(qEnts).size.toDouble() / qEnts.size
            0.30 * normAct + 0.25 * (node.salience * node.confidence) + 0.25 * (0.55 * tagScore + 0.45 * entScore).coerceIn(0.0, 1.0) +
                0.20 * (node.qUtility + 1.0) * 0.5
        }
        val hawkes = DoubleArray(stage1.size) { EpisodicIndex.hawkesIntensity(stage1[it].hawkesExcitation, stage1[it].hawkesLastTick, now) }
        val fused = EpisodicIndex.rrf(listOf(cos, bm25, composite, hawkes), stage1.size)
        val chosen = stage1.indices.sortedWith(compareByDescending<Int> { fused[it] }.thenByDescending { stage1[it].id }).take(topK).map { stage1[it] }
        val exc = chosen.map { n ->
            n.hawkesExcitation * exp(-EpisodicIndex.HAWKES_BETA * (now - n.hawkesLastTick).coerceAtLeast(0L)) +
                EpisodicIndex.HAWKES_ALPHA * n.salience.coerceIn(0.0, 1.0)
        }
        return chosen.map { it.id } to exc
    }

    @Test
    fun twoStageRetrievalMatchesReferenceBitForBit() {
        val rng = SplitMix64(31337L)
        val words = listOf("horta", "praça", "comer", "sucesso", "falha", "usina", "boato", "colheita", "água", "escola", "ção", "🌻")
        repeat(25) { round ->
            val nodes = List(5 + rng.nextInt(120)) { node(rng, it + 1L + round * 1000L, words) }
            val text = "Fome=0.${rng.nextInt(99)} " + List(3) { words[rng.nextInt(words.size)] }.joinToString(" ")
            val tags = setOf("FOME", "ESTIGMERGIA", "FOOD_SCENT").filter { rng.nextInt(2) == 0 }.toSet()
            val ents = setOf("H1", "FOOD_SCENT").filter { rng.nextInt(2) == 0 }.toSet()
            val now = 2400L + rng.nextInt(500)
            val (refIds, refExc) = referenceRetrieve(nodes, now, text, tags, ents, 4)
            val r = NativeCognition.retrieveBatch(listOf(NativeCognition.MemoryQuery(nodes, now, text, tags, ents, 4, emptySet()))).single()
            assertEquals("rodada $round", refIds, r.chosen.map { it.id })
            assertEquals(refExc.map { it.toRawBits() }, r.newExcitation.map { it.toRawBits() })
            assertEquals(minOf(24, nodes.size), r.stage1)
        }
    }

    @Test
    fun retrieveTwoStageAppliesHawkesAndWorkingMemory() {
        val rng = SplitMix64(5L)
        val nodes = List(30) { node(rng, it + 1L, listOf("horta", "comer", "usina")) }
        val engine = FourLayerMemoryAndSleepEngine()
        val r = engine.retrieveTwoStage(nodes, 3000L, "horta comer", setOf("COMER"), emptySet(), topK = 4)
        assertEquals(4, r.memories.size)
        assertEquals(30, r.corpusSize)
        for (m in r.memories) {
            assertEquals(3000L, m.recentAccessTicks.last())
            assertEquals(3000L, m.hawkesLastTick)
        }
        assertEquals(r.memories.map { it.id }.toSet(), engine.activeWorkingIds())
        assertEquals(0, engine.retrieveTwoStage(emptyList(), 1L, "x", emptySet(), emptySet()).corpusSize)
    }

    // ------------------------------------------------------------- ledger e cadeia
    @Test
    fun ledgerZeroSumIsCheckedNatively() {
        val ok = StockFlowDoubleEntryLedger()
        ok.mintInitialEndowment("C1", "FOOD", 5.0, 1, "h")
        ok.transfer("C1", "C2", "FOOD", 2.5, 2, "h")
        assertTrue(NativeCognition.ledgerViolations(ok).isEmpty())
        val broken = StockFlowDoubleEntryLedger(balancesMicro = mutableMapOf("C1" to mutableMapOf("FOOD" to 7L, "CREDITO" to 0L)))
        assertEquals(listOf("FOOD"), NativeCognition.ledgerViolations(broken))
        val overflow = StockFlowDoubleEntryLedger(balancesMicro = mutableMapOf(
            "A" to mutableMapOf("X" to Long.MAX_VALUE), "B" to mutableMapOf("X" to 1L), "C" to mutableMapOf("X" to Long.MIN_VALUE)))
        // a JVM daria soma 0 (estouro silencioso); o kernel acusa o estouro
        assertEquals(listOf("X"), NativeCognition.ledgerViolations(overflow))
    }

    private fun chain(n: Int): List<CanonicalEvent> {
        var prev = GENESIS_HASH
        return (1..n).map { i ->
            val actor = if (i % 3 == 0) null else "C🌻$i"
            val payload = "x=$i;nome=Conceição ☀"
            val h = WorldStateHashChain.computeHash(i.toLong(), i / 2L, 38, "TESTE", actor, payload, prev)
            CanonicalEvent(i.toLong(), i / 2L, 38, "TESTE", actor, payload, prev, h).also { prev = h }
        }
    }

    @Test
    fun hashChainVerifiedNatively() {
        val ev = chain(40)
        val ok = WorldStateHashChain.verify(ev)
        assertTrue(ok.reason, ok.ok)
        assertEquals(40, ok.checked)
        assertEquals(ev.last().hash, ok.headHash)
        val tampered = ev.toMutableList().also { it[9] = it[9].copy(payload = "adulterado") }
        val bad = WorldStateHashChain.verify(tampered)
        assertFalse(bad.ok)
        assertEquals(10L, bad.firstBadSeq)
        assertEquals("event_hash adulterado", bad.reason)
        assertEquals(ev[8].hash, bad.headHash)
        val gap = ev.toMutableList().also { it.removeAt(5) }
        val g = WorldStateHashChain.verify(gap)
        assertEquals("lacuna de sequência após 5", g.reason)
        val window = ev.subList(10, 20)
        assertTrue(WorldStateHashChain.verify(window, ev[9].hash).ok)
        val anchor = WorldStateHashChain.verify(window, GENESIS_HASH)
        assertEquals("prev_hash do primeiro evento não confere com a âncora", anchor.reason)
        assertTrue(WorldStateHashChain.verify(emptyList()).ok)
    }

    @Test
    fun worldRunsWithNativeCognitionWithoutViolations() {
        val a = WorldCore.create(4242L, 12)
        val b = WorldCore.create(4242L, 12)
        val all = mutableListOf<CanonicalEvent>()
        repeat(600) {
            val ra = a.step()
            val rb = b.step()
            assertTrue("violações: ${ra.invariantViolations}", ra.invariantViolations.isEmpty())
            assertEquals(ra.events.map { it.hash }, rb.events.map { it.hash })
            all += ra.events
        }
        assertEquals(a.state.lastEventHash, b.state.lastEventHash)
        assertTrue("consultas de memória pelo kernel", a.state.retrievalQueries > 0)
        val rep = WorldStateHashChain.verify(all)
        assertTrue(rep.reason, rep.ok)
        assertEquals(a.state.lastEventHash, rep.headHash)
    }

    @Test
    fun hawkesPowSanity() {
        // garante que a referência e o kernel usam a mesma ativação de nível base
        val n = AssociativeMemoryNode(1, "C", MemoryLayer.EPISODIC, "a", emptySet(), emptySet(), salience = 1.0, confidence = 1.0,
            sourceEventId = null, sourceAgentId = null, createdTick = 0)
        assertEquals(ln(100.0.pow(-0.5)), n.baseLevelActivation(100), 0.0)
    }
}
