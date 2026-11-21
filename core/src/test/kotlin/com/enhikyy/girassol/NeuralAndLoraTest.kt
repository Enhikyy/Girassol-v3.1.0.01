package com.enhikyy.girassol

import com.enhikyy.girassol.core.cognition.NeuralProtocol
import com.enhikyy.girassol.core.ports.CorePorts
import com.enhikyy.girassol.core.ports.NeuralDecision
import com.enhikyy.girassol.core.ports.NeuralDeliberationPort
import com.enhikyy.girassol.core.ports.NeuralRequest
import com.enhikyy.girassol.core.world.TICKS_PER_DAY
import com.enhikyy.girassol.core.world.WorldCore
import com.enhikyy.girassol.core.world.WorldSnapshotCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Porta neural falsa e determinística: responde no tick seguinte com a 2ª opção válida. */
private class FakeNeuralPort : NeuralDeliberationPort {
    private val pending = ArrayDeque<NeuralRequest>()
    var submitted = 0
    override fun isAvailable() = true
    override fun trySubmit(request: NeuralRequest): Boolean {
        if (pending.size >= 2) return false
        pending += request; submitted++
        return true
    }
    override fun drainResults(): List<NeuralDecision> {
        val out = pending.map {
            val action = it.allowedActions.getOrElse(1) { _ -> it.allowedActions.first() }
            NeuralDecision(it.agentId, it.tick, action, "Pensei bem sobre isso.", "Vou fazer isso agora.", 24, 30, "fake")
        }
        pending.clear()
        return out
    }
}

class NeuralAndLoraTest {

    @Test
    fun gramaticaRestringeAcoesETemFormaGbnf() {
        val g = NeuralProtocol.grammar(listOf("COMER", "DESCANSAR"))
        assertTrue(g.contains("root ::="))
        assertTrue(g.contains("acao ::= \"\\\"COMER\\\"\" | \"\\\"DESCANSAR\\\"\""))
        assertTrue(g.contains("ch{1,72}"))
    }

    @Test
    fun parserEstritoAceitaApenasJsonValido() {
        val ok = NeuralProtocol.parse("{\"acao\":\"COMER\",\"pensamento\":\"Estou com fome.\",\"fala\":\"Vou a horta!\"}", setOf("COMER"))
        assertNotNull(ok)
        assertEquals("COMER", ok!!.action)
        assertNull(NeuralProtocol.parse("{\"acao\":\"VOAR\",\"pensamento\":\"x\",\"fala\":\"y\"}", setOf("COMER")))
        assertNull(NeuralProtocol.parse("{\"acao\":\"COMER\",\"pensamento\":\"x\"}", setOf("COMER")))
        assertNull(NeuralProtocol.parse("texto livre", setOf("COMER")))
        assertNull(NeuralProtocol.parse("{\"acao\":\"COMER\",\"pensamento\":\"a\\\"b\",\"fala\":\"y\"}", setOf("COMER")))
    }

    @Test
    fun decisoesNeuraisEntramComoEventosEReplayEDeterministico() {
        val a = WorldCore.create(314L, 24, ports = CorePorts(neural = FakeNeuralPort()))
        val b = WorldCore.create(314L, 24, ports = CorePorts(neural = FakeNeuralPort()))
        repeat(300) { a.step(); b.step() }
        assertEquals(a.state.lastEventHash, b.state.lastEventHash)
        assertEquals(WorldSnapshotCodec.stateHash(a.state), WorldSnapshotCodec.stateHash(b.state))
        assertTrue("decisões neurais aplicadas", a.state.neuralDecisionsApplied > 0)
        assertTrue(a.state.recentEvents.any { it.type == "LLM_DECISAO" })
    }

    @Test
    fun modoEconomicoProduzFalasSimbolicas() {
        val core = WorldCore.create(21L, 24)
        repeat(200) { core.step() }
        assertTrue(core.state.speechesEmitted > 0)
        assertTrue(core.state.aliveCitizens().any { it.lastSpeech.isNotBlank() })
    }

    @Test
    fun cicloLoraDoSonoTestaEmSandboxERegistra() {
        val core = WorldCore.create(808L, 24)
        repeat((TICKS_PER_DAY * 2 + 1).toInt()) { core.step() }
        val s = core.state
        assertTrue("ciclos LoRA registrados: ${s.loraLedger.size}", s.loraLedger.isNotEmpty())
        val rec = s.loraLedger.last()
        assertTrue(rec.samples >= WorldCore.LORA_MIN_SAMPLES)
        assertTrue(rec.mseAfter.isFinite() && rec.mseBefore.isFinite())
        if (rec.accepted) assertTrue(s.lora.acceptedCycles > 0)
        assertEquals(0, s.metrics.invariantsViolated)
        // determinismo com o ciclo LoRA
        val twin = WorldCore.create(808L, 24)
        repeat((TICKS_PER_DAY * 2 + 1).toInt()) { twin.step() }
        assertEquals(WorldSnapshotCodec.stateHash(s), WorldSnapshotCodec.stateHash(twin.state))
    }

    @Test
    fun parserAcceptsLlamaStyleSpacedOutput() {
        val raw = "{ \"acao\": \"COMER\", \"pensamento\": \"Estou com fome hoje.\", \"fala\": \"Vamos para a horta!\" }"
        val p = NeuralProtocol.parse(raw, listOf("COMER", "DESCANSAR"))!!
        assertEquals("COMER", p.action)
        assertEquals("Vamos para a horta!", p.speech)
        assertNull(NeuralProtocol.parse(raw, listOf("DESCANSAR")))
        assertNull(NeuralProtocol.parse("{ \"acao\": \"COMER\" }", listOf("COMER")))
        assertNotNull(NeuralProtocol.parse("{\n  \"acao\": \"COMER\",\n  \"pensamento\": \"ok\",\n  \"fala\": \"oi\"\n}", listOf("COMER")))
    }
}
