package com.enhikyy.girassol.render3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectLevelTest {
    @Test
    fun levelsStepDownToNone() {
        assertEquals(EffectLevel.REDUCED, EffectLevel.FULL.lower())
        assertEquals(EffectLevel.MINIMAL, EffectLevel.REDUCED.lower())
        assertEquals(EffectLevel.NONE, EffectLevel.MINIMAL.lower())
        assertNull(EffectLevel.NONE.lower())
        assertFalse(EffectLevel.NONE.postProcessing)
        assertTrue(EffectLevel.FULL.fxaa && !EffectLevel.REDUCED.fxaa)
        assertEquals(listOf(3, 2, 1, 0), EffectLevel.entries.map { it.id })
    }

    @Test
    fun governorWaitsWarmupThenDowngradesOnSustainedLowFps() {
        val g = FpsGovernor(floorFps = 20.0, windowSeconds = 8, warmupSeconds = 5)
        repeat(5) { assertFalse(g.onSecond(5.0)) } // aquecimento ignora compilação de shaders
        repeat(7) { assertFalse(g.onSecond(12.0)) }
        assertTrue(g.onSecond(12.0))
        // após degradar, novo aquecimento
        repeat(5) { assertFalse(g.onSecond(1.0)) }
    }

    @Test
    fun governorKeepsLevelAtTargetFps() {
        val g = FpsGovernor()
        repeat(60) { assertFalse(g.onSecond(29.5)) }
        // um pico curto de queda não derruba o nível
        repeat(3) { assertFalse(g.onSecond(8.0)) }
        repeat(20) { assertFalse(g.onSecond(30.0)) }
    }

    @Test
    fun policyKeeps2dWhileCalibratingOrWithoutLevel() {
        val running = GpuCalibrationState(status = GpuCalStatus.RUNNING)
        assertEquals(RenderMode.ECO_2D, RenderModePolicy.effective(RenderMode.MODE_3D, 0, null, true, running).mode)
        val none = GpuCalibrationState(status = GpuCalStatus.DONE, calibrated = null, active = null)
        assertEquals(RenderMode.ECO_2D, RenderModePolicy.effective(RenderMode.MODE_3D, 0, null, true, none).mode)
        val ok = GpuCalibrationState(status = GpuCalStatus.DONE, calibrated = EffectLevel.REDUCED, active = EffectLevel.MINIMAL)
        assertEquals(RenderMode.MODE_3D, RenderModePolicy.effective(RenderMode.MODE_3D, 0, null, true, ok).mode)
    }
}
