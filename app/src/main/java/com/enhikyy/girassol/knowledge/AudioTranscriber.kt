package com.enhikyy.girassol.knowledge

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.nio.ByteOrder

/**
 * Transcrição offline de áudio com SenseVoice-Small (Sherpa-ONNX, int8) para a Biblioteca.
 *
 * Pipeline: MediaExtractor/MediaCodec (qualquer formato que o aparelho decodifique: m4a, mp3,
 * ogg, wav, flac…) → PCM 16 bits → mono → reamostragem linear para 16 kHz → janelas de 25 s
 * (o SenseVoice não é streaming) → OfflineRecognizer. Idiomas do modelo: zh/en/ja/ko/yue —
 * português é transcrito foneticamente e com baixa precisão (limitação documentada).
 */
object AudioTranscriber {
    private const val TAG = "GirassolASR"
    const val MODEL_ASSET = "sherpa/sense-voice/model.int8.onnx"
    const val TOKENS_ASSET = "sherpa/sense-voice/tokens.txt"
    private const val SAMPLE_RATE = 16_000
    private const val WINDOW_S = 25
    private const val MAX_SECONDS = 60 * 60

    @Volatile private var recognizer: OfflineRecognizer? = null

    fun modelAvailable(context: Context): Boolean =
        runCatching { context.assets.open(MODEL_ASSET).close(); context.assets.open(TOKENS_ASSET).close(); true }.getOrDefault(false)

    @Synchronized
    private fun recognizer(context: Context): OfflineRecognizer {
        recognizer?.let { return it }
        if (!modelAvailable(context)) error("modelo SenseVoice não empacotado neste build (use o APK com modelos embutidos)")
        val model = OfflineModelConfig().apply {
            senseVoice = OfflineSenseVoiceModelConfig().apply {
                this.model = MODEL_ASSET
                language = "auto"
                useInverseTextNormalization = true
            }
            tokens = TOKENS_ASSET
            numThreads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
            provider = "cpu"
        }
        val cfg = OfflineRecognizerConfig().apply { modelConfig = model }
        return OfflineRecognizer(context.assets, cfg).also { recognizer = it }
    }

    fun release() = synchronized(this) { recognizer?.release(); recognizer = null }

    /** Decodifica o áudio para PCM mono 16 kHz em float [-1, 1]. */
    fun decodeToMono16k(context: Context, uri: Uri): FloatArray {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: run { ex.release(); error("arquivo sem faixa de áudio") }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmFloat = false
        val mono = FloatArrayBuilder()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    }
                    o >= 0 -> {
                        val ob = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        if (pcmFloat) {
                            val fb = ob.asFloatBuffer()
                            while (fb.remaining() >= channels) { var s = 0f; repeat(channels) { s += fb.get() }; mono.add(s / channels) }
                        } else {
                            val sb = ob.asShortBuffer()
                            while (sb.remaining() >= channels) { var s = 0f; repeat(channels) { s += sb.get() / 32768f }; mono.add(s / channels) }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (mono.size > rate.toLong() * MAX_SECONDS) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }; codec.release(); ex.release()
        }
        return resampleLinear(mono.toArray(), rate, SAMPLE_RATE)
    }

    fun resampleLinear(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty()) return x
        val n = (x.size.toLong() * to / from).toInt()
        val out = FloatArray(n)
        val step = from.toDouble() / to
        for (i in 0 until n) {
            val p = i * step
            val k = p.toInt()
            val f = (p - k).toFloat()
            val a = x[k.coerceAtMost(x.size - 1)]
            val b = x[(k + 1).coerceAtMost(x.size - 1)]
            out[i] = a + (b - a) * f
        }
        return out
    }

    /** Transcreve o áudio inteiro em janelas; [onProgress] recebe 0..1. */
    fun transcribe(context: Context, uri: Uri, onProgress: (Float) -> Unit = {}): String {
        val rec = recognizer(context)
        val pcm = decodeToMono16k(context, uri)
        if (pcm.isEmpty()) return ""
        val win = SAMPLE_RATE * WINDOW_S
        val sb = StringBuilder()
        var off = 0
        while (off < pcm.size) {
            val end = minOf(pcm.size, off + win)
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(pcm.copyOfRange(off, end), SAMPLE_RATE)
                rec.decode(stream)
                val t = rec.getResult(stream).text.trim()
                if (t.isNotEmpty()) sb.append(t).append('\n')
            } finally { stream.release() }
            off = end
            onProgress(off.toFloat() / pcm.size)
        }
        Log.i(TAG, "transcritos ${pcm.size / SAMPLE_RATE}s → ${sb.length} caracteres")
        return sb.toString().trim()
    }

    private class FloatArrayBuilder {
        private var a = FloatArray(1 shl 16)
        var size = 0; private set
        fun add(v: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
        fun toArray() = a.copyOf(size)
    }
}
