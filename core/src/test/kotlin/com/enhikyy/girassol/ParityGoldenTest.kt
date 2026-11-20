package com.enhikyy.girassol

import com.enhikyy.girassol.core.ports.ParityVectors
import com.enhikyy.girassol.core.world.Pathfinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Assinaturas fixadas também em native-tests/native_tests.cpp (paridade JVM ↔ C++). */
class ParityGoldenTest {
    @Test
    fun navigationSignatureIsStable() {
        assertEquals(ParityVectors.NAV_GOLDEN, ParityVectors.kotlinNavSignature())
    }

    @Test
    fun navigationVectorExercisesRealPaths() {
        val g = ParityVectors.navGrid()
        var found = 0
        for (k in 0 until ParityVectors.NAV_QUERIES) {
            val q = g.queries
            if (Pathfinding.findPathGrid(g.w, g.tiles, q[k * 4], q[k * 4 + 1], q[k * 4 + 2], q[k * 4 + 3], g.trail, g.danger).isNotEmpty()) found++
        }
        assertTrue("caminhos encontrados: $found", found > 32)
    }

    @Test
    fun loraSignatureIsStable() {
        assertEquals(ParityVectors.LORA_GOLDEN, ParityVectors.kotlinLoraSignature())
    }
}
