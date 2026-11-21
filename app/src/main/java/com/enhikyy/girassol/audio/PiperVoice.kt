package com.enhikyy.girassol.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.enhikyy.girassol.llm.LlmQuantPolicy
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v3.7 — Piper VITS pt-BR (Sherpa-ONNX) como TTS opcional nos perfis 8/12 GB. O pacote vem de
 * [VoicePacks.PIPER_PT_BR] (SHA-256 do arquivo e de cada modelo revalidados antes de carregar).
 * Áudio em streaming para AudioTrack estéreo com pan pela posição do cidadão; amplitude RMS vai
 * para [VoiceAmplitude] (boca/gestos no Filament), igual aos demais caminhos de voz.
 */
object PiperVoice {
    private const val TAG = "GirassolPiper"
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "girassol-piper").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)
    @Volatile private var tts: OfflineTts? = null
    @Volatile var lastError: String = ""; private set

    fun available(context: Context, profile: LlmQuantPolicy.DeviceProfile): Boolean =
        VoicePacks.allowedFor(profile) && VoicePacks.isInstalled(VoicePacks.dirOf(context, VoicePacks.PIPER_PT_BR), VoicePacks.PIPER_PT_BR)

    @Synchronized
    private fun ensure(context: Context, profile: LlmQuantPolicy.DeviceProfile): OfflineTts {
        tts?.let { return it }
        val dir = VoicePacks.verifiedDir(context, VoicePacks.PIPER_PT_BR, profile) ?: error("pacote Piper ausente ou com SHA-256 inválido")
        return createEngine(dir).also { tts = it }
    }

    /** Carrega o Piper VITS pt-BR de um diretório verificado (caminhos absolutos em filesDir). */
    fun createEngine(dir: File): OfflineTts {
        val vits = OfflineTtsVitsModelConfig().apply {
            model = File(dir, "pt_BR-faber-medium.onnx").absolutePath
            tokens = File(dir, "tokens.txt").absolutePath
            dataDir = File(dir, "espeak-ng-data").absolutePath
        }
        val model = OfflineTtsModelConfig().apply {
            this.vits = vits
            numThreads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
            provider = "cpu"
        }
        // assetManager = null → caminhos absolutos no armazenamento interno
        return OfflineTts(null, OfflineTtsConfig().apply { this.model = model })
    }

    fun speak(context: Context, profile: LlmQuantPolicy.DeviceProfile, citizenId: String, text: String, pan: Float,
              pitchFemale: Boolean = false, onState: (Boolean) -> Unit = {}): Boolean {
        if (text.isBlank() || !busy.compareAndSet(false, true)) return false
        val app = context.applicationContext
        exec.execute {
            var track: AudioTrack? = null
            try {
                val engine = ensure(app, profile)
                val rate = engine.sampleRate()
                track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT) * 4)
                    .build()
                if (pitchFemale) runCatching { track.playbackParams = track.playbackParams.setPitch(1.15f) }
                track.play()
                onState(true)
                val l = ((1f - pan) / 2f).coerceIn(0f, 1f); val r = ((1f + pan) / 2f).coerceIn(0f, 1f)
                val t = track
                engine.generateWithCallback(text.take(400), 0, 1.0f) { samples ->
                    VoiceAmplitude.pushFloat(citizenId, samples)
                    val st = FloatArray(samples.size * 2)
                    for (i in samples.indices) { st[2 * i] = samples[i] * l; st[2 * i + 1] = samples[i] * r }
                    t.write(st, 0, st.size, AudioTrack.WRITE_BLOCKING)
                    1
                }
                lastError = ""
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "Piper falhou", t)
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
