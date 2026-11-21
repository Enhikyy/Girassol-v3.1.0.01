package com.enhikyy.girassol.ui.glass
import com.enhikyy.girassol.hardware.HardwareProfile
import org.junit.Assert.assertEquals
import org.junit.Test
class GlassTierPolicyTest {
    @Test fun selectsThreeTiers() {
        assertEquals(GlassTier.LOW, GlassTierPolicy.resolve(4096, HardwareProfile.Tier.BASICO, dynamicMemoryMb = 2048))
        assertEquals(GlassTier.MID, GlassTierPolicy.resolve(8192, HardwareProfile.Tier.MEDIO, dynamicMemoryMb = 2048))
        assertEquals(GlassTier.HIGH, GlassTierPolicy.resolve(12288, HardwareProfile.Tier.MEDIO, dynamicMemoryMb = 2048))
    }
    @Test fun lowMemoryAlwaysDegrades() {
        assertEquals(GlassTier.LOW, GlassTierPolicy.resolve(12288, HardwareProfile.Tier.ALTO, lowMemory = true, dynamicMemoryMb = 2048))
        assertEquals(GlassTier.LOW, GlassTierPolicy.resolve(8192, HardwareProfile.Tier.MEDIO, dynamicMemoryMb = 128))
        assertEquals(GlassTier.LOW, GlassTierPolicy.resolve(8192, HardwareProfile.Tier.MEDIO, dynamicMemoryMb = 512))
    }
}