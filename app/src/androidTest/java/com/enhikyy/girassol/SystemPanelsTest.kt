package com.enhikyy.girassol

import android.Manifest
import android.os.Process
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.enhikyy.girassol.nativebridge.GeaNativeBridge
import com.enhikyy.girassol.nativebridge.LlmNativeBridge
import com.enhikyy.girassol.render3d.EffectLevel
import com.enhikyy.girassol.render3d.GpuCalibration
import com.enhikyy.girassol.system.MemoryGuard
import com.enhikyy.girassol.system.SigningInfo
import com.enhikyy.girassol.system.SigningKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** v3.3: painéis de CPU/LLM, memória real + disjuntor, GPU e assinatura na aba Sistema. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SystemPanelsTest {
    @get:Rule(order = 0)
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    @Before
    fun openSystemTab() {
        rule.waitUntilAtLeastOneExists(hasTestTag("tab_SISTEMA"), 60_000)
        rule.onNodeWithTag("tab_SISTEMA").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("system_list"), 10_000)
    }

    @After
    fun rearm() = MemoryGuard.rearmManually()

    private fun scrollTo(tag: String): SemanticsNodeInteraction {
        rule.onNodeWithTag("system_list").performScrollToNode(hasTestTag(tag))
        return rule.onNodeWithTag(tag)
    }

    private fun SemanticsNodeInteraction.text(): String =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: ""

    @Test
    fun simdFlagsAndVariantAreReported() {
        val flags = scrollTo("simd_flags").text()
        assertTrue(flags, flags.contains("NEON=") && flags.contains("DOTPROD=") && flags.contains("FP16=") && flags.contains("I8MM="))
        val variant = scrollTo("llm_variant").text()
        assertTrue(variant, variant.isNotBlank())
        // o emulador x86_64 deve sempre usar a variante base
        if (!LlmNativeBridge.cpuFlags.aarch64) assertEquals("base", LlmNativeBridge.loadedVariant)
    }

    @Test
    fun realMemoryTelemetryFromProcStatus() {
        val m = MemoryGuard.sample()
        assertTrue("VmRSS não lido: ${m.rssKb}", m.rssKb > 0)
        assertTrue("VmHWM < VmRSS", m.hwmKb >= m.rssKb)
        assertTrue("leitura não veio do C++", m.nativeSource == GeaNativeBridge.isNativeLoaded)
        assertTrue(m.sysTotalMb > 0 && m.sysAvailMb > 0)
        val t = scrollTo("mem_rss").text()
        assertTrue(t, t.contains("VmRSS") && t.contains("MB"))
    }

    @Test
    fun breakerTripsAndRearms() {
        scrollTo("breaker_test").performClick()
        rule.waitUntil(10_000) { MemoryGuard.state.value.breakerOpen }
        rule.waitUntilAtLeastOneExists(hasTestTag("breaker_state") and hasText("ABERTO", substring = true), 10_000)
        // o loop determinístico continua: a simulação segue sem o LLM
        scrollTo("breaker_rearm").performClick()
        rule.waitUntil(10_000) { !MemoryGuard.state.value.breakerOpen }
    }

    @Test
    fun gpuDriverAndEffectLevelAreReported() {
        rule.waitUntil(60_000) { GpuCalibration.state.value.status.name == "DONE" }
        val r = scrollTo("gl_renderer").text()
        assertTrue(r, r.startsWith("GL_RENDERER:"))
        assertTrue(scrollTo("gpu_level").text().isNotBlank())
    }

    @Test
    fun debugSignatureIsDetectedWithGrsWarning() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val sig = SigningInfo.read(ctx)
        assertEquals(SigningKind.DEBUG, sig.kind)
        assertTrue(scrollTo("signing_kind").text().contains("debug", ignoreCase = true))
        assertTrue(scrollTo("signing_warning").text().contains(".grs"))
        scrollTo("grs_export")
    }

    @Test
    fun gpuProbeRunsInSeparateProcess() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pid = Process.myPid()
        val r = GpuCalibration.runProbe(ctx, EffectLevel.NONE, 30_000)
        assertNotNull(r)
        assertFalse("sonda expirou: ${r.message}", r.message == "timeout")
        assertFalse("sonda caiu", r.crashed)
        // nível sem pós deve renderizar fora da tela até no SwiftShader do emulador
        assertTrue("sonda falhou: ${r.message}", r.ok)
        assertEquals("o processo principal não pode morrer com a sonda", pid, Process.myPid())
        assertTrue(GeaNativeBridge.isNativeLoaded)
    }
}
