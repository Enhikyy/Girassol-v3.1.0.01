package com.enhikyy.girassol.core.memory

import com.enhikyy.girassol.core.util.SplitMix64
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Recuperação episódica em 2 estágios.
 *
 * Estágio 1 — embeddings binários bit[256] (4 × Long = 32 bytes) por SimHash de
 * características (palavras + tags + entidades, hashing FNV-1a 64 expandido por SplitMix64).
 * A distância de Hamming é XOR + popcount (Long.bitCount na JVM, __builtin_popcountll no C++
 * em cpp/memory/binary_episodic_index.hpp — mesmos bits, verificado por vetor dourado).
 *
 * Estágio 2 — sobre os K sobreviventes, Reciprocal Rank Fusion de quatro listas:
 * cosseno float32 do embedding denso (feature hashing com sinal, 256 dimensões), BM25
 * (k1 = 1,2; b = 0,75; estatísticas de corpus das memórias do cidadão — o mesmo ranking que
 * o FTS5 do SQLite expõe na aba Mente), ativação ACT-R/MemRL/A-MEM e intensidade de Hawkes
 * λ_m(t) = μ0 + Σ α·v_i·e^{−β(t−t_i)}.
 *
 * Observação honesta: os embeddings são hashing de características (determinísticos e
 * offline), não embeddings neurais aprendidos.
 */
object EpisodicIndex {
    const val BITS = 256
    const val WORDS = BITS / 64
    const val DENSE_DIM = 256
    const val STAGE1_K = 24
    const val RRF_K = 60.0
    const val BM25_K1 = 1.2
    const val BM25_B = 0.75
    const val HAWKES_MU0 = 0.05
    const val HAWKES_ALPHA = 0.6
    const val HAWKES_BETA = 1.0 / 480.0  // meia-vida ≈ 333 ticks (≈ 0,7 dia)

    /** Palavras normalizadas (minúsculas, alfanuméricas, ≥ 2 caracteres). */
    fun terms(text: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (ch in text) {
            if (ch.isLetterOrDigit() || ch == '_') cur.append(ch.lowercaseChar())
            else { if (cur.length >= 2) out += cur.toString(); cur.setLength(0) }
        }
        if (cur.length >= 2) out += cur.toString()
        return out
    }

    fun features(content: String, tags: Collection<String>, entities: Collection<String>): List<String> =
        terms(content) + tags.map { "#" + it.lowercase() } + entities.map { "@" + it.lowercase() }

    /** FNV-1a 64 sobre os bytes UTF-8 (idêntico ao C++). */
    fun fnv1a64(s: String): Long {
        var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        for (b in s.encodeToByteArray()) {
            h = h xor (b.toLong() and 0xFF)
            h *= 0x100000001b3L
        }
        return h
    }

    /** SimHash de 256 bits: cada característica vota ±1 em cada bit (SplitMix64 semeado pelo hash). */
    fun simHash(features: List<String>): LongArray {
        val acc = IntArray(BITS)
        for (f in features) {
            val rng = SplitMix64(fnv1a64(f))
            for (w in 0 until WORDS) {
                val r = rng.nextLong()
                for (b in 0 until 64) acc[w * 64 + b] += if ((r ushr b) and 1L == 1L) 1 else -1
            }
        }
        val out = LongArray(WORDS)
        for (i in 0 until BITS) if (acc[i] > 0) out[i / 64] = out[i / 64] or (1L shl (i % 64))
        return out
    }

    fun hamming(a: LongArray, b: LongArray): Int {
        var d = 0
        for (i in 0 until WORDS) d += java.lang.Long.bitCount(a[i] xor b[i])
        return d
    }

