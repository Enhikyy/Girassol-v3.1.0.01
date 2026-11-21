package com.enhikyy.girassol.sim

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v3.9 — o mundo não congela no tick 0: o motor inicia sozinho a 1x ao abrir o app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SimulationAutoStartTest {
    @After fun stop() = SimulationHub.pause()

    @Test
    fun engineAutoStartsAndTicks() {
        SimulationHub.ensureLoaded(ApplicationProvider.getApplicationContext())
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val st = SimulationHub.status.value
            val tick = SimulationHub.render.value?.tick ?: -1L
            if (st.loaded && st.running && tick > 0) break
            Thread.sleep(100)
        }
        val st = SimulationHub.status.value
        assertTrue("não carregou: $st", st.loaded)
        assertTrue("não iniciou sozinho: $st", st.running)
        assertTrue("velocidade inicial deveria ser 1x: ${st.targetTps}", st.targetTps == SimulationHub.DEFAULT_TPS)
        val tick = SimulationHub.render.value?.tick ?: -1L
        assertTrue("mundo congelado no tick $tick", tick > 0)
        assertTrue(SimulationHub.SPEEDS == listOf(1, 2, 8, 30))
    }
}
