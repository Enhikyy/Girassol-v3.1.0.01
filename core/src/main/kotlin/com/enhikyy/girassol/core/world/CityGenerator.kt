package com.enhikyy.girassol.core.world

import com.enhikyy.girassol.core.util.SplitMix64
import com.enhikyy.girassol.core.util.StreamSeeds

/**
 * Geração urbana por distritos 12×12 com uma variante determinística de
 * Wave Function Collapse: cada distrito escolhe um dos seis tipos (moradia,
 * agricultura, reciclagem, energia, pesquisa, ágora) ponderando compatibilidade
 * com vizinhos já colapsados e a demanda corrente da cidade.
 * A cidade cresce em anéis (3×3 → 4×4 → 5×5 …) sem teto fixo de ambientes.
 */
object CityGenerator {

    /** Compatibilidade de adjacência (simétrica) entre tipos de distrito. */
    private val compat: Map<Pair<DistrictType, DistrictType>, Double> = buildMap {
        val t = DistrictType.entries
        for (a in t) for (b in t) put(a to b, 1.0)
        fun set(a: DistrictType, b: DistrictType, v: Double) { put(a to b, v); put(b to a, v) }
        set(DistrictType.MORADIA, DistrictType.AGORA, 1.8)
        set(DistrictType.MORADIA, DistrictType.AGRICULTURA, 1.4)
        set(DistrictType.MORADIA, DistrictType.RECICLAGEM, 0.5)
        set(DistrictType.MORADIA, DistrictType.ENERGIA, 0.6)
        set(DistrictType.AGRICULTURA, DistrictType.RECICLAGEM, 1.6)
        set(DistrictType.ENERGIA, DistrictType.PESQUISA, 1.7)
        set(DistrictType.ENERGIA, DistrictType.RECICLAGEM, 1.3)
        set(DistrictType.PESQUISA, DistrictType.AGORA, 1.4)
        set(DistrictType.AGORA, DistrictType.AGORA, 0.3)
        set(DistrictType.ENERGIA, DistrictType.ENERGIA, 0.5)
    }

    fun initialLayout(seed: Long): List<District> {
        val rng = StreamSeeds.rng(seed, "WORLD", 0L, "wfc-initial")
        val assigned = HashMap<Pair<Int, Int>, DistrictType>()
        assigned[1 to 1] = DistrictType.AGORA
        val required = ArrayDeque(listOf(DistrictType.MORADIA, DistrictType.MORADIA, DistrictType.AGRICULTURA,
            DistrictType.AGRICULTURA, DistrictType.ENERGIA, DistrictType.PESQUISA, DistrictType.RECICLAGEM))
        val cells = (0 until 3).flatMap { z -> (0 until 3).map { x -> x to z } }.filter { it != (1 to 1) }.toMutableList()
        // Colapso por menor entropia (número de vizinhos já atribuídos; empate pela ordem estável)
        while (cells.isNotEmpty()) {
            val next = cells.maxWith(compareBy<Pair<Int, Int>> { c -> neighbors(c).count { it in assigned } }
                .thenByDescending { it.second * 100 + it.first })
            cells.remove(next)
            val type = if (required.isNotEmpty()) {
                // escolhe entre os obrigatórios restantes o mais compatível com a vizinhança
                val options = required.distinct()
                val chosen = weightedPick(options, rng) { t -> neighborScore(next, t, assigned) }
                required.remove(chosen)
                chosen
            } else {
                weightedPick(DistrictType.entries, rng) { t -> neighborScore(next, t, assigned) }
            }
            assigned[next] = type
        }
        return assigned.entries.sortedWith(compareBy({ it.key.second }, { it.key.first }))
            .map { (c, t) -> District("D${c.first}_${c.second}", c.first, c.second, t, 0L) }
    }

    fun expansionRing(state: WorldState, demand: Map<DistrictType, Double>, rng: SplitMix64): List<District> {
        val k = state.districtsPerSide
        val assigned = state.districts.associate { (it.dx to it.dz) to it.type }.toMutableMap()
        val newCells = (0..k).map { k to it } + (0 until k).map { it to k }
        val out = mutableListOf<District>()
        for (c in newCells) {
            val type = weightedPick(DistrictType.entries, rng) { t ->
                neighborScore(c, t, assigned) * (0.25 + (demand[t] ?: 0.0))
            }
            assigned[c] = type
            out += District("D${c.first}_${c.second}", c.first, c.second, type, state.tick)
        }
        return out
    }

    private fun neighbors(c: Pair<Int, Int>) =
        listOf(c.first + 1 to c.second, c.first - 1 to c.second, c.first to c.second + 1, c.first to c.second - 1)

    private fun neighborScore(c: Pair<Int, Int>, t: DistrictType, assigned: Map<Pair<Int, Int>, DistrictType>): Double {
        var s = 1.0
        for (n in neighbors(c)) assigned[n]?.let { s *= compat.getValue(t to it) }
        return s
    }

