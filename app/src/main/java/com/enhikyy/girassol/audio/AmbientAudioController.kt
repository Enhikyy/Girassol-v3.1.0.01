package com.enhikyy.girassol.audio

import com.enhikyy.girassol.core.world.WorldRenderSnapshot
import com.enhikyy.girassol.core.world.DashboardSnapshot
import com.enhikyy.girassol.nativebridge.GeaNativeBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Controla o sintetizador binaural C++ (AAudio). A estabilidade social (1 − PSI) define o
 * batimento binaural, a emoção coletiva o timbre e a tensão o ruído de sala; falas novas
 * dos cidadãos geram blips espacializados relativos ao centro da cidade.
 */
object AmbientAudioController {
    private val _status = MutableStateFlow("desligado")
    val status: StateFlow<String> = _status.asStateFlow()
    @Volatile var running = false
        private set
    private val lastSpoken = HashMap<String, Long>()

    fun start(): Boolean {
        if (!GeaNativeBridge.isNativeLoaded) { _status.value = "biblioteca nativa indisponível"; return false }
        running = runCatching { GeaNativeBridge.nativeAudioStart() }.getOrDefault(false)
        _status.value = runCatching { GeaNativeBridge.nativeAudioStatus() }.getOrDefault("erro")
        return running
    }

    fun stop() {
        if (GeaNativeBridge.isNativeLoaded) runCatching { GeaNativeBridge.nativeAudioStop() }
        running = false
        _status.value = "silenciado"
    }

    fun refreshStatus() {
        if (running) _status.value = runCatching { GeaNativeBridge.nativeAudioStatus() }.getOrDefault("erro")
    }

    fun update(dash: DashboardSnapshot?, render: WorldRenderSnapshot?) {
        if (!running) { speakNewest(render); return }
        if (dash != null) {
            val psi = (dash.society.cliodynamics?.politicalStressIndicator ?: 0.0).toFloat()
            val stability = (1f - psi.coerceIn(0f, 1f))
            val mood = dash.mind.meanMood.toFloat().coerceIn(-1f, 1f)
            val tension = (dash.mind.metrics.meanFreeEnergy.toFloat() / 2f).coerceIn(0f, 1f)
            runCatching { GeaNativeBridge.nativeAudioSetParams(stability, mood, tension, 0.35f) }
        }
        if (render != null) {
            val half = render.widthTiles / 2f
            for (a in render.agents) {
                if (a.speech.isBlank() || a.speechAgeTicks > 2) continue
                if (lastSpoken[a.id] == render.tick - a.speechAgeTicks) continue
                lastSpoken[a.id] = render.tick - a.speechAgeTicks
                val pitch = ((a.id.hashCode() and 0xFF) / 255f)
                runCatching { GeaNativeBridge.nativeAudioSpeech((a.fx - half) / 4f, (a.fz - half) / 4f, pitch) }
            }
            if (lastSpoken.size > 400) lastSpoken.clear()
        }
        speakNewest(render)
    }

    private var lastTtsTick = -1L

    /** Voz TTS offline para a fala mais recente (independe do sintetizador AAudio). */
    private fun speakNewest(render: WorldRenderSnapshot?) {
        if (render == null || !CitizenVoice.status.value.enabled) return
        val a = render.agents.filter { it.speech.isNotBlank() && it.speechAgeTicks <= 2 }.minByOrNull { it.speechAgeTicks } ?: return
        val tick = render.tick - a.speechAgeTicks
        if (tick == lastTtsTick) return
        lastTtsTick = tick
        CitizenVoice.speak(a.id, a.speech, CitizenVoice.panFor(a.fx, render.widthTiles / 2f))
    }
}
