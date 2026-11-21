package com.enhikyy.girassol.core.rsi

import com.enhikyy.girassol.core.util.SplitMix64
import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/**
 * CMA-ES compacto (Hansen & Ostermeier 2001; Hansen 2016, "The CMA Evolution Strategy:
 * A Tutorial") para hiperparâmetros contínuos de decisão e locomoção dos cidadãos.
 *
 * Determinístico e portátil bit a bit para o espelho C++20 (`evolution/cma_es.hpp`):
 *  - normais por Irwin–Hall (12 uniformes SplitMix64 − 6), sem log/cos;
 *  - pesos de recombinação lineares (μ − i), sem log;
 *  - exp() próprio ([detExp]) com redução de faixa e Taylor de ordem fixa;
 *  - autodecomposição de C por Jacobi cíclico (só +, −, ×, ÷, sqrt).
 * Coordenadas normalizadas em [0, 1]^n; a minimização é sobre o *custo* (−aptidão).
 */
@Serializable
data class CmaEsState(
    val n: Int,
    val lambda: Int,
    var generation: Int = 0,
    var sigma: Double,
    val mean: DoubleArray,
    val c: DoubleArray,          // matriz de covariância n×n (linha-maior)
    val pc: DoubleArray,
    val ps: DoubleArray,
    var b: DoubleArray,          // autovetores (colunas)
    var d: DoubleArray,          // raízes dos autovalores
    var bestCost: Double = Double.MAX_VALUE,
    var bestX: DoubleArray = DoubleArray(0),
    /** Amostras da geração corrente (y = B·D·z) aguardando tell(). */
    var pendingY: DoubleArray = DoubleArray(0),
    var lastPromoted: Boolean = true
) {
    companion object {
        fun initial(x0: DoubleArray, sigma0: Double, lambda: Int): CmaEsState {
            val n = x0.size
            val id = DoubleArray(n * n).also { for (i in 0 until n) it[i * n + i] = 1.0 }
            return CmaEsState(n, lambda, 0, sigma0, x0.copyOf(), id.copyOf(), DoubleArray(n), DoubleArray(n), id.copyOf(), DoubleArray(n) { 1.0 })
        }
    }
}

object CmaEs {
    fun mu(s: CmaEsState) = s.lambda / 2

    fun weights(s: CmaEsState): DoubleArray {
        val mu = mu(s)
        val w = DoubleArray(mu) { (mu - it).toDouble() }
        var sum = 0.0
        for (v in w) sum += v
        for (i in w.indices) w[i] = w[i] / sum
        return w
    }

    fun mueff(w: DoubleArray): Double { var s = 0.0; for (v in w) s += v * v; return 1.0 / s }

    /** Normal padrão aproximada (Irwin–Hall n=12): média 0, variância 1, caudas em ±6. */
    fun normal(rng: SplitMix64): Double {
        var s = 0.0
        for (i in 0 until 12) s += rng.nextDouble()
        return s - 6.0
    }

    /** exp determinístico: x = k·ln2 + r, |r| ≤ ln2/2, Taylor de grau 20 em r. */
    fun detExp(x: Double): Double {
        if (x > 700.0) return Double.MAX_VALUE
        if (x < -700.0) return 0.0
        val ln2 = 0.6931471805599453
        var k = (x / ln2).toLong()
        var r = x - k.toDouble() * ln2
        if (r > ln2 / 2) { r -= ln2; k++ } else if (r < -ln2 / 2) { r += ln2; k-- }
        var term = 1.0
        var sum = 1.0
        for (i in 1..20) { term = term * r / i.toDouble(); sum += term }
        var p = 1.0
        if (k >= 0) { for (i in 0 until k) p *= 2.0 } else { for (i in 0 until -k) p *= 0.5 }
        return sum * p
    }

    /** Amostra λ candidatos x_k = m + σ·B·D·z_k (guarda y_k para o tell). */
    fun ask(s: CmaEsState, rng: SplitMix64): List<DoubleArray> {
        val n = s.n
        val ys = DoubleArray(s.lambda * n)
        val out = ArrayList<DoubleArray>(s.lambda)
        for (k in 0 until s.lambda) {
            val z = DoubleArray(n) { normal(rng) }
            val x = DoubleArray(n)
            for (i in 0 until n) {
                var y = 0.0
                for (j in 0 until n) y += s.b[i * n + j] * (s.d[j] * z[j])
                ys[k * n + i] = y
                x[i] = s.mean[i] + s.sigma * y
            }
            out += x
        }
        s.pendingY = ys
        return out
    }

