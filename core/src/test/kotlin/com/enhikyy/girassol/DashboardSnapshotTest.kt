package com.enhikyy.girassol

import com.enhikyy.girassol.core.world.DashboardSnapshot
import com.enhikyy.girassol.core.world.WorldCore
import com.enhikyy.girassol.core.world.WorldRenderSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardSnapshotTest {
    @Test
    fun projecoesDaUiRefletemOEstado() {
        val core = WorldCore.create(7L, 24)
        repeat(300) { core.step() }
        val d = DashboardSnapshot.of(core.state)
        assertEquals(core.state.tick, d.tick)
        assertTrue(d.citizens.isNotEmpty())
        assertTrue(d.society.institutions.isNotEmpty())
        val first = d.citizens.first()
        assertNotNull(DashboardSnapshot.detailOf(core.state, first.id))
        val r = WorldRenderSnapshot.of(core.state)
        assertEquals(d.citizens.count { it.alive }, r.agents.size)
    }
}
