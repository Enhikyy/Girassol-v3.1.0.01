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

    // ==================== 3. NAVMESH + RVO2 / ORCA ====================
    @JvmStatic external fun nativeCreateNavWorld(
        gridW: Int,
        gridH: Int,
        cellSize: Float,
        originX: Float,
        originZ: Float
    ): Long

    @JvmStatic external fun nativeDestroyNavWorld(handle: Long)

    @JvmStatic external fun nativeNavBakeObstacles(
        handle: Long,
        flatBoxesMinXMinZMaxXMaxZ: FloatArray,
        clearance: Float
    )

    @JvmStatic external fun nativeNavFindPath(
        handle: Long,
        startX: Float,
        startZ: Float,
        goalX: Float,
        goalZ: Float
    ): FloatArray

    // ==================== 4. ÁUDIO BINAURAL HRTF ====================
    @JvmStatic external fun nativeCreateBinauralMixer(sampleRate: Int): Long

    @JvmStatic external fun nativeDestroyBinauralMixer(handle: Long)

    // ==================== 5. TREINAMENTO LORA TEMPO REAL ====================
    @JvmStatic external fun nativeCreateLoraAdapter(
        inDim: Int,
        outDim: Int,
        rank: Int,
        alpha: Float,
        seed: Long
    ): Long

    @JvmStatic external fun nativeDestroyLoraAdapter(handle: Long)

    @JvmStatic external fun nativeLoraTrainStepMse(
        handle: Long,
        baseOutArr: FloatArray,
        xArr: FloatArray,
        targetYArr: FloatArray,
        lrA: Float,
        ewcLambda: Float
    ): Float

    @JvmStatic external fun nativeLoraConsolidateEwc(handle: Long)

    // ==================== 6. HARDWARE / AUTOTESTE ====================
    /** Capacidades do SoC (HWCAP/cpuid + sondagem dlopen de OpenCL/Vulkan) em formato chave=valor;... */
    @JvmStatic external fun nativeProbeHardware(): String

    /** Autoteste nativo: string vazia = sucesso; caso contrário, descrição da falha. */
    @JvmStatic external fun nativeSelfTest(): String

    /** [inferências/s, checksum de 32 bits] de um benchmark determinístico do Tsetlin nativo. */
    @JvmStatic external fun nativeBenchmarkTsetlin(seed: Long, iterations: Int): DoubleArray
}
