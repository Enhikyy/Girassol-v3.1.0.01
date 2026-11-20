package com.enhikyy.girassol.nativebridge

import com.enhikyy.girassol.core.reflex.KotlinTsetlinMachine
import com.enhikyy.girassol.core.util.SplitMix64
import java.util.Locale

data class NativeDiagnosticReport(
    val nativeLoaded: Boolean,
    val selfTest: String,
    val parity: String,
    val benchmark: String,
    val kotlinBenchmark: String
)

/** Diagnóstico executado sob demanda na tela Sistema (fora da thread de UI). */
object NativeDiagnostics {
    fun run(): NativeDiagnosticReport {
        val kotlinIps = kotlinBenchmark()
        if (!GeaNativeBridge.isNativeLoaded) {
            return NativeDiagnosticReport(false, "biblioteca nativa indisponível", "-", "-", kotlinIps)
        }
        val self = runCatching { GeaNativeBridge.nativeSelfTest() }.getOrElse { "erro: ${it.message}" }
            .ifEmpty { "OK (SplitMix64, aprendizado Tsetlin, roundtrip GRST)" }
        val parity = runCatching { parityCheck() }.getOrElse { "erro: ${it.message}" }
        val bench = runCatching {
            val r = GeaNativeBridge.nativeBenchmarkTsetlin(5L, 200_000)
            String.format(Locale.ROOT, "%,.0f inferências/s (checksum %08x)", r[0], r[1].toLong())
        }.getOrElse { "erro: ${it.message}" }
        return NativeDiagnosticReport(true, self, parity, bench, kotlinIps)
    }

    /** Treina a mesma máquina em Kotlin e em C++ e compara os blobs GRST byte a byte. */
    private fun parityCheck(): String {
        val handle = GeaNativeBridge.nativeCreateTsetlin(16, 20, 64, 3.5f, 8, 2026L)
        try {
            val kt = KotlinTsetlinMachine(16, 20, 64, 3.5f, 8, 2026L)
            val data = SplitMix64(99L)
            val f = ByteArray(16)
            repeat(300) {
                for (i in f.indices) f[i] = (data.nextLong() ushr 63).toByte()
                val target = f[0].toInt() == 1 && f[5].toInt() == 0
                kt.trainStep(f, target)
                GeaNativeBridge.nativeTsetlinTrainStep(handle, f, target)
            }
            val a = kt.serialize()
            val b = GeaNativeBridge.nativeTsetlinSerialize(handle)
            return if (a.contentEquals(b)) "OK: Kotlin e C++ idênticos bit a bit (${a.size} bytes)"
            else "DIVERGENTE (${a.size} vs ${b.size} bytes)"
        } finally {
            GeaNativeBridge.nativeDestroyTsetlin(handle)
        }
    }

    private fun kotlinBenchmark(): String {
        val tm = KotlinTsetlinMachine(16, 20, 64, 3.5f, 8, 5L)
        val rng = SplitMix64(5L xor 0xA5A5A5A5L)
        val f = ByteArray(16)
        repeat(64) { for (i in f.indices) f[i] = (rng.nextLong() and 1L).toByte(); tm.trainStep(f, (f[0].toInt() xor f[3].toInt()) == 0) }
        val n = 200_000
        var acc = 0
        val t0 = System.nanoTime()
        repeat(n) { for (i in f.indices) f[i] = (rng.nextLong() and 1L).toByte(); acc += tm.predictVote(f) }
        val secs = (System.nanoTime() - t0) / 1e9
        return String.format(Locale.ROOT, "%,.0f inferências/s (Kotlin/ART, Σ=%d)", n / secs, acc)
    }
}
