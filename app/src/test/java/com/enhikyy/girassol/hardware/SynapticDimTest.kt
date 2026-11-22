package com.enhikyy.girassol.hardware

import org.junit.Assert.assertEquals
import org.junit.Test

class SynapticDimTest {
    @Test
    fun dimensionFollowsRamTiers() {
        assertEquals(128, HardwareAutoTuner.synapticDimForRam(2048))  // emulador headless do CI
        assertEquals(256, HardwareAutoTuner.synapticDimForRam(4096))
        assertEquals(256, HardwareAutoTuner.synapticDimForRam(7600))  // 8 GB: D = 256, sem teto
        assertEquals(256, HardwareAutoTuner.synapticDimForRam(10239))
        assertEquals(256, HardwareAutoTuner.synapticDimForRam(11500)) // 12 GB
        assertEquals(256, HardwareAutoTuner.synapticDimForRam(24000))
    }
}
