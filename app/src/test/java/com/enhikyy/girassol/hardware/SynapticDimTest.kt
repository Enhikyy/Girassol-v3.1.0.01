package com.enhikyy.girassol.hardware

import org.junit.Assert.assertEquals
import org.junit.Test

class SynapticDimTest {
    @Test
    fun dimensionFollowsRamTiers() {
        assertEquals(64, HardwareAutoTuner.synapticDimForRam(3800))   // aparelho de 4 GB
        assertEquals(64, HardwareAutoTuner.synapticDimForRam(6143))
        assertEquals(128, HardwareAutoTuner.synapticDimForRam(7600))  // 8 GB
        assertEquals(128, HardwareAutoTuner.synapticDimForRam(10239))
        assertEquals(256, HardwareAutoTuner.synapticDimForRam(11500)) // 12 GB
        assertEquals(256, HardwareAutoTuner.synapticDimForRam(24000))
    }
}
