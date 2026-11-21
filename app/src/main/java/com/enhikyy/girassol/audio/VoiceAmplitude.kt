package com.enhikyy.girassol.audio

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Amplitude vocal (RMS 0..1) do cidadão que está falando — alimenta a abertura da boca e o
 * gesto procedural no Filament. Escrita pela thread de áudio (Pocket-TTS ou o callback
 * onAudioAvailable do TextToSpeech), lida pelo renderizador a cada quadro com decaimento.
 */
object VoiceAmplitude {
    private class Sample(@Volatile var value: Float, @Volatile var atNanos: Long)
    private val levels = ConcurrentHashMap<String, Sample>()
    private const val DECAY_S = 0.12

    fun pushPcm16(citizenId: String, pcm: ByteArray, len: Int = pcm.size) {
        var acc = 0.0; var n = 0
        var i = 0
        while (i + 1 < len) { val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort() / 32768.0; acc += s * s; n++; i += 2 }
        if (n > 0) push(citizenId, sqrt(acc / n).toFloat())
    }

    fun pushFloat(citizenId: String, x: FloatArray) {
        if (x.isEmpty()) return
        var acc = 0.0; for (v in x) acc += v * v
        push(citizenId, sqrt(acc / x.size).toFloat())
    }

    fun push(citizenId: String, rms: Float) {
        val v = (rms * 4f).coerceIn(0f, 1f)  // fala típica tem RMS ~0,05–0,25
        val s = levels.getOrPut(citizenId) { Sample(0f, 0L) }
        s.value = v; s.atNanos = System.nanoTime()
    }

    /** Amplitude atual com decaimento exponencial desde a última amostra. */
    fun level(citizenId: String, nowNanos: Long = System.nanoTime()): Float {
        val s = levels[citizenId] ?: return 0f
        val dt = (nowNanos - s.atNanos) / 1e9
        if (dt > 1.0) { levels.remove(citizenId); return 0f }
        return (s.value * exp(-dt / DECAY_S)).toFloat()
    }

    fun clear() = levels.clear()
}
