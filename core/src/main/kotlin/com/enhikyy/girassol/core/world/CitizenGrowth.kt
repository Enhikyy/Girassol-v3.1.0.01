package com.enhikyy.girassol.core.world

import kotlin.math.sqrt

/**
 * Crescimento infantil → adulto dos cidadãos nascidos na simulação (fundadores nascem adultos).
 *
 * A idade em dias é mapeada numa curva de crescimento suave (smoothstep) até [ADULT_DAYS]:
 *  - altura total de 0,55 → 1,0;
 *  - proporção cabeça/corpo de ~1:4 (criança) → ~1:7,5 (adulto), i.e. escala da cabeça 1,45 → 1,0;
 *  - membros proporcionalmente mais curtos na infância (0,78 → 1,0);
 *  - passada (comprimento do passo) acompanha a perna.
 * Função pura do tick e da data de nascimento → idêntica em todos os aparelhos/replays.
 */
object CitizenGrowth {
    data class Proportions(val height: Float, val headScale: Float, val limbScale: Float, val torsoWidth: Float, val stride: Float, val childhood: Float)

    fun maturity(ageDays: Double, founder: Boolean): Float {
        if (founder) return 1f
        val t = (ageDays / ADULT_DAYS).coerceIn(0.0, 1.0)
        return (t * t * (3.0 - 2.0 * t)).toFloat()
    }

    fun proportions(maturity: Float): Proportions {
        val m = maturity.coerceIn(0f, 1f)
        val height = 0.55f + 0.45f * m
        val head = 1.45f - 0.45f * m
        val limb = 0.78f + 0.22f * m
        val torso = 0.80f + 0.20f * m
        return Proportions(height, head, limb, torso, 0.62f * height * limb, 1f - m)
    }

    /**
     * Cinemática procedural sincronizada: a cadência do passo segue a velocidade real ORCA/RVO2
     * (|v| em tiles/tick) e a passada; para crianças a cadência é maior (passos curtos).
     * Retorna a fase em ciclos por tick de simulação.
     */
    fun gaitCyclesPerTick(vx: Double, vz: Double, p: Proportions): Float {
        val speed = sqrt(vx * vx + vz * vz).toFloat()
        return if (p.stride <= 1e-4f) 0f else speed / (2f * p.stride)
    }
}
