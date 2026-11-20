package com.enhikyy.girassol.core.biology

import com.enhikyy.girassol.core.util.SplitMix64
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tanh

/**
 * Rede Closed-form Continuous-time (CfC, Hasani et al. 2022) com 48 neurônios.
 *
 * Forma fechada usada (variante com constante de tempo dependente apenas da entrada):
 *   x(t+Δ) = e^{−Δ·σ(I)} ⊙ x(t) + (1 − e^{−Δ·σ(I)}) ⊙ g(I)
 *   σ(I) = κ·softplus(W_f·I + b_f)   (taxa de relaxamento > 0)
 *   g(I) = tanh(W_h·I + b_h)        (atrator)
 * É a solução exata de dx/dt = −σ(I)⊙(x − g(I)) com entrada constante no intervalo, logo
 * invariante à discretização: avançar Δ1 e depois Δ2 é idêntico a avançar Δ1+Δ2.
 *
 * Saídas: quatro grupos de 12 neurônios → cortisol, ocitocina, dopamina (ODEs hormonais de
 * 1ª ordem com solução exponencial fechada) e multiplicador de locomoção ∈ [0,75; 1,25].
 * Pesos derivados de (semente do mundo, id do cidadão): reprodutíveis e sem persistir matriz;
 * apenas o estado x (48 doubles) e os níveis hormonais são persistidos no snapshot.
 */
class CfcNeuromodulator(seed: Long) {
    private val wf = DoubleArray(N * IN)
    private val bf = DoubleArray(N)
    private val wh = DoubleArray(N * IN)
    private val bh = DoubleArray(N)

    init {
        val rng = SplitMix64(seed)
        fun r(scale: Double) = (rng.nextDouble() * 2 - 1) * scale
        for (i in 0 until N * IN) { wf[i] = r(0.8); wh[i] = r(1.2) }
        for (i in 0 until N) { bf[i] = r(0.5); bh[i] = r(0.3) }
        // grupos com polaridade biológica plausível (entrada: 6 necessidades, humor, recompensa, perigo, contato social)
        for (n in 0 until N) {
            val group = n / GROUP
            when (group) {
                0 -> { wh[n * IN + 0] -= 1.5; wh[n * IN + 1] -= 1.0; wh[n * IN + 8] += 1.8; wh[n * IN + 6] -= 0.8 } // cortisol: fome, energia baixa, perigo
                1 -> { wh[n * IN + 9] += 1.6; wh[n * IN + 3] += 0.9; wh[n * IN + 6] += 0.5 }                    // ocitocina: contato social
                2 -> { wh[n * IN + 7] += 1.8; wh[n * IN + 6] += 0.6 }                                            // dopamina: recompensa
                else -> { wh[n * IN + 1] += 1.2; wh[n * IN + 8] += 0.6; wh[n * IN + 6] += 0.4 }                 // locomoção: energia, fuga
            }
        }
    }

    /** Avança o estado [x] (tamanho [N]) por [dt] ticks com a entrada [input] (tamanho [IN]). */
    fun advance(x: DoubleArray, input: DoubleArray, dt: Double) {
        require(x.size == N && input.size == IN)
        for (n in 0 until N) {
            var f = bf[n]
            var h = bh[n]
            val o = n * IN
            for (k in 0 until IN) { f += wf[o + k] * input[k]; h += wh[o + k] * input[k] }
            val rate = KAPPA * softplus(f)
            val decay = exp(-dt * rate)
            x[n] = decay * x[n] + (1.0 - decay) * tanh(h)
        }
    }

    data class Readout(val cortisolTarget: Double, val oxytocinTarget: Double, val dopamineTarget: Double, val locomotion: Double)

    fun readout(x: DoubleArray): Readout {
        fun mean(g: Int): Double { var s = 0.0; for (n in g * GROUP until (g + 1) * GROUP) s += x[n]; return s / GROUP }
        return Readout(sigmoid(2.5 * mean(0)), sigmoid(2.5 * mean(1)), sigmoid(2.5 * mean(2)), 0.75 + 0.5 * sigmoid(2.5 * mean(3)))
    }

    companion object {
        const val N = 48
        const val IN = 10
        const val GROUP = 12
        const val KAPPA = 0.02          // taxa base de relaxamento por tick
        const val HORMONE_TAU = 120.0   // constante de tempo das ODEs hormonais (ticks)

        fun softplus(v: Double) = if (v > 30) v else ln(1.0 + exp(v))
        fun sigmoid(v: Double) = 1.0 / (1.0 + exp(-v))

        /** dh/dt = (alvo − h)/τ com solução fechada: h(t+Δ) = alvo + (h − alvo)·e^{−Δ/τ}. */
        fun hormoneStep(level: Double, target: Double, dt: Double): Double =
            target + (level - target) * exp(-dt / HORMONE_TAU)

        fun seedFor(worldSeed: Long, citizenId: String): Long {
            var h = worldSeed xor -0x61c8864680b583ebL
            for (ch in citizenId) h = (h xor ch.code.toLong()) * 0x100000001b3L
            return h
        }
    }
}
