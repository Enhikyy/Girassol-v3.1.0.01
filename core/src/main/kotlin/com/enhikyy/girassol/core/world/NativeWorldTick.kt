package com.enhikyy.girassol.core.world

/**
 * v3.7 — ponte JNI do WorldTick C++20 (`app/src/main/cpp/world/world_tick.cpp`).
 *
 * Necessidades, envelhecimento/morte, atualização social, reprodução por mérito, IRT 2PL,
 * Heider, assembleia e PER rodam no kernel nativo determinístico. Não há implementação Kotlin
 * alternativa: sem a biblioteca o mundo não roda.
 *  - Android: `libgirassol_core.so` (empacotada no APK).
 *  - JVM (testes): biblioteca de host indicada por `-Dgirassol.worldtick.lib=...` (Gradle).
 */
object NativeWorldTick {
    const val NEEDS_STRIDE = 12
    const val MERIT_L = 13
    const val MERIT_D = 19
    const val SOCIAL_D = 14
    const val IRT_ITEMS = 5
    const val NO_THETA = -1.0e9

    const val BIT_ADULT = 1L
    const val BIT_ANOKHIN = 2L
    const val BIT_BRIER = 4L
    const val BIT_PROMOTION = 8L
    const val BIT_WELLBEING = 16L
    const val BIT_IRT = 32L
    const val BIT_COMMONS = 64L
    const val ALL_CITIZEN_BITS = 63L

    init {
        val explicit = System.getProperty("girassol.worldtick.lib")
        if (!explicit.isNullOrBlank()) System.load(explicit) else System.loadLibrary("girassol_core")
    }

    @JvmStatic external fun nativeStreamSeed(seed: Long, agentId: String, tick: Long, stream: String): Long
    @JvmStatic external fun nativeStepNeeds(packed: DoubleArray, n: Int, night: Boolean)
    @JvmStatic external fun nativeNeedDecay(metabolismo: Double, sociabilidade: Double, conscienciosidade: Double, curiosidade: Double): DoubleArray
    @JvmStatic external fun nativeLifecycle(ids: Array<String>, packed: LongArray, longevity: DoubleArray, tick: Long): IntArray
    @JvmStatic external fun nativeStepSocial(seed: Long, tick: Long, ids: Array<String>, groups: Array<String>, rows: DoubleArray,
                                             tieI: IntArray, tieD: DoubleArray, outD: DoubleArray): LongArray
    @JvmStatic external fun nativeStepDaily(seed: Long, tick: Long, housing: Int, admissionCap: Int, commonsFood: Double,
                                            ids: Array<String>, nAlive: Int, meritL: LongArray, meritD: DoubleArray,
                                            tieI: IntArray, tieD: DoubleArray, items: DoubleArray): LongArray
    @JvmStatic external fun nativeMeritCitizens(ids: Array<String>, meritL: LongArray, meritD: DoubleArray, tick: Long): LongArray
    @JvmStatic external fun nativeMeritEstimate(ids: Array<String>, meritL: LongArray, meritD: DoubleArray, commonsFood: Double, tick: Long): LongArray
    @JvmStatic external fun nativePerSample(priorities: DoubleArray, batch: Int, alpha: Double, beta: Double, seed: Long, weightsOut: DoubleArray): IntArray
    @JvmStatic external fun nativeIrtProbability(theta: Double, a: Double, b: Double): Double
    @JvmStatic external fun nativeParitySignature(): Long

    /** Empacota as colunas de mérito/IRT de cada cidadão nas linhas compactas do kernel. */
    fun packMerit(rows: List<Citizen>): Pair<LongArray, DoubleArray> {
        val l = LongArray(rows.size * MERIT_L)
        val d = DoubleArray(rows.size * MERIT_D)
        rows.forEachIndexed { i, c ->
            val o = i * MERIT_L
            l[o] = c.birthTick; l[o + 1] = c.children.toLong(); l[o + 2] = c.lastReproductionTick
            l[o + 3] = if (c.superacao) 1 else 0
            l[o + 4] = when (c.sex) { "F" -> 1; "M" -> 2; else -> 0 }
            l[o + 5] = c.acceptorBaseN.toLong(); l[o + 6] = c.acceptorBaselineTick
            l[o + 7] = c.brierBaseN.toLong(); l[o + 8] = c.brierBaselineTick
            l[o + 9] = c.promotions.toLong(); l[o + 10] = c.promotionsAtLastReproduction.toLong()
            l[o + 11] = c.generation.toLong(); l[o + 12] = c.irtTheta0Tick
            val p = i * MERIT_D
            d[p] = c.acceptorBaseSum; d[p + 1] = c.acceptorEma; d[p + 2] = c.brierBaseSum; d[p + 3] = c.brierEma
            d[p + 4] = c.meanNeed(); d[p + 5] = c.opinionCommons
            d[p + 6] = c.irtTheta; d[p + 7] = c.irtThetaSe; d[p + 8] = c.irtTheta0
            for (j in 0 until IRT_ITEMS) { d[p + 9 + j] = c.irtAttempts[j]; d[p + 14 + j] = c.irtSuccesses[j] }
        }
        return l to d
    }

    /** Aplica de volta os campos que o ciclo diário altera. */
    fun unpackMerit(rows: List<Citizen>, l: LongArray, d: DoubleArray) {
        rows.forEachIndexed { i, c ->
            val o = i * MERIT_L
            c.children = l[o + 1].toInt(); c.lastReproductionTick = l[o + 2]
            c.promotionsAtLastReproduction = l[o + 10].toInt(); c.irtTheta0Tick = l[o + 12]
            val p = i * MERIT_D
            c.irtTheta = d[p + 6]; c.irtThetaSe = d[p + 7]; c.irtTheta0 = d[p + 8]
            for (j in 0 until IRT_ITEMS) { c.irtAttempts[j] = d[p + 9 + j]; c.irtSuccesses[j] = d[p + 14 + j] }
        }
    }
}
