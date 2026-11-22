package com.enhikyy.girassol.render3d

import com.enhikyy.girassol.core.world.ObjectView
import com.enhikyy.girassol.core.world.SmartObjectType
import com.enhikyy.girassol.core.world.TileType
import kotlin.math.PI
import kotlin.math.abs

/**
 * v4.1 — URBAN_OVERCAST: converte a grade lógica (tiles de 4 m) em blocos urbanos modulares.
 *  - v4.1: janelas reais recuadas 15–25 cm (requadro, vidro rugosidade 0,08 aceso a 2700–4500 K,
 *    caixilho com montante, peitoril saliente), térreo com marquise/vitrine ou pilotis, antenas,
 *    meio-fio chanfrado com sarjeta, faixas de pedestre gastas, postes de braço curvo com cúpula
 *    emissiva, bancos, floreiras e árvores multivolume;
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
    const val LAMP_ARM_R = 0.7f
    const val LAMP_REACH = 1.55f
    /** Altura do centro da luminária (base da calota) acima da calçada. */
    const val LAMP_HEAD_Y = LAMP_H - 0.9f + LAMP_ARM_R - 0.06f
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

        // ---------------- meio-fio chanfrado (granito claro) + sarjeta, faixas de pedestre gastas e eixo tracejado
        for (z in 0 until w) for (x in 0 until w) {
            val tt = t(x, z) ?: continue
            val x0 = x * TILE_M; val z0 = z * TILE_M
            if (raised(tt)) {
                for ((dx, dz) in arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    if (t(x + dx, z + dz) != TileType.RUA) continue
                    curbAndGutter(m("curb"), m("gutter"), x0, z0, dx, dz)
                }
            }
            if (tt == TileType.RUA) {
                val ew = t(x - 1, z) == TileType.RUA || t(x + 1, z) == TileType.RUA
                val ns = t(x, z - 1) == TileType.RUA || t(x, z + 1) == TileType.RUA
                fun isCross(xx: Int, zz: Int): Boolean {
                    if (t(xx, zz) != TileType.RUA) return false
                    val e = t(xx - 1, zz) == TileType.RUA || t(xx + 1, zz) == TileType.RUA
                    val s = t(xx, zz - 1) == TileType.RUA || t(xx, zz + 1) == TileType.RUA
                    return e && s
                }
                if (ew && !ns) {
                    if (isCross(x - 1, z) || isCross(x + 1, z)) crosswalk(m("paint_worn"), x0, z0, alongX = true, seed = hash(x, z, 61))
                    else if (x % 2 == 0) m("paint_worn").rect(V3(x0 + 1.0f, 0.006f, z0 + TILE_M / 2 + 0.06f), V3(2.0f, 0f, 0f), V3(0f, 0f, -0.12f))
                } else if (ns && !ew) {
                    if (isCross(x, z - 1) || isCross(x, z + 1)) crosswalk(m("paint_worn"), x0, z0, alongX = false, seed = hash(x, z, 61))
                    else if (z % 2 == 0) m("paint_worn").rect(V3(x0 + TILE_M / 2 - 0.06f, 0.006f, z0 + 3.0f), V3(0.12f, 0f, 0f), V3(0f, 0f, -2.0f))
                }
            }
        }

        // ---------------- edifícios
        val ids = WindowIds()
        for (bk in blocks(w, tiles)) building(bk, ::m, glows, ids)

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
            val head = V3(cx + dir.first * LAMP_REACH, SIDEWALK_H + LAMP_HEAD_Y, cz + dir.second * LAMP_REACH)
            lamps += V3(head.x, head.y - 0.35f, head.z); glows += V3(head.x, head.y - 0.12f, head.z)
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

        // ---------------- árvores multivolume, bancos, floreiras, barreiras de obra, objetos inteligentes
        val benchesByYaw = HashMap<Int, MutableList<V3>>()
        for (z in 0 until w) for (x in 0 until w) {
            val tt = t(x, z)
            val h = hash(x, z, 31)
            if (tt == TileType.GRAMA && h % 7 == 0) tree(::m, x * TILE_M + 1f + (h % 13) / 6.5f, z * TILE_M + 1.2f + (h % 11) / 5.5f, 0f, h, grate = false)
            if (tt == TileType.PRACA && h % 9 == 0) tree(::m, x * TILE_M + 2f, z * TILE_M + 2f, SIDEWALK_H, h, grate = true)
            if (tt == TileType.PRACA && h % 9 == 4) planter(::m, x * TILE_M + 1.0f, z * TILE_M + 1.0f, h)
            if (tt == TileType.CALCADA && h % 23 == 0) barrier(m("concrete"), m("barrier_orange"), m("barrier_white"), x * TILE_M + 1.0f, z * TILE_M + 1.4f, h)
            if (tt == TileType.CALCADA || tt == TileType.PRACA) {
                val dir = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).firstOrNull { (dx, dz) -> t(x + dx, z + dz) == TileType.RUA }
                if (dir != null) {
                    val along = if (dir.first != 0) z else x
                    // bancos nos tiles ímpares (postes ficam nos pares), voltados para a rua
                    if (along % 2 == 1 && h % 3 != 0) {
                        val yawIdx = when (dir) { (1 to 0) -> 0; (0 to -1) -> 1; (-1 to 0) -> 2; else -> 3 }
                        val bx = x * TILE_M + TILE_M / 2 + dir.first * 0.2f
                        val bz = z * TILE_M + TILE_M / 2 + dir.second * 0.2f
                        benchesByYaw.getOrPut(yawIdx) { ArrayList() }.add(V3(bx, SIDEWALK_H, bz))
                    } else if (along % 2 == 1 && h % 23 != 0) {
                        planter(::m, x * TILE_M + TILE_M / 2 - 0.6f, z * TILE_M + TILE_M / 2 - 0.6f, h)
                    }
                }
            }
        }
        for (o in objects) objectProp(o, ::m, glows)

        val parts = mats.filterValues { !it.isEmpty }.map { (id, b) -> ScenePart(id, b.build(), castShadows = id != "ground_far" && id != "water" && id != "asphalt" && id != "grass" && id != "field") }
            .sortedBy { it.mat }
        // instanciação: poste e luminária compartilham malha; 1 renderable por guinada
        val inst = ArrayList<InstancedPart>()
        val pole = polePostMesh(); val shell = poleShellMesh(); val head = poleHeadMesh()
        for ((yi, list) in lampsByYaw.toSortedMap()) {
            val yaw = (yi * PI / 2).toFloat()
            for (chunk in list.chunked(64)) {
                inst += InstancedPart("metal_dark", pole, yaw, chunk)
                inst += InstancedPart("metal_dark", shell, yaw, chunk)
                inst += InstancedPart("lamp_head", head, yaw, chunk, castShadows = false)
            }
        }
        val benchWood = benchWoodMesh(); val benchMetal = benchMetalMesh()
        for ((yi, list) in benchesByYaw.toSortedMap()) {
            val yaw = (yi * PI / 2).toFloat()
            for (chunk in list.chunked(64)) {
                inst += InstancedPart("wood", benchWood, yaw, chunk)
                inst += InstancedPart("metal_dark", benchMetal, yaw, chunk)
            }
        }
        val floorFn: (Float, Float) -> Float = { sx, sz ->
            val tx = (sx / TILE_M).toInt(); val tz = (sz / TILE_M).toInt()
            if (raised(t(tx, tz))) SIDEWALK_H else 0f
        }
        return WorldScene(WorldPreset.URBAN_OVERCAST, parts, inst, lamps, glows, V3(-160f, -0.1f, -160f), V3(size + 160f, MAX_FLOORS * FLOOR_H + 8f, size + 160f),
            ceilingY = null, mapX = { it }, mapZ = { it }, floorAt = floorFn)
    }

    /** Estilo do térreo: loja com marquise e vitrine, ou pilotis recuados com saguão envidraçado. */
    enum class GroundStyle { SHOPFRONT, PILOTIS }

    fun groundStyleFor(bk: Block): GroundStyle = if (hash(bk.x0, bk.z0, 41) % 3 == 0) GroundStyle.PILOTIS else GroundStyle.SHOPFRONT

    /** Profundidade do recuo das janelas (0,15–0,25 m), determinística por edifício. */
    fun revealDepthFor(bk: Block): Float = 0.15f + (hash(bk.x0, bk.z0, 53) % 1000) / 1000f * 0.10f

    /** Contador de janelas (id único codificado em u do vidro). */
    private class WindowIds { var next = 0 }

    /**
     * Uma fachada plana de [a] a [b] (no chão, sentido anti-horário visto de fora) com [floors]
     * pavimentos: vãos de ~2,5 m, janelas recuadas com requadro (4 faces), vidro, caixilho com
     * montante central e peitoril saliente; painéis de peitoril teal quando [teal].
     * O térreo é tratado à parte (vitrine ou pilotis), então aqui começa em [firstFloor].
     */
    private fun facade(m: (String) -> LitMeshBuilder, a: V3, b: V3, floors: Int, firstFloor: Int, depth: Float, teal: Boolean,
                       ids: WindowIds, uBase: Float) {
        val len = (b - a).length()
        if (len < 1f) return
        val ux = (b - a) * (1f / len)
        val up = V3(0f, 1f, 0f)
        val n = ux.cross(up) // para fora (a→b anti-horário visto de cima com +Y)
        val nb = maxOf(1, (len / 2.5f).toInt())
        val bay = len / nb
        val ww = minOf(1.55f, bay * 0.62f)
        val sill = 0.90f; val wh = 1.65f
        val wall = m("facade"); val spand = m(if (teal) "spandrel" else "facade")
        val rev = m("reveal"); val glass = m("window_glass"); val frame = m("frame"); val sillM = m("sill")
        for (k in firstFloor until floors) {
            val y0 = k * FLOOR_H
            val ys = y0 + sill; val yh = ys + wh; val y1 = y0 + FLOOR_H
            // faixa de peitoril (abaixo das janelas) e faixa de verga (acima)
            spand.rect(a + up * y0, ux * len, up * sill, u0 = uBase, v0 = y0)
            wall.rect(a + up * yh, ux * len, up * (y1 - yh), u0 = uBase, v0 = yh)
            for (i in 0 until nb) {
                val c0 = i * bay; val wl = c0 + (bay - ww) / 2; val wr = wl + ww
                // montantes de parede entre janelas
                wall.rect(a + ux * c0 + up * ys, ux * (wl - c0), up * wh, u0 = uBase + c0, v0 = ys)
                wall.rect(a + ux * wr + up * ys, ux * (c0 + bay - wr), up * wh, u0 = uBase + wr, v0 = ys)
                val pL = a + ux * wl; val pR = a + ux * wr
                val inward = n * (-depth)
                // requadro: ombreiras, testeira (voltada para baixo) e fundo do peitoril (para cima)
                rev.rect(pL + up * ys, inward, up * wh)                                       // ombreira esquerda (normal +ux)
                rev.rect(pR + up * ys + inward, n * depth, up * wh)                         // ombreira direita (normal −ux)
                rev.rect(pL + up * yh + inward, ux * ww, n * depth)                           // testeira (normal −Y)
                rev.rect(pL + up * ys, ux * ww, inward)                                       // base (normal +Y)
                // vidro no fundo do requadro, id da janela em u
                val id = ids.next++
                glass.rectUv(pL + up * ys + inward, ux * ww, up * wh, id.toFloat(), id + 0.999f, 0f, 1f)
                // caixilho: anel de 5 cm + montante central, 1 cm à frente do vidro
                val fz = n * (-depth + 0.012f); val fw = 0.05f
                frame.rect(pL + up * ys + fz, ux * ww, up * fw)
                frame.rect(pL + up * (yh - fw) + fz, ux * ww, up * fw)
                frame.rect(pL + up * (ys + fw) + fz, ux * fw, up * (wh - 2 * fw))
                frame.rect(pR - ux * fw + up * (ys + fw) + fz, ux * fw, up * (wh - 2 * fw))
                frame.rect(pL + ux * (ww / 2 - 0.02f) + up * (ys + fw) + fz, ux * 0.04f, up * (wh - 2 * fw))
                frame.rect(pL + up * (ys + wh * 0.68f) + fz, ux * ww, up * 0.035f)       // travessa da bandeira
                // peitoril saliente (pedra), 6 cm para fora da parede
                val s0 = pL - ux * 0.06f + inward * 0.5f + up * (ys - 0.05f)
                sillBox(sillM, s0, ux * (ww + 0.12f), n * (depth * 0.5f + 0.06f), up * 0.05f)
            }
        }
    }

    /** Caixa orientada (origem + 3 arestas ortogonais) sem a face de trás — peitoris e marquises. */
    private fun sillBox(b: LitMeshBuilder, o: V3, ex: V3, ez: V3, ey: V3) {
        b.rect(o + ez, ex, ey)                      // frente (normal +ez)
        b.rect(o + ey + ez, ex, ez * -1f)           // topo (normal +Y)
        b.rect(o, ex, ez)                           // fundo (normal −Y)
        b.rect(o, ez, ey)                           // ponta esquerda (normal −ex)
        b.rect(o + ex + ez, ez * -1f, ey)           // ponta direita (normal +ex)
    }

    private fun building(bk: Block, m: (String) -> LitMeshBuilder, glows: MutableList<V3>, ids: WindowIds) {
        val inset = 0.35f
        val x0 = bk.x0 * TILE_M + inset; val z0 = bk.z0 * TILE_M + inset
        val x1 = (bk.x1 + 1) * TILE_M - inset; val z1 = (bk.z1 + 1) * TILE_M - inset
        val hgt = bk.floors * FLOOR_H
        val depth = revealDepthFor(bk)
        val teal = hash(bk.x0, bk.z0, 17) % 2 == 0
        val style = groundStyleFor(bk)
        // 4 fachadas (anti-horário visto de cima): +Z, +X, −Z, −X
        val corners = listOf(V3(x0, 0f, z1), V3(x1, 0f, z1), V3(x1, 0f, z0), V3(x0, 0f, z0))
        for (f in 0 until 4) {
            val a = corners[f]; val b = corners[(f + 1) % 4]
            facade(m, a, b, bk.floors, 1, depth, teal, ids, if (f % 2 == 0) a.x else a.z)
            groundFloor(m, a, b, style, ids, glows)
        }
        val c = m("concrete")
        // frisos de laje em relevo (sombra de contato/SSAO marcam cada pavimento)
        for (k in 1 until bk.floors) {
            val y = k * FLOOR_H
            c.box(V3(x0 - 0.12f, y - 0.12f, z0 - 0.12f), V3(x1 + 0.12f, y + 0.10f, z1 + 0.12f), top = true, bottom = true)
        }
        // cobertura + platibanda com rufo metálico
        c.box(V3(x0, hgt - 0.05f, z0), V3(x1, hgt + 0.02f, z1), top = true, sides = false)
        val pw = 0.25f; val ph = 0.9f
        c.box(V3(x0 - 0.05f, hgt, z0 - 0.05f), V3(x1 + 0.05f, hgt + ph, z0 + pw))
        c.box(V3(x0 - 0.05f, hgt, z1 - pw), V3(x1 + 0.05f, hgt + ph, z1 + 0.05f))
        c.box(V3(x0 - 0.05f, hgt, z0 + pw), V3(x0 + pw, hgt + ph, z1 - pw))
        c.box(V3(x1 - pw, hgt, z0 + pw), V3(x1 + 0.05f, hgt + ph, z1 - pw))
        val cap = m("metal_rail")
        cap.box(V3(x0 - 0.08f, hgt + ph, z0 - 0.08f), V3(x1 + 0.08f, hgt + ph + 0.04f, z0 + pw + 0.03f))
        cap.box(V3(x0 - 0.08f, hgt + ph, z1 - pw - 0.03f), V3(x1 + 0.08f, hgt + ph + 0.04f, z1 + 0.08f))
        cap.box(V3(x0 - 0.08f, hgt + ph, z0 + pw), V3(x0 + pw + 0.03f, hgt + ph + 0.04f, z1 - pw))
        cap.box(V3(x1 - pw - 0.03f, hgt + ph, z0 + pw), V3(x1 + 0.08f, hgt + ph + 0.04f, z1 - pw))
        // casas de máquinas (HVAC) com grelhas e exaustor
        val hv = m("hvac")
        val h = hash(bk.x0, bk.z0, 3)
        val cx = (x0 + x1) / 2; val cz = (z0 + z1) / 2
        hv.chamferBox(V3(cx - 1.6f, hgt, cz - 1.1f), V3(cx + 0.4f, hgt + 1.5f, cz + 0.6f), 0.06f)
        for (g in 0 until 5) m("metal_dark").box(V3(cx - 1.5f + g * 0.36f, hgt + 0.35f, cz + 0.6f), V3(cx - 1.32f + g * 0.36f, hgt + 1.2f, cz + 0.63f))
        if ((x1 - x0) > 9f) hv.chamferBox(V3(cx + 1.8f, hgt, cz - 0.8f), V3(cx + 3.2f, hgt + 1.1f, cz + 0.8f), 0.05f)
        m("metal_dark").cylinder(cx + 1.0f, cz + 1.6f, hgt, hgt + 1.8f, 0.35f, segments = 12)
        m("metal_dark").cylinder(cx + 1.0f, cz + 1.6f, hgt + 1.8f, hgt + 1.95f, 0.45f, 0.30f, segments = 12)
        // antenas: mastro treliçado + parabólica (≈ 60 % dos telhados)
        if (h % 5 >= 2) antenna(m("metal_rail"), m("hvac"), x0 + 1.2f + (h % 7) * 0.2f, z0 + 1.2f, hgt, 2.4f + (h % 4) * 0.5f)
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

    /**
     * Térreo de uma fachada: [GroundStyle.SHOPFRONT] = vitrines altas entre pilares, soleira e
     * marquise em balanço de 1,2 m com faixa de luz; [GroundStyle.PILOTIS] = saguão envidraçado
     * recuado 1,6 m sob o volume, pilotis cilíndricos a cada vão e forro (laje inferior).
     */
    private fun groundFloor(m: (String) -> LitMeshBuilder, a: V3, b: V3, style: GroundStyle, ids: WindowIds, glows: MutableList<V3>) {
        val len = (b - a).length()
        if (len < 1f) return
        val ux = (b - a) * (1f / len); val up = V3(0f, 1f, 0f); val n = ux.cross(up)
        val nb = maxOf(1, (len / 2.5f).toInt()); val bay = len / nb
        val glass = m("window_glass"); val frame = m("frame"); val wall = m("facade")
        when (style) {
            GroundStyle.SHOPFRONT -> {
                val gy0 = 0.35f; val gy1 = 2.85f
                wall.rect(a, ux * len, up * gy0)                                             // embasamento
                wall.rect(a + up * gy1, ux * len, up * (FLOOR_H - gy1))                      // faixa acima da vitrine
                for (i in 0 until nb) {
                    val c0 = i * bay
                    val pier = 0.28f
                    wall.rect(a + ux * c0 + up * gy0, ux * pier, up * (gy1 - gy0))
                    val pl = a + ux * (c0 + pier); val gw = bay - pier
                    val inward = n * -0.12f
                    m("reveal").rect(pl + up * gy0, inward, up * (gy1 - gy0))                       // ombreira esquerda
                    m("reveal").rect(pl + ux * gw + up * gy0 + inward, n * 0.12f, up * (gy1 - gy0)) // ombreira direita
                    m("reveal").rect(pl + up * gy1 + inward, ux * gw, n * 0.12f)                     // testeira
                    m("reveal").rect(pl + up * gy0, ux * gw, inward)                                 // soleira
                    val id = ids.next++
                    glass.rectUv(pl + up * gy0 + inward, ux * gw, up * (gy1 - gy0), id.toFloat(), id + 0.999f, 0f, 1f)
                    val fz = n * -0.105f
                    frame.rect(pl + up * gy0 + fz, ux * gw, up * 0.06f)
                    frame.rect(pl + up * (gy1 - 0.06f) + fz, ux * gw, up * 0.06f)
                    frame.rect(pl + up * 2.2f + fz, ux * gw, up * 0.04f)
                }
                // marquise em balanço: laje fina + rufo + faixa emissiva por baixo
                val my = 3.0f; val md = 1.2f
                val mq = m("marquee")
                mq.rect(a + up * (my + 0.12f) + n * md, ux * len, n * -md)                   // topo (normal +Y)
                mq.rect(a + n * md + up * my, ux * len, up * 0.12f)                          // testa (normal +n)
                mq.rect(a + up * my, ux * len, n * md)                                       // forro (normal −Y)
                m("lamp_head").rect(a + ux * 0.4f + up * (my - 0.005f) + n * (md - 0.33f), ux * (len - 0.8f), n * 0.08f)
                glows += a + ux * (len / 2) + up * (my - 0.1f) + n * md
            }
            GroundStyle.PILOTIS -> {
                val rec = 1.6f
                val ar = a - n * rec
                val lobbyH = FLOOR_H - 0.35f
                // saguão envidraçado recuado (entre os recuos das fachadas vizinhas)
                val span = (len - 2 * rec).coerceAtLeast(0.5f)
                val panels = maxOf(1, (span / 2.5f).toInt()); val pw = span / panels
                val g0 = ar + ux * rec
                for (i in 0 until panels) {
                    val id = ids.next++
                    glass.rectUv(g0 + ux * (i * pw) + up * 0.05f, ux * pw, up * (lobbyH - 0.05f), id.toFloat(), id + 0.999f, 0f, 1f)
                    frame.rect(g0 + ux * (i * pw) + n * 0.01f, ux * 0.06f, up * lobbyH)
                }
                frame.rect(g0 + n * 0.01f, ux * span, up * 0.08f)
                // o vidro fecha exatamente o retângulo interno recuado; forro sob o balanço (normal −Y) e viga de borda
                m("concrete").rect(ar + up * lobbyH, ux * len, n * rec)
                m("concrete").rect(a + up * lobbyH, ux * len, up * (FLOOR_H - lobbyH))
                // pilotis na linha da fachada
                val col = m("concrete")
                for (i in 0..nb) {
                    val p = a + ux * (i * bay).coerceIn(0.3f, len - 0.3f) - n * 0.3f
                    col.cylinder(p.x, p.z, 0f, lobbyH, 0.24f, 0.22f, segments = 14, caps = false)
                }
            }
        }
    }

    /** Antena de telhado: mastro, travessas e parabólica. */
    private fun antenna(metal: LitMeshBuilder, dish: LitMeshBuilder, x: Float, z: Float, y: Float, h: Float) {
        metal.cylinder(x, z, y, y + 0.25f, 0.12f, 0.12f, segments = 8)
        metal.tube(V3(x, y + 0.25f, z), V3(x, y + h, z), 0.03f)
        for (k in 1..3) {
            val yy = y + h * (0.45f + 0.15f * k)
            val w = 0.55f - 0.12f * k
            metal.tube(V3(x - w, yy, z), V3(x + w, yy, z), 0.012f, segments = 4)
        }
        metal.tube(V3(x, y + h * 0.9f, z), V3(x + 0.9f, y, z + 0.6f), 0.006f, segments = 3) // estai
        metal.tube(V3(x, y + h * 0.9f, z), V3(x - 0.8f, y, z - 0.7f), 0.006f, segments = 3)
        dish.dome(V3(x + 0.55f, y + 0.9f, z + 0.9f), 0.42f, 0.16f, 0.42f, upper = false, rings = 4, segments = 12)
        metal.tube(V3(x + 0.55f, y, z + 0.9f), V3(x + 0.55f, y + 0.9f, z + 0.9f), 0.025f)
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

    /**
     * Árvore multivolume: tronco cônico com raízes, 3 galhos e 5–7 volumes de copa (dois tons),
     * determinística por [h]; grelha metálica de proteção quando plantada em praça.
     */
    private fun tree(m: (String) -> LitMeshBuilder, x: Float, z: Float, y: Float, h: Int, grate: Boolean) {
        val trunk = m("trunk")
        val s = 0.85f + (h % 7) * 0.06f
        trunk.cylinder(x, z, y, y + 2.4f * s, 0.17f * s, 0.10f * s, segments = 10, caps = false)
        for (k in 0 until 3) {
            val ang = (h % 360) * 0.0174f + k * 2.094f
            val by = y + (1.7f + 0.3f * k) * s
            trunk.tube(V3(x, by, z), V3(x + kotlin.math.cos(ang) * 0.9f * s, by + 0.9f * s, z + kotlin.math.sin(ang) * 0.9f * s), 0.05f * s, segments = 6)
        }
        val clumps = 5 + h % 3
        for (k in 0 until clumps) {
            val ang = k * 6.2832f / clumps + (h % 17) * 0.1f
            val r = if (k == 0) 0f else 0.85f * s
            val cy = y + (3.1f + 0.5f * ((h shr k) and 1)) * s + if (k == 0) 0.5f * s else 0f
            val rad = (if (k == 0) 1.35f else 0.85f + 0.12f * ((h shr (k + 2)) and 3)) * s
            m(if (k % 2 == 0) "leaf" else "leaf_light").sphere(V3(x + kotlin.math.cos(ang) * r, cy, z + kotlin.math.sin(ang) * r), rad, rad * 0.82f, rad, rings = 6, segments = 10)
        }
        if (grate) {
            val g = m("metal_dark")
            g.box(V3(x - 0.75f, y, z - 0.75f), V3(x + 0.75f, y + 0.02f, z - 0.65f)); g.box(V3(x - 0.75f, y, z + 0.65f), V3(x + 0.75f, y + 0.02f, z + 0.75f))
            g.box(V3(x - 0.75f, y, z - 0.65f), V3(x - 0.65f, y + 0.02f, z + 0.65f)); g.box(V3(x + 0.65f, y, z - 0.65f), V3(x + 0.75f, y + 0.02f, z + 0.65f))
            for (k in -2..2) g.box(V3(x - 0.65f, y, z + k * 0.24f - 0.015f), V3(x + 0.65f, y + 0.015f, z + k * 0.24f + 0.015f))
        }
    }

    /** Floreira de concreto chanfrada com terra e arbustos. */
    private fun planter(m: (String) -> LitMeshBuilder, x: Float, z: Float, h: Int) {
        val y = SIDEWALK_H
        m("planter").chamferBox(V3(x, y, z), V3(x + 1.2f, y + 0.55f, z + 1.2f), 0.04f)
        m("soil").rect(V3(x + 0.08f, y + 0.50f, z + 1.12f), V3(1.04f, 0f, 0f), V3(0f, 0f, -1.04f))
        for (k in 0 until 3) {
            val ox = x + 0.3f + 0.3f * k; val oz = z + 0.35f + 0.25f * ((h shr k) and 1)
            m(if (k == 1) "leaf_light" else "leaf").sphere(V3(ox, y + 0.78f, oz), 0.32f, 0.28f, 0.32f, rings = 5, segments = 8)
        }
    }

    /** Meio-fio de granito (chanfro 3 cm) na borda da calçada voltada para a rua + sarjeta escura no asfalto. */
    private fun curbAndGutter(curb: LitMeshBuilder, gutter: LitMeshBuilder, x0: Float, z0: Float, dx: Int, dz: Int) {
        val cw = 0.18f
        val (mn, mx) = when {
            dx == 1 -> V3(x0 + TILE_M - cw, -0.05f, z0) to V3(x0 + TILE_M, SIDEWALK_H + 0.008f, z0 + TILE_M)
            dx == -1 -> V3(x0, -0.05f, z0) to V3(x0 + cw, SIDEWALK_H + 0.008f, z0 + TILE_M)
            dz == 1 -> V3(x0, -0.05f, z0 + TILE_M - cw) to V3(x0 + TILE_M, SIDEWALK_H + 0.008f, z0 + TILE_M)
            else -> V3(x0, -0.05f, z0) to V3(x0 + TILE_M, SIDEWALK_H + 0.008f, z0 + cw)
        }
        curb.chamferBox(mn, mx, 0.03f)
        val gw = 0.35f; val gy = 0.004f
        when {
            dx == 1 -> gutter.rect(V3(x0 + TILE_M, gy, z0 + TILE_M), V3(gw, 0f, 0f), V3(0f, 0f, -TILE_M))
            dx == -1 -> gutter.rect(V3(x0 - gw, gy, z0 + TILE_M), V3(gw, 0f, 0f), V3(0f, 0f, -TILE_M))
            dz == 1 -> gutter.rect(V3(x0, gy, z0 + TILE_M + gw), V3(TILE_M, 0f, 0f), V3(0f, 0f, -gw))
            else -> gutter.rect(V3(x0, gy, z0), V3(TILE_M, 0f, 0f), V3(0f, 0f, -gw))
        }
    }

    /**
     * Faixa de pedestres gasta: 6 barras de 0,45 m paralelas ao tráfego, cada uma quebrada em
     * segmentos com falhas e larguras levemente diferentes (desgaste da pintura por pneus).
     */
    private fun crosswalk(paint: LitMeshBuilder, x0: Float, z0: Float, alongX: Boolean, seed: Int) {
        val y = 0.006f
        val barW = 0.45f; val gap = 0.20f; val barL = 2.6f
        for (k in 0 until 6) {
            val off = 0.2f + k * (barW + gap)
            var s = 0f
            var seg = 0
            while (s < barL) {
                val hs = hash(seed, k, seg)
                val l = (0.45f + (hs % 100) / 100f * 0.7f).coerceAtMost(barL - s)
                val wv = barW * (0.86f + (hs % 13) / 100f)
                if (hs % 5 != 0) {
                    if (alongX) paint.rect(V3(x0 + 0.7f + s, y, z0 + off + wv), V3(l, 0f, 0f), V3(0f, 0f, -wv))
                    else paint.rect(V3(x0 + off, y, z0 + 0.7f + s + l), V3(wv, 0f, 0f), V3(0f, 0f, -l))
                }
                s += l + 0.04f + (hs % 7) / 100f
                seg++
            }
        }
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

    /** Pontos do braço curvo do poste (local: coluna em +Y, braço em +X): arco de 90° + trecho reto. */
    fun lampArmPoints(): List<V3> {
        val pts = ArrayList<V3>()
        val r = LAMP_ARM_R; val y0 = LAMP_H - 0.9f
        for (i in 0..8) {
            val th = Math.PI - (Math.PI / 2) * i / 8.0
            pts += V3((r + r * kotlin.math.cos(th)).toFloat(), (y0 + r * kotlin.math.sin(th)).toFloat(), 0f)
        }
        pts += V3(LAMP_REACH - 0.05f, y0 + r, 0f)
        return pts
    }

    /** Poste local: base troncocônica, coluna afunilada, anel decorativo e braço curvo (tubo). */
    fun polePostMesh(): LitMesh {
        val b = LitMeshBuilder()
        b.cylinder(0f, 0f, 0f, 0.12f, 0.20f, 0.20f, segments = 14)
        b.cylinder(0f, 0f, 0.12f, 0.55f, 0.16f, 0.11f, segments = 14)
        b.cylinder(0f, 0f, 0.55f, LAMP_H - 0.9f, 0.085f, 0.062f, segments = 12, caps = false)
        b.cylinder(0f, 0f, 2.6f, 2.7f, 0.10f, 0.10f, segments = 12)
        b.polyTube(listOf(V3(0f, LAMP_H - 0.95f, 0f)) + lampArmPoints(), 0.045f, segments = 8)
        return b.build()
    }

    /** Carcaça da luminária: calota metálica superior sobre o difusor. */
    fun poleShellMesh(): LitMesh {
        val b = LitMeshBuilder()
        b.dome(V3(LAMP_REACH, LAMP_HEAD_Y, 0f), 0.34f, 0.20f, 0.34f, upper = true, rings = 5, segments = 16)
        b.cylinder(LAMP_REACH, 0f, LAMP_HEAD_Y - 0.03f, LAMP_HEAD_Y, 0.35f, 0.35f, segments = 16, caps = false)
        return b.build()
    }

    /** Difusor emissivo em cúpula voltada para baixo (meia-esfera achatada). */
    fun poleHeadMesh(): LitMesh {
        val b = LitMeshBuilder()
        b.dome(V3(LAMP_REACH, LAMP_HEAD_Y - 0.03f, 0f), 0.30f, 0.17f, 0.30f, upper = false, rings = 5, segments = 16)
        return b.build()
    }

    /** Banco de praça (local: assento ao longo de Z, voltado para +X): ripas de madeira. */
    fun benchWoodMesh(): LitMesh {
        val b = LitMeshBuilder()
        val l = 1.8f
        for (k in 0 until 4) {
            val x = -0.22f + k * 0.12f
            b.chamferBox(V3(x, 0.44f, -l / 2), V3(x + 0.10f, 0.475f, l / 2), 0.008f)
        }
        for (k in 0 until 3) {
            val y = 0.58f + k * 0.13f; val x = -0.30f - k * 0.035f
            b.chamferBox(V3(x - 0.035f, y, -l / 2), V3(x, y + 0.10f, l / 2), 0.008f)
        }
        return b.build()
    }

    /** Estrutura de ferro fundido do banco: 2 pés laterais com apoio de braço e encosto. */
    fun benchMetalMesh(): LitMesh {
        val b = LitMeshBuilder()
        for (zz in floatArrayOf(-0.72f, 0.72f)) {
            b.box(V3(-0.27f, 0f, zz - 0.03f), V3(-0.22f, 0.44f, zz + 0.03f))
            b.box(V3(0.18f, 0f, zz - 0.03f), V3(0.23f, 0.44f, zz + 0.03f))
            b.box(V3(-0.28f, 0.40f, zz - 0.03f), V3(0.24f, 0.44f, zz + 0.03f))
            b.tube(V3(-0.27f, 0.44f, zz), V3(-0.38f, 0.95f, zz), 0.022f)
            b.tube(V3(0.20f, 0.44f, zz), V3(0.20f, 0.64f, zz), 0.02f)
            b.tube(V3(0.20f, 0.64f, zz), V3(-0.30f, 0.66f, zz), 0.02f)
        }
        return b.build()
    }

    @Suppress("unused") private fun near(a: Float, b: Float) = abs(a - b) < 1e-4f
}
