package com.enhikyy.girassol.render3d

import com.enhikyy.girassol.core.world.ObjectView
import com.enhikyy.girassol.core.world.SmartObjectType
import com.enhikyy.girassol.core.world.TileType
import kotlin.math.PI
import kotlin.math.abs

/**
 * v4.0 — URBAN_OVERCAST: converte a grade lógica (tiles de 4 m) em blocos urbanos modulares.
 *  - RUA: asfalto molhado (poças SSR 0,12 / seco 0,85), faixa central orientada pela via;
 *  - CALCADA/PRACA: placas elevadas 0,15 m com meio-fio chanfrado;
 *  - EDIFICIO: volumes de 3–8 pavimentos (3,333 m) com fachada de concreto #6E767A, painéis
 *    teal #1D5961, janelas emissivas (~35 %), frisos de laje em relevo, platibanda, casas de
 *    máquinas e letreiros luminosos nos telhados;
 *  - mobiliário: postes de 6 m com braço (instanciados por guinada), fiação aérea em catenária,
 *    guarda-corpos metálicos junto à água, barreiras de obra, árvores.
 * Determinístico: a mesma grade gera sempre a mesma cena (testado na JVM).
 */
object UrbanSceneBuilder {
    const val FLOOR_H = 10f / 3f
    const val SIDEWALK_H = 0.15f
    const val CURB_CHAMFER = 0.05f
    const val LAMP_H = 6f
    const val MIN_FLOORS = 3
    const val MAX_FLOORS = 8

    private fun hash(a: Int, b: Int, salt: Int = 0): Int {
        var h = a * 73856093 xor b * 19349663 xor salt * 83492791
        h = h xor (h ushr 13); h *= 0x5bd1e995; h = h xor (h ushr 15)
        return h and 0x7fffffff
    }

    /** Pavimentos de um volume (3..8), determinístico por posição. */
    fun floorsFor(x0: Int, z0: Int): Int = MIN_FLOORS + hash(x0, z0, 7) % (MAX_FLOORS - MIN_FLOORS + 1)

    data class Block(val x0: Int, val z0: Int, val x1: Int, val z1: Int, val floors: Int)

    /** Agrupa tiles EDIFICIO em retângulos (componentes conexos) e divide os longos em volumes de alturas distintas. */
    fun blocks(w: Int, tiles: IntArray): List<Block> {
        val seen = BooleanArray(tiles.size)
        val out = ArrayList<Block>()
        val b = TileType.EDIFICIO.ordinal
        for (z in 0 until w) for (x in 0 until w) {
            val i = z * w + x
            if (seen[i] || tiles[i] != b) continue
            var x1 = x; var z1 = z; var minX = x; var minZ = z
            // componente conexo (flood fill) → caixa envolvente
            val stack = ArrayDeque<Int>(); stack.add(i); seen[i] = true
            while (stack.isNotEmpty()) {
                val k = stack.removeLast(); val kx = k % w; val kz = k / w
                if (kx > x1) x1 = kx; if (kz > z1) z1 = kz; if (kx < minX) minX = kx; if (kz < minZ) minZ = kz
                for ((nx, nz) in arrayOf(kx + 1 to kz, kx - 1 to kz, kx to kz + 1, kx to kz - 1)) {
                    if (nx !in 0 until w || nz !in 0 until w) continue
                    val n = nz * w + nx
                    if (!seen[n] && tiles[n] == b) { seen[n] = true; stack.add(n) }
                }
            }
            val lenX = x1 - minX + 1; val lenZ = z1 - minZ + 1
            if (lenX >= 5 && lenX >= lenZ) {
                val mid = minX + lenX / 2
                out += Block(minX, minZ, mid - 1, z1, floorsFor(minX, minZ)); out += Block(mid, minZ, x1, z1, floorsFor(mid, minZ + 1))
            } else if (lenZ >= 5) {
                val mid = minZ + lenZ / 2
                out += Block(minX, minZ, x1, mid - 1, floorsFor(minX, minZ)); out += Block(minX, mid, x1, z1, floorsFor(minX + 1, mid))
            } else out += Block(minX, minZ, x1, z1, floorsFor(minX, minZ))
        }
        return out
    }

