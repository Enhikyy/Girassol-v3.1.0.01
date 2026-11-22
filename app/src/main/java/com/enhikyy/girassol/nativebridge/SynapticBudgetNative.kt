package com.enhikyy.girassol.nativebridge

import android.util.Log
import java.nio.ByteBuffer

/**
 * Ponte JNI do Orçamento Sináptico (libgirassol_sb.so). Os métodos são registrados por
 * RegisterNatives no JNI_OnLoad (girassol_sb_jni.cpp); nomes e assinaturas são preservados
 * pelo R8 (proguard-rules.pro) e verificados no CI (scripts/check_r8_jni.py).
 *
 * O estado vive num ByteBuffer direto alinhado a 64 bytes (deslocamento `off`), sem heap nativo.
 * observe/commit/autoteste usam O(D²) doubles de pilha: chame-os só da thread do
 * [com.enhikyy.girassol.synaptic.SynapticBudgetEngine] (pilha de 64 MB).
 */
object SynapticBudgetNative {
    private const val TAG = "SynapticBudgetNative"

    @Volatile var loaded: Boolean = false
        private set
    @Volatile var loadError: String = ""
        private set

    init {
        loaded = try {
            System.loadLibrary("girassol_sb"); true
        } catch (t: Throwable) {
            loadError = "${t.javaClass.simpleName}: ${t.message}"
            Log.w(TAG, "libgirassol_sb indisponível", t)
            false
        }
    }

    @JvmStatic external fun nativeStateBytes(dim: Int): Long
    @JvmStatic external fun nativeSerializedBytes(dim: Int): Long
    @JvmStatic external fun nativeAlignOffset(buf: ByteBuffer): Int
    @JvmStatic external fun nativeInit(buf: ByteBuffer, off: Int, dim: Int): Int
    @JvmStatic external fun nativeObserve(buf: ByteBuffer, off: Int, dim: Int, x: DoubleArray, n: Int, w: Double): Int
    @JvmStatic external fun nativeCommit(buf: ByteBuffer, off: Int, dim: Int, rank: Int): Int
    @JvmStatic external fun nativeCommitForTail(buf: ByteBuffer, off: Int, dim: Int, frac: Double, maxRank: Int): Int
    @JvmStatic external fun nativeRelease(buf: ByteBuffer, off: Int, dim: Int, count: Int)
    @JvmStatic external fun nativeProject(buf: ByteBuffer, off: Int, dim: Int, g: DoubleArray, m: Int): Int
    /** out[10] = posto, tarefas, fração livre, energia, ε atual, nº ε, cauda(posto), blocos LoRA, linhas preparadas, ativo. */
    @JvmStatic external fun nativeTelemetry(buf: ByteBuffer, off: Int, dim: Int, out: DoubleArray): Int
    @JvmStatic external fun nativeEigenvalues(buf: ByteBuffer, off: Int, dim: Int, out: DoubleArray): Int
    @JvmStatic external fun nativeEnableEpsHistory(buf: ByteBuffer, off: Int, dim: Int, on: Boolean)
    @JvmStatic external fun nativeEpsHistory(buf: ByteBuffer, off: Int, dim: Int, out: DoubleArray?): Int
    @JvmStatic external fun nativeForgettingBound(buf: ByteBuffer, off: Int, dim: Int, fInf: Double, c: Double): Double
    @JvmStatic external fun nativeRankForTail(buf: ByteBuffer, off: Int, dim: Int, frac: Double): Int
    @JvmStatic external fun nativeSerialize(buf: ByteBuffer, off: Int, dim: Int, out: ByteArray): Int
    @JvmStatic external fun nativeDeserialize(buf: ByteBuffer, off: Int, dim: Int, data: ByteArray): Int
    /** jOut[2] = {J*, varreduras}. */
    @JvmStatic external fun nativePlanProtection(h: Int, d: Double, rho: Double, kappa: Double, fInf: Double, c: Double,
                                                 fMin: Double, uOut: DoubleArray, jOut: DoubleArray?): Int
    /** out5 = {tau, alpha, R²exp, R²pot, regime(0 desconhecido, 1 exponencial, 2 potência)}. */
    @JvmStatic external fun nativeAnalyzeSpectrum(lam: DoubleArray, i0: Int, i1: Int, out5: DoubleArray): Int
    @JvmStatic external fun nativeCumulativeBound(fInf: Double, c: Double, eps: DoubleArray, count: Int): Double
    @JvmStatic external fun nativeFnv1a64(data: ByteArray, len: Int): Long
    @JvmStatic external fun nativeSetActive(buf: ByteBuffer?, off: Int, dim: Int): Int
    @JvmStatic external fun nativeBridgeHandle(): Long
    /** out[3] = linhas observadas, posto resultante, status. */
    @JvmStatic external fun nativeConsolidateStaged(w: Double, frac: Double, maxRank: Int, out: IntArray): Int
    /** ints[21] = verificações, falhas, falhas por teste (índice 0..18); hashOut[0] = hash T13. */
    @JvmStatic external fun nativeRunSelfTests(ints: IntArray, hashOut: LongArray): String
    @JvmStatic external fun nativeAbi(): String

    /** Hash canônico do T13 (x86_64 e arm64-v8a, -ffp-contract=off -fno-fast-math). */
    const val CANONICAL_T13_HASH: String = "9ab8752a00f798b6"
}
