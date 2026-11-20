package com.enhikyy.girassol

import com.enhikyy.girassol.core.biology.CfcNeuromodulator
import com.enhikyy.girassol.core.cognition.ActiveInferencePianoOrchestrator
import com.enhikyy.girassol.core.cognition.NeuralProtocol
import com.enhikyy.girassol.core.forge.PersonalAdapter
import com.enhikyy.girassol.core.forge.TiesMerging
import com.enhikyy.girassol.core.memory.AssociativeMemoryNode
import com.enhikyy.girassol.core.memory.EpisodicIndex
import com.enhikyy.girassol.core.memory.EpistemicImmunity
import com.enhikyy.girassol.core.memory.FourLayerMemoryAndSleepEngine
import com.enhikyy.girassol.core.memory.MemoryLayer
import com.enhikyy.girassol.core.memory.SubjectiveOpinion
import com.enhikyy.girassol.core.ports.ParityVectors
import com.enhikyy.girassol.core.util.SplitMix64
import com.enhikyy.girassol.core.world.CrowdNavigation
import com.enhikyy.girassol.core.world.DistrictNavMesh
import com.enhikyy.girassol.core.world.OrcaAgent
import com.enhikyy.girassol.core.world.Pathfinding
import com.enhikyy.girassol.core.world.TileType
import com.enhikyy.girassol.core.world.Vec2
import com.enhikyy.girassol.core.world.WorldCore
import com.enhikyy.girassol.core.world.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

class V34EnginesTest {

    private fun node(id: Long, content: String, tags: Set<String>, tick: Long = 0L) = AssociativeMemoryNode(
        id = id, ownerAgentId = "C1", layer = MemoryLayer.EPISODIC, content = content, tags = tags, entityIds = emptySet(),
        salience = 0.5, confidence = 1.0, sourceEventId = null, sourceAgentId = null, createdTick = tick, verifiedByWorldCore = true
    )

    @Test fun simHashTem256BitsEHammingSimetrico() {
        val a = EpisodicIndex.simHash(listOf("colher", "horta", "#sucesso"))
        val b = EpisodicIndex.simHash(listOf("colher", "horta", "#falha"))
        val c = EpisodicIndex.simHash(listOf("usina", "energia", "reparo", "#manter_usina"))
        assertEquals(4, a.size)
        assertEquals(0, EpisodicIndex.hamming(a, a))
        assertEquals(EpisodicIndex.hamming(a, b), EpisodicIndex.hamming(b, a))
        assertTrue("textos parecidos ficam mais próximos", EpisodicIndex.hamming(a, b) < EpisodicIndex.hamming(a, c))
    }

    @Test fun assinaturaDeParidadeBinariaFixa() {
        assertEquals(EpisodicIndex.PARITY_GOLDEN, EpisodicIndex.paritySignature())
    }

    @Test fun hawkesRecursivoIgualASomaExplicita() {
        val n = node(1, "x", setOf("A"))
        val events = listOf(10L to 0.4, 50L to 0.9, 400L to 0.3)
        for ((t, v) in events) EpisodicIndex.hawkesExcite(n, t, v)
        val now = 900L
        val explicit = EpisodicIndex.HAWKES_MU0 + events.sumOf { (t, v) -> EpisodicIndex.HAWKES_ALPHA * v * exp(-EpisodicIndex.HAWKES_BETA * (now - t)) }
        assertEquals(explicit, EpisodicIndex.hawkesIntensity(n.hawkesExcitation, n.hawkesLastTick, now), 1e-12)
    }

    @Test fun bm25EPrefereDocumentoComTermoRaro() {
        val corpus = listOf(listOf("comer", "horta"), listOf("comer", "praca"), listOf("usina", "reparo"))
        val s = EpisodicIndex.bm25(listOf("usina"), corpus, corpus)
        assertTrue(s[2] > s[0] && s[0] == 0.0)
        val rrf = EpisodicIndex.rrf(listOf(doubleArrayOf(0.1, 0.9, 0.5), doubleArrayOf(0.2, 0.8, 0.9)), 3)
        assertTrue(rrf[1] > rrf[0] && rrf[2] > rrf[0])
    }

