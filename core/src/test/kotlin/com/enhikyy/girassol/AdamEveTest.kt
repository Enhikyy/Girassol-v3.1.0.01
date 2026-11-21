package com.enhikyy.girassol

import com.enhikyy.girassol.core.ports.CorePorts
import com.enhikyy.girassol.core.ports.NeuralDecision
import com.enhikyy.girassol.core.ports.NeuralDeliberationPort
import com.enhikyy.girassol.core.ports.NeuralRequest
import com.enhikyy.girassol.core.world.ADULT_DAYS
import com.enhikyy.girassol.core.world.DashboardSnapshot
import com.enhikyy.girassol.core.world.TICKS_PER_DAY
import com.enhikyy.girassol.core.world.WorldCore
import com.enhikyy.girassol.core.world.WorldRenderSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.6 "Adão e Eva": mundo vazio, 1 modelo .gguf = 1 fundador, crescimento só por reprodução. */
class AdamEveTest {
    @Test fun newWorldStartsEmptyAndRunsSilently() {
        val core = WorldCore.create(42L)
        assertEquals(0, core.state.citizens.size)
        repeat((TICKS_PER_DAY * 2 + 5).toInt()) { core.step() }
        assertEquals("nenhum cidadão sintético pode surgir", 0, core.state.citizens.size)
        assertEquals(0, DashboardSnapshot.of(core.state).citizens.size)
        assertTrue(WorldRenderSnapshot.of(core.state).agents.isEmpty())
    }

    @Test fun oneModelIsOnePersonAndTwoModelsAreTwo() {
        val core = WorldCore.create(7L)
        val adao = core.spawnFounder("adao.gguf", "Adão", "M")
        assertEquals(1, core.state.aliveCitizens().size)
        assertSame("o mesmo modelo não cria outra pessoa", adao, core.spawnFounder("adao.gguf"))
        val eva = core.spawnFounder("eva.gguf", "Eva", "F")
        assertEquals(2, core.state.aliveCitizens().size)
        assertEquals("adao.gguf", adao.effectiveModelKey)
        assertEquals("eva.gguf", eva.effectiveModelKey)
        assertNotEquals(adao.id, eva.id)
        assertTrue(adao.ageDays(core.state.tick) >= ADULT_DAYS)
        assertTrue(core.state.recentEvents.any { it.type == "FUNDADOR" && it.payload.contains("eva.gguf") })
    }

    @Test fun neuralRequestsCarryTheCitizensOwnModel() {
        val seen = HashMap<String, String?>()
        val port = object : NeuralDeliberationPort {
            override fun isAvailable() = true
            override fun trySubmit(request: NeuralRequest): Boolean { seen[request.agentId] = request.modelKey; return false }
            override fun drainResults(): List<NeuralDecision> = emptyList()
        }
        val core = WorldCore.create(9L, ports = CorePorts(neural = port))
        val a = core.spawnFounder("lfm2.5-230m-f16.gguf", "Adão", "M")
        val b = core.spawnFounder("smollm2-360m-q4_0.gguf", "Eva", "F")
        var n = 0
        while ((seen[a.id] == null || seen[b.id] == null) && n++ < TICKS_PER_DAY * 6) {
            core.step()
            for (c in core.state.aliveCitizens()) c.needs.replaceAll { _, v -> maxOf(v, 0.5) }
        }
        assertEquals("lfm2.5-230m-f16.gguf", seen[a.id])
        assertEquals("smollm2-360m-q4_0.gguf", seen[b.id])
    }

    @Test fun childrenOnlyComeFromReproductionAndInheritLineageModel() {
        val core = WorldCore.create(2026L)
        val a = core.spawnFounder("a.gguf", "Adão", "M")
        val b = core.spawnFounder("b.gguf", "Eva", "F")
        a.social.getOrCreateTie(b.id).apply { affinity = 0.9; familiarity = 0.9 }
        b.social.getOrCreateTie(a.id).apply { affinity = 0.9; familiarity = 0.9 }
        repeat((TICKS_PER_DAY * 30).toInt()) {
            core.step()
            // mantém os fundadores alimentados para isolar a regra de reprodução
            for (c in core.state.aliveCitizens()) c.needs.replaceAll { _, v -> maxOf(v, 0.7) }
        }
        val born = core.state.citizens.filter { it.parents.isNotEmpty() }
        assertTrue("o casal fundador deveria ter filhos", born.isNotEmpty())
        assertEquals(core.state.citizens.size, born.size + 2)
        for (child in born) {
            assertNull(child.modelKey)
            assertTrue(child.lineageModelKey in setOf("a.gguf", "b.gguf"))
        }
    }

    @Test fun sameSexFoundersDoNotReproduce() {
        val core = WorldCore.create(5L)
        val a = core.spawnFounder("a.gguf", "A", "F")
        val b = core.spawnFounder("b.gguf", "B", "F")
        a.social.getOrCreateTie(b.id).apply { affinity = 0.9; familiarity = 0.9 }
        b.social.getOrCreateTie(a.id).apply { affinity = 0.9; familiarity = 0.9 }
        repeat((TICKS_PER_DAY * 20).toInt()) {
            core.step()
            for (c in core.state.aliveCitizens()) c.needs.replaceAll { _, v -> maxOf(v, 0.7) }
        }
        assertEquals(2, core.state.citizens.size)
    }
}
