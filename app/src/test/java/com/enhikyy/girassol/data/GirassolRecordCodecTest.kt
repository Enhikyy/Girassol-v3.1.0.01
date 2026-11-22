package com.enhikyy.girassol.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GirassolRecordCodecTest {
    /** Monta um GRSB no mesmo layout do núcleo C++ (grsb_serialize). */
    private fun grsb(dim: Int, payload: Int = 256): ByteArray {
        val bb = ByteBuffer.allocate(GirassolRecordCodec.GRSB_HEADER_BYTES + payload + 8).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("GRSB".toByteArray()).putInt(1).putInt(dim).putInt(0).putLong(payload.toLong())
        for (i in 0 until payload) bb.put(((i * 37) xor dim).toByte())
        val arr = bb.array()
        val h = GirassolRecordCodec.fnv1a64(arr, 0, GirassolRecordCodec.GRSB_HEADER_BYTES + payload)
        bb.putLong(GirassolRecordCodec.GRSB_HEADER_BYTES + payload, h)
        return arr
    }

    @Test
    fun fnvMatchesReferenceVectors() {
        assertEquals(0xcbf29ce484222325uL.toLong(), GirassolRecordCodec.fnv1a64(ByteArray(0)))
        assertEquals(0xaf63dc4c8601ec8cuL.toLong(), GirassolRecordCodec.fnv1a64("a".toByteArray()))
        assertEquals(0x85944171f73967e8uL.toLong(), GirassolRecordCodec.fnv1a64("foobar".toByteArray()))
    }

    @Test
    fun containerRoundTrip() {
        val world = ByteArray(5000) { (it * 13).toByte() }
        for (d in intArrayOf(64, 128, 256)) {
            val blob = grsb(d)
            val info = GirassolRecordCodec.inspectGrsb(blob)
            assertEquals(d, info.dim)
            val dec = GirassolRecordCodec.decode(GirassolRecordCodec.encode(world, blob))
            assertArrayEquals(world, dec.world)
            assertArrayEquals(blob, dec.synaptic)
        }
    }

    @Test
    fun legacyGrsWithoutFooterPassesThrough() {
        val world = ByteArray(300) { it.toByte() }
        val dec = GirassolRecordCodec.decode(world)
        assertArrayEquals(world, dec.world)
        assertNull(dec.synaptic)
        assertArrayEquals(world, GirassolRecordCodec.encode(world, null))
    }

    @Test
    fun corruptionIsRejected() {
        val blob = grsb(64)
        val flipped = blob.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { GirassolRecordCodec.inspectGrsb(flipped) }
        val badDim = grsb(96)
        assertThrows(IllegalArgumentException::class.java) { GirassolRecordCodec.inspectGrsb(badDim) }
        val container = GirassolRecordCodec.encode(ByteArray(64), blob)
        container[70] = (container[70].toInt() xor 0x40).toByte()
        assertThrows(IllegalArgumentException::class.java) { GirassolRecordCodec.decode(container) }
    }
}
