package com.enhikyy.girassol

import com.enhikyy.girassol.core.reflex.KotlinTsetlinMachine
import com.enhikyy.girassol.core.util.SplitMix64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        assertEquals("2ed3a414a018e2ec", h.toString(16).padStart(16, '0'))
    }

    @Test
    fun v2RestauraParametrosPrngEContinuaTreinoIdentico() {
        val original = KotlinTsetlinMachine(16, 20, 64, 3.5f, 8, 2026L)
        val data = SplitMix64(99L)
        val features = ByteArray(16)
        repeat(300) {
            for (i in features.indices) features[i] = (data.nextLong() ushr 63).toByte()
            original.trainStep(features, features[0].toInt() == 1 && features[5].toInt() == 0)
        }

        val blob = original.serialize()
        val restored = KotlinTsetlinMachine.deserialize(blob)
        assertNotNull("estado GRST V2 deve ser restaurável", restored)
        assertArrayEquals("serialização restaurada deve ser idêntica", blob, restored!!.serialize())

        repeat(40) { step ->
            for (i in features.indices) features[i] = (data.nextLong() ushr 63).toByte()
            val target = (step % 3) == 0 || (features[0].toInt() == 1 && features[5].toInt() == 0)
            original.trainStep(features, target)
            restored.trainStep(features, target)
            assertEquals(original.predictVote(features), restored.predictVote(features))
            assertArrayEquals("PRNG e autômatos devem permanecer sincronizados", original.serialize(), restored.serialize())
        }
    }

    @Test
    fun leitorAceitaV1LegadoSemAfirmarContinuidadeDoPrng() {
        val source = KotlinTsetlinMachine(8, 10, 32, 3.5f, 5, 99L)
        val features = byteArrayOf(1, 0, 1, 1, 0, 0, 1, 0)
        repeat(25) { source.trainStep(features, it % 2 == 0) }
        val v2 = source.serialize()

        val legacy = java.nio.ByteBuffer.allocate(20 + 10 + 8 * 10 * 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(0x47525354)
            .putInt(8).putInt(10).putInt(32).putInt(5)
        legacy.put(v2, 36, v2.size - 36)
        val restored = KotlinTsetlinMachine.deserialize(legacy.array(), specificityS = 3.5f, rngState = 123L)
        assertNotNull(restored)
        assertEquals(source.predictVote(features), restored!!.predictVote(features))
    }

    @Test
    fun leitorRejeitaAutomaInvalidoEDimensoesInvalidas() {
        val machine = KotlinTsetlinMachine(4, 6, 16, 3.5f, 3, 1L)
        val corrupt = machine.serialize()
        // O primeiro estado começa após o header V2 (36 bytes) e as polaridades.
        corrupt[36 + 6] = 0
        assertNull(KotlinTsetlinMachine.deserialize(corrupt))
        assertNull(KotlinTsetlinMachine.deserialize(byteArrayOf(1, 2, 3)))
    }
}
