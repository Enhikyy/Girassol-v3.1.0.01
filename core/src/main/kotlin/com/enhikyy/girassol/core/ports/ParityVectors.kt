package com.enhikyy.girassol.core.ports

import com.enhikyy.girassol.core.forge.LoraAdapterState
import com.enhikyy.girassol.core.forge.LoraMath
import com.enhikyy.girassol.core.forge.LoraSample
import com.enhikyy.girassol.core.util.SplitMix64
import com.enhikyy.girassol.core.world.Pathfinding
import com.enhikyy.girassol.core.world.TileType

/**
 * Vetores sintéticos de paridade JVM ↔ C++. Os mesmos geradores existem em
 * native-tests/native_tests.cpp; as assinaturas (FNV-1a 64) estão fixadas nos dois lados
 * e são verificadas também no aparelho, na inicialização, antes de ativar o backend nativo.
 */
object ParityVectors {
    const val NAV_GOLDEN: Long = 0x30cd3f41edf40ac4L  // v3.4: A* + clearance do DistrictNavMesh
    const val LORA_GOLDEN: Long = -1349069367639751732L // 0xed47248ecc78c3cc

    const val NAV_W = 40
    const val NAV_QUERIES = 64

    class NavGrid(val w: Int, val tiles: IntArray, val trail: FloatArray, val danger: FloatArray, val queries: IntArray)

    fun navGrid(seed: Long = 2026L): NavGrid {
        val rng = SplitMix64(seed)
        val w = NAV_W
        val tiles = IntArray(w * w)
        val trail = FloatArray(w * w)
        val danger = FloatArray(w * w)
        for (i in 0 until w * w) {
            val r = ((rng.nextLong() ushr 1) % 100L).toInt()
            tiles[i] = when {
                r < 18 -> TileType.EDIFICIO.ordinal
                r < 22 -> TileType.AGUA.ordinal
                r < 40 -> TileType.RUA.ordinal
                r < 48 -> TileType.CALCADA.ordinal
                r < 60 -> TileType.CAMPO.ordinal
                else -> TileType.GRAMA.ordinal
            }
            trail[i] = rng.nextFloat() * 12f
            danger[i] = if ((rng.nextLong() ushr 1) % 10L == 0L) rng.nextFloat() * 8f else 0f
        }
        val q = IntArray(NAV_QUERIES * 4) { ((rng.nextLong() ushr 1) % w.toLong()).toInt() }
        return NavGrid(w, tiles, trail, danger, q)
    }

    fun navSignature(find: (NavGrid, Int, Int, Int, Int) -> IntArray): Long {
        val grid = navGrid()
        var h = FNV_OFFSET
        for (k in 0 until NAV_QUERIES) {
            val p = find(grid, grid.queries[k * 4], grid.queries[k * 4 + 1], grid.queries[k * 4 + 2], grid.queries[k * 4 + 3])
            h = fnv(h, p.size.toLong())
            for (v in p) h = fnv(h, v.toLong())
        }
        return h
    }

    fun kotlinNavSignature(): Long = navSignature { g, sx, sz, gx, gz ->
        Pathfinding.findPathGrid(g.w, g.tiles, sx, sz, gx, gz, g.trail, g.danger).toIntArray()
    }

    const val LORA_SAMPLES = 64
    const val LORA_EPOCHS = 3
    const val LORA_LR = 0.01f
    const val LORA_EWC = 15f

    fun loraSamples(seed: Long = 77L): List<LoraSample> {
        val rng = SplitMix64(seed)
        return List(LORA_SAMPLES) {
            val action = ((rng.nextLong() ushr 1) % 13L).toInt()
            val tick = ((rng.nextLong() ushr 1) % 480L).toInt()
            val x = FloatArray(LoraAdapterState.IN_DIM)
            x[action] = 1f
            x[13 + tick * 4 / 480] = 1f
            x[LoraAdapterState.IN_DIM - 1] = 1f
            LoraSample(x, rng.nextFloat() * 2f - 1f)
        }
    }

    fun loraInitial(): LoraAdapterState = LoraAdapterState.initial(1234L).also {
        // linha de base não nula para exercitar EWC (âncora ≠ peso) e Fisher
        for (i in it.fisherA.indices) it.fisherA[i] = 0.01f
        for (i in it.fisherB.indices) it.fisherB[i] = 0.02f
    }

    fun loraSignature(packed: FloatArray, step: Int): Long {
        var h = FNV_OFFSET
        for (v in packed) h = fnv(h, java.lang.Float.floatToRawIntBits(v).toLong() and 0xFFFFFFFFL)
        return fnv(h, step.toLong())
    }

    fun kotlinLoraSignature(): Long {
        val s = loraInitial()
        LoraMath.trainEpochs(s, loraSamples(), LORA_EPOCHS, LORA_LR, LORA_EWC)
        return loraSignature(s.packed(), s.step)
    }

    private const val FNV_OFFSET = -0x340d631b7bdddcdbL  // 0xcbf29ce484222325
    private const val FNV_PRIME = 0x100000001b3L
    fun fnv(h0: Long, v: Long): Long {
        var h = h0
        for (b in 0 until 8) {
            h = h xor ((v ushr (b * 8)) and 0xFF)
            h *= FNV_PRIME
        }
        return h
    }
}
