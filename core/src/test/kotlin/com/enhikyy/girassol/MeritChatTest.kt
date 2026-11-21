package com.enhikyy.girassol

import com.enhikyy.girassol.core.cognition.NeuralProtocol
import com.enhikyy.girassol.core.world.ReproductionMerit
import com.enhikyy.girassol.core.world.WorldCore
import com.enhikyy.girassol.core.world.WorldSnapshotCodec
import com.enhikyy.girassol.core.world.TICKS_PER_DAY
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

class MeritChatTest {
    @Test
    fun etaStatesFollowPopulation() {
        val core = WorldCore.create(7L, 0)
        assertEquals(ReproductionMerit.Status.SEM_POPULACAO, ReproductionMerit.estimate(core.state).status)
        core.spawnFounder("adao.gguf", "Adão", "M")
        assertEquals(ReproductionMerit.Status.PRECISA_PAR, ReproductionMerit.estimate(core.state).status)
        core.spawnFounder("eva.gguf", "Eva", "F")
        val eta = ReproductionMerit.estimate(core.state)
        assertEquals(ReproductionMerit.Status.AGUARDANDO, eta.status)
        assertEquals(1, eta.nextGeneration)
        assertTrue("promoção RSI/LoRA" in eta.blockers)
    }

    @Test
    fun noBirthWithoutMerit() {
        val core = WorldCore.create(11L, 0)
        core.spawnFounder("adao.gguf", "Adão", "M")
        core.spawnFounder("eva.gguf", "Eva", "F")
        repeat((TICKS_PER_DAY * 3).toInt()) { core.step(core.state.telemetry) }
        val adam = core.state.citizens.first { it.name == "Adão" }
        assertTrue("linhas de base t0 devem acumular", adam.acceptorBaseN > 0 || adam.brierBaseN > 0)
        // todo nascimento registrado exige mérito dos dois pais (evento NASCIMENTO_MERITO)
        val births = core.state.citizens.count { it.generation > 0 }
        assertEquals(births.toLong(), core.state.recentEvents.count { it.type == "NASCIMENTO_MERITO" }.toLong().coerceAtMost(births.toLong()))
        for (child in core.state.citizens.filter { it.generation > 0 }) assertEquals(2, child.parents.size)
    }

    @Test
    fun chatIsPersistedInSnapshotAndHashChain() {
        val core = WorldCore.create(3L, 0)
        val eva = core.spawnFounder("eva.gguf", "Eva", "F")
        val before = core.state.eventSeq
        core.postUserChat(eva.id, "Olá Eva, como está a horta?", 1000L)
        val reply = core.symbolicReply(eva.id, "como está a horta?")
        assertTrue(reply.isNotBlank())
        assertNotNull(core.postCitizenReply(eva.id, eva.id, reply, "simbolico", 1001L))
        assertEquals(before + 2, core.state.eventSeq)
        val restored = WorldSnapshotCodec.decode(WorldSnapshotCodec.encode(core.state))
        assertEquals(2, restored.chat.size)
        assertEquals("usuario", restored.chat[0].author)
        assertEquals(eva.id, restored.chat[1].author)
        assertNotNull(core.chatPromptFor(eva.id, "tudo bem?"))
    }

    @Test
    fun chatGrammarParses() {
        assertEquals("Estou bem, obrigado.", NeuralProtocol.parseChat("{\"fala\":\"Estou bem, obrigado.\"}"))
        assertTrue(NeuralProtocol.chatGrammar().contains("fala"))
    }
}
