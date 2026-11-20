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
}