    private fun <T> weightedPick(options: List<T>, rng: SplitMix64, weight: (T) -> Double): T {
        val w = options.map { weight(it).coerceAtLeast(1e-6) }
        val total = w.sum()
        var r = rng.nextDouble() * total
        for (i in options.indices) {
            r -= w[i]
            if (r <= 0) return options[i]
        }
        return options.last()
    }

    /** Reconstrói todo o mapa de tiles e (re)publica objetos inteligentes dos distritos novos. */
    fun rebuildTiles(state: WorldState) {
        val w = state.widthTiles
        val tiles = IntArray(w * w) { TileType.GRAMA.ordinal }
        for (d in state.districts) paintDistrict(tiles, w, d)
        state.tiles = tiles
    }

    fun spawnObjectsFor(state: WorldState, d: District) {
        val ox = d.dx * DISTRICT_SIZE
        val oz = d.dz * DISTRICT_SIZE
        fun add(type: SmartObjectType, lx: Int, lz: Int) {
            val id = "${type.name.take(3)}-${state.nextObjectSerial++}"
            val obj = SmartObject(id, type, d.id, ox + lx, oz + lz)
            state.objects += obj
            state.board.publishAdvertisement(
                AffordanceAdvertisement(id, type.name, obj.tileX.toFloat(), obj.tileZ.toFloat(), 10_000f, type.capacity)
            )
        }
        when (d.type) {
            DistrictType.MORADIA -> { add(SmartObjectType.CASA, 3, 5); add(SmartObjectType.CASA, 8, 5); add(SmartObjectType.CASA, 3, 10); add(SmartObjectType.CASA, 8, 10) }
            DistrictType.AGRICULTURA -> { add(SmartObjectType.HORTA, 3, 3); add(SmartObjectType.HORTA, 8, 4); add(SmartObjectType.HORTA, 5, 9) }
            DistrictType.RECICLAGEM -> { add(SmartObjectType.COMPOSTEIRA, 3, 9); add(SmartObjectType.COMPOSTEIRA, 9, 9); add(SmartObjectType.OFICINA, 6, 2) }
            DistrictType.ENERGIA -> { add(SmartObjectType.USINA_SOLAR, 2, 6); add(SmartObjectType.USINA_SOLAR, 10, 6) }
            DistrictType.PESQUISA -> { add(SmartObjectType.LABORATORIO, 4, 9); add(SmartObjectType.ESCOLA, 8, 9) }
            DistrictType.AGORA -> { add(SmartObjectType.PRACA, 4, 4); add(SmartObjectType.PRACA, 8, 8); add(SmartObjectType.OFICINA, 10, 3) }
        }
    }

    private fun paintDistrict(tiles: IntArray, w: Int, d: District) {
        val ox = d.dx * DISTRICT_SIZE
        val oz = d.dz * DISTRICT_SIZE
        fun set(lx: Int, lz: Int, t: TileType) { tiles[(oz + lz) * w + (ox + lx)] = t.ordinal }
        fun rect(x0: Int, z0: Int, x1: Int, z1: Int, t: TileType) { for (z in z0..z1) for (x in x0..x1) set(x, z, t) }
        rect(0, 0, DISTRICT_SIZE - 1, DISTRICT_SIZE - 1, TileType.GRAMA)
        when (d.type) {
            DistrictType.MORADIA -> { rect(2, 2, 4, 4, TileType.EDIFICIO); rect(7, 2, 9, 4, TileType.EDIFICIO); rect(2, 7, 4, 9, TileType.EDIFICIO); rect(7, 7, 9, 9, TileType.EDIFICIO) }
            DistrictType.AGRICULTURA -> { rect(2, 2, 10, 10, TileType.CAMPO); rect(6, 6, 6, 7, TileType.AGUA) }
            DistrictType.RECICLAGEM -> { rect(3, 4, 9, 7, TileType.EDIFICIO); rect(5, 1, 7, 1, TileType.EDIFICIO) }
            DistrictType.ENERGIA -> { rect(4, 2, 8, 3, TileType.EDIFICIO); rect(4, 5, 8, 6, TileType.EDIFICIO); rect(4, 8, 8, 9, TileType.EDIFICIO) }
            DistrictType.PESQUISA -> { rect(3, 3, 9, 7, TileType.EDIFICIO) }
            DistrictType.AGORA -> { rect(2, 2, 10, 10, TileType.PRACA); rect(6, 5, 6, 6, TileType.AGUA) }
        }
        // malha viária: ruas nas bordas (compartilhadas) e calçadas internas
        for (i in 0 until DISTRICT_SIZE) { set(i, 0, TileType.RUA); set(0, i, TileType.RUA) }
        for (i in 1 until DISTRICT_SIZE) {
            if (TileType.entries[tiles[(oz + 1) * w + ox + i]] == TileType.GRAMA) set(i, 1, TileType.CALCADA)
            if (TileType.entries[tiles[(oz + i) * w + ox + 1]] == TileType.GRAMA) set(1, i, TileType.CALCADA)
        }
    }
}
