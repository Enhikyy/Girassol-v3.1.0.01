package com.enhikyy.girassol.audio

import com.enhikyy.girassol.knowledge.AudioTranscriber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioInputSafetyTest {
    private fun pcm16Wav(): ByteArray {
        val b = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(38)
        b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16)
        b.putShort(1.toShort())       // PCM
        b.putShort(1.toShort())       // mono
        b.putInt(16_000)
        b.putInt(32_000)
        b.putShort(2.toShort())
        b.putShort(16.toShort())
        b.put("data".toByteArray()).putInt(2)
        b.putShort(16_384.toShort())  // 0.5
        return b.array()
    }

    @Test
    fun decodesValidPcm16Wav() {
        val decoded = PocketTtsVoice.readWav(pcm16Wav())
        assertNotNull(decoded)
        assertEquals(16_000, decoded!!.second)
        assertEquals(1, decoded.first.size)
        assertEquals(0.5f, decoded.first[0], 0.0001f)
    }

    @Test
    fun rejectsNegativeOrOutOfRangeChunkLength() {
        val negative = pcm16Wav()
        ByteBuffer.wrap(negative).order(ByteOrder.LITTLE_ENDIAN).putInt(16, -1)
        assertNull(PocketTtsVoice.readWav(negative))

        val tooLarge = pcm16Wav()
        ByteBuffer.wrap(tooLarge).order(ByteOrder.LITTLE_ENDIAN).putInt(16, Int.MAX_VALUE)
        assertNull(PocketTtsVoice.readWav(tooLarge))
    }

    @Test
    fun rejectsTruncatedFormatAndInvalidChannelCount() {
        val truncated = pcm16Wav()
        ByteBuffer.wrap(truncated).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 12)
        assertNull(PocketTtsVoice.readWav(truncated))

        val noChannels = pcm16Wav()
        ByteBuffer.wrap(noChannels).order(ByteOrder.LITTLE_ENDIAN).putShort(22, 0)
        assertNull(PocketTtsVoice.readWav(noChannels))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidResamplingRate() {
        AudioTranscriber.resampleLinear(floatArrayOf(0.5f), 0, 16_000)
    }
}
