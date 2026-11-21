package com.enhikyy.girassol.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.enhikyy.girassol.knowledge.AudioTranscriber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * v3.9 — ditado offline da cápsula de comando: grava PCM 16 kHz mono com AudioRecord enquanto
 * o microfone está ativo e transcreve no aparelho (Whisper tiny pt / SenseVoice-Small).
 */
object MicDictation {
    enum class Phase { IDLE, RECORDING, TRANSCRIBING }
    data class State(val phase: Phase = Phase.IDLE, val level: Float = 0f, val error: String = "")

    private const val TAG = "GirassolMic"
    private const val RATE = 16_000
    private const val MAX_SECONDS = 30
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "girassol-mic").apply { isDaemon = true } }
    @Volatile private var recording = false

    /** Requer RECORD_AUDIO já concedida. [onText] roda na thread do microfone. */
    @SuppressLint("MissingPermission")
    fun start(context: Context, onText: (String) -> Unit) {
        if (recording || _state.value.phase == Phase.TRANSCRIBING) return
        val app = context.applicationContext
        recording = true
        _state.value = State(Phase.RECORDING)
        worker.execute {
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
            val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2) }
                .getOrNull()
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                recording = false; rec?.release()
                _state.value = State(error = "Microfone indisponível"); return@execute
            }
            val out = FloatArray(RATE * MAX_SECONDS)
            var n = 0
            val buf = ShortArray(minBuf / 2)
            try {
                rec.startRecording()
                while (recording && n < out.size) {
                    val r = rec.read(buf, 0, buf.size)
                    if (r <= 0) continue
                    var peak = 0f
                    for (i in 0 until r) { if (n >= out.size) break; val v = buf[i] / 32768f; out[n++] = v; peak = maxOf(peak, kotlin.math.abs(v)) }
                    _state.value = State(Phase.RECORDING, level = peak)
                }
            } finally { runCatching { rec.stop() }; rec.release() }
            recording = false
            _state.value = State(Phase.TRANSCRIBING)
            val text = runCatching { AudioTranscriber.transcribeMic(app, out.copyOf(n)) }
                .onFailure { Log.w(TAG, "transcrição falhou", it) }
                .getOrElse { _state.value = State(error = it.message ?: "Transcrição indisponível"); return@execute }
            _state.value = State()
            if (text.isNotBlank()) onText(text)
        }
    }

    fun stop() { recording = false }
}
