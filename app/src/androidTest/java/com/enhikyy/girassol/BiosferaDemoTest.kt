package com.enhikyy.girassol

import android.Manifest
import androidx.compose.ui.test.ExperimentalTestApi
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
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Roteiro de demonstração (não roda no CI normal): só executa com `-e demo true`.
 * Povoa a Biosfera com 4 fundadores, abre o modo 3D e mantém o app vivo enquanto o host
 * grava capturas/vídeo (scripts/biosfera_demo.sh).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class BiosferaDemoTest {
    @get:Rule(order = 0)
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val demo = InstrumentationRegistry.getArguments().getString("demo") == "true"

    @Before
    fun only() = assumeTrue("roteiro de demonstração: use -e demo true", demo)

    private fun mark(step: String) = android.util.Log.i("BiosferaDemo", "PASSO $step")

    @Test
    fun biosfera3dTour() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        RenderModePolicy.set(ctx, RenderMode.MODE_3D)
        rule.waitUntilAtLeastOneExists(hasTestTag("tab_BIOSFERA"), 60_000)
        rule.waitUntil(60_000) { SimulationHub.status.value.loaded }
        runBlocking {
            SimulationHub.spawnFounder("iara.gguf", "Iara Peroba", "F")
            SimulationHub.spawnFounder("livia.gguf", "Lívia Aroeira", "F")
            SimulationHub.spawnFounder("orlando.gguf", "Orlando Aroeira", "M")
            SimulationHub.spawnFounder("gael.gguf", "Gael Jacarandá", "M")
        }
        rule.onNodeWithTag("tab_BIOSFERA").performClick()
        runCatching { rule.waitUntilAtLeastOneExists(hasTestTag("world_viewport_3d"), 30_000) }
        mark("3D_PRONTO")
        Thread.sleep(45_000)               // host: capturas + órbita/inclinação
        SimulationHub.setSpeed(8)
        mark("VELOCIDADE_8X")
        Thread.sleep(25_000)
        // toque num cidadão projetado → cartão do cidadão
        WorldPickRegistry.positions.values.firstOrNull()?.let { p ->
            runCatching { rule.onNodeWithTag("world_viewport_3d").performTouchInput { click(p) } }
        }
        mark("CARTAO")
        Thread.sleep(15_000)
        mark("FIM")
    }
}
