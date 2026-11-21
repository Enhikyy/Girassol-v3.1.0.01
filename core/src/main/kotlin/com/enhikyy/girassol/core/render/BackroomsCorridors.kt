package com.enhikyy.girassol.core.render

import com.enhikyy.girassol.core.world.ObjectView
import com.enhikyy.girassol.core.world.SmartObjectType
import com.enhikyy.girassol.core.world.TileType

/**
 * Corredores liminares estilo Backrooms que interligam o setor Severance/Lumon (laboratórios
 * do distrito de Pesquisa) à ala Modernismo Tropical (praças da Ágora e hortas).
 *
 * Camada puramente visual: o traçado segue o menor caminho sobre tiles caminháveis (BFS
 * determinístico, vizinhança N/L/S/O em ordem fixa), então os cidadãos que já andam por ali
 * passam "dentro" do corredor sem alterar a física, o replay ou os goldens C++.
 * Mesma grade + mesmos objetos ⇒ mesmos corredores em qualquer aparelho.
 */
object BackroomsCorridors {
    class Corridor(val fromId: String, val toId: String, val cells: List<Int>)

    private val LUMON = setOf(SmartObjectType.LABORATORIO)
    private val TROPICAL = setOf(SmartObjectType.PRACA, SmartObjectType.HORTA)

    fun plan(width: Int, tiles: IntArray, objects: List<ObjectView>, maxCorridors: Int = 4, maxLength: Int = 64): List<Corridor> {
        if (width <= 0 || tiles.size < width * width) return emptyList()
        val types = TileType.entries
        fun walkable(i: Int) = types[tiles[i].coerceIn(0, types.size - 1)].walkable
        val labs = objects.filter { it.type in LUMON }.sortedBy { it.id }
        val wing = objects.filter { it.type in TROPICAL }.sortedBy { it.id }
        if (labs.isEmpty() || wing.isEmpty()) return emptyList()
        val out = ArrayList<Corridor>()
        val used = HashSet<String>()
        for (lab in labs) {
            if (out.size >= maxCorridors) break
            val target = wing.filter { it.id !in used }
                .minWithOrNull(compareBy<ObjectView>({ kotlin.math.abs(it.x - lab.x) + kotlin.math.abs(it.z - lab.z) }, { it.id })) ?: break
            val start = lab.z * width + lab.x
            val goal = target.z * width + target.x
            if (start !in tiles.indices || goal !in tiles.indices || !walkable(start) || !walkable(goal)) continue
            val prev = IntArray(width * width) { -2 }
            val q = ArrayDeque<Int>()
            prev[start] = -1; q.addLast(start)
            while (q.isNotEmpty()) {
                val c = q.removeFirst()
                if (c == goal) break
                val cx = c % width; val cz = c / width
                for ((dx, dz) in DIRS) {
                    val nx = cx + dx; val nz = cz + dz
                    if (nx !in 0 until width || nz !in 0 until width) continue
                    val n = nz * width + nx
                    if (prev[n] != -2 || !walkable(n)) continue
                    prev[n] = c; q.addLast(n)
                }
            }
            if (prev[goal] == -2) continue
            val path = ArrayList<Int>()
            var c = goal
            while (c != -1) { path.add(c); c = prev[c] }
            path.reverse()
            if (path.size > maxLength || path.size < 3) continue
            used += target.id
            out += Corridor(lab.id, target.id, path)
        }
        return out
    }

    /** Arestas de parede: para cada célula, 4 bits (N, L, S, O) indicando parede naquele lado. */
    fun wallMask(width: Int, tiles: IntArray, corridor: Corridor): IntArray {
        val set = corridor.cells.toHashSet()
        val types = TileType.entries
        val first = corridor.cells.first(); val last = corridor.cells.last()
        return IntArray(corridor.cells.size) { k ->
            val c = corridor.cells[k]
            if (c == first || c == last) return@IntArray 0     // entradas abertas
            val cx = c % width; val cz = c / width
            var m = 0
            for ((bit, d) in DIRS.withIndex()) {
                val nx = cx + d.first; val nz = cz + d.second
                if (nx !in 0 until width || nz !in 0 until width) { m = m or (1 shl bit); continue }
                val n = nz * width + nx
                if (n in set) continue
                // portas onde o corredor cruza a malha viária (os cidadãos atravessam ali)
                if (types[tiles[n].coerceIn(0, types.size - 1)] == TileType.RUA) continue
                m = m or (1 shl bit)
            }
            m
        }
    }

    val DIRS = listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0)
}
