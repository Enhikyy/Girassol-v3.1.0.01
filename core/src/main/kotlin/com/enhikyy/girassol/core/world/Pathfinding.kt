package com.enhikyy.girassol.core.world

import java.util.PriorityQueue
import kotlin.math.abs

/**
 * A* em grade 4-conexa com custo estigmérgico: trilhas (TRAIL_WEAR) barateiam o
 * deslocamento, fazendo caminhos emergirem organicamente; DANGER_TRACE encarece.
 *
 * Esta é a implementação de referência. O espelho nativo (cpp/navigation/grid_astar.hpp)
 * replica a mesma aritmética float, a mesma chave de prioridade (bits do float << 32 | índice)
 * e o mesmo limite de expansão — os caminhos são idênticos, preservando a reprodutibilidade
 * por semente qualquer que seja o backend.
 */
object Pathfinding {
    fun findPath(state: WorldState, sx: Int, sz: Int, gx: Int, gz: Int, trail: FloatArray?, danger: FloatArray?): MutableList<Int> =
        findPathGrid(state.widthTiles, state.tiles, sx, sz, gx, gz, trail, danger)

    fun stepCost(tileOrdinal: Int): Float = when (tileOrdinal) {
        TileType.RUA.ordinal, TileType.CALCADA.ordinal -> 0.8f
        TileType.CAMPO.ordinal -> 1.2f
        else -> 1.0f
    }

    fun walkable(tileOrdinal: Int): Boolean = TileType.entries[tileOrdinal].walkable

    fun findPathGrid(w: Int, tiles: IntArray, sx: Int, sz: Int, gx: Int, gz: Int, trail: FloatArray?, danger: FloatArray?): MutableList<Int> {
        if (gx !in 0 until w || gz !in 0 until w) return mutableListOf()
        val start = sz * w + sx
        val goal = gz * w + gx
        if (start == goal) return mutableListOf()
        val g = FloatArray(w * w) { Float.MAX_VALUE }
        val parent = IntArray(w * w) { -1 }
        val closed = BooleanArray(w * w)
        val open = PriorityQueue<Long>()
        fun key(f: Float, idx: Int): Long = (java.lang.Float.floatToIntBits(f).toLong() shl 32) or idx.toLong()
        g[start] = 0f
        open.add(key(h(sx, sz, gx, gz), start))
        val dx = intArrayOf(1, -1, 0, 0)
        val dz = intArrayOf(0, 0, 1, -1)
        var expanded = 0
        while (open.isNotEmpty()) {
            val cur = (open.poll() and 0xFFFFFFFFL).toInt()
            if (closed[cur]) continue
            closed[cur] = true
            if (cur == goal) break
            if (++expanded > w * w) break
            val cx = cur % w
            val cz = cur / w
            for (d in 0 until 4) {
                val nx = cx + dx[d]
                val nz = cz + dz[d]
                if (nx !in 0 until w || nz !in 0 until w) continue
                val ni = nz * w + nx
                if (closed[ni]) continue
                val tile = tiles[ni]
                if (!walkable(tile) && ni != goal) continue
                var cost = stepCost(tile)
                if (trail != null) cost *= (1f - 0.35f * (trail[ni] / 10f).coerceAtMost(1f))
                if (danger != null) cost *= (1f + (danger[ni] / 5f).coerceAtMost(2f))
                val ng = g[cur] + cost
                if (ng < g[ni]) {
                    g[ni] = ng
                    parent[ni] = cur
                    open.add(key(ng + h(nx, nz, gx, gz) * 0.5f, ni))
                }
            }
        }
        if (parent[goal] == -1) return mutableListOf()
        val path = ArrayList<Int>()
        var t = goal
        while (t != start && t != -1) { path.add(t); t = parent[t] }
        path.reverse()
        return path
    }

    private fun h(x: Int, z: Int, gx: Int, gz: Int): Float = (abs(x - gx) + abs(z - gz)).toFloat()
}