    @Test fun recuperacaoEmDoisEstagiosEncontraMemoriaRelevante() {
        val engine = FourLayerMemoryAndSleepEngine()
        val mem = (1L..60L).map { i -> node(i, "Explorar praça $i: sucesso explorou", setOf("EXPLORAR", "PRACA")) }.toMutableList()
        mem += node(99, "Manter usina em Usina U3: sucesso +20 energia urbana", setOf("MANTER_USINA", "USINA"))
        val r = engine.retrieveTwoStage(mem, 1000L, "usina energia", setOf("MANTER_USINA"), emptySet(), topK = 3, workingCapacity = 7)
        assertEquals(EpisodicIndex.STAGE1_K, r.stage1Candidates)
        assertEquals(99L, r.memories.first().id)
        assertTrue(r.memories.first().hawkesExcitation > 0.0)
        assertTrue(engine.workingMemorySize() in 1..9)
    }

    @Test fun memoriaDeTrabalhoRespeita7Mais2() {
        val engine = FourLayerMemoryAndSleepEngine()
        val mem = (1L..40L).map { node(it, "evento $it", setOf("T$it")) }
        repeat(5) { engine.retrieveTwoStage(mem, 100L + it, "evento", setOf("T$it"), emptySet(), topK = 4, workingCapacity = 12) }
        assertTrue(engine.workingMemorySize() <= 9)
        engine.clearWorkingMemory()
        repeat(5) { engine.retrieveTwoStage(mem, 100L + it, "evento", setOf("T$it"), emptySet(), topK = 4, workingCapacity = 2) }
        assertTrue(engine.workingMemorySize() <= 5)
    }

    @Test fun sharpWaveRippleReplayPropagaValorParaTras() {
        val a = node(1, "a", setOf("X")); val b = node(2, "b", setOf("X")); val c = node(3, "c", setOf("X"))
        a.linkedMemoryIds += 2; b.linkedMemoryIds += setOf(1L, 3L); c.linkedMemoryIds += 2
        c.qUtility = 1.0
        val updates = FourLayerMemoryAndSleepEngine().sharpWaveRippleReplay(listOf(a, b, c))
        assertTrue(updates >= 2)
        assertTrue(b.qUtility > 0.0 && a.qUtility > 0.0 && b.qUtility > a.qUtility)
    }

    @Test fun fusaoCumulativaParaFontesIndependentesEMediaParaMesmaFonte() {
        val w = SubjectiveOpinion(0.6, 0.1, 0.3)
        val cum = SubjectiveOpinion.cumulativeFuse(w, w)
        val avg = SubjectiveOpinion.averagingFuse(w, w)
        assertTrue("independentes reduzem a incerteza", cum.uncertainty < w.uncertainty)
        assertEquals("mesma fonte não conta duas vezes", w.belief, avg.belief, 1e-9)
        assertEquals(w.uncertainty, avg.uncertainty, 1e-9)
        val fused = EpistemicImmunity.fuseReports(listOf("A" to w, "A" to w, "B" to w))
        val manual = SubjectiveOpinion.cumulativeFuse(SubjectiveOpinion.averagingFuse(w, w), w)
        assertEquals(manual.belief, fused.belief, 1e-9)
    }

    @Test fun cfcInvarianteADeltaT() {
        val net = CfcNeuromodulator(CfcNeuromodulator.seedFor(42L, "C7"))
        val input = DoubleArray(CfcNeuromodulator.IN) { 0.1 * it - 0.3 }
        val x1 = DoubleArray(CfcNeuromodulator.N) { 0.05 * (it % 7) - 0.1 }
        val x2 = x1.copyOf()
        net.advance(x1, input, 0.7); net.advance(x1, input, 2.3)
        net.advance(x2, input, 3.0)
        for (i in x1.indices) assertEquals(x2[i], x1[i], 1e-12)
        val r = net.readout(x1)
        assertTrue(r.locomotion in 0.75..1.25 && r.cortisolTarget in 0.0..1.0)
        val h = CfcNeuromodulator.hormoneStep(CfcNeuromodulator.hormoneStep(0.2, 0.8, 10.0), 0.8, 15.0)
        assertEquals(CfcNeuromodulator.hormoneStep(0.2, 0.8, 25.0), h, 1e-12)
    }

