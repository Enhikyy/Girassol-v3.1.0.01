package com.enhikyy.girassol

import com.enhikyy.girassol.core.util.StreamSeeds
import com.enhikyy.girassol.core.world.NativeWorldTick
import com.enhikyy.girassol.core.world.TICKS_PER_DAY
import com.enhikyy.girassol.core.world.WorldCore
import com.enhikyy.girassol.core.world.WorldSnapshotCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.7 — WorldTick em C++20 via JNI: paridade de sementes, IRT 2PL, PER e herança de léxico. */
class V37WorldTickTest {
    @Test fun nativeStreamSeedMatchesKotlinDerivation() {
        for (seed in listOf(0L, 1L, -7L, 2026L, Long.MAX_VALUE))
            for (id in listOf("WORLD", "C1", "C42", "áé"))
                for (tick in listOf(0L, 1L, 4321L))
                    for (stream in listOf("needs", "social", "merit-birth"))
                        assertEquals("$seed/$id/$tick/$stream", StreamSeeds.derive(seed, id, tick, stream),
                            NativeWorldTick.nativeStreamSeed(seed, id, tick, stream))
    }

    @Test fun irtProbabilityIsLogistic2pl() {
        assertEquals(0.5, NativeWorldTick.nativeIrtProbability(0.3, 1.4, 0.3), 1e-12)
        val p = NativeWorldTick.nativeIrtProbability(1.0, 1.2, 0.0)
        assertEquals(1.0 / (1.0 + Math.exp(-1.2)), p, 1e-12)
    }

    @Test fun perSamplingFavoursHighPriority() {
        val pr = DoubleArray(64) { if (it == 7) 50.0 else 0.01 }
        val w = DoubleArray(32)
        val idx = NativeWorldTick.nativePerSample(pr, 32, 0.6, 0.4, 99L, w)
        assertEquals(32, idx.size)
        assertTrue("PER não priorizou", idx.count { it == 7 } > 16)
        assertTrue(w.all { it > 0.0 && it <= 1.0 + 1e-12 })
    }

    private fun foundedWorld(seed: Long): WorldCore {
        val core = WorldCore.create(seed)
        val a = core.spawnFounder("a.gguf", "Adão", "M")
        val b = core.spawnFounder("b.gguf", "Eva", "F")
        a.social.getOrCreateTie(b.id).apply { affinity = 0.9; familiarity = 0.9 }
        b.social.getOrCreateTie(a.id).apply { affinity = 0.9; familiarity = 0.9 }
        return core
    }

    @Test fun nativeWorldTickCalibratesIrtFillsPerAndInheritsLexicon() {
        val core = foundedWorld(2026L)
        repeat((TICKS_PER_DAY * 30).toInt()) {
            core.step()
            for (c in core.state.aliveCitizens()) c.needs.replaceAll { _, v -> maxOf(v, 0.7) }
        }
        val st = core.state
        val founders = st.citizens.filter { it.founder }
        assertTrue("IRT 2PL não calibrou θ", founders.all { it.irtCalibrated && it.irtThetaSe < 1.0 })
        assertTrue("itens IRT não foram estimados", st.irtItemA.any { it != 1.0 } || st.irtItemB.any { it != 0.0 })
        assertTrue("PER não reamostrou", st.perReplays > 0)
        assertTrue("buffer PER vazio", founders.all { it.replay.isNotEmpty() && it.replay.size <= com.enhikyy.girassol.core.world.PER_CAPACITY })
        val born = st.citizens.filter { it.parents.isNotEmpty() }
        assertTrue("sem reprodução g1", born.isNotEmpty())
        val birth = st.recentEvents.firstOrNull { it.type == "NASCIMENTO_MERITO" }
        if (birth != null) assertTrue(birth.payload.contains("theta"))
        for (child in born) {
            val parentGlyphs = st.citizens.filter { it.id in child.parents }.flatMap { p -> p.lexicon.entries.map { it.glyph } }.toSet()
            assertTrue("léxico do filho não é individual", child.lexicon !== st.citizens.first { it.id == child.parents.first() }.lexicon)
            assertTrue(child.lexicon.inheritedEntries <= child.lexicon.entries.size + child.lexicon.mutatedEntries)
            if (child.lexicon.inheritedEntries > 0 && parentGlyphs.isNotEmpty())
                assertTrue(child.lexicon.entries.any { it.glyph in parentGlyphs } || child.lexicon.mutatedEntries > 0)
        }
    }

    @Test fun nativeWorldTickIsDeterministicAcrossRuns() {
        fun run(): String {
            val core = foundedWorld(77L)
            repeat((TICKS_PER_DAY * 6).toInt()) {
                core.step()
                for (c in core.state.aliveCitizens()) c.needs.replaceAll { _, v -> maxOf(v, 0.6) }
            }
            return WorldSnapshotCodec.stateHash(core.state)
        }
        assertEquals(run(), run())
    }
}
