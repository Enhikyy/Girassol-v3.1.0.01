package com.enhikyy.girassol

import android.Manifest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.enhikyy.girassol.render3d.RenderMode
import com.enhikyy.girassol.render3d.RenderModePolicy
import com.enhikyy.girassol.render3d.WorldPickRegistry
import com.enhikyy.girassol.sim.SimulationHub
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Aba Mundo: alternância 3D/Modo Eco 2D e seleção de cidadão por toque. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class WorldTabTest {
    @get:Rule(order = 0)
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    @Before
    fun startIn2d() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        RenderModePolicy.set(ctx, RenderMode.ECO_2D)
        // v3.6: Chat é a aba inicial; o mundo começa com 0 cidadãos (Adão e Eva vêm de .gguf).
        rule.waitUntilAtLeastOneExists(hasTestTag("tab_BIOSFERA"), 60_000)
        rule.waitUntil(60_000) { SimulationHub.status.value.loaded }
        runBlocking {
            SimulationHub.spawnFounder("teste-adao.gguf", "Adão", "M")
            SimulationHub.spawnFounder("teste-eva.gguf", "Eva", "F")
        }
        rule.onNodeWithTag("tab_BIOSFERA").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("world_mode_label"), 60_000)
    }

    private fun modeIs(mode: RenderMode) = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("modo:${mode.name}"))

    @Test
    fun toggles3dAnd2d() {
        rule.onNodeWithTag("world_mode_2d").performClick()
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag("world_mode_label").assert(modeIs(RenderMode.ECO_2D)); true }.getOrDefault(false) }
        rule.onNodeWithTag("world_viewport_2d").assertIsDisplayed()

        rule.onNodeWithTag("world_mode_3d").performClick()
        // Em GPU/emulador sem suporte, a política cai para o Modo Eco com motivo explícito.
        rule.waitUntil(20_000) {
            rule.onAllNodes(hasTestTag("world_viewport_3d")).fetchSemanticsNodes().isNotEmpty() ||
                runCatching { rule.onNodeWithTag("world_mode_label").assert(modeIs(RenderMode.ECO_2D)); RenderModePolicy.preferred.value == RenderMode.MODE_3D }.getOrDefault(false)
        }
        assertTrue(RenderModePolicy.preferred.value == RenderMode.MODE_3D)

        rule.onNodeWithTag("world_mode_2d").performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("world_viewport_2d")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(rule.onAllNodes(hasTestTag("world_viewport_3d")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun tapOnCitizenOpensCardIn2d() {
        rule.onNodeWithTag("world_mode_2d").performClick()
        rule.waitUntil(30_000) { WorldPickRegistry.mode == "2D" && WorldPickRegistry.positions.isNotEmpty() }
        val (_, pos) = WorldPickRegistry.positions.entries.first().toPair()
        // a posição registrada é o topo do marcador; o alvo de toque é o centro do cidadão
        rule.onNodeWithTag("world_viewport_2d").performTouchInput { click(pos.copy(y = pos.y + 3f)) }
        rule.waitUntilAtLeastOneExists(hasTestTag("agent_card"), 10_000)
        rule.onNodeWithTag("agent_card_name").assertIsDisplayed()
    }

    @Test
    fun tapOnCitizenOpensCardIn3dWhenAvailable() {
        rule.onNodeWithTag("world_mode_3d").performClick()
        val has3d = runCatching {
            rule.waitUntil(20_000) { WorldPickRegistry.mode == "3D" && WorldPickRegistry.positions.isNotEmpty() }
            true
        }.getOrDefault(false)
        if (!has3d) {
            // Sem GLES 3 no emulador: o fallback 2D deve estar ativo e explicado.
            rule.onNodeWithTag("world_viewport_2d").assertIsDisplayed()
            return
        }
        // posição projetada = centro do corpo; tenta alguns cidadãos (eles se movem entre quadros)
        var opened = false
        for (pos in WorldPickRegistry.positions.values.take(5)) {
            rule.onNodeWithTag("world_viewport_3d").performTouchInput {
                android.util.Log.i("WorldTabTest", "toque em $pos; nó ${width}x$height")
                click(pos)
            }
            opened = runCatching { rule.waitUntilAtLeastOneExists(hasTestTag("agent_card"), 4_000); true }.getOrDefault(false)
            if (opened) break
        }
        assertTrue("toque 3D não selecionou nenhum cidadão", opened)
    }
}