    @Test fun efeNaFormaKlPrefereNecessidadesSatisfeitas() {
        val w = DoubleArray(3) { 1.0 }
        val good = ActiveInferencePianoOrchestrator.computeExpectedFreeEnergyKl(doubleArrayOf(0.95, 0.9, 0.92), w, 0.95, 0.2, 0.6, 0.3, 0.1, 0.0, 30.0)
        val bad = ActiveInferencePianoOrchestrator.computeExpectedFreeEnergyKl(doubleArrayOf(0.2, 0.3, 0.1), w, 0.95, 0.2, 0.6, 0.3, 0.1, 0.0, 30.0)
        assertTrue(good.total < bad.total)
        assertEquals(0.0, ActiveInferencePianoOrchestrator.bernoulliKl(0.95, 0.95), 1e-12)
        val cool = ActiveInferencePianoOrchestrator.neuralThermalCost(0.05, 30.0)
        val hot = ActiveInferencePianoOrchestrator.neuralThermalCost(0.05, 46.0)
        assertTrue("C_neural cresce com a temperatura do SoC", hot > 2 * cool)
    }

    @Test fun tiesMergingTrimEleicaoDeSinalEMediaDisjunta() {
        val a = floatArrayOf(0.9f, -0.1f, 0.4f, 0.0f)
        val b = floatArrayOf(-0.2f, -0.6f, 0.5f, 0.05f)
        val m = TiesMerging.merge(listOf(a, b), density = 0.5)
        assertEquals(0.9f, m[0], 1e-6f)    // a mantém 0.9 (top-2); b descarta −0.2 → só a vota
        assertEquals(-0.6f, m[1], 1e-6f)   // só b mantém −0.6
        assertEquals(0.45f, m[2], 1e-6f)   // ambos concordam: média disjunta
        assertEquals(0.0f, m[3], 1e-6f)
        val w = FloatArray(18)
        val x = FloatArray(18).also { it[2] = 1f; it[17] = 1f }
        repeat(50) { PersonalAdapter.train(w, x, 0.8f) }
        assertEquals(0.8f, PersonalAdapter.predict(w, x), 0.02f)
    }

    @Test fun orcaEvitaColisaoFrontal() {
        var a = Vec2(0.0, 0.0); var b = Vec2(4.0, 0.02)
        var va = Vec2.ZERO; var vb = Vec2.ZERO
        var minDist = Double.MAX_VALUE
        repeat(12) {
            val v = CrowdNavigation.stepRvo2OrcaVelocities(listOf(
                OrcaAgent(a, va, Vec2(0.5, 0.0), 0.3, 0.5), OrcaAgent(b, vb, Vec2(-0.5, 0.0), 0.3, 0.5)), 1.0)
            va = v[0]; vb = v[1]
            a += va; b += vb
            minDist = minOf(minDist, (a - b).length())
        }
        assertTrue("distância mínima $minDist ≥ 2r", minDist >= 0.6 - 1e-6)
        assertTrue("ambos avançaram", a.x > 2.0 && b.x < 2.0)
    }

