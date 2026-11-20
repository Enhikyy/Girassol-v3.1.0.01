package com.enhikyy.girassol.core.forge

import com.enhikyy.girassol.core.util.SplitMix64
import com.enhikyy.girassol.core.world.ActionType
import com.enhikyy.girassol.core.world.TICKS_PER_DAY
import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/**
 * Adaptador LoRA coletivo (ΔW = B·A, posto R) treinado durante o sono a partir das
 * memórias episódicas consolidadas. Prediz a utilidade (MemRL) de uma ação num período
 * do dia e enviesa levemente a energia livre esperada. Base nula: com B = 0 o
 * comportamento é idêntico ao de antes do primeiro ciclo aceito.
 *
 * Toda a aritmética é float IEEE sem funções transcendentais (apenas +, −, ×, ÷ e √,
 * que são corretamente arredondadas), na mesma ordem do C++ (forge/lora_trainer_realtime.hpp,
 * compilado com -ffp-contract=off): os dois backends produzem bits idênticos.
 */
@Serializable
class LoraAdapterState(
    val inDim: Int = IN_DIM,
    val outDim: Int = OUT_DIM,
    val rank: Int = RANK,
    val scaling: Float = ALPHA / RANK,
    var a: FloatArray = FloatArray(RANK * IN_DIM),
    var b: FloatArray = FloatArray(OUT_DIM * RANK),
    var anchorA: FloatArray = FloatArray(RANK * IN_DIM),
    var anchorB: FloatArray = FloatArray(OUT_DIM * RANK),
    var fisherA: FloatArray = FloatArray(RANK * IN_DIM),
    var fisherB: FloatArray = FloatArray(OUT_DIM * RANK),
    var mA: FloatArray = FloatArray(RANK * IN_DIM),
    var vA: FloatArray = FloatArray(RANK * IN_DIM),
    var mB: FloatArray = FloatArray(OUT_DIM * RANK),
    var vB: FloatArray = FloatArray(OUT_DIM * RANK),
    var b1Pow: Float = 1f,
    var b2Pow: Float = 1f,
    var step: Int = 0,
    var acceptedCycles: Int = 0,
    var version: Int = 0
) {
    fun packed(): FloatArray {
        val parts = listOf(a, b, anchorA, anchorB, fisherA, fisherB, mA, vA, mB, vB)
        val out = FloatArray(parts.sumOf { it.size } + 2)
        var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        out[o++] = b1Pow; out[o] = b2Pow
        return out
    }

    fun withPacked(p: FloatArray, newStep: Int): LoraAdapterState {
        val ra = rank * inDim
        val rb = outDim * rank
        val sizes = intArrayOf(ra, rb, ra, rb, ra, rb, ra, ra, rb, rb)
        require(p.size >= sizes.sum() + 2) { "estado LoRA empacotado inválido" }
        var o = 0
        fun take(n: Int) = p.copyOfRange(o, o + n).also { o += n }
        return LoraAdapterState(inDim, outDim, rank, scaling,
            take(ra), take(rb), take(ra), take(rb), take(ra), take(rb), take(ra), take(ra), take(rb), take(rb),
            p[o], p[o + 1], newStep, acceptedCycles, version)
    }

    fun copyState(): LoraAdapterState = withPacked(packed(), step)

    fun deltaNorm(): Float {
        var s = 0f
        for (v in b) s += v * v
        return sqrt(s)
    }

    companion object {
        const val IN_DIM = 18
        const val OUT_DIM = 1
        const val RANK = 4
        const val ALPHA = 8f

        fun initial(seed: Long): LoraAdapterState {
            val s = LoraAdapterState()
            LoraMath.initA(s.a, s.inDim, seed)
            s.a.copyInto(s.anchorA)
            return s
        }
    }
}

@Serializable
data class LoraSample(val x: FloatArray, val y: Float)

data class LoraTrainResult(val state: LoraAdapterState, val mseFirstEpoch: Float, val mseLastEpoch: Float, val backend: String)

@Serializable
data class LoraCycleRecord(
    val tick: Long,
    val samples: Int,
    val mseBefore: Float,
    val mseAfter: Float,
    val baselineScore: Double,
    val candidateScore: Double,
    val accepted: Boolean,
    val backend: String,
    val deltaNorm: Float
)

interface LoraTrainerPort {
    val backend: String
    fun train(state: LoraAdapterState, samples: List<LoraSample>, epochs: Int, lr: Float, ewcLambda: Float): LoraTrainResult
}

object LoraFeatures {
    /** one-hot da ação (13) + período do dia (4) + viés. */
    fun of(action: ActionType, tick: Long): FloatArray {
        val x = FloatArray(LoraAdapterState.IN_DIM)
        x[action.ordinal] = 1f
        val period = ((tick % TICKS_PER_DAY) * 4 / TICKS_PER_DAY).toInt().coerceIn(0, 3)
        x[ActionType.entries.size + period] = 1f
        x[LoraAdapterState.IN_DIM - 1] = 1f
        return x
    }
}

