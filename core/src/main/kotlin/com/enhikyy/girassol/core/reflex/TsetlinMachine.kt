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
    private val taStates: IntArray = IntArray(checkedStateCount(numFeatures, numClauses)) { numStates.coerceAtLeast(2) }
) : ReflexClassifier {

    init {
        require(numFeatures > 0 && numClauses > 0) { "dimensões Tsetlin devem ser positivas" }
        require(numStates in 2..(Int.MAX_VALUE / 2)) { "numStates fora do intervalo" }
        require(thresholdT >= 1) { "thresholdT deve ser positivo" }
        require(specificityS.isFinite() && specificityS >= 1.01f) { "specificityS inválido" }
        require(taStates.size == checkedStateCount(numFeatures, numClauses))
        require(taStates.all { it in 1..(numStates * 2) }) { "estado de autômato fora do intervalo" }
    }

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
        val totalBytes = V2_HEADER_BYTES.toLong() + numClauses.toLong() + taStates.size.toLong() * 4L
        require(totalBytes <= Int.MAX_VALUE) { "estado Tsetlin excede o limite de serialização" }
        val buf = ByteBuffer.allocate(totalBytes.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MAGIC_V2)
            .putInt(FORMAT_VERSION)
            .putInt(numFeatures)
            .putInt(numClauses)
            .putInt(numStates)
            .putInt(thresholdT)
            .putInt(java.lang.Float.floatToRawIntBits(specificityS))
            .putLong(rngState)
        for (c in 0 until numClauses) buf.put(polarity(c).toByte())
        for (st in taStates) buf.putInt(st)
        return buf.array()
    }

    fun includedLiteralCount(): Int = taStates.count { it > numStates }

    fun copyMachine(): KotlinTsetlinMachine =
        KotlinTsetlinMachine(numFeatures, numClauses, numStates, specificityS, thresholdT, rngState, taStates.copyOf())

    companion object {
        private fun checkedStateCount(features: Int, clauses: Int): Int {
            require(features > 0 && clauses > 0) { "dimensões Tsetlin devem ser positivas" }
            val count = features.toLong() * clauses.toLong() * 2L
            require(count <= Int.MAX_VALUE) { "dimensões Tsetlin excedem o espaço de endereçamento" }
            return count.toInt()
        }

        private const val MAGIC_V1 = 0x47525354
        private const val MAGIC_V2 = 0x32545347 // "GST2" em little-endian
        private const val FORMAT_VERSION = 2
        private const val V2_HEADER_BYTES = 36

        /**
         * Lê o formato V2 completo (parâmetros + estado do PRNG). Mantém leitura do
         * legado V1, que não guardava specificityS nem rngState; nesse caso os valores
         * recebidos pelos argumentos continuam sendo usados como fallback.
         */
        fun deserialize(blob: ByteArray, specificityS: Float = 3.5f, rngState: Long = 42L): KotlinTsetlinMachine? {
            if (blob.size < 20) return null
            return runCatching {
                val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
                val magic = buf.int
                val nf: Int
                val nc: Int
                val ns: Int
                val tt: Int
                val storedSpecificity: Float
                val storedRng: Long
                val headerBytes: Int
                when (magic) {
                    MAGIC_V1 -> {
                        nf = buf.int
                        nc = buf.int
                        ns = buf.int
                        tt = buf.int
                        storedSpecificity = specificityS
                        storedRng = rngState
                        headerBytes = 20
                    }
                    MAGIC_V2 -> {
                        val version = buf.int
                        require(version == FORMAT_VERSION) { "versão GRST não suportada: $version" }
                        nf = buf.int
                        nc = buf.int
                        ns = buf.int
                        tt = buf.int
                        storedSpecificity = java.lang.Float.intBitsToFloat(buf.int)
                        storedRng = buf.long
                        headerBytes = V2_HEADER_BYTES
                    }
                    else -> return null
                }
                require(nf > 0 && nc > 0 && ns >= 2 && tt >= 1)
                require(storedSpecificity.isFinite() && storedSpecificity >= 1.01f)
                val expectedStates = nc.toLong() * nf.toLong() * 2L
                require(expectedStates <= Int.MAX_VALUE)
                val expectedBytes = headerBytes.toLong() + nc.toLong() + expectedStates * 4L
                require(expectedBytes == blob.size.toLong())
                repeat(nc) { index ->
                    val expectedPolarity = if (index % 2 == 0) 1 else -1
                    require(buf.get().toInt() == expectedPolarity) { "polaridade GRST inválida" }
                }
                val states = IntArray(expectedStates.toInt()) { buf.int }
                require(states.all { it in 1..(ns * 2) }) { "estado de autômato fora do intervalo" }
                KotlinTsetlinMachine(nf, nc, ns, storedSpecificity, tt, storedRng, states)
            }.getOrNull()
        }
    }
}
