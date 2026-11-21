package com.enhikyy.girassol

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.enhikyy.girassol.audio.PiperVoice
import com.enhikyy.girassol.audio.VoicePacks
import com.enhikyy.girassol.knowledge.AudioTranscriber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * v3.8 — download REAL (GitHub releases do sherpa-onnx) com SHA-256 fixado, extração atômica e
 * carregamento nativo: Piper VITS pt-BR sintetiza e o Whisper tiny transcreve a fala do Piper.
 * Também simula a queda da conexão no meio do download para validar a retomada por Range.
 */
@RunWith(AndroidJUnit4::class)
class VoicePackDeviceTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun freshRoot(name: String) = File(ctx.cacheDir, name).apply { deleteRecursively(); mkdirs() }

    /** Derruba a conexão real depois de [cut] bytes (uma vez) para forçar a retomada. */
    private fun cuttingOpener(cut: Long): (String, Long) -> VoicePacks.Opened {
        var done = false
        return { url, offset ->
            val o = VoicePacks.openHttp(url, offset)
            if (done) o else {
                done = true
                val limited = object : java.io.InputStream() {
                    var n = 0L
                    override fun read(): Int = if (n >= cut) throw java.net.SocketException("queda simulada") else o.input.read().also { if (it >= 0) n++ }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (n >= cut) throw java.net.SocketException("queda simulada")
                        val r = o.input.read(b, off, minOf(len.toLong(), cut - n).toInt())
                        if (r > 0) n += r
                        return r
                    }
                }
                VoicePacks.Opened(limited, o.startOffset, o.close)
            }
        }
    }

    @Test
    fun piperDownloadsResumesSynthesizesAndWhisperTranscribes() {
        val root = freshRoot("voice-it")
        val retries = mutableListOf<Int>()
        val piperDir = VoicePacks.installFrom(root, VoicePacks.PIPER_PT_BR, cuttingOpener(4L * 1024 * 1024),
            onRetry = { a, _ -> retries += a }, sleep = {})
        assertEquals(listOf(1), retries)
        assertTrue(VoicePacks.isInstalled(piperDir, VoicePacks.PIPER_PT_BR))
        assertNull(VoicePacks.verifyFiles(piperDir, VoicePacks.PIPER_PT_BR))
        assertTrue(!VoicePacks.partFile(root, VoicePacks.PIPER_PT_BR).exists())

        val tts = PiperVoice.createEngine(piperDir)
        val audio = try { tts.generate("Olá, bom dia. Hoje vamos colher girassóis na horta.", 0, 1.0f) } finally { tts.release() }
        assertEquals(22_050, audio.sampleRate)
        assertTrue("Piper gerou ${audio.samples.size} amostras", audio.samples.size > audio.sampleRate)
        assertTrue(audio.samples.any { kotlin.math.abs(it) > 0.01f })

        val whisperDir = VoicePacks.installFrom(root, VoicePacks.WHISPER_TINY, VoicePacks::openHttp, sleep = {})
        assertNull(VoicePacks.verifyFiles(whisperDir, VoicePacks.WHISPER_TINY))
        // reamostra 22,05 kHz → 16 kHz (interpolação linear) e transcreve
        val ratio = audio.sampleRate / 16_000.0
        val pcm = FloatArray((audio.samples.size / ratio).toInt()) { i ->
            val x = i * ratio; val a = x.toInt(); val f = (x - a).toFloat()
            val s0 = audio.samples[a]; val s1 = audio.samples[minOf(a + 1, audio.samples.size - 1)]
            s0 + (s1 - s0) * f
        }
        val rec = AudioTranscriber.whisperFrom(whisperDir)
        val text = try { AudioTranscriber.transcribePcm(rec, pcm) } finally { rec.release() }
        android.util.Log.i("VoicePackDeviceTest", "Whisper: '$text'")
        assertTrue("Whisper não transcreveu a fala do Piper", text.isNotBlank())
        root.deleteRecursively()
    }
}
