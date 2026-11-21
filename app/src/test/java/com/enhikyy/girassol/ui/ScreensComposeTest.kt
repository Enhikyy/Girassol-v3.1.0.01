package com.enhikyy.girassol.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.enhikyy.girassol.core.world.ChatMessage
import com.enhikyy.girassol.core.world.DashboardSnapshot
import com.enhikyy.girassol.core.world.TelemetryInput
import com.enhikyy.girassol.core.world.WorldCore
import com.enhikyy.girassol.sim.LiveSample
import com.enhikyy.girassol.sim.WorldSpeech
import com.enhikyy.girassol.ui.screens.ChatBackend
import com.enhikyy.girassol.ui.screens.ChatScreen
import com.enhikyy.girassol.ui.screens.EvolutionContent
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v3.9 — validação de UI na JVM (Robolectric + Compose): cápsula do Chat dispara inferência e
 * mostra a resposta; nenhum cartão com mais de 120 dp vazio; árvore geracional sem sobreposição.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h915dp-xxhdpi")
class ScreensComposeTest {
    @get:Rule val rule = createComposeRule()

    private fun world(founders: Int = 4, ticks: Int = 40): DashboardSnapshot {
        val core = WorldCore.create(7L)
        val names = listOf("Iara Peroba", "Lívia Aroeira", "Orlando Aroeira", "Gael Jacarandá")
        val sexes = listOf("F", "F", "M", "M")
        repeat(founders) { core.spawnFounder("m$it.gguf", names[it], sexes[it]) }
        repeat(ticks) { core.step(TelemetryInput(), 1_000L * it) }
        return DashboardSnapshot.of(core.state)
    }

    private class FakeBackend(d: DashboardSnapshot) : ChatBackend {
        override val dashboard = MutableStateFlow<DashboardSnapshot?>(d)
        override val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
        override val worldSpeech = MutableStateFlow<List<WorldSpeech>>(emptyList())
        override val pending = MutableStateFlow<Set<String>>(emptySet())
        val sent = ArrayList<Pair<String?, String>>()
        override fun send(target: String?, text: String) {
            sent += target to text
            val c = dashboard.value!!.citizens.first { it.alive }
            val now = System.currentTimeMillis()
            pending.value = setOf(c.id)
            messages.value = messages.value +
                ChatMessage(1, 10, now, target, "usuario", "Você", text, "usuario") +
                ChatMessage(2, 11, now + 1, target, c.id, c.name, "Olá! Estou na praça cuidando da horta.", "gguf:m0.gguf")
            pending.value = emptySet()
        }
        override fun stop() {}
    }

    @Test
    fun chatCapsuleTriggersInferenceAndAddsReplyBubble() {
        val backend = FakeBackend(world())
        rule.setContent { ChatScreen(onOpenCitizen = {}, backend = backend, voiceEnabled = false) }
        rule.onNodeWithTag("command_capsule").assertExists()
        rule.onNodeWithTag("chat_input").performTextInput("Como está a cidade hoje?")
        rule.onNodeWithTag("chat_send").performClick()
        rule.waitForIdle()
        assertEquals(1, backend.sent.size)
        assertEquals("Como está a cidade hoje?", backend.sent.single().second)
        rule.waitUntil(5_000) { rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "chat_msg_2")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("chat_msg_2").assertExists()
        assertTrue(rule.onAllNodes(hasText("Olá! Estou na praça", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun evolutionHasNoTallEmptyCardsAndNoDeadPlaceholders() {
        val d = world()
        val live = (1..30).map { LiveSample(it.toLong(), 4, 0.1, 0.7, 0.6, 0.5) }
        rule.setContent { EvolutionContent(d, live, 1.0, onOpenCitizen = {}) }
        rule.waitForIdle()
        assertFalse("placeholder morto ainda presente",
            rule.onAllNodes(hasText("O gráfico começa ao fim do 2º dia", substring = true)).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "live_chart")).fetchSemanticsNodes().isNotEmpty())
        assertNoTallEmpty()
        rule.onNodeWithTag("evolution_list").performScrollToNode(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "evo_citizens"))
        rule.waitForIdle()
        assertNoTallEmpty()
        // cartões estilo The Sims com ação atual em português natural (sem "Ocioso" cru)
        val sims = rule.onAllNodes(SemanticsMatcher("sims") { (it.config.getOrNull(SemanticsProperties.TestTag) ?: "").startsWith("sims_card_") })
            .fetchSemanticsNodes(atLeastOneRootRequired = true)
        assertEquals(4, sims.size)
        assertFalse(rule.onAllNodes(hasText("Ação atual: Ocioso", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun lineageNodesNeverOverlapOnPhones() {
        val d = world()
        val width = androidx.compose.runtime.mutableIntStateOf(360)
        rule.setContent { Box(Modifier.width(width.intValue.dp)) { EvolutionContent(d, emptyList(), 1.0, onOpenCitizen = {}) } }
        for (w in listOf(360, 380, 400, 412)) {
            width.intValue = w
            rule.waitForIdle()
            val nodes = rule.onAllNodes(SemanticsMatcher("lineage") {
                (it.config.getOrNull(SemanticsProperties.TestTag) ?: "").startsWith("lineage_node_")
            }, useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue("árvore sem nós em ${w}dp", nodes.size >= 4)
            for (i in nodes.indices) for (j in i + 1 until nodes.size) {
                val a = nodes[i].boundsInRoot; val b = nodes[j].boundsInRoot
                assertFalse("nós sobrepostos em ${w}dp: $a × $b", a.overlaps(b))
            }
        }
    }

    private fun assertNoTallEmpty() {
        val density = rule.density.density
        val tagged = rule.onAllNodes(SemanticsMatcher("tagged") { it.config.getOrNull(SemanticsProperties.TestTag) != null }, useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue(tagged.isNotEmpty())
        for (n in tagged) {
            val hDp = n.boundsInRoot.height / density
            if (hDp > 120f) {
                val tag = n.config.getOrNull(SemanticsProperties.TestTag)
                assertTrue("\"$tag\" tem ${hDp.toInt()} dp sem conteúdo", hasContent(n))
            }
        }
    }

    private fun hasContent(n: SemanticsNode): Boolean {
        val txt = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
        if (txt.isNotBlank()) return true
        return n.children.any { hasContent(it) }
    }
}
