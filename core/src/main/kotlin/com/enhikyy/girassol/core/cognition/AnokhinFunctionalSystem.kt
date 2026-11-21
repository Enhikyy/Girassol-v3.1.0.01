package com.enhikyy.girassol.core.cognition

import com.enhikyy.girassol.core.util.SplitMix64

/**
 * Sistema Funcional de P.K. Anokhin (1935/1974), em dois tempos explícitos:
 *
 *  1. Síntese aferente — antes de agir, o cidadão integra motivação dominante (déficits),
 *     memória (utilidade MemRL da ação), aferência situacional (necessidades atuais) e
 *     aferência de disparo (a ação escolhida) num modelo do resultado esperado: o vetor de
 *     necessidades projetado e a probabilidade de sucesso. Esse modelo é gravado no
 *     **Aceitador de Resultados da Ação** (`ActionResultAcceptor`) junto com a intenção.
 *  2. Aferência de retorno — após a ação, o resultado sensorial obtido é comparado com o
 *     modelo *antes* de qualquer atualização de plasticidade. O descompasso (sinalizado:
 *     pior/melhor que o esperado) define a surpresa e o fator de plasticidade usados pelo
 *     WorldCore na memória episódica, no MemRL, no reflexo e na neuromodulação.
 *
 * A aritmética usa apenas +, −, ×, ÷ e comparações em double, na mesma ordem do espelho
 * C++20 (`cognitive/anokhin_functional_system.hpp`), para paridade bit a bit.
 */
object AnokhinFunctionalSystem {
    const val NEEDS = 6
    /** Peso da previsão de sucesso no descompasso (o resto vem das necessidades). */
    const val SUCCESS_WEIGHT = 0.5
    /** Abaixo disso o resultado "confirma" o aceitador (sem surpresa). */
    const val CONFIRM_THRESHOLD = 0.04
    const val MAX_PLASTICITY = 2.0

    /** Modelo do resultado esperado (saída da síntese aferente). */
    data class Acceptor(val expectedNeeds: DoubleArray, val expectedSuccess: Double)

    data class Comparison(
        /** Descompasso não sinalizado em [0, 1]. */
        val mismatch: Double,
        /** Sinal: > 0 = resultado melhor que o esperado; < 0 = pior. */
        val valence: Double,
        /** Surpresa usada no appraisal (0 quando o aceitador é confirmado). */
        val surprise: Double,
        /** Multiplicador de taxa de aprendizado (1 = plasticidade basal). */
        val plasticity: Double,
        val confirmed: Boolean
    )

    /**
     * Síntese aferente: pesos de motivação dominante = déficit relativo de cada necessidade
     * (normalizado), modulados pela memória (utilidade MemRL ∈ [−1, 1]) que desloca a
     * expectativa de sucesso, e pela confiança da previsão do executor.
     */
    fun afferentSynthesis(projectedNeeds: DoubleArray, forecastSuccess: Double, memoryUtility: Double): Acceptor {
        require(projectedNeeds.size == NEEDS)
        val mem = clamp(memoryUtility, -1.0, 1.0)
        val p = clamp(forecastSuccess + 0.1 * mem, 0.02, 0.98)
        return Acceptor(projectedNeeds.copyOf(), p)
    }

    /** Pesos motivacionais: déficits atuais normalizados (motivação dominante pesa mais). */
    fun motivationWeights(needsAtDecision: DoubleArray): DoubleArray {
        val w = DoubleArray(NEEDS)
        var sum = 0.0
        for (k in 0 until NEEDS) { w[k] = 0.05 + (1.0 - clamp(needsAtDecision[k], 0.0, 1.0)); sum += w[k] }
        for (k in 0 until NEEDS) w[k] = w[k] / sum
        return w
    }

    /** Aceitador de Resultados da Ação: compara esperado × obtido. */
    fun compare(acceptor: Acceptor, weights: DoubleArray, obtainedNeeds: DoubleArray, success: Boolean): Comparison {
        var err = 0.0
        var signed = 0.0
        for (k in 0 until NEEDS) {
            val d = obtainedNeeds[k] - acceptor.expectedNeeds[k]
            err += weights[k] * (if (d < 0.0) -d else d)
            signed += weights[k] * d
        }
        val o = if (success) 1.0 else 0.0
        val ds = o - acceptor.expectedSuccess
        val mismatch = clamp((1.0 - SUCCESS_WEIGHT) * err + SUCCESS_WEIGHT * (if (ds < 0.0) -ds else ds), 0.0, 1.0)
        val valence = clamp((1.0 - SUCCESS_WEIGHT) * signed + SUCCESS_WEIGHT * ds, -1.0, 1.0)
        val confirmed = mismatch < CONFIRM_THRESHOLD
        val surprise = if (confirmed) 0.0 else mismatch
        // resultados piores que o esperado são mais plásticos (aversão a perda / sinal de erro)
        val asym = if (valence < 0.0) 1.5 else 1.0
        val plasticity = clamp(1.0 + asym * surprise * 2.0, 1.0, MAX_PLASTICITY)
        return Comparison(mismatch, valence, surprise, plasticity, confirmed)
    }

    private fun clamp(v: Double, lo: Double, hi: Double) = if (v < lo) lo else if (v > hi) hi else v

    // ------------------------------------------------------------ paridade JVM ↔ C++

    const val PARITY_CASES = 128

    /** Assinatura FNV-1a dos bits de (mismatch, valence, plasticity, confirmed) de 128 casos. */
    fun paritySignature(compareFn: (DoubleArray, Double, Double, DoubleArray, DoubleArray, Boolean) -> DoubleArray = ::kotlinCase): Long {
        val rng = SplitMix64(1935L)
        var h = -0x340d631b7bdddcdbL
        for (i in 0 until PARITY_CASES) {
            val projected = DoubleArray(NEEDS) { rng.nextDouble() }
            val forecast = rng.nextDouble()
            val mem = rng.nextDouble() * 2.0 - 1.0
            val atDecision = DoubleArray(NEEDS) { rng.nextDouble() }
            val obtained = DoubleArray(NEEDS) { rng.nextDouble() }
            val success = (rng.nextLong() ushr 63) == 1L
            val out = compareFn(projected, forecast, mem, atDecision, obtained, success)
            for (v in out) h = fnv(h, java.lang.Double.doubleToRawLongBits(v))
        }
        return h
    }

    fun kotlinCase(projected: DoubleArray, forecast: Double, mem: Double, atDecision: DoubleArray, obtained: DoubleArray, success: Boolean): DoubleArray {
        val acc = afferentSynthesis(projected, forecast, mem)
        val c = compare(acc, motivationWeights(atDecision), obtained, success)
        return doubleArrayOf(c.mismatch, c.valence, c.plasticity, if (c.confirmed) 1.0 else 0.0)
    }

    private fun fnv(h0: Long, v: Long): Long {
        var h = h0
        for (b in 0 until 8) { h = h xor ((v ushr (b * 8)) and 0xFF); h *= 0x100000001b3L }
        return h
    }
}
