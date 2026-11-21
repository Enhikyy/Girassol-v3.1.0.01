package com.enhikyy.girassol.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Voz neural offline Pocket-TTS (Sherpa-ONNX int8, clonagem por áudio de referência).
 * Os blocos de áudio chegam pelo callback de geração, vão direto para um AudioTrack em
 * streaming e alimentam [VoiceAmplitude] (boca/gesto no Filament). Pocket-TTS é centrado em
 * inglês; frases em português saem com sotaque (limitação documentada) — o TextToSpeech pt-BR
 * do Android continua como padrão e o Pocket-TTS é opcional.
 */
object PocketTtsVoice {
    private const val TAG = "GirassolPocketTts"
    private const val DIR = "sherpa/pocket-tts"
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "girassol-pocket-tts").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var reference: Pair<FloatArray, Int>? = null
    @Volatile var lastError: String = ""; private set

    private fun files(context: Context): List<String> = context.assets.list(DIR)?.toList() ?: emptyList()

    fun available(context: Context): Boolean = runCatching {
        val f = files(context); listOf("lm_flow", "lm_main", "encoder", "decoder", "text_cond").all { k -> f.any { it.contains(k) && it.endsWith(".onnx") } }
    }.getOrDefault(false)

    @Synchronized
    private fun ensure(context: Context): OfflineTts {
        tts?.let { return it }
        val f = files(context)
        fun pick(key: String) = f.filter { it.contains(key) && it.endsWith(".onnx") }.minByOrNull { if (it.contains("int8")) 0 else 1 }
            ?.let { "$DIR/$it" } ?: error("Pocket-TTS: arquivo '$key' ausente")
        val pocket = OfflineTtsPocketModelConfig().apply {
            lmFlow = pick("lm_flow"); lmMain = pick("lm_main"); encoder = pick("encoder"); decoder = pick("decoder")
            textConditioner = pick("text_cond")
            vocabJson = "$DIR/vocab.json"; tokenScoresJson = "$DIR/token_scores.json"
            voiceEmbeddingCacheCapacity = 8
        }
        val model = OfflineTtsModelConfig().apply {
            this.pocket = pocket
            numThreads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
        }
        reference = f.firstOrNull { it.endsWith(".wav") }?.let { readWav(context.assets.open("$DIR/$it").readBytes()) }
        return OfflineTts(context.assets, OfflineTtsConfig().apply { this.model = model }).also { tts = it }
    }

    /** WAV PCM16/float32 → mono float. */
    fun readWav(b: ByteArray): Pair<FloatArray, Int>? {
        if (b.size < 44 || String(b, 0, 4) != "RIFF" || String(b, 8, 4) != "WAVE") return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var p = 12; var ch = 1; var rate = 16000; var bits = 16; var fmt = 1
        var formatSeen = false
        while (p.toLong() + 8L <= b.size.toLong()) {
            val id = String(b, p, 4)
            val len = bb.getInt(p + 4)
            // Nunca use o tamanho declarado sem validar o limite do arquivo (evita overflow e OOB).
            if (len < 0 || p.toLong() + 8L + len.toLong() > b.size.toLong()) return null
            if (id == "fmt ") {
                if (len < 16) return null
                fmt = bb.getShort(p + 8).toInt()
                ch = bb.getShort(p + 10).toInt()
                rate = bb.getInt(p + 12)
                bits = bb.getShort(p + 22).toInt()
                // Este parser suporta exatamente PCM16 e IEEE float32 (mono ou multicanal).
                if (ch !in 1..8 || rate !in 1..192_000 ||
                    !((fmt == 1 && bits == 16) || (fmt == 3 && bits == 32))) return null
                formatSeen = true
            }
            if (id == "data") {
                if (!formatSeen) return null
                val frame = ch * bits / 8
                if (frame <= 0 || len % frame != 0) return null
                val n = len / frame
                val out = FloatArray(n)
                for (i in 0 until n) {
                    var s = 0f
                    for (c in 0 until ch) {
                        val o = p + 8 + i * frame + c * bits / 8
                        s += if (fmt == 3) bb.getFloat(o) else bb.getShort(o) / 32768f
                    }
                    out[i] = s / ch
                }
                return out to rate
            }
            p = (p.toLong() + 8L + len.toLong() + (len and 1).toLong()).toInt()
        }
        return null
    }

    /** Sintetiza e toca (descarta se já houver uma fala em andamento). */
    fun speak(context: Context, citizenId: String, text: String, pan: Float, onState: (Boolean) -> Unit = {}): Boolean {
        if (text.isBlank() || !busy.compareAndSet(false, true)) return false
        val app = context.applicationContext
        exec.execute {
            var track: AudioTrack? = null
            try {
                val engine = ensure(app)
                val rate = engine.sampleRate()
                track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT) * 4)
                    .build()
                track.play()
                onState(true)
                val l = ((1f - pan) / 2f).coerceIn(0f, 1f); val r = ((1f + pan) / 2f).coerceIn(0f, 1f)
                val cfg = GenerationConfig().apply {
                    speed = 1.0f
                    reference?.let { (pcm, sr) -> referenceAudio = pcm; referenceSampleRate = sr }
                }
                val t = track
                engine.generateWithConfigAndCallback(text.take(200), cfg) { samples ->
                    VoiceAmplitude.pushFloat(citizenId, samples)
                    val st = FloatArray(samples.size * 2)
                    for (i in samples.indices) { st[2 * i] = samples[i] * l; st[2 * i + 1] = samples[i] * r }
                    t.write(st, 0, st.size, AudioTrack.WRITE_BLOCKING)
                    1  // continuar
                }
                lastError = ""
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "Pocket-TTS falhou", t)
            } finally {
                runCatching { track?.stop(); track?.release() }
                onState(false)
                busy.set(false)
            }
        }
        return true
    }

    fun release() = exec.execute { synchronized(this) { tts?.release(); tts = null } }
}
