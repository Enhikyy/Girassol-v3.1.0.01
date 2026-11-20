package com.enhikyy.girassol.core.reflex

import com.enhikyy.girassol.core.util.SplitMix64
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Contrato comum à implementação Kotlin e à nativa (JNI). */
interface ReflexClassifier {
    val numFeatures: Int
    fun predictVote(features: ByteArray): Int
    fun confidence(features: ByteArray): Float
    fun trainStep(features: ByteArray, targetPositive: Boolean)
    fun serialize(): ByteArray
}

/**
 * Máquina de Tsetlin persistente — porte fiel de `cognitive/tsetlin_machine.hpp`.
 * 2·C·F autômatos com estados em [1, 2N]; literal incluído quando estado > N.
 * Votação com early-exit exato, feedback Tipo I/II e PRNG SplitMix64 compartilhado
 * com o C++ (resultados idênticos bit a bit para a mesma semente).
 */
@Serializable
class KotlinTsetlinMachine(
    override val numFeatures: Int,
    val numClauses: Int,
    val numStates: Int = 64,
    val specificityS: Float = 3.5f,
    val thresholdT: Int = 8,
    private var rngState: Long = 42L,
    private val taStates: IntArray = IntArray(numClauses * numFeatures * 2) { numStates.coerceAtLeast(2) }
) : ReflexClassifier {

    private fun offset(c: Int) = c * numFeatures * 2
    private fun polarity(c: Int) = if (c % 2 == 0) 1 else -1

    /**
     * Avalia a cláusula. Na inferência uma cláusula vazia vale `false`; no treino vale
     * `true` (como no TM original), o que permite que o feedback Tipo I inclua literais.
     */
    fun evaluateClause(c: Int, features: ByteArray, training: Boolean = false): Boolean {
        if (c >= numClauses || features.size < numFeatures) return false
        val off = offset(c)
        var hasIncluded = false
        for (f in 0 until numFeatures) {
            val includePos = taStates[off + f] > numStates
            val includeNeg = taStates[off + numFeatures + f] > numStates
            if (includePos) {
                hasIncluded = true
                if (features[f].toInt() == 0) return false
            }
            if (includeNeg) {
                hasIncluded = true
                if (features[f].toInt() != 0) return false
            }
        }
        return hasIncluded || training
    }

    override fun predictVote(features: ByteArray): Int {
        if (features.size < numFeatures) return 0
        var vote = 0
        var remaining = numClauses
        for (c in 0 until numClauses) {
            if (evaluateClause(c, features)) vote += polarity(c)
            remaining--
            if (abs(vote) > thresholdT + remaining) break
        }
        return vote
    }

    override fun confidence(features: ByteArray): Float {
        val v = predictVote(features).coerceIn(-thresholdT, thresholdT)
        return abs(v).toFloat() / thresholdT.toFloat()
    }

    override fun trainStep(features: ByteArray, targetPositive: Boolean) {
        if (features.size < numFeatures) return
        val rng = SplitMix64(rngState)
        val vote = predictVote(features).coerceIn(-thresholdT, thresholdT)
        val targetSign = if (targetPositive) 1 else -1
        val updateProb = (thresholdT - targetSign * vote).toFloat() / (2 * thresholdT).toFloat()
        for (c in 0 until numClauses) {
            if (rng.nextFloat() >= updateProb) continue
            val matches = evaluateClause(c, features, training = true)
            if (polarity(c) == targetSign) typeI(c, features, matches, rng)
            else if (matches) typeII(c, features)
        }
        rngState = rng.state
    }

    private fun typeI(c: Int, features: ByteArray, matches: Boolean, rng: SplitMix64) {
        val off = offset(c)
        val s = specificityS.coerceAtLeast(1.01f)
        val pReinforce = (s - 1f) / s
        val pForget = 1f / s
        val maxState = numStates * 2
        for (f in 0 until numFeatures) {
            val pi = off + f
            val ni = off + numFeatures + f
            if (matches && features[f].toInt() != 0) {
                if (rng.nextFloat() < pReinforce && taStates[pi] < maxState) taStates[pi]++
                if (rng.nextFloat() < pForget && taStates[ni] > 1) taStates[ni]--
            } else if (matches && features[f].toInt() == 0) {
                if (rng.nextFloat() < pReinforce && taStates[ni] < maxState) taStates[ni]++
                if (rng.nextFloat() < pForget && taStates[pi] > 1) taStates[pi]--
            } else {
                if (rng.nextFloat() < pForget && taStates[pi] > 1) taStates[pi]--
                if (rng.nextFloat() < pForget && taStates[ni] > 1) taStates[ni]--
            }
        }
    }

    private fun typeII(c: Int, features: ByteArray) {
        val off = offset(c)
        for (f in 0 until numFeatures) {
            if (features[f].toInt() == 0 && taStates[off + f] <= numStates) taStates[off + f]++
            if (features[f].toInt() != 0 && taStates[off + numFeatures + f] <= numStates) taStates[off + numFeatures + f]++
        }
    }

    /** Mesmo formato binário "GRST" (little-endian) do C++: header 20B + polaridades + estados. */
    override fun serialize(): ByteArray {
        val buf = ByteBuffer.allocate(20 + numClauses + taStates.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x47525354).putInt(numFeatures).putInt(numClauses).putInt(numStates).putInt(thresholdT)
        for (c in 0 until numClauses) buf.put(polarity(c).toByte())
        for (st in taStates) buf.putInt(st)
        return buf.array()
    }

    fun includedLiteralCount(): Int = taStates.count { it > numStates }

    fun copyMachine(): KotlinTsetlinMachine =
        KotlinTsetlinMachine(numFeatures, numClauses, numStates, specificityS, thresholdT, rngState, taStates.copyOf())

    companion object {
        fun deserialize(blob: ByteArray, specificityS: Float = 3.5f, rngState: Long = 42L): KotlinTsetlinMachine? {
            if (blob.size < 20) return null
            val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
            if (buf.int != 0x47525354) return null
            val nf = buf.int; val nc = buf.int; val ns = buf.int; val tt = buf.int
            if (nf <= 0 || nc <= 0 || ns < 2 || tt < 1) return null
            val expected = nc.toLong() * nf * 2
            if (blob.size.toLong() != 20L + nc + expected * 4) return null
            repeat(nc) { buf.get() }
            val states = IntArray(expected.toInt()) { buf.int }
            return KotlinTsetlinMachine(nf, nc, ns, specificityS, tt, rngState, states)
        }
    }
}
