package com.enhikyy.girassol.nativebridge

object GeaNativeBridge {
    @Volatile
    var isNativeLoaded: Boolean = false
        private set

    init {
        isNativeLoaded = try {
            System.loadLibrary("girassol_core")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    // ==================== 1. TSETLIN MACHINE ====================
    @JvmStatic external fun nativeCreateTsetlin(
        numFeatures: Int,
        numClauses: Int,
        numStates: Int,
        specificityS: Float,
        thresholdT: Int,
        seed: Long
    ): Long

    @JvmStatic external fun nativeDestroyTsetlin(handle: Long)

    @JvmStatic external fun nativeTsetlinTrainStep(
        handle: Long,
        featuresArr: ByteArray,
        targetPositive: Boolean
    )

    @JvmStatic external fun nativeTsetlinPredictVote(
        handle: Long,
        featuresArr: ByteArray
    ): Int

    @JvmStatic external fun nativeTsetlinConfidence(
        handle: Long,
        featuresArr: ByteArray
    ): Float

    @JvmStatic external fun nativeTsetlinSerialize(handle: Long): ByteArray

    @JvmStatic external fun nativeTsetlinDeserialize(
        handle: Long,
        blobArr: ByteArray
    ): Boolean

    // ==================== 2. MAP-ELITES + AURORA ====================
    @JvmStatic external fun nativeCreateMapElites(
        trajectoryDim: Int,
        binsPerAxis: Int
    ): Long

    @JvmStatic external fun nativeDestroyMapElites(handle: Long)

    @JvmStatic external fun nativeMapElitesSubmit(
        handle: Long,
        citizenId: Long,
        generation: Int,
        fitness: Float,
        explicitAxis: Float,
        trajectoryStatsArr: FloatArray,
        weightsArr: FloatArray
    ): Boolean

    @JvmStatic external fun nativeMapElitesCoverage(handle: Long): Float

    // ==================== 3. NAVEGAÇÃO (espelho exato do Pathfinding Kotlin) ====================
    /** A* 4-conexo estigmérgico em C++; retorna os índices de tile do caminho (sem a origem). */
    @JvmStatic external fun nativeNavFindPathGrid(
        w: Int, tiles: IntArray, sx: Int, sz: Int, gx: Int, gz: Int, trail: FloatArray?, danger: FloatArray?
    ): IntArray

    // ==================== 4. ÁUDIO BINAURAL (AAudio) ====================
    @JvmStatic external fun nativeAudioStart(): Boolean
    @JvmStatic external fun nativeAudioStop()
    @JvmStatic external fun nativeAudioSetParams(stability: Float, mood: Float, tension: Float, gain: Float)
    @JvmStatic external fun nativeAudioSpeech(x: Float, z: Float, pitch: Float)
    @JvmStatic external fun nativeAudioStatus(): String
    /** Renderização offline: [rms, pico, quadros]. */
    @JvmStatic external fun nativeAudioOfflineProbe(stability: Float, frames: Int): FloatArray

    // ==================== 5. LORA (espelho exato do LoraMath Kotlin) ====================
    /** Estado empacotado' + [mseFirst, mseLast, bits(step)]; null em entrada inválida. */
    @JvmStatic external fun nativeLoraTrain(
        inDim: Int, outDim: Int, rank: Int, scaling: Float, packed: FloatArray, step: Int,
        xs: FloatArray, ys: FloatArray, n: Int, epochs: Int, lr: Float, ewc: Float
    ): FloatArray?

    // ==================== 5b. CPU SIMD + MEMÓRIA ====================
    // O motor LLM foi movido para LlmNativeBridge (bibliotecas por variante de CPU).
    /** [neon, dotprod, fp16, i8mm, sve, sve2, aarch64, variante recomendada]. */
    @JvmStatic external fun nativeCpuFeatures(): IntArray
    /** [VmRSS, VmHWM, VmSize, RssAnon, RssFile, VmSwap] em kB, de /proc/self/status. */
    @JvmStatic external fun nativeProcMemory(): LongArray

    // ==================== 6. HARDWARE / AUTOTESTE ====================
    /** Capacidades do SoC (HWCAP/cpuid + sondagem dlopen de OpenCL/Vulkan) em formato chave=valor;... */
    @JvmStatic external fun nativeProbeHardware(): String

    /** Autoteste nativo: string vazia = sucesso; caso contrário, descrição da falha. */
    @JvmStatic external fun nativeSelfTest(): String

    /** [inferências/s, checksum de 32 bits] de um benchmark determinístico do Tsetlin nativo. */
    @JvmStatic external fun nativeBenchmarkTsetlin(seed: Long, iterations: Int): DoubleArray
}
