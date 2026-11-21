package com.enhikyy.girassol.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.8 — orçamento dinâmico: MemAvailable ∧ ActivityManager − (limiar + Filament + gráfico + 2×WAL + folga). */
class RamBudgetTest {
    private val mb = 1024L * 1024

    @Test
    fun parsesProcMeminfo() {
        val meminfo = """
            MemTotal:        3718484 kB
            MemFree:          120332 kB
            MemAvailable:    1433600 kB
            Buffers:            4100 kB
        """.trimIndent()
        assertEquals(1_433_600L, RamBudget.parseMemAvailableKb(meminfo))
        assertEquals(-1L, RamBudget.parseMemAvailableKb("MemTotal: 1 kB\n"))
        assertEquals(-1L, RamBudget.parseMemAvailableKb("MemAvailable: lixo kB"))
    }

    private fun inputs(total: Long, kernelMb: Long, amMb: Long, filament: Long = 0, wal: Long = 0, low: Boolean = false) =
        RamBudget.Inputs(total, if (kernelMb < 0) -1 else kernelMb * 1024, amMb * mb, 200 * mb, low, filament, 50 * mb, wal)

    @Test
    fun usesTheSmallerSourceAndSubtractsReserves() {
        val b = RamBudget.compute(inputs(7_800 * mb, 3_000, 2_500, filament = 100 * mb, wal = 20 * mb))
        assertEquals(2_500 * mb, b.availableBytes)
        assertEquals("ActivityManager", b.source)
        assertEquals((200 + 100 + 50 + 40) * mb + RamBudget.MIN_FREE_BYTES, b.reserveBytes)
        assertEquals(2_500 * mb - b.reserveBytes, b.dynamicBytes)
        val k = RamBudget.compute(inputs(7_800 * mb, 1_000, 2_500))
        assertEquals("/proc/meminfo", k.source)
        assertEquals(1_000 * mb, k.availableBytes)
        val noKernel = RamBudget.compute(inputs(7_800 * mb, -1, 2_000))
        assertEquals("ActivityManager", noKernel.source)
    }

    @Test
    fun dynamicIsClampedBetweenZeroAndProfileCeiling() {
        val rich = RamBudget.compute(inputs(11_600 * mb, 9_000, 9_000))
        assertEquals(LlmQuantPolicy.budgetFor(11_600 * mb), rich.dynamicBytes)
        val starved = RamBudget.compute(inputs(3_631 * mb, 400, 400, filament = 300 * mb))
        assertEquals(0L, starved.dynamicBytes)
        assertFalse(starved.fits(1))
        val ok = RamBudget.compute(inputs(3_631 * mb, 1_800, 1_800))
        assertTrue(ok.fits(305 * mb))
        assertFalse(RamBudget.compute(inputs(3_631 * mb, 1_800, 1_800, low = true)).fits(1))
    }
}
