package com.enhikyy.girassol.render3d

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * v3.9 — ciclo solar contínuo da Biosfera (480 ticks = 24 h; tick 0 = 06:00, como o SimClock). Kotlin puro, testável na JVM.
 * Amanhecer laranja, meio-dia neutro-quente, entardecer âmbar e noite azulada com janelas
 * e postes acesos (emissivo sobe quando o sol se põe).
 */
object SunCycle {
    const val TICKS_PER_DAY = 480L

    class Sun(val dir: FloatArray, val amount: Float, val tint: FloatArray, val emissive: Float, val glow: Float, val sky: FloatArray, val daylight: Float)

    fun dayFraction(tick: Long): Float = ((tick % TICKS_PER_DAY).toFloat() / TICKS_PER_DAY + 0.25f) % 1f

    /** [f] = fração do dia (0 = meia-noite, 0.5 = meio-dia). */
    fun at(f: Float): Sun {
        // elevação: nasce às 6 h (0.25) e se põe às 18 h (0.75)
        val ang = (f - 0.25f) * 2f * PI.toFloat()
        val elev = sin(ang)
        val daylight = smooth(-0.15f, 0.30f, elev)
        val az = ang
        val dir = floatArrayOf(-cos(az) * 0.85f, max(elev, 0.05f), -0.35f)
        // cor: laranja perto do horizonte, neutro-quente no alto
        val warm = 1f - smooth(0.05f, 0.55f, elev)
        val dayTint = floatArrayOf(0.98f - 0.02f * warm, 0.94f - 0.16f * warm, 0.86f - 0.30f * warm)
        val nightTint = floatArrayOf(0.42f, 0.46f, 0.62f)
        val tint = FloatArray(3) { i -> nightTint[i] + (dayTint[i] - nightTint[i]) * daylight }
        val emissive = FilamentCityRenderer.NIGHT_EMISSIVE + (0.8f - FilamentCityRenderer.NIGHT_EMISSIVE) * daylight
        val glow = 0.55f + (0.22f - 0.55f) * daylight
        val skyDay = floatArrayOf(0.050f + 0.06f * warm, 0.060f + 0.015f * warm, 0.070f - 0.02f * warm)
        val sky = FloatArray(3) { i -> skyDay[i] * daylight }
        return Sun(dir, 0.85f * daylight, tint, emissive, glow, sky, daylight)
    }

    /** v4.1 (Kodak 70 mm): elevação cinematográfica do sol — Dia/Tarde 28° (névoa distante), Entardecer 15° dourado. */
    const val DAY_ELEVATION_DEG = 28f
    const val DUSK_ELEVATION_DEG = 15f

    /**
     * Direção PARA o sol (normalizada) com a elevação cinematográfica fixa da fase e o azimute do
     * ciclo contínuo [f]: a luz-chave rasante desenha sombras longas e bem recortadas nas fachadas.
     */
    fun cinematicDir(f: Float, phase: TimePhase): FloatArray {
        val elevDeg = when (phase) {
            TimePhase.MORNING, TimePhase.AFTERNOON -> DAY_ELEVATION_DEG
            TimePhase.DUSK -> DUSK_ELEVATION_DEG
            else -> return at(f).dir.let { d -> val n = kotlin.math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]); floatArrayOf(d[0] / n, d[1] / n, d[2] / n) }
        }
        val ang = (f - 0.25f) * 2f * PI.toFloat()
        var hx = -cos(ang) * 0.85f; var hz = -0.35f
        val hn = kotlin.math.sqrt(hx * hx + hz * hz).coerceAtLeast(1e-4f)
        hx /= hn; hz /= hn
        val e = elevDeg * PI.toFloat() / 180f
        return floatArrayOf(hx * cos(e), sin(e), hz * cos(e))
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

}
