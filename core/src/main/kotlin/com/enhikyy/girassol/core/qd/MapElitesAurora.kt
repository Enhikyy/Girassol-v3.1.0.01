package com.enhikyy.girassol.core.qd

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.sqrt

@Serializable
data class EliteNicheCell(
    val citizenId: String,
    val generation: Int,
    val fitness: Float,
    val behaviorDescriptor: List<Float>,
    val policyWeights: List<Float>
)

/**
 * AURORA: codificador comportamental não supervisionado por PCA incremental
 * (Regra de Oja para PC1 e deflação de Gram–Schmidt para PC2). Porte de
 * `evolution/map_elites_aurora.hpp`.
 */
@Serializable
class AuroraBehavioralEncoder(
    private val dim: Int = 8,
    private val pc1: FloatArray = FloatArray(dim).also { if (dim > 0) it[0] = 1f },
    private val pc2: FloatArray = FloatArray(dim).also { if (dim > 1) it[1] = 1f },
    private val mean: FloatArray = FloatArray(dim)
) {
    fun projectAndAdapt(stats: FloatArray, lr: Float = 0.015f): Pair<Float, Float> {
        if (stats.size < dim || dim == 0) return 0.5f to 0.5f
        val x = FloatArray(dim)
        for (i in 0 until dim) {
            mean[i] = 0.99f * mean[i] + 0.01f * stats[i]
            x[i] = stats[i] - mean[i]
        }
        var y1 = 0f
        for (i in 0 until dim) y1 += pc1[i] * x[i]
        var n1 = 0f
        for (i in 0 until dim) { pc1[i] += lr * y1 * (x[i] - y1 * pc1[i]); n1 += pc1[i] * pc1[i] }
        n1 = sqrt(n1.coerceAtLeast(1e-8f))
        for (i in 0 until dim) pc1[i] /= n1
        val x2 = FloatArray(dim) { x[it] - y1 * pc1[it] }
        var y2 = 0f
        for (i in 0 until dim) y2 += pc2[i] * x2[i]
        var n2 = 0f
        for (i in 0 until dim) { pc2[i] += lr * y2 * (x2[i] - y2 * pc2[i]); n2 += pc2[i] * pc2[i] }
        n2 = sqrt(n2.coerceAtLeast(1e-8f))
        for (i in 0 until dim) pc2[i] /= n2
        val u = 1f / (1f + exp(-y1.coerceIn(-20f, 20f)))
        val v = 1f / (1f + exp(-y2.coerceIn(-20f, 20f)))
        return u to v
    }
}

/** Arquivo MAP-Elites com nichos 3D (eixo explícito + 2 latentes AURORA). */
@Serializable
class MapElitesArchive(
    val binsPerAxis: Int = 8,
    private val archive: MutableMap<Int, EliteNicheCell> = sortedMapOf<Int, EliteNicheCell>().toMutableMap()
) {
    private fun bin(v: Float): Int = (v.coerceIn(0f, 0.9999f) * binsPerAxis).toInt()

    fun keyOf(bd: List<Float>): Int = (bin(bd[0]) * binsPerAxis + bin(bd[1])) * binsPerAxis + bin(bd[2])

    fun submit(candidate: EliteNicheCell): Boolean {
        val key = keyOf(candidate.behaviorDescriptor)
        val existing = archive[key]
        if (existing == null || candidate.fitness > existing.fitness) {
            archive[key] = candidate
            return true
        }
        return false
    }

    fun coverageRatio(): Float = archive.size.toFloat() / (binsPerAxis * binsPerAxis * binsPerAxis).toFloat()
    fun occupiedNiches(): Int = archive.size
    fun bestFitness(): Float = archive.values.maxOfOrNull { it.fitness } ?: 0f

    /** Doador de curiosidade determinístico (ordem de chave estável). */
    fun selectCuriosityDonor(seed: Long): EliteNicheCell? {
        if (archive.isEmpty()) return null
        val keys = archive.keys.sorted()
        return archive[keys[((seed ushr 1) % keys.size).toInt()]]
    }

    fun cells(): List<Pair<Int, EliteNicheCell>> = archive.keys.sorted().map { it to archive.getValue(it) }
}
