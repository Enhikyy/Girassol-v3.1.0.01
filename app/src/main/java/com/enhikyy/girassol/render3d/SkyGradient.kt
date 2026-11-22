package com.enhikyy.girassol.render3d

/**
 * v4.1 — céu em gradiente da Aba Mundo (dia / entardecer / noite) integrado à névoa: a cor do
 * horizonte do domo [sky_gradient.mat] é a MESMA cor da névoa da View, então os prédios ao longe
 * se dissolvem no céu sem costura. Kotlin puro (testado na JVM); cores em sRGB.
 */
object SkyGradient {
    /**
     * [zenith]/[horizon]: sRGB; [zenithI]/[horizonI]: intensidade em unidades de tela (pré-tonemapping);
     * [sun]: sRGB do disco, [sunI] intensidade; [stars] 0..1; [gradientExp] curvatura; [haze] altura da bruma.
     */
    data class Sky(
        val zenith: FloatArray, val zenithI: Float,
        val horizon: FloatArray, val horizonI: Float,
        val sun: FloatArray, val sunI: Float,
        val stars: Float, val gradientExp: Float, val haze: Float,
        /** Cor da névoa (sRGB) = horizonte. */
        val fog: FloatArray
    )

    fun of(p: TimePhase): Sky = when (p) {
        TimePhase.MORNING -> sky(floatArrayOf(0.40f, 0.56f, 0.78f), 0.62f, floatArrayOf(0.80f, 0.84f, 0.86f), 0.95f,
            floatArrayOf(1.0f, 0.96f, 0.88f), 5.0f, 0f, 0.55f, 0.10f)
        TimePhase.AFTERNOON -> sky(floatArrayOf(0.36f, 0.50f, 0.74f), 0.58f, floatArrayOf(0.86f, 0.80f, 0.70f), 0.95f,
            floatArrayOf(1.0f, 0.86f, 0.66f), 5.0f, 0f, 0.55f, 0.10f)
        TimePhase.DUSK -> sky(floatArrayOf(0.17f, 0.20f, 0.38f), 0.42f, floatArrayOf(0.98f, 0.56f, 0.32f), 0.95f,
            floatArrayOf(1.0f, 0.52f, 0.22f), 7.0f, 0.15f, 0.40f, 0.16f)
        // v4.1: céu noturno profundo #070B14 no zênite, horizonte azul-ardósia escuro sem névoa cinza
        TimePhase.NIGHT -> sky(NIGHT_ZENITH, 4.5f, floatArrayOf(0.040f, 0.055f, 0.095f), 4.0f,
            floatArrayOf(0.75f, 0.80f, 1.0f), 0.6f, 1f, 0.70f, 0.03f)
        TimePhase.INTERIOR -> sky(floatArrayOf(0.02f, 0.02f, 0.02f), 0.1f, floatArrayOf(0.03f, 0.03f, 0.03f), 0.1f,
            floatArrayOf(0f, 0f, 0f), 0f, 0f, 1f, 0.05f)
    }

    private fun sky(z: FloatArray, zi: Float, h: FloatArray, hi: Float, s: FloatArray, si: Float, stars: Float, exp: Float, haze: Float) =
        Sky(z, zi, h, hi, s, si, stars, exp, haze, h.copyOf())

    /** Interpola dois céus (transições suaves entre fases). */
    fun lerp(a: Sky, b: Sky, t: Float): Sky {
        val k = t.coerceIn(0f, 1f)
        fun l(x: FloatArray, y: FloatArray) = FloatArray(3) { x[it] + (y[it] - x[it]) * k }
        fun f(x: Float, y: Float) = x + (y - x) * k
        return Sky(l(a.zenith, b.zenith), f(a.zenithI, b.zenithI), l(a.horizon, b.horizon), f(a.horizonI, b.horizonI),
            l(a.sun, b.sun), f(a.sunI, b.sunI), f(a.stars, b.stars), f(a.gradientExp, b.gradientExp), f(a.haze, b.haze), l(a.fog, b.fog))
    }

    /** #070B14 em sRGB (especificação da cena Noite). */
    val NIGHT_ZENITH = floatArrayOf(0x07 / 255f, 0x0B / 255f, 0x14 / 255f)

    /** Cosseno do raio angular do disco solar (≈ 1,6° aparente, exagerado para leitura no celular). */
    const val SUN_DISK_COS = 0.99961f
}