    fun build(w: Int, tiles: IntArray, objects: List<ObjectView>): WorldScene {
        fun t(x: Int, z: Int): TileType? = if (x in 0 until w && z in 0 until w) TileType.entries[tiles[z * w + x]] else null
        fun raised(tt: TileType?) = tt == TileType.CALCADA || tt == TileType.PRACA
        val mats = HashMap<String, LitMeshBuilder>()
        fun m(id: String) = mats.getOrPut(id) { LitMeshBuilder() }
        val lampsByYaw = HashMap<Int, MutableList<V3>>()
        val lamps = ArrayList<V3>()
        val glows = ArrayList<V3>()
        val size = w * TILE_M

        // ---------------- chão
        for (z in 0 until w) for (x in 0 until w) {
            val tt = t(x, z) ?: continue
            val x0 = x * TILE_M; val z0 = z * TILE_M
            when (tt) {
                TileType.RUA -> {
                    val ew = t(x - 1, z) == TileType.RUA || t(x + 1, z) == TileType.RUA
                    val ns = t(x, z - 1) == TileType.RUA || t(x, z + 1) == TileType.RUA
                    if (ew && !ns) m("asphalt").rect(V3(x0, 0f, z0), V3(0f, 0f, TILE_M), V3(TILE_M, 0f, 0f))
                    else m("asphalt").rect(V3(x0, 0f, z0 + TILE_M), V3(TILE_M, 0f, 0f), V3(0f, 0f, -TILE_M))
                }
                TileType.CALCADA, TileType.PRACA ->
                    m("sidewalk").chamferBox(V3(x0, -0.05f, z0), V3(x0 + TILE_M, SIDEWALK_H, z0 + TILE_M), CURB_CHAMFER)
                TileType.GRAMA -> m("grass").rect(V3(x0, 0.01f, z0 + TILE_M), V3(TILE_M, 0f, 0f), V3(0f, 0f, -TILE_M), worldUv = true)
                TileType.CAMPO -> m("field").rect(V3(x0, 0.02f, z0 + TILE_M), V3(TILE_M, 0f, 0f), V3(0f, 0f, -TILE_M), worldUv = true)
                TileType.AGUA -> {
                    m("water").rect(V3(x0, -0.12f, z0 + TILE_M), V3(TILE_M, 0f, 0f), V3(0f, 0f, -TILE_M), worldUv = true)
                    // borda de concreto e guarda-corpo nas faces com piso caminhável
                    for ((dx, dz) in arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                        val nb = t(x + dx, z + dz)
                        if (nb == null || nb == TileType.AGUA || nb == TileType.EDIFICIO) continue
                        railing(m("metal_rail"), m("curb"), x0, z0, dx, dz)
                    }
                }
                TileType.EDIFICIO -> m("concrete").rect(V3(x0, 0.005f, z0 + TILE_M), V3(TILE_M, 0f, 0f), V3(0f, 0f, -TILE_M), worldUv = true)
            }
        }
        // piso distante (horizonte encoberto)
        m("ground_far").rect(V3(-160f, -0.06f, size + 160f), V3(size + 320f, 0f, 0f), V3(0f, 0f, -(size + 320f)), worldUv = true)

        // ---------------- edifícios
        for (bk in blocks(w, tiles)) building(bk, ::m, glows)

        // ---------------- postes (calçada voltada para a rua, a cada 2 tiles) + fiação
        val poleTops = HashMap<Int, MutableList<V3>>() // linha → topos (para a fiação)
        for (z in 0 until w) for (x in 0 until w) {
            if (t(x, z) != TileType.CALCADA) continue
            val dir = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).firstOrNull { (dx, dz) -> t(x + dx, z + dz) == TileType.RUA } ?: continue
            val along = if (dir.first != 0) z else x
            if (along % 2 != 0) continue
            val cx = x * TILE_M + TILE_M / 2 + dir.first * (TILE_M / 2 - 0.35f)
            val cz = z * TILE_M + TILE_M / 2 + dir.second * (TILE_M / 2 - 0.35f)
            // guinada: braço (+X local) aponta para a rua
            val yawIdx = when (dir) { (1 to 0) -> 0; (0 to -1) -> 1; (-1 to 0) -> 2; else -> 3 }
            lampsByYaw.getOrPut(yawIdx) { ArrayList() }.add(V3(cx, SIDEWALK_H, cz))
            val head = V3(cx + dir.first * 1.5f, SIDEWALK_H + LAMP_H - 0.25f, cz + dir.second * 1.5f)
            lamps += V3(head.x, head.y - 0.3f, head.z); glows += head
            val key = if (dir.first != 0) 1_000_000 + x * 4 + (dir.first + 1) else 2_000_000 + z * 4 + (dir.second + 1)
            poleTops.getOrPut(key) { ArrayList() }.add(V3(cx, SIDEWALK_H + LAMP_H + 0.1f, cz))
        }
        for ((_, tops) in poleTops) {
            val sorted = tops.sortedWith(compareBy({ it.x }, { it.z }))
            for (i in 0 until sorted.size - 1) {
                val a = sorted[i]; val b = sorted[i + 1]
                if ((b - a).length() > TILE_M * 2.6f) continue
                catenary(m("wire"), a, b, 0.35f)
                catenary(m("wire"), a + V3(0f, -0.35f, 0f), b + V3(0f, -0.35f, 0f), 0.45f)
            }
        }

