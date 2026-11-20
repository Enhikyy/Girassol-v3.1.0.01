package com.enhikyy.girassol.core.util

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale

/** Formatação independente de locale (evita vírgula decimal em pt-BR quebrando hashes). */
fun String.fmt(vararg args: Any?): String = String.format(Locale.ROOT, this, *args)

fun ByteArray.toHex(): String {
    val hex = "0123456789abcdef"
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[i * 2] = hex[v ushr 4]
        out[i * 2 + 1] = hex[v and 0x0F]
    }
    return String(out)
}

object Sha256 {
    fun hex(text: String): String = bytes(text.toByteArray(Charsets.UTF_8)).toHex()
    fun bytes(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
}

/**
 * SplitMix64 — PRNG pequeno, rápido e idêntico bit a bit na JVM e no C++20
 * (`app/src/main/cpp/common/splitmix64.hpp`). É usado tanto pela máquina de Tsetlin
 * Kotlin quanto pela nativa, permitindo testes de paridade JVM ↔ JNI.
 */
class SplitMix64(seed: Long) {
    var state: Long = seed
        private set

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    /** Float uniforme em [0,1) com 24 bits de mantissa (mesma fórmula no C++). */
    fun nextFloat(): Float = (nextLong() ushr 40).toFloat() * (1.0f / 16777216.0f)

    /** Double uniforme em [0,1) com 53 bits. */
    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() * (1.0 / 9007199254740992.0)

    fun nextInt(bound: Int): Int {
        require(bound > 0)
        return ((nextLong() ushr 33) % bound).toInt()
    }

    fun nextGaussian(): Double {
        // Box–Muller determinístico
        val u1 = nextDouble().coerceAtLeast(1e-12)
        val u2 = nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }
}

/**
 * Semente por fluxo: seed_{a,t,s} = Truncate64(SHA-256(seed_world || agentId || t || stream)).
 * Nascimentos e mortes não deslocam a sequência aleatória dos demais agentes.
 */
object StreamSeeds {
    fun derive(worldSeed: Long, agentId: String, tick: Long, stream: String): Long {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(ByteBuffer.allocate(8).putLong(worldSeed).array())
        md.update(agentId.toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(ByteBuffer.allocate(8).putLong(tick).array())
        md.update(stream.toByteArray(Charsets.UTF_8))
        return ByteBuffer.wrap(md.digest(), 0, 8).long
    }

    fun rng(worldSeed: Long, agentId: String, tick: Long, stream: String): SplitMix64 =
        SplitMix64(derive(worldSeed, agentId, tick, stream))
}

/** Relaxamento analítico fechado: x(t+Δt) = x* + (x(t) − x*)·e^{−λΔt}. */
fun relaxTowards(current: Double, target: Double, lambda: Double, dt: Double = 1.0): Double =
    target + (current - target) * kotlin.math.exp(-lambda * dt)
