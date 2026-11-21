package com.enhikyy.girassol

import android.Manifest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.enhikyy.girassol.nativebridge.GeaNativeBridge
import com.enhikyy.girassol.nativebridge.NativeModules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Aba Sistema: diagnóstico C++, benchmark de CPU e toggles dos módulos nativos. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SystemTabTest {
    @get:Rule(order = 0)
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    @Before
    fun openSystemTab() {
        rule.waitUntilAtLeastOneExists(hasTestTag("tab_SISTEMA"), 60_000)
        rule.onNodeWithTag("tab_SISTEMA").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("system_list"), 10_000)
        // v3.6: painéis técnicos ficam recolhidos em "Detalhes técnicos do hardware"
        rule.onNodeWithTag("system_list").performScrollToNode(hasTestTag("hw_details_toggle"))
        rule.onNodeWithTag("hw_details_toggle").performClick()
        rule.waitForIdle()
    }

    @Test
    fun nativeLibraryLoadsAndParityPasses() {
        assertTrue("libgirassol_core não carregou", GeaNativeBridge.isNativeLoaded)
        val p = NativeModules.verifyParity()
        assertTrue(p.message, p.navOk && p.loraOk)
    }

    @Test
    fun diagnosticsRunAndShowResults() {
        rule.onNodeWithTag("system_list").performScrollToNode(hasTestTag("system_diagnostics"))
        rule.onNodeWithTag("system_diagnostics").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("diag_result"), 60_000)
    }

    @Test
    fun cpuBenchmarkRuns() {
        rule.onNodeWithTag("system_list").performScrollToNode(hasTestTag("system_benchmark"))
        rule.onNodeWithTag("system_benchmark").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("benchmark_result"), 60_000)
    }

    @Test
    fun moduleTogglesFlipAndRestore() {
        for (tag in listOf("toggle_nav", "toggle_lora", "toggle_audio", "toggle_llm")) {
            val before = NativeModules.toggles.value
            rule.onNodeWithTag("system_list").performScrollToNode(hasTestTag(tag))
            rule.onNodeWithTag(tag).performClick()
            rule.waitForIdle()
            val after = NativeModules.toggles.value
            assertTrue("$tag não alterou", before != after)
            rule.onNodeWithTag(tag).performClick()
            rule.waitForIdle()
            assertEquals(before, NativeModules.toggles.value)
        }
    }
}