object LoraMath {
    private const val BETA1 = 0.9f
    private const val BETA2 = 0.999f
    private const val EPS = 1e-8f
    private const val WD = 0.01f

    fun initA(a: FloatArray, inDim: Int, seed: Long) {
        val rng = SplitMix64(seed)
        val limit = 1f / sqrt(inDim.toFloat())
        for (i in a.indices) a[i] = (rng.nextFloat() * 2f - 1f) * limit
    }

    fun predict(s: LoraAdapterState, x: FloatArray, out: FloatArray = FloatArray(s.outDim), h: FloatArray = FloatArray(s.rank)): FloatArray {
        for (r in 0 until s.rank) {
            var acc = 0f
            val off = r * s.inDim
            for (k in 0 until s.inDim) acc += s.a[off + k] * x[k]
            h[r] = acc
        }
        for (d in 0 until s.outDim) {
            var acc = 0f
            val off = d * s.rank
            for (r in 0 until s.rank) acc += s.b[off + r] * h[r]
            out[d] = 0f + s.scaling * acc
        }
        return out
    }

    /** Um passo de MSE + EWC + AdamW. Muta o estado; retorna o MSE do passo. */
    fun trainStep(s: LoraAdapterState, x: FloatArray, y: FloatArray, lr: Float, ewc: Float): Float {
        val h = FloatArray(s.rank)
        val pred = predict(s, x, FloatArray(s.outDim), h)
        var mse = 0f
        val dY = FloatArray(s.outDim)
        for (d in 0 until s.outDim) {
            val diff = pred[d] - y[d]
            mse += diff * diff
            dY[d] = (2f / s.outDim.toFloat()) * diff
        }
        mse /= s.outDim.toFloat()
        val gradB = FloatArray(s.b.size)
        val dH = FloatArray(s.rank)
        for (d in 0 until s.outDim) {
            val off = d * s.rank
            for (r in 0 until s.rank) {
                val g = s.scaling * dY[d] * h[r]
                gradB[off + r] = g + ewc * s.fisherB[off + r] * (s.b[off + r] - s.anchorB[off + r])
                s.fisherB[off + r] = 0.95f * s.fisherB[off + r] + 0.05f * (g * g)
                dH[r] += s.scaling * s.b[off + r] * dY[d]
            }
        }
        val gradA = FloatArray(s.a.size)
        for (r in 0 until s.rank) {
            val off = r * s.inDim
            for (k in 0 until s.inDim) {
                val g = dH[r] * x[k]
                gradA[off + k] = g + ewc * s.fisherA[off + k] * (s.a[off + k] - s.anchorA[off + k])
                s.fisherA[off + k] = 0.95f * s.fisherA[off + k] + 0.05f * (g * g)
            }
        }
        s.step++
        s.b1Pow *= BETA1
        s.b2Pow *= BETA2
        adamW(s.a, gradA, s.mA, s.vA, lr, s.b1Pow, s.b2Pow)
        adamW(s.b, gradB, s.mB, s.vB, lr * 4f, s.b1Pow, s.b2Pow)
        return mse
    }

    private fun adamW(w: FloatArray, g: FloatArray, m: FloatArray, v: FloatArray, lr: Float, b1Pow: Float, b2Pow: Float) {
        val b1Corr = 1f - b1Pow
        val b2Corr = 1f - b2Pow
        val oneMinusB1 = 1f - BETA1
        val oneMinusB2 = 1f - BETA2
        for (i in w.indices) {
            m[i] = BETA1 * m[i] + oneMinusB1 * g[i]
            v[i] = BETA2 * v[i] + oneMinusB2 * (g[i] * g[i])
            val mHat = m[i] / b1Corr
            val vHat = v[i] / b2Corr
            w[i] = w[i] - lr * (mHat / (sqrt(vHat) + EPS) + WD * w[i])
        }
    }

    fun trainEpochs(s: LoraAdapterState, samples: List<LoraSample>, epochs: Int, lr: Float, ewc: Float): Pair<Float, Float> {
        var first = 0f
        var last = 0f
        val y = FloatArray(1)
        for (e in 0 until epochs) {
            var sum = 0f
            for (smp in samples) { y[0] = smp.y; sum += trainStep(s, smp.x, y, lr, ewc) }
            val avg = if (samples.isEmpty()) 0f else sum / samples.size.toFloat()
            if (e == 0) first = avg
            last = avg
        }
        return first to last
    }

    fun consolidateAnchor(s: LoraAdapterState) {
        s.a.copyInto(s.anchorA)
        s.b.copyInto(s.anchorB)
    }
}

/** Implementação de referência (JVM). */
object KotlinLoraTrainer : LoraTrainerPort {
    override val backend = "kotlin-lora"
    override fun train(state: LoraAdapterState, samples: List<LoraSample>, epochs: Int, lr: Float, ewcLambda: Float): LoraTrainResult {
        val s = state.copyState()
        val (first, last) = LoraMath.trainEpochs(s, samples, epochs, lr, ewcLambda)
        return LoraTrainResult(s, first, last, backend)
    }
}