    /** Embedding denso float32 (feature hashing com sinal), normalizado L2. */
    fun dense(features: List<String>): FloatArray {
        val v = FloatArray(DENSE_DIM)
        for (f in features) {
            val h = fnv1a64(f)
            val idx = ((h ushr 1) % DENSE_DIM).toInt()
            v[idx] += if (h < 0) -1f else 1f
        }
        var n = 0f
        for (x in v) n += x * x
        if (n > 0f) { val inv = 1f / sqrt(n); for (i in v.indices) v[i] *= inv }
        return v
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** Intensidade de Hawkes λ_m(t) a partir do estado recursivo (excitação, último evento). */
    fun hawkesIntensity(excitation: Double, lastTick: Long, now: Long): Double =
        HAWKES_MU0 + excitation * exp(-HAWKES_BETA * (now - lastTick).coerceAtLeast(0L))

    /** Novo evento de reforço com valência v: E ← E·e^{−βΔ} + α·v (forma recursiva exata). */
    fun hawkesExcite(node: AssociativeMemoryNode, now: Long, valence: Double) {
        val decayed = node.hawkesExcitation * exp(-HAWKES_BETA * (now - node.hawkesLastTick).coerceAtLeast(0L))
        node.hawkesExcitation = decayed + HAWKES_ALPHA * valence.coerceIn(0.0, 1.0)
        node.hawkesLastTick = now
    }

    /** BM25 de cada documento candidato contra a consulta, com estatísticas do corpus inteiro. */
    fun bm25(query: List<String>, candidates: List<List<String>>, corpus: List<List<String>>): DoubleArray {
        val n = corpus.size.coerceAtLeast(1)
        val avgdl = corpus.sumOf { it.size }.toDouble() / n
        val qset = query.toSortedSet()
        val df = HashMap<String, Int>()
        for (doc in corpus) for (t in doc.toSet()) if (t in qset) df[t] = (df[t] ?: 0) + 1
        return DoubleArray(candidates.size) { i ->
            val doc = candidates[i]
            val tf = HashMap<String, Int>()
            for (t in doc) if (t in qset) tf[t] = (tf[t] ?: 0) + 1
            var s = 0.0
            for (t in qset) {
                val f = tf[t] ?: continue
                val d = df[t] ?: 0
                val idf = ln(1.0 + (n - d + 0.5) / (d + 0.5))
                s += idf * f * (BM25_K1 + 1) / (f + BM25_K1 * (1 - BM25_B + BM25_B * doc.size / avgdl.coerceAtLeast(1e-9)))
            }
            s
        }
    }

    /** Reciprocal Rank Fusion: Σ_listas 1/(k + posição). Empates resolvidos pelo índice. */
    fun rrf(scoreLists: List<DoubleArray>, size: Int): DoubleArray {
        val out = DoubleArray(size)
        for (scores in scoreLists) {
            val order = (0 until size).sortedWith(compareByDescending<Int> { scores[it] }.thenBy { it })
            order.forEachIndexed { rank, idx -> out[idx] += 1.0 / (RRF_K + rank + 1) }
        }
        return out
    }

    /** Assinatura FNV de vetores sintéticos — igual a binaryIndexSignature() no C++. */
    fun paritySignature(): Long {
        val rng = SplitMix64(31337L)
        val words = listOf("horta", "praca", "comer", "sucesso", "falha", "usina", "boato", "colheita", "agua", "escola")
        var h = -0x340d631b7bdddcdbL
        val docs = List(32) { List(4) { words[((rng.nextLong() ushr 1) % words.size).toInt()] } }
        val hashes = docs.map { simHash(it) }
        for (i in hashes.indices) {
            for (w in hashes[i]) { h = (h xor w) * 0x100000001b3L }
            val d = hamming(hashes[i], hashes[(i + 1) % hashes.size])
            h = (h xor d.toLong()) * 0x100000001b3L
        }
        return h
    }

    /** Assinatura dourada de [paritySignature]; a mesma constante está em native-tests. */
    const val PARITY_GOLDEN: Long = 0x01ddb39b8e7c5fb9L
}