    /** Atualiza média, caminhos de evolução, covariância (rank-1 + rank-μ) e passo σ. */
    fun tell(s: CmaEsState, costs: DoubleArray) {
        val n = s.n
        require(costs.size == s.lambda && s.pendingY.size == s.lambda * n) { "tell sem ask correspondente" }
        val order = (0 until s.lambda).sortedWith(compareBy<Int> { costs[it] }.thenBy { it })
        val w = weights(s)
        val mueff = mueff(w)
        val nd = n.toDouble()
        val cc = (4.0 + mueff / nd) / (nd + 4.0 + 2.0 * mueff / nd)
        val cs = (mueff + 2.0) / (nd + mueff + 5.0)
        val c1 = 2.0 / ((nd + 1.3) * (nd + 1.3) + mueff)
        val cmuRaw = 2.0 * (mueff - 2.0 + 1.0 / mueff) / ((nd + 2.0) * (nd + 2.0) + mueff)
        val cmu = if (cmuRaw < 1.0 - c1) cmuRaw else 1.0 - c1
        val inner = sqrt((mueff - 1.0) / (nd + 1.0)) - 1.0
        val damps = 1.0 + 2.0 * (if (inner > 0.0) inner else 0.0) + cs
        val chiN = sqrt(nd) * (1.0 - 1.0 / (4.0 * nd) + 1.0 / (21.0 * nd * nd))

        val best = order[0]
        if (costs[best] < s.bestCost) {
            s.bestCost = costs[best]
            s.bestX = DoubleArray(n) { s.mean[it] + s.sigma * s.pendingY[best * n + it] }
        }
        // y_w = Σ w_i y_{i:λ}
        val yw = DoubleArray(n)
        for (i in w.indices) { val k = order[i]; for (j in 0 until n) yw[j] += w[i] * s.pendingY[k * n + j] }
        for (j in 0 until n) s.mean[j] += s.sigma * yw[j]
        // C^{-1/2} y_w = B D^{-1} Bᵀ y_w
        val bty = DoubleArray(n)
        for (i in 0 until n) { var acc = 0.0; for (j in 0 until n) acc += s.b[j * n + i] * yw[j]; bty[i] = acc / s.d[i] }
        val cInvY = DoubleArray(n)
        for (i in 0 until n) { var acc = 0.0; for (j in 0 until n) acc += s.b[i * n + j] * bty[j]; cInvY[i] = acc }
        val csf = sqrt(cs * (2.0 - cs) * mueff)
        var psNorm2 = 0.0
        for (i in 0 until n) { s.ps[i] = (1.0 - cs) * s.ps[i] + csf * cInvY[i]; psNorm2 += s.ps[i] * s.ps[i] }
        val psNorm = sqrt(psNorm2)
        s.generation++
        val decay = 1.0 - powInt(1.0 - cs, 2 * s.generation)
        val hsig = psNorm / sqrt(if (decay > 1e-300) decay else 1e-300) / chiN < 1.4 + 2.0 / (nd + 1.0)
        val ccf = sqrt(cc * (2.0 - cc) * mueff)
        for (i in 0 until n) s.pc[i] = (1.0 - cc) * s.pc[i] + (if (hsig) ccf * yw[i] else 0.0)
        val deltaH = if (hsig) 0.0 else cc * (2.0 - cc)
        for (i in 0 until n) for (j in 0 until n) {
            var rankMu = 0.0
            for (q in w.indices) { val k = order[q]; rankMu += w[q] * s.pendingY[k * n + i] * s.pendingY[k * n + j] }
            val idx = i * n + j
            s.c[idx] = (1.0 - c1 - cmu) * s.c[idx] + c1 * (s.pc[i] * s.pc[j] + deltaH * s.c[idx]) + cmu * rankMu
        }
        s.sigma = s.sigma * detExp((cs / damps) * (psNorm / chiN - 1.0))
        if (s.sigma > 0.5) s.sigma = 0.5
        if (s.sigma < 1e-4) s.sigma = 1e-4
        // simetriza e decompõe C = B D² Bᵀ
        for (i in 0 until n) for (j in i + 1 until n) { val v = 0.5 * (s.c[i * n + j] + s.c[j * n + i]); s.c[i * n + j] = v; s.c[j * n + i] = v }
        val (vals, vecs) = jacobiEigen(s.c, n)
        s.b = vecs
        s.d = DoubleArray(n) { val v = vals[it]; sqrt(if (v > 1e-20) v else 1e-20) }
        s.pendingY = DoubleArray(0)
    }

    private fun powInt(x: Double, e: Int): Double { var r = 1.0; for (i in 0 until e) r *= x; return r }

