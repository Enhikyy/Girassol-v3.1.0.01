package com.enhikyy.girassol.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryBreakerTest {
    private val mb = MemoryBreaker.MB

    @Test
    fun limitsPerRamClass() {
        assertEquals(1_200 * mb, MemoryBreaker.limitForTotalRam(3L * 1024 * mb))
        // aparelho de "4 GB" reporta ~3,6–3,8 GB em totalMem
        assertEquals(1_800 * mb, MemoryBreaker.limitForTotalRam(3_700L * mb))
        assertEquals(1_800 * mb, MemoryBreaker.limitForTotalRam(4L * 1024 * mb))
        assertEquals(2_600 * mb, MemoryBreaker.limitForTotalRam(6L * 1024 * mb))
        assertEquals(3_200 * mb, MemoryBreaker.limitForTotalRam(8L * 1024 * mb))
    }

    @Test
    fun tripsOnRssAboveLimitAndRearmsWithHysteresis() {
        val b = MemoryBreaker(1_800 * mb)
        assertEquals(MemoryBreaker.Action.NONE, b.onSample(1_000 * mb, 0))
        assertEquals(MemoryBreaker.Action.TRIP, b.onSample(1_900 * mb, 2_000))
        assertTrue(b.open)
        // abaixo do limite mas acima de 80 %: continua aberto
        assertEquals(MemoryBreaker.Action.NONE, b.onSample(1_500 * mb, 70_000))
        // abaixo de 80 %: precisa ficar 60 s abaixo
        assertEquals(MemoryBreaker.Action.NONE, b.onSample(1_000 * mb, 80_000))
        assertEquals(MemoryBreaker.Action.NONE, b.onSample(1_000 * mb, 120_000))
        assertEquals(MemoryBreaker.Action.REARM, b.onSample(1_000 * mb, 140_001))
        assertFalse(b.open)
        assertEquals(1, b.trips)
    }

    @Test
    fun criticalTrimTripsAndDelaysRearm() {
        val b = MemoryBreaker(1_800 * mb)
        assertEquals(MemoryBreaker.Action.TRIP, b.onCriticalSignal("onTrimMemory(15)", 1_000))
        assertEquals(MemoryBreaker.Action.NONE, b.onCriticalSignal("onTrimMemory(15)", 5_000))
        assertEquals(MemoryBreaker.Action.NONE, b.onSample(500 * mb, 30_000))
        assertEquals(MemoryBreaker.Action.NONE, b.onSample(500 * mb, 64_000))
        // novo sinal crítico zera a contagem
        b.onCriticalSignal("onLowMemory", 70_000)
        assertEquals(MemoryBreaker.Action.NONE, b.onSample(500 * mb, 125_000))
        assertEquals(MemoryBreaker.Action.NONE, b.onSample(500 * mb, 130_000))
        assertEquals(MemoryBreaker.Action.REARM, b.onSample(500 * mb, 190_001))
    }

    @Test
    fun unknownRssNeverTripsOrRearms() {
        val b = MemoryBreaker(1_800 * mb)
        assertEquals(MemoryBreaker.Action.NONE, b.onSample(-1, 0))
        b.onCriticalSignal("x", 0)
        assertEquals(MemoryBreaker.Action.NONE, b.onSample(-1, 500_000))
        assertTrue(b.open)
    }

    @Test
    fun mmapFilePagesDoNotCountAsPressure() {
        // SM-A226BR com LFM2.5-230M-F16 (~460 MB) mapeado: VmRSS alto, RssAnon baixo
        val pm = longArrayOf(2_100_000, 2_200_000, 9_000_000, 420_000, 1_680_000, 0)
        assertEquals(420_000L * 1024, MemoryBreaker.pressureBytes(pm))
        val b = MemoryBreaker(MemoryBreaker.limitForTotalRam(3_631L * mb))
        assertEquals(MemoryBreaker.Action.NONE, b.onSample(MemoryBreaker.pressureBytes(pm), 0))
        assertFalse(b.open)
        // sem RssAnon (kernel antigo) cai no VmRSS
        assertEquals(1_000L * 1024, MemoryBreaker.pressureBytes(longArrayOf(1_000, -1, -1, -1, -1, -1)))
    }

    @Test
    fun realLowMemoryTripsAndBlocksRearm() {
        val b = MemoryBreaker(1_800 * mb, rearmHoldMs = 30_000)
        assertEquals(MemoryBreaker.Action.TRIP, b.onSample(400 * mb, 0, systemLowMemory = true))
        assertEquals(MemoryBreaker.Action.NONE, b.onSample(400 * mb, 40_000, systemLowMemory = true))
        assertEquals(MemoryBreaker.Action.NONE, b.onSample(400 * mb, 75_000))
        assertEquals(MemoryBreaker.Action.REARM, b.onSample(400 * mb, 105_001))
    }

    @Test
    fun hidingTheUiNeverTripsTheBreaker() {
        assertFalse(MemoryBreaker.isCriticalTrim(20, false)) // UI_HIDDEN (seletor de arquivos)
        assertFalse(MemoryBreaker.isCriticalTrim(40, false))
        assertFalse(MemoryBreaker.isCriticalTrim(80, false))
        assertTrue(MemoryBreaker.isCriticalTrim(80, true))
        assertTrue(MemoryBreaker.isCriticalTrim(15, false))
    }
}
