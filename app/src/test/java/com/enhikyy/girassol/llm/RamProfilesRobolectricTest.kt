package com.enhikyy.girassol.llm

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.enhikyy.girassol.audio.VoicePacks
import com.enhikyy.girassol.render3d.FilamentMemory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * v3.8 — perfis físicos de RAM simulados no Robolectric (ActivityManager sombreado):
 * 4 GB (SM-A226BR / Moto G62 5G, 3631 MB visíveis), 8 GB e 12 GB.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RamProfilesRobolectricTest {
    private val mb = 1024L * 1024
    private val gb = 1024L * mb
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun device(totalMem: Long, availMem: Long, threshold: Long = 216 * mb, low: Boolean = false) {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().apply {
            this.totalMem = totalMem; this.availMem = availMem; this.threshold = threshold; lowMemory = low
        }
        shadowOf(am).setMemoryInfo(mi)
    }

    @After fun resetFilament() { FilamentMemory.add(-FilamentMemory.nativeBytes()) }

    @Test
    fun profile4GbSmA226brAndMotoG62() {
        device(3631 * mb, 1400 * mb)
        val total = ctx.getSystemService(ActivityManager::class.java).let { am -> ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem }
        val p = LlmQuantPolicy.profileFor(total)
        assertEquals("4 GB", p.label)
        assertEquals(1, p.poolCapacity)
        assertEquals(LlmQuantPolicy.KV_F16, p.kvType)
        assertTrue(LlmQuantPolicy.lazyFor(total))
        assertFalse("4 GB não baixa Piper/Whisper (SenseVoice + TTS do Android offline)", VoicePacks.allowedFor(p))
        val b = RamBudget.read(ctx)
        assertTrue(b.staticBytes <= 1_600L * mb)
        assertTrue(b.dynamicBytes in 0..b.staticBytes)
        assertTrue(b.availableBytes <= 1400 * mb)
    }

    @Test
    fun profile8GbKeepsTwoResidentModelsAndVoicePacks() {
        device(7_800L * mb, 4 * gb)
        val p = LlmQuantPolicy.profileFor(7_800L * mb)
        assertEquals("8 GB", p.label)
        assertEquals(2, p.poolCapacity)
        assertTrue(VoicePacks.allowedFor(p))
        val b = RamBudget.read(ctx)
        assertEquals(LlmQuantPolicy.budgetFor(7_800L * mb), b.staticBytes)
        assertTrue(b.dynamicBytes > 0)
    }

    @Test
    fun profile12GbKeepsThreeResidentModels() {
        device(11_600L * mb, 7 * gb)
        val p = LlmQuantPolicy.profileFor(11_600L * mb)
        assertEquals("12 GB", p.label)
        assertEquals(3, p.poolCapacity)
        assertEquals(4096, p.nCtx)
        assertTrue(VoicePacks.allowedFor(p))
        assertTrue(RamBudget.read(ctx).dynamicBytes > 0)
    }

    @Test
    fun filamentAndLowMemoryShrinkTheDynamicBudget() {
        device(7_800L * mb, 2 * gb)
        val before = RamBudget.read(ctx)
        FilamentMemory.add(600 * mb)
        val after = RamBudget.read(ctx)
        assertTrue("Filament nativo deve ser descontado", after.reserveBytes - before.reserveBytes >= 600 * mb)
        assertTrue(after.dynamicBytes <= before.dynamicBytes)
        device(7_800L * mb, 2 * gb, low = true)
        val low = RamBudget.read(ctx)
        assertTrue(low.lowMemory)
        assertFalse("sob lowMemory nenhum modelo extra é admitido", low.fits(1))
    }

    @Test
    fun pressureTrimLevels() {
        assertTrue(LocalLlmManager.isPressureTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, false))
        assertTrue(LocalLlmManager.isPressureTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, false))
        assertTrue(LocalLlmManager.isPressureTrim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE, false))
        assertTrue(LocalLlmManager.isPressureTrim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, true))
        assertFalse("UI escondida não é pressão", LocalLlmManager.isPressureTrim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, false))
    }
}
