package com.enhikyy.girassol

import com.enhikyy.girassol.core.reflex.KotlinTsetlinMachine
import com.enhikyy.girassol.core.util.SplitMix64
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Paridade JVM ↔ C++: o mesmo procedimento roda em native-tests/native_tests.cpp.
 * Se o checksum FNV-1a do blob GRST divergir, Kotlin e nativo deixaram de ser
 * bit a bit equivalentes.
 */
class TsetlinParityTest {
    @Test
    fun vetorDeReferenciaSplitMix64() {
        assertEquals(0xE220A8397B1DCDAFuL.toLong(), SplitMix64(0L).nextLong())
    }

    @Test
    fun assinaturaIgualAoNativo() {
        val tm = KotlinTsetlinMachine(16, 20, 64, 3.5f, 8, 2026L)
        val data = SplitMix64(99L)
        val f = ByteArray(16)
        repeat(300) {
            for (i in f.indices) f[i] = (data.nextLong() ushr 63).toByte()
            tm.trainStep(f, f[0].toInt() == 1 && f[5].toInt() == 0)
        }
        var h = 1469598103934665603uL
        for (b in tm.serialize()) h = (h xor (b.toUByte().toULong())) * 1099511628211uL
        assertEquals("28e9517335a66aea", h.toString(16).padStart(16, '0'))
    }
}
