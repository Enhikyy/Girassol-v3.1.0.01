package com.enhikyy.girassol

import android.Manifest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.enhikyy.girassol.core.world.NativeCognition
import com.enhikyy.girassol.sim.SimulationHub
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v3.8 — percorre Mundo (Biosfera), Cidadãos, Sociedade, Mente (seções da aba Evolução),
 * Evolução e Sistema com o mundo real rodando (Adão e Eva), sem dados de exemplo.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AllTabsTest {
    @get:Rule(order = 0)
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    @Before
    fun founders() {
        rule.waitUntilAtLeastOneExists(hasTestTag("tab_BIOSFERA"), 60_000)
        rule.waitUntil(60_000) { SimulationHub.status.value.loaded }
        runBlocking {
            SimulationHub.spawnFounder("teste-adao.gguf", "Adão", "M")
            SimulationHub.spawnFounder("teste-eva.gguf", "Eva", "F")
        }
    }

    @Test
    fun nativeCognitionKernelRunsOnDevice() {
        assertEquals(NativeCognition.PARITY_GOLDEN, NativeCognition.nativeParitySignature())
    }

    @Test
    fun worldTabShowsLivePopulation() {
        rule.onNodeWithTag("tab_BIOSFERA").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("hud_population"), 30_000)
        rule.waitUntil(30_000) { rule.onAllNodes(hasText("cidadãos", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun evolutionCitizensSocietyAndMindSectionsOpen() {
        rule.onNodeWithTag("tab_EVOLUCAO").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("evolution_list"), 30_000)
        rule.onNodeWithTag("evolution_list").performScrollToNode(hasTestTag("evo_citizens"))
        // Cidadãos: Adão e Eva aparecem na lista viva
        rule.waitUntil(30_000) { rule.onAllNodes(hasText("Adão", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        // Sociedade
        rule.onNodeWithTag("evolution_list").performScrollToNode(hasTestTag("evo_society"))
        rule.onNodeWithTag("evo_society").performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Cliodinâmica (Turchin)")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("evo_society").performClick()
        // Mente: memória 2 estágios + busca FTS no SQLite real
        rule.onNodeWithTag("evolution_list").performScrollToNode(hasTestTag("evo_mind"))
        rule.onNodeWithTag("evo_mind").performClick()
        rule.onNodeWithTag("evolution_list").performScrollToNode(hasTestTag("v34_memory"))
        rule.onNodeWithTag("v34_memory").assertIsDisplayed()
        rule.onNodeWithTag("evolution_list").performScrollToNode(hasTestTag("fts_query"))
        rule.onNodeWithTag("fts_query").performTextInput("comer")
        rule.onNodeWithTag("fts_search").performClick()
        rule.waitUntil(20_000) { rule.onAllNodes(hasText("resultados", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun systemTabShowsDynamicRamBudgetAndVoicePacks() {
        rule.onNodeWithTag("tab_SISTEMA").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("system_list"), 10_000)
        // publicado pelo MemoryGuard (/proc/meminfo + ActivityManager − Filament − WAL)
        rule.waitUntil(40_000) {
            runCatching { rule.onNodeWithTag("system_list").performScrollToNode(hasText("Orçamento dinâmico de RAM")); true }.getOrDefault(false)
        }
        rule.onNodeWithTag("system_list").performScrollToNode(hasTestTag("v35_hashchain"))
        rule.onNodeWithTag("system_list").performScrollToNode(hasTestTag("verify_hash_chain"))
        rule.onNodeWithTag("verify_hash_chain").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("hash_chain_result"), 30_000)
        assertTrue(rule.onAllNodes(hasTestTag("hash_chain_result")).fetchSemanticsNodes().isNotEmpty())
    }
}