    /** Jacobi cíclico para matriz simétrica: retorna (autovalores, autovetores em colunas). */
    fun jacobiEigen(m: DoubleArray, n: Int, sweeps: Int = 24): Pair<DoubleArray, DoubleArray> {
        val a = m.copyOf()
        val v = DoubleArray(n * n).also { for (i in 0 until n) it[i * n + i] = 1.0 }
        for (sweep in 0 until sweeps) {
            var off = 0.0
            for (p in 0 until n) for (q in p + 1 until n) off += a[p * n + q] * a[p * n + q]
            if (off < 1e-30) break
            for (p in 0 until n) for (q in p + 1 until n) {
                val apq = a[p * n + q]
                if (apq == 0.0) continue
                val app = a[p * n + p]; val aqq = a[q * n + q]
                val theta = (aqq - app) / (2.0 * apq)
                val t = (if (theta >= 0.0) 1.0 else -1.0) / ((if (theta >= 0.0) theta else -theta) + sqrt(theta * theta + 1.0))
                val cth = 1.0 / sqrt(t * t + 1.0)
                val sth = t * cth
                for (k in 0 until n) {
                    val akp = a[k * n + p]; val akq = a[k * n + q]
                    a[k * n + p] = cth * akp - sth * akq
                    a[k * n + q] = sth * akp + cth * akq
                }
                for (k in 0 until n) {
                    val apk = a[p * n + k]; val aqk = a[q * n + k]
                    a[p * n + k] = cth * apk - sth * aqk
                    a[q * n + k] = sth * apk + cth * aqk
                }
                for (k in 0 until n) {
                    val vkp = v[k * n + p]; val vkq = v[k * n + q]
                    v[k * n + p] = cth * vkp - sth * vkq
                    v[k * n + q] = sth * vkp + cth * vkq
                }
            }
        }
        return DoubleArray(n) { a[it * n + it] } to v
    }

    // ------------------------------------------------------------ paridade JVM ↔ C++

    /** Esfera deslocada + elipsoide em n=6, 30 gerações, λ=8: assinatura FNV de (média, σ). */
    fun paritySignature(): Long {
        val s = CmaEsState.initial(DoubleArray(6) { 0.5 }, 0.2, 8)
        val rng = SplitMix64(2001L)
        for (g in 0 until 30) {
            val xs = ask(s, rng)
            val costs = DoubleArray(xs.size) { k -> var f = 0.0; for (i in 0 until 6) { val d = xs[k][i] - 0.2 - 0.1 * i; f += (1.0 + i) * d * d }; f }
            tell(s, costs)
        }
        var h = -0x340d631b7bdddcdbL
        fun mix(v: Long) { for (b in 0 until 8) { h = h xor ((v ushr (b * 8)) and 0xFF); h *= 0x100000001b3L } }
        for (v in s.mean) mix(java.lang.Double.doubleToRawLongBits(v))
        mix(java.lang.Double.doubleToRawLongBits(s.sigma))
        mix(s.generation.toLong())
        return h
    }
}

/**
 * Espaço de busca do CMA-ES sobre o [CognitivePolicyGenome]: 4 hiperparâmetros de decisão e 2
 * de locomoção, normalizados nos limites de [CognitivePolicyGenome.clamped].
 */
object PolicySearchSpace {
    private val lo = doubleArrayOf(0.10, 0.01, 0.02, 0.05, 0.50, 0.80)
    private val hi = doubleArrayOf(1.50, 0.20, 0.35, 0.50, 3.00, 1.20)
    const val DIM = 6
    val labels = listOf("w_epistêmico", "gate_empate", "histerese", "α_MemRL", "sens_térmica", "escala_locomoção")

    fun encode(g: CognitivePolicyGenome): DoubleArray {
        val v = doubleArrayOf(g.epistemicExplorationWeight, g.cognitiveGateTieThreshold, g.intentionSwitchHysteresis,
            g.memRlLearningRate, g.thermalThrottleSensitivity, g.locomotionSpeedScale)
        return DoubleArray(DIM) { ((v[it] - lo[it]) / (hi[it] - lo[it])).coerceIn(0.0, 1.0) }
    }

    fun decode(x: DoubleArray, base: CognitivePolicyGenome): CognitivePolicyGenome {
        fun at(i: Int) = lo[i] + x[i].coerceIn(0.0, 1.0) * (hi[i] - lo[i])
        return base.copy(
            version = base.version + 1,
            epistemicExplorationWeight = at(0), cognitiveGateTieThreshold = at(1), intentionSwitchHysteresis = at(2),
            memRlLearningRate = at(3), thermalThrottleSensitivity = at(4), locomotionSpeedScale = at(5)
        ).clamped()
    }
}
