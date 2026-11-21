package com.enhikyy.girassol.ui.glass
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.enhikyy.girassol.hardware.HardwareAutoTuner
import com.enhikyy.girassol.hardware.HardwareProfile
import com.enhikyy.girassol.llm.RamBudget

enum class GlassTier(val rank: Int) { LOW(0), MID(1), HIGH(2) }
object GlassTierPolicy {
    fun resolve(totalRamMb: Long, hardwareTier: HardwareProfile.Tier, lowMemory: Boolean = false, dynamicMemoryMb: Long = Long.MAX_VALUE): GlassTier {
        var tier = when { totalRamMb >= 10_240L -> GlassTier.HIGH; totalRamMb >= 6_144L -> GlassTier.MID; else -> GlassTier.LOW }
        if (hardwareTier == HardwareProfile.Tier.BASICO && tier != GlassTier.LOW) tier = GlassTier.MID
        if (lowMemory || dynamicMemoryMb < 256L) return GlassTier.LOW
        if (dynamicMemoryMb < 768L && tier == GlassTier.HIGH) return GlassTier.MID
        if (dynamicMemoryMb < 768L && tier == GlassTier.MID) return GlassTier.LOW
        return tier
    }
}
object GlassPerformanceController {
    var tier by mutableStateOf(GlassTier.LOW)
        private set
    fun load(context: Context) {
        runCatching {
            val hw = HardwareAutoTuner.detect(context)
            val budget = RamBudget.read(context)
            tier = GlassTierPolicy.resolve(hw.totalRamMb, hw.tier, budget.lowMemory, budget.dynamicMb)
        }.onFailure { tier = GlassTier.LOW }
    }
}
val LocalGlassTier = staticCompositionLocalOf { GlassTier.LOW }
