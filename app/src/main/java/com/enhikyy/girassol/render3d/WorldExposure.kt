package com.enhikyy.girassol.render3d

import kotlin.math.log2
import kotlin.math.pow

/** Faixa de iluminação física da Aba Mundo (câmera + luz direcional + céu/IBL). */
enum class LightBand { DAY, DUSK, NIGHT, INTERIOR }

/**
 * Câmera física (f/N, obturador em s, ISO) + iluminâncias da faixa, aplicadas em conjunto
 * com `Camera.setExposure(aperture, shutterSpeed, sensitivity)` — nunca exposição fixa.
 */
data class ExposureSetting(
    val aperture: Float, val shutter: Float, val iso: Float,
    /** Luz direcional (lux). */
    val sunLux: Float,
    /** Céu/IBL: iluminância no plano horizontal (lux). */
    val iblLux: Float
) {
    /** EV100 = log2(N²/t) − log2(S/100). */
    val ev100: Float get() = (log2(aperture * aperture / shutter) - log2(iso / 100f))
    /** Fator de exposição do Filament: 1 / (1,2 · 2^EV100). */
    val exposure: Float get() = (1.0 / (1.2 * 2.0.pow(ev100.toDouble()))).toFloat()
    /** Luminância pré-tonemapping de um difuso de albedo 0,5 sob a luz direcional (NdotL = 1). */
    val diffuseSun: Float get() = (0.5f / Math.PI.toFloat()) * sunLux * exposure
    val diffuseSky: Float get() = (0.5f / Math.PI.toFloat()) * iblLux * exposure
}

object WorldExposure {
    /**
     * Noite e Interior usam a câmera pedida (f/4, 1/30 s, ISO 400 → EV100 ≈ 6,9). Com 3 500 lux de
     * direcional essa câmera leva um difuso de albedo 0,5 a ≈ 3,9 antes do tonemapping (branco
     * estourado); por isso a direcional noturna/interna fica em 350 lux e o céu em 250 lux, mantendo
     * a mesma câmera e a proporção luz:céu — nunca zero, para não haver tela preta.
     */
    const val NIGHT_SUN_LUX = 350f
    const val NIGHT_IBL_LUX = 250f

    /** v4.1 (Kodak 70 mm): IBL a 12–18 % da luz-chave → sombras profundas e bem recortadas. */
    const val IBL_FRACTION_DAY = 0.15f
    const val IBL_FRACTION_DUSK = 0.14f
    val DAY = ExposureSetting(16f, 1f / 125f, 100f, 65000f, 65000f * IBL_FRACTION_DAY)
    val DUSK = ExposureSetting(11f, 1f / 60f, 100f, 25000f, 25000f * IBL_FRACTION_DUSK)
    val NIGHT = ExposureSetting(4f, 1f / 30f, 400f, NIGHT_SUN_LUX, NIGHT_IBL_LUX)
    val INTERIOR = NIGHT

    /** Entardecer: 16h48–18h43 da fração do dia (o resto do período claro é "Dia/Tarde"). */
    fun band(dayFraction: Float, preset: WorldPreset): LightBand {
        val ph = TimePhase.of(dayFraction, preset)
        return when {
            ph == TimePhase.INTERIOR -> LightBand.INTERIOR
            ph == TimePhase.NIGHT -> LightBand.NIGHT
            ph == TimePhase.DUSK -> LightBand.DUSK
            else -> LightBand.DAY
        }
    }

    fun setting(b: LightBand): ExposureSetting = when (b) {
        LightBand.DAY -> DAY
        LightBand.DUSK -> DUSK
        LightBand.NIGHT -> NIGHT
        LightBand.INTERIOR -> INTERIOR
    }

    /** Iluminância de céu de referência por fase do IBL (calibra a intensidade do Skybox). */
    fun iblLuxFor(p: TimePhase): Float = when (p) {
        TimePhase.MORNING -> DAY.iblLux
        TimePhase.AFTERNOON -> DAY.iblLux
        TimePhase.DUSK -> DUSK.iblLux
        TimePhase.NIGHT -> NIGHT.iblLux
        TimePhase.INTERIOR -> INTERIOR.iblLux
    }
}