    @Test fun circuloNuncaAtravessaCaixa() {
        val p = CrowdNavigation.resolveCircleAABB(1.1, 1.5, 0.3, 1.0, 1.0, 2.0, 2.0)
        assertTrue(p.x <= 0.7 + 1e-6)
        val q = CrowdNavigation.resolveCircleAABB(0.85, 1.5, 0.3, 1.0, 1.0, 2.0, 2.0)
        assertTrue(q.x <= 0.7 + 1e-6)
        val far = CrowdNavigation.resolveCircleAABB(0.2, 0.2, 0.3, 1.0, 1.0, 2.0, 2.0)
        assertEquals(0.2, far.x, 0.0)
        // movimentos aleatórios numa grade com edifícios: o centro nunca entra em tile bloqueado
        val g = ParityVectors.navGrid(7L)
        val rng = SplitMix64(9L)
        var px = 0.5; var pz = 0.5
        for (i in 0 until g.w * g.w) if (Pathfinding.walkable(g.tiles[i])) { px = i % g.w + 0.5; pz = i / g.w + 0.5; break }
        repeat(4000) {
            val r = CrowdNavigation.resolveAgainstGrid(g.w, g.tiles, px + (rng.nextDouble() - 0.5), pz + (rng.nextDouble() - 0.5), 0.3, emptySet())
            px = r.x; pz = r.y
            val idx = floor(pz).toInt() * g.w + floor(px).toInt()
            assertTrue("centro em tile bloqueado em ($px,$pz)", Pathfinding.walkable(g.tiles[idx]))
        }
    }

    @Test fun clearanceDistanciaDeChebyshev() {
        val w = 7
        val tiles = IntArray(w * w) { TileType.GRAMA.ordinal }
        tiles[3 * w + 3] = TileType.EDIFICIO.ordinal
        val c = DistrictNavMesh.computeClearance(w, tiles)
        assertEquals(0, c[3 * w + 3].toInt())
        assertEquals(1, c[3 * w + 4].toInt())
        assertEquals(1, c[0].toInt()) // borda
        assertEquals(2, c[1 * w + 1].toInt())
        assertTrue(DistrictNavMesh.clearanceCostFactor(1) > DistrictNavMesh.clearanceCostFactor(3))
    }

    @Test fun protocoloNeuralUsaPrefixoImutavelEChavesDeFastForward() {
        val p = NeuralProtocol.build("Ana", "Geração 0.", mapOf("fome" to 0.4), 0.1, emptyList(),
            listOf(ActionType.COMER to 0.2), night = false, drought = false, recentSpeeches = (1..9).map { "fala $it" }, seed = 77L)
        assertEquals(NeuralProtocol.IMMUTABLE_WORLD_PREFIX, p.worldPrefix)
        assertEquals(listOf("acao", "pensamento", "fala"), p.jsonKeys)
        assertEquals(6, p.recentSpeeches.size)
        assertEquals(77L, p.seed)
    }

    @Test fun mundoIntegraCfcOrcaMemoriaEHerancaTies() {
        val core = WorldCore.create(2026L)
        repeat(480 * 3) { core.step() }
        val s = core.state
        val alive = s.aliveCitizens()
        assertTrue(s.retrievalQueries > 0 && s.retrievalStage1Total > 0)
        assertTrue("ORCA ajustou passos", s.orcaAdjustedSteps > 0)
        assertTrue(alive.all { it.cfcState.size == 48 && it.locomotion in 0.75..1.25 })
        assertTrue(alive.any { abs(it.cortisol - 0.3) > 1e-6 })
        assertTrue(alive.all { !it.px.isNaN() && floor(it.px).toInt() == it.x && floor(it.pz).toInt() == it.z })
        assertTrue(alive.any { c -> c.memories.any { it.bits != null && it.hawkesExcitation > 0 } })
        assertTrue(alive.any { c -> c.personalAdapter.any { it != 0f } })
        assertTrue(alive.any { it.recentSpeeches.isNotEmpty() && it.recentSpeeches.size <= 6 })
        // nenhum cidadão em deslocamento dentro de edifício que não seja origem/destino
        for (c in alive) {
            val t = s.tileAt(c.x, c.z)
            if (!t.walkable) {
                val idx = c.z * s.widthTiles + c.x
                val atTarget = c.intention?.let { it.targetZ * s.widthTiles + it.targetX == idx } ?: true
                assertTrue("${c.id} atravessou edifício", atTarget || c.path.isEmpty())
            }
        }
        val d = sqrt(alive.sumOf { it.vx * it.vx + it.vz * it.vz })
        assertFalse(d.isNaN())
    }
}
