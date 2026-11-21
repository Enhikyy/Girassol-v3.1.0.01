package com.enhikyy.girassol.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.enhikyy.girassol.nativebridge.GeaNativeBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

data class VoiceStatus(
    val enabled: Boolean = false,
    val engine: String = "-",
    val voice: String = "-",
    val offline: Boolean = false,
    val spoken: Long = 0,
    val skipped: Long = 0,
    val message: String = "desligado"
)

/**
 * Vozes dos cidadãos. Padrão: TextToSpeech do Android com voz pt-BR **offline**
 * (isNetworkConnectionRequired = false). Opcional (v3.5): Pocket-TTS neural do Sherpa-ONNX
 * embutido no APK com modelos ([PocketTtsVoice], centrado em inglês). Nos dois caminhos a fala
 * é posicionada em estéreo pela posição do cidadão, abaixa a trilha Trautonium (ducking) e a
 * amplitude RMS vai para [VoiceAmplitude] (boca e gestos sincronizados no Filament).
 */
object CitizenVoice {
    private const val TAG = "GirassolTTS"
    private const val MIN_GAP_MS = 2_500L
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var lastSpeakMs = 0L
    @Volatile var usePocketTts = false
    @Volatile private var appCtx: Context? = null
    private val _status = MutableStateFlow(VoiceStatus())
    val status: StateFlow<VoiceStatus> = _status.asStateFlow()

    fun setEnabled(context: Context, on: Boolean) {
        val app = context.applicationContext
        appCtx = app
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (on && tts == null) start(app)
            if (!on && tts != null) stop()
        }
    }

    private fun start(context: Context) {
        _status.update { it.copy(enabled = true, message = "iniciando TextToSpeech") }
        tts = TextToSpeech(context) { code ->
            val t = tts ?: return@TextToSpeech
            if (code != TextToSpeech.SUCCESS) {
                _status.update { it.copy(message = "TextToSpeech indisponível ($code)") }
                return@TextToSpeech
            }
            val ptBr = Locale.forLanguageTag("pt-BR")
            val lang = t.setLanguage(ptBr)
            val offlineVoice = runCatching {
                t.voices?.filter { v -> v.locale.language == "pt" && !v.isNetworkConnectionRequired }
                    ?.minByOrNull { v -> (if (v.locale.country == "BR") 0 else 1) * 1000 - v.quality }
            }.getOrNull()
            if (offlineVoice != null) t.voice = offlineVoice
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = duck(true)
                override fun onDone(utteranceId: String?) = duck(false)
                override fun onAudioAvailable(utteranceId: String?, audio: ByteArray?) {
                    if (utteranceId != null && audio != null) VoiceAmplitude.pushPcm16(utteranceId.substringBeforeLast('-'), audio)
                }
                @Deprecated("API antiga", ReplaceWith("onError(utteranceId, code)"))
                override fun onError(utteranceId: String?) = duck(false)
                override fun onError(utteranceId: String?, errorCode: Int) = duck(false)
            })
            ready = lang != TextToSpeech.LANG_MISSING_DATA && lang != TextToSpeech.LANG_NOT_SUPPORTED
            _status.update {
                it.copy(engine = t.defaultEngine ?: "-", voice = offlineVoice?.name ?: "padrão",
                    offline = offlineVoice != null,
                    message = when {
                        !ready -> "sem dados de voz pt-BR instalados"
                        offlineVoice == null -> "pronto (voz padrão; sem voz pt offline confirmada)"
                        else -> "pronto (voz pt offline)"
                    })
            }
        }
    }

    private fun duck(speaking: Boolean) {
        if (GeaNativeBridge.isNativeLoaded) runCatching { GeaNativeBridge.nativeAudioSetExternalSpeech(speaking) }
    }

    /** Pan estéreo −1..1 a partir da posição contínua relativa ao centro da cidade. */
    fun panFor(fx: Float, half: Float): Float = if (half <= 0f) 0f else ((fx - half) / half).coerceIn(-1f, 1f)

    /** Fala uma frase (limite de uma a cada [MIN_GAP_MS]); retorna se foi enfileirada. */
    fun speak(id: String, text: String, pan: Float): Boolean {
        val ctx = appCtx
        if (usePocketTts && ctx != null && PocketTtsVoice.available(ctx)) {
            val now = System.currentTimeMillis()
            if (now - lastSpeakMs < MIN_GAP_MS) { _status.update { it.copy(skipped = it.skipped + 1) }; return false }
            val ok = PocketTtsVoice.speak(ctx, id, text, pan) { speaking -> duck(speaking) }
            if (ok) { lastSpeakMs = now; _status.update { it.copy(spoken = it.spoken + 1, engine = "Pocket-TTS (Sherpa-ONNX)") } }
            return ok
        }
        val t = tts
        if (t == null || !ready || text.isBlank()) return false
        val now = System.currentTimeMillis()
        if (now - lastSpeakMs < MIN_GAP_MS || t.isSpeaking) {
            _status.update { it.copy(skipped = it.skipped + 1) }
            return false
        }
        lastSpeakMs = now
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, pan.coerceIn(-1f, 1f)) }
        val r = t.speak(text.take(160), TextToSpeech.QUEUE_FLUSH, params, "$id-$now")
        if (r == TextToSpeech.SUCCESS) _status.update { it.copy(spoken = it.spoken + 1) }
        else Log.w(TAG, "speak falhou: $r")
        return r == TextToSpeech.SUCCESS
    }

    fun stop() {
        ready = false
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
        duck(false)
        _status.update { it.copy(enabled = false, message = "desligado") }
    }
}