        // ---------------- árvores, barreiras de obra, objetos inteligentes
        for (z in 0 until w) for (x in 0 until w) {
            val tt = t(x, z)
            val h = hash(x, z, 31)
            if (tt == TileType.GRAMA && h % 7 == 0) tree(m("trunk"), m("leaf"), x * TILE_M + 1f + (h % 13) / 6.5f, z * TILE_M + 1.2f + (h % 11) / 5.5f, 0f)
            if (tt == TileType.PRACA && h % 9 == 0) tree(m("trunk"), m("leaf"), x * TILE_M + 2f, z * TILE_M + 2f, SIDEWALK_H)
            if (tt == TileType.CALCADA && h % 23 == 0) barrier(m("concrete"), m("barrier_orange"), m("barrier_white"), x * TILE_M + 1.0f, z * TILE_M + 1.4f, h)
        }
        for (o in objects) objectProp(o, ::m, glows)

        val parts = mats.filterValues { !it.isEmpty }.map { (id, b) -> ScenePart(id, b.build(), castShadows = id != "ground_far" && id != "water" && id != "asphalt" && id != "grass" && id != "field") }
            .sortedBy { it.mat }
        // instanciação: poste e luminária compartilham malha; 1 renderable por guinada
        val inst = ArrayList<InstancedPart>()
        val pole = polePostMesh(); val head = poleHeadMesh()
        for ((yi, list) in lampsByYaw.toSortedMap()) {
            val yaw = (yi * PI / 2).toFloat()
            for (chunk in list.chunked(64)) {
                inst += InstancedPart("metal_dark", pole, yaw, chunk)
                inst += InstancedPart("lamp_head", head, yaw, chunk, castShadows = false)
            }
        }
        val floorFn: (Float, Float) -> Float = { sx, sz ->
            val tx = (sx / TILE_M).toInt(); val tz = (sz / TILE_M).toInt()
            if (raised(t(tx, tz))) SIDEWALK_H else 0f
        }
        return WorldScene(WorldPreset.URBAN_OVERCAST, parts, inst, lamps, glows, V3(-160f, -0.1f, -160f), V3(size + 160f, MAX_FLOORS * FLOOR_H + 8f, size + 160f),
            ceilingY = null, mapX = { it }, mapZ = { it }, floorAt = floorFn)
    }

    private fun building(bk: Block, m: (String) -> LitMeshBuilder, glows: MutableList<V3>) {
        val inset = 0.35f
        val x0 = bk.x0 * TILE_M + inset; val z0 = bk.z0 * TILE_M + inset
        val x1 = (bk.x1 + 1) * TILE_M - inset; val z1 = (bk.z1 + 1) * TILE_M - inset
        val hgt = bk.floors * FLOOR_H
        val f = m("facade")
        // fachadas: UV local (u ao longo da parede, v = altura desde o chão) → 1 vão = 2,5 m, 1 pavimento = 3,333 m
        f.rect(V3(x0, 0f, z1), V3(x1 - x0, 0f, 0f), V3(0f, hgt, 0f), u0 = x0)               // +Z
        f.rect(V3(x1, 0f, z0), V3(x0 - x1, 0f, 0f), V3(0f, hgt, 0f), u0 = -x1)              // −Z
        f.rect(V3(x1, 0f, z1), V3(0f, 0f, z0 - z1), V3(0f, hgt, 0f), u0 = -z1)              // +X
        f.rect(V3(x0, 0f, z0), V3(0f, 0f, z1 - z0), V3(0f, hgt, 0f), u0 = z0)               // −X
        val c = m("concrete")
        // embasamento e frisos de laje em relevo (sombra de contato/SSAO marcam cada pavimento)
        c.box(V3(x0 - 0.08f, 0f, z0 - 0.08f), V3(x1 + 0.08f, 0.6f, z1 + 0.08f))
        for (k in 1 until bk.floors) {
            val y = k * FLOOR_H
            c.box(V3(x0 - 0.12f, y - 0.12f, z0 - 0.12f), V3(x1 + 0.12f, y + 0.12f, z1 + 0.12f), top = true, bottom = true)
        }
        // cobertura + platibanda
        c.box(V3(x0, hgt - 0.05f, z0), V3(x1, hgt + 0.02f, z1), top = true, sides = false)
        val pw = 0.25f; val ph = 0.9f
        c.box(V3(x0 - 0.05f, hgt, z0 - 0.05f), V3(x1 + 0.05f, hgt + ph, z0 + pw))
        c.box(V3(x0 - 0.05f, hgt, z1 - pw), V3(x1 + 0.05f, hgt + ph, z1 + 0.05f))
        c.box(V3(x0 - 0.05f, hgt, z0 + pw), V3(x0 + pw, hgt + ph, z1 - pw))
        c.box(V3(x1 - pw, hgt, z0 + pw), V3(x1 + 0.05f, hgt + ph, z1 - pw))
        // casas de máquinas (HVAC)
        val hv = m("hvac")
        val h = hash(bk.x0, bk.z0, 3)
        val cx = (x0 + x1) / 2; val cz = (z0 + z1) / 2
        hv.chamferBox(V3(cx - 1.6f, hgt, cz - 1.1f), V3(cx + 0.4f, hgt + 1.5f, cz + 0.6f), 0.06f)
        if ((x1 - x0) > 9f) hv.chamferBox(V3(cx + 1.8f, hgt, cz - 0.8f), V3(cx + 3.2f, hgt + 1.1f, cz + 0.8f), 0.05f)
        m("metal_dark").cylinder(cx + 1.0f, cz + 1.6f, hgt, hgt + 1.8f, 0.35f, segments = 12)
        // letreiro luminoso em ~40 % dos telhados, voltado para +Z ou +X
        if (h % 5 < 2) {
            val bw = minOf(6f, (x1 - x0) * 0.7f); val bh = 3f; val y0 = hgt + 1.4f
            val faceZ = h % 2 == 0
            val bb = m("billboard"); val fr = m("metal_dark")
            if (faceZ) {
                val bx = cx - bw / 2; val bz = z1 - 1.2f
                bb.rect(V3(bx, y0, bz + 0.12f), V3(bw, 0f, 0f), V3(0f, bh, 0f))
                fr.box(V3(bx - 0.1f, y0 - 0.1f, bz - 0.05f), V3(bx + bw + 0.1f, y0 + bh + 0.1f, bz + 0.1f))
                fr.box(V3(bx + 0.6f, hgt, bz - 0.4f), V3(bx + 0.75f, y0, bz - 0.25f)); fr.box(V3(bx + bw - 0.75f, hgt, bz - 0.4f), V3(bx + bw - 0.6f, y0, bz - 0.25f))
                glows += V3(cx, y0 + bh / 2, bz + 0.5f)
            } else {
                val bz = cz + bw / 2; val bx = x1 - 1.2f
                bb.rect(V3(bx + 0.12f, y0, bz), V3(0f, 0f, -bw), V3(0f, bh, 0f))
                fr.box(V3(bx - 0.05f, y0 - 0.1f, bz - bw - 0.1f), V3(bx + 0.1f, y0 + bh + 0.1f, bz + 0.1f))
                fr.box(V3(bx - 0.4f, hgt, bz - 0.75f), V3(bx - 0.25f, y0, bz - 0.6f)); fr.box(V3(bx - 0.4f, hgt, bz - bw + 0.6f), V3(bx - 0.25f, y0, bz - bw + 0.75f))
                glows += V3(bx + 0.5f, y0 + bh / 2, cz)
            }
        }
    }

    /** Guarda-corpo de aço (montantes a cada 1 m + 2 tubos) sobre mureta de concreto. */
    private fun railing(rail: LitMeshBuilder, curb: LitMeshBuilder, x0: Float, z0: Float, dx: Int, dz: Int) {
        val y = 0.2f
        val (a, b) = when {
            dx == 1 -> V3(x0 + TILE_M - 0.1f, y, z0) to V3(x0 + TILE_M - 0.1f, y, z0 + TILE_M)
            dx == -1 -> V3(x0 + 0.1f, y, z0) to V3(x0 + 0.1f, y, z0 + TILE_M)
            dz == 1 -> V3(x0, y, z0 + TILE_M - 0.1f) to V3(x0 + TILE_M, y, z0 + TILE_M - 0.1f)
            else -> V3(x0, y, z0 + 0.1f) to V3(x0 + TILE_M, y, z0 + 0.1f)
        }
        curb.box(V3(minOf(a.x, b.x) - 0.12f, -0.12f, minOf(a.z, b.z) - 0.12f), V3(maxOf(a.x, b.x) + 0.12f, y, maxOf(a.z, b.z) + 0.12f))
        for (k in 0..4) {
            val p = a + (b - a) * (k / 4f)
            rail.tube(p, p + V3(0f, 1.0f, 0f), 0.025f)
        }
        rail.tube(a + V3(0f, 1.0f, 0f), b + V3(0f, 1.0f, 0f), 0.03f)
        rail.tube(a + V3(0f, 0.5f, 0f), b + V3(0f, 0.5f, 0f), 0.018f)
    }

    /** Fio aéreo entre dois postes, aproximado por 8 segmentos de catenária (parábola). */
    fun catenary(wire: LitMeshBuilder, a: V3, b: V3, sag: Float, segments: Int = 8) {
        var prev = a
        for (i in 1..segments) {
            val s = i / segments.toFloat()
            val p = a + (b - a) * s + V3(0f, -4f * sag * s * (1f - s), 0f)
            wire.tube(prev, p, 0.012f, segments = 4)
            prev = p
        }
    }

    private fun tree(trunk: LitMeshBuilder, leaf: LitMeshBuilder, x: Float, z: Float, y: Float) {
        trunk.cylinder(x, z, y, y + 2.6f, 0.14f, 0.10f, segments = 8)
        leaf.sphere(V3(x, y + 3.4f, z), 1.5f, 1.3f, 1.5f, rings = 6, segments = 10)
        leaf.sphere(V3(x + 0.6f, y + 2.9f, z - 0.4f), 0.9f, 0.8f, 0.9f, rings = 5, segments = 8)
    }

    /** Barreira de obra: mureta New Jersey de concreto chanfrada + cavaletes listrados laranja/branco. */
    private fun barrier(concrete: LitMeshBuilder, orange: LitMeshBuilder, white: LitMeshBuilder, x: Float, z: Float, h: Int) {
        val y = SIDEWALK_H
        concrete.chamferBox(V3(x, y, z), V3(x + 2.0f, y + 0.8f, z + 0.45f), 0.12f)
        val bx = x + 0.2f; val bz = z + 1.3f
        for (k in 0 until 3) {
            val yy = y + 0.45f + k * 0.22f
            (if (k % 2 == 0) orange else white).box(V3(bx, yy, bz), V3(bx + 1.6f, yy + 0.16f, bz + 0.04f), bottom = true)
        }
        white.tube(V3(bx + 0.1f, y, bz - 0.2f), V3(bx + 0.1f, y + 1.15f, bz + 0.02f), 0.025f)
        white.tube(V3(bx + 1.5f, y, bz - 0.2f), V3(bx + 1.5f, y + 1.15f, bz + 0.02f), 0.025f)
        if (h % 2 == 0) orange.cylinder(x + 2.6f, z + 0.3f, y, y + 0.7f, 0.18f, 0.04f, segments = 10)
    }

    private fun objectProp(o: ObjectView, m: (String) -> LitMeshBuilder, glows: MutableList<V3>) {
        val cx = o.x * TILE_M + TILE_M / 2; val cz = o.z * TILE_M + TILE_M / 2
        val y = 0.02f
        when (o.type) {
            SmartObjectType.USINA_SOLAR -> {
                // painéis fotovoltaicos inclinados sobre estrutura metálica
                for (k in -1..1) {
                    val z0 = cz + k * 1.2f
                    m("metal_dark").box(V3(cx - 1.5f, y, z0 - 0.04f), V3(cx - 1.4f, y + 0.9f, z0 + 0.04f))
                    m("metal_dark").box(V3(cx + 1.4f, y, z0 - 0.04f), V3(cx + 1.5f, y + 0.9f, z0 + 0.04f))
                    m("solar").quad(V3(cx - 1.6f, y + 0.6f, z0 + 0.45f), V3(cx + 1.6f, y + 0.6f, z0 + 0.45f),
                        V3(cx + 1.6f, y + 1.2f, z0 - 0.45f), V3(cx - 1.6f, y + 1.2f, z0 - 0.45f))
                }
            }
            SmartObjectType.HORTA, SmartObjectType.COMPOSTEIRA -> {
                m("concrete").chamferBox(V3(cx - 1.4f, y, cz - 0.6f), V3(cx + 1.4f, y + 0.55f, cz + 0.6f), 0.06f)
                m("leaf").box(V3(cx - 1.25f, y + 0.55f, cz - 0.45f), V3(cx + 1.25f, y + 0.75f, cz + 0.45f))
            }
            else -> {
                // quiosque/abrigo com placa luminosa ciano (casa, escola, laboratório, praça, oficina)
                m("concrete").chamferBox(V3(cx - 1.2f, y, cz - 0.3f), V3(cx + 1.2f, y + 0.45f, cz + 0.3f), 0.05f) // banco
                m("metal_dark").box(V3(cx - 1.3f, y, cz + 0.9f), V3(cx - 1.2f, y + 2.6f, cz + 1.0f))
                m("metal_dark").box(V3(cx + 1.2f, y, cz + 0.9f), V3(cx + 1.3f, y + 2.6f, cz + 1.0f))
                m("metal_dark").box(V3(cx - 1.5f, y + 2.6f, cz - 0.6f), V3(cx + 1.5f, y + 2.72f, cz + 1.2f), bottom = true)
                m("sign_cyan").rect(V3(cx - 0.8f, y + 2.0f, cz + 1.01f), V3(1.6f, 0f, 0f), V3(0f, 0.4f, 0f))
                glows += V3(cx, y + 2.2f, cz + 1.2f)
            }
        }
    }

    /** Poste local: coluna em +Y, braço em +X até a luminária (1,5 m), base na origem. */
    fun polePostMesh(): LitMesh {
        val b = LitMeshBuilder()
        b.cylinder(0f, 0f, 0f, 0.5f, 0.16f, 0.13f, segments = 10)
        b.cylinder(0f, 0f, 0.5f, LAMP_H, 0.085f, 0.06f, segments = 10)
        b.tube(V3(0f, LAMP_H - 0.35f, 0f), V3(1.45f, LAMP_H - 0.15f, 0f), 0.04f)
        b.tube(V3(0f, LAMP_H - 0.9f, 0f), V3(0.7f, LAMP_H - 0.33f, 0f), 0.025f)
        return b.build()
    }

    /** Cabeça da luminária (difusor emissivo voltado para baixo). */
    fun poleHeadMesh(): LitMesh {
        val b = LitMeshBuilder()
        b.chamferBox(V3(1.15f, LAMP_H - 0.30f, -0.16f), V3(1.75f, LAMP_H - 0.12f, 0.16f), 0.05f)
        b.rect(V3(1.2f, LAMP_H - 0.305f, -0.12f), V3(0.5f, 0f, 0f), V3(0f, 0f, 0.24f))
        return b.build()
    }

    @Suppress("unused") private fun near(a: Float, b: Float) = abs(a - b) < 1e-4f
}
