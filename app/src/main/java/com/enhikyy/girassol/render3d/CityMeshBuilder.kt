package com.enhikyy.girassol.render3d

import com.enhikyy.girassol.core.render.BackroomsCorridors
import com.enhikyy.girassol.core.render.CitizenRig
import com.enhikyy.girassol.core.world.ObjectView
import com.enhikyy.girassol.core.world.SmartObjectType
import com.enhikyy.girassol.core.world.TileType
import kotlin.math.max
import kotlin.math.pow

/** Malha CPU (posições xyz, cores rgba lineares com a = emissivo, índices). */
class CityMesh(val positions: FloatArray, val colors: FloatArray, val indices: IntArray) {
    val vertexCount get() = positions.size / 3
    fun bounds(): Pair<V3, V3> {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until vertexCount) {
            val x = positions[i * 3]; val y = positions[i * 3 + 1]; val z = positions[i * 3 + 2]
            if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
            if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
        }
        if (vertexCount == 0) return V3(0f, 0f, 0f) to V3(1f, 1f, 1f)
        return V3(minX, minY, minZ) to V3(maxX, maxY, maxZ)
    }

    /** Concatena duas malhas (índices de [o] deslocados). */
    fun merge(o: CityMesh): CityMesh {
        if (o.vertexCount == 0) return this
        val base = vertexCount
        return CityMesh(positions + o.positions, colors + o.colors, indices + IntArray(o.indices.size) { o.indices[it] + base })
    }
}

/** Sprites de brilho (halation): 4 vértices por sprite com o mesmo centro; uv = canto; cor.a = tamanho. */
class GlowSprites(val positions: FloatArray, val uvs: FloatArray, val colors: FloatArray, val indices: IntArray) {
    val count get() = positions.size / 12
}

/**
 * Gera a cidade brutalista/liminar a partir dos tiles. Iluminação lambertiana e oclusão de
 * ambiente são "assadas" na cor do vértice (material unlit), o que mantém o custo de GPU
 * baixo em SoCs de entrada. Totalmente determinístico: mesma grade ⇒ mesma malha.
 */
object CityMeshBuilder {
    private val LIGHT = V3(-0.45f, 1f, -0.3f).normalized()

    private class Buf {
        val p = ArrayList<Float>(); val c = ArrayList<Float>(); val i = ArrayList<Int>()
        fun vtx(x: Float, y: Float, z: Float, col: FloatArray) {
            p.add(x); p.add(y); p.add(z); c.add(col[0]); c.add(col[1]); c.add(col[2]); c.add(col[3])
        }
        /** Emite o quad com enrolamento anti-horário visto de fora (normal ~ [outward]). */
        fun quad(a: V3, b: V3, cc: V3, d: V3, col: FloatArray, bottomShade: Float = 1f, bottomIdx: IntArray = intArrayOf(), outward: V3? = null) {
            val base = p.size / 3
            val corners = arrayOf(a, b, cc, d)
            for ((k, v) in corners.withIndex()) {
                val s = if (k in bottomIdx) bottomShade else 1f
                vtx(v.x, v.y, v.z, floatArrayOf(col[0] * s, col[1] * s, col[2] * s, col[3]))
            }
            val n = (b - a).cross(cc - a)
            val want = outward ?: V3(0f, 1f, 0f)
            if (n.dot(want) >= 0f) {
                i.add(base); i.add(base + 1); i.add(base + 2); i.add(base); i.add(base + 2); i.add(base + 3)
            } else {
                i.add(base); i.add(base + 2); i.add(base + 1); i.add(base); i.add(base + 3); i.add(base + 2)
            }
        }
        fun mesh() = CityMesh(p.toFloatArray(), c.toFloatArray(), i.toIntArray())
    }

    fun srgbToLinear(c: Float) = c.coerceIn(0f, 1f).toDouble().pow(2.2).toFloat()
    private fun lin(r: Float, g: Float, b: Float, e: Float = 0f) = floatArrayOf(srgbToLinear(r), srgbToLinear(g), srgbToLinear(b), e)

    fun hash(x: Int, z: Int, salt: Int = 0): Int {
        var h = (x * 73856093) xor (z * 19349663) xor (salt * 83492791)
        h = h xor (h ushr 13); h *= 0x5bd1e995; h = h xor (h ushr 15)
        return h and 0x7fffffff
    }

    fun tileColor(t: TileType, x: Int, z: Int): FloatArray {
        val j = (hash(x, z, 1) % 7) * 0.006f
        return when (t) {
            TileType.GRAMA -> lin(0.50f + j, 0.47f + j, 0.27f)       // carpete mostarda (Backrooms)
            TileType.RUA -> lin(0.13f, 0.14f + j, 0.15f)
            TileType.CALCADA -> lin(0.50f + j, 0.50f + j, 0.48f)
            TileType.EDIFICIO -> lin(0.40f, 0.40f, 0.38f)
            TileType.CAMPO -> lin(0.34f, 0.40f + j, 0.21f)
            TileType.PRACA -> lin(0.60f + j, 0.58f, 0.53f)
            TileType.AGUA -> lin(0.04f, 0.16f, 0.18f + j)
        }
    }

    fun buildingHeight(x: Int, z: Int): Float = 1.6f + (hash(x / 3, z / 3, 7) % 6) * 0.85f

    private fun shade(n: V3) = 0.38f + 0.62f * max(0f, n.dot(LIGHT))

    private fun box(b: Buf, x0: Float, z0: Float, x1: Float, z1: Float, y0: Float, y1: Float, base: FloatArray, ao: Float = 0.62f) {
        fun col(n: V3) = floatArrayOf(base[0] * shade(n), base[1] * shade(n), base[2] * shade(n), base[3])
        // topo
        b.quad(V3(x0, y1, z0), V3(x0, y1, z1), V3(x1, y1, z1), V3(x1, y1, z0), col(V3(0f, 1f, 0f)))
        // faces laterais (índices 0 e 3 são os vértices inferiores => oclusão de ambiente)
        b.quad(V3(x0, y0, z1), V3(x0, y1, z1), V3(x1, y1, z1), V3(x1, y0, z1), col(V3(0f, 0f, 1f)), ao, intArrayOf(0, 3), V3(0f, 0f, 1f))
        b.quad(V3(x1, y0, z0), V3(x1, y1, z0), V3(x0, y1, z0), V3(x0, y0, z0), col(V3(0f, 0f, -1f)), ao, intArrayOf(0, 3), V3(0f, 0f, -1f))
        b.quad(V3(x1, y0, z1), V3(x1, y1, z1), V3(x1, y1, z0), V3(x1, y0, z0), col(V3(1f, 0f, 0f)), ao, intArrayOf(0, 3), V3(1f, 0f, 0f))
        b.quad(V3(x0, y0, z0), V3(x0, y1, z0), V3(x0, y1, z1), V3(x0, y0, z1), col(V3(-1f, 0f, 0f)), ao, intArrayOf(0, 3), V3(-1f, 0f, 0f))
    }

    fun objectColor(t: SmartObjectType): FloatArray = when (t) {
        SmartObjectType.CASA -> lin(0.66f, 0.60f, 0.50f)
        SmartObjectType.HORTA -> lin(0.30f, 0.52f, 0.26f)
        SmartObjectType.COMPOSTEIRA -> lin(0.42f, 0.32f, 0.22f)
        SmartObjectType.USINA_SOLAR -> lin(0.16f, 0.26f, 0.40f)
        SmartObjectType.LABORATORIO -> lin(0.30f, 0.52f, 0.52f)   // teal Lumon
        SmartObjectType.ESCOLA -> lin(0.72f, 0.66f, 0.42f)
        SmartObjectType.PRACA -> lin(0.82f, 0.70f, 0.30f)
        SmartObjectType.OFICINA -> lin(0.55f, 0.40f, 0.30f)
    }

    fun build(width: Int, tiles: IntArray, objects: List<ObjectView>): CityMesh {
        val b = Buf()
        val types = TileType.values()
        for (z in 0 until width) for (x in 0 until width) {
            val t = types[tiles[z * width + x].coerceIn(0, types.size - 1)]
            val y = if (t == TileType.AGUA) -0.08f else 0f
            val c = tileColor(t, x, z)
            val col = floatArrayOf(c[0] * shade(V3(0f, 1f, 0f)), c[1] * shade(V3(0f, 1f, 0f)), c[2] * shade(V3(0f, 1f, 0f)), c[3])
            if (t != TileType.EDIFICIO) {
                b.quad(V3(x.toFloat(), y, z.toFloat()), V3(x.toFloat(), y, z + 1f), V3(x + 1f, y, z + 1f), V3(x + 1f, y, z.toFloat()), col)
            } else {
                val h = buildingHeight(x, z)
                val concrete = lin(0.55f + (hash(x, z, 3) % 5) * 0.012f, 0.54f, 0.51f)
                box(b, x + 0.02f, z + 0.02f, x + 0.98f, z + 0.98f, 0f, h, concrete)
                // laje de cobertura brutalista (beiral)
                box(b, x - 0.03f, z - 0.03f, x + 1.03f, z + 1.03f, h, h + 0.12f, lin(0.47f, 0.46f, 0.44f), 0.9f)
                // janelas: faixas fluorescentes por andar, parcialmente acesas (emissivo em a)
                var floorY = 0.55f
                var floor = 0
                while (floorY + 0.3f < h) {
                    val lit = hash(x, z, 11 + floor) % 3 != 0
                    val wc = if (lit) lin(1.0f, 0.86f, 0.58f, 1f) else lin(0.10f, 0.11f, 0.11f)
                    val y0 = floorY; val y1 = floorY + 0.22f
                    b.quad(V3(x + 0.12f, y0, z + 0.99f), V3(x + 0.12f, y1, z + 0.99f), V3(x + 0.88f, y1, z + 0.99f), V3(x + 0.88f, y0, z + 0.99f), wc, outward = V3(0f, 0f, 1f))
                    b.quad(V3(x + 0.99f, y0, z + 0.88f), V3(x + 0.99f, y1, z + 0.88f), V3(x + 0.99f, y1, z + 0.12f), V3(x + 0.99f, y0, z + 0.12f), wc, outward = V3(1f, 0f, 0f))
                    floorY += 0.55f; floor++
                }
            }
        }
        for (o in objects) {
            val c = objectColor(o.type)
            val h = when (o.type) { SmartObjectType.ESCOLA, SmartObjectType.LABORATORIO -> 0.9f; SmartObjectType.PRACA -> 0.12f; else -> 0.55f }
            box(b, o.x + 0.18f, o.z + 0.18f, o.x + 0.82f, o.z + 0.82f, 0f, h, c)
        }
        backrooms(b, width, tiles, objects)
        return b.mesh()
    }

    /**
     * v3.9 — cenário: terreno contínuo em Y=0 além da grade (sem vazio preto nas bordas),
     * árvores estilizadas nos gramados e no entorno e postes de luz nas calçadas/ruas
     * (cabeça emissiva onde ficam os sprites de halation). Malha separada da cidade.
     */
    fun scenery(width: Int, tiles: IntArray, margin: Int = 36): CityMesh {
        val b = Buf()
        val w = width.toFloat(); val m = margin.toFloat()
        val up = V3(0f, 1f, 0f)
        val earth = lin(0.26f, 0.29f, 0.17f)
        val earthCol = floatArrayOf(earth[0] * shade(up), earth[1] * shade(up), earth[2] * shade(up), 0f)
        // anel de terreno em volta da cidade, levemente abaixo para evitar z-fighting
        val y = -0.015f
        b.quad(V3(-m, y, -m), V3(-m, y, 0f), V3(w + m, y, 0f), V3(w + m, y, -m), earthCol)
        b.quad(V3(-m, y, w), V3(-m, y, w + m), V3(w + m, y, w + m), V3(w + m, y, w), earthCol)
        b.quad(V3(-m, y, 0f), V3(-m, y, w), V3(0f, y, w), V3(0f, y, 0f), earthCol)
        b.quad(V3(w, y, 0f), V3(w, y, w), V3(w + m, y, w), V3(w + m, y, 0f), earthCol)
        val types = TileType.values()
        // árvores nos gramados da cidade
        for (z in 0 until width) for (x in 0 until width) {
            val t = types[tiles[z * width + x].coerceIn(0, types.size - 1)]
            if (t == TileType.GRAMA && hash(x, z, 23) % 6 == 0) tree(b, x + 0.5f, z + 0.5f, 0.8f + (hash(x, z, 29) % 4) * 0.12f, hash(x, z, 31))
            if (t == TileType.CALCADA && (x * 7 + z * 3) % 9 == 0) lamp(b, x + 0.5f, z + 0.5f)
            if (t == TileType.RUA && (x + z) % 4 == 0) lamp(b, x + 0.08f, z + 0.08f)
        }
        // bosque no entorno (determinístico)
        var k = 0
        for (gz in -margin until width + margin step 3) for (gx in -margin until width + margin step 3) {
            val inside = gx in -1..width && gz in -1..width
            if (inside || hash(gx, gz, 41) % 3 != 0 || k > 420) continue
            val jx = (hash(gx, gz, 43) % 100) / 100f * 2f; val jz = (hash(gx, gz, 47) % 100) / 100f * 2f
            tree(b, gx + jx, gz + jz, 1.0f + (hash(gx, gz, 53) % 5) * 0.18f, hash(gx, gz, 59)); k++
        }
        return b.mesh()
    }

    private fun tree(b: Buf, cx: Float, cz: Float, s: Float, h: Int) {
        val trunk = lin(0.30f, 0.21f, 0.14f)
        val greens = arrayOf(lin(0.22f, 0.40f, 0.20f), lin(0.28f, 0.46f, 0.22f), lin(0.18f, 0.34f, 0.24f))
        val leaf = greens[h % greens.size]
        box(b, cx - 0.06f * s, cz - 0.06f * s, cx + 0.06f * s, cz + 0.06f * s, 0f, 0.55f * s, trunk, 0.6f)
        box(b, cx - 0.36f * s, cz - 0.36f * s, cx + 0.36f * s, cz + 0.36f * s, 0.45f * s, 1.05f * s, leaf, 0.75f)
        box(b, cx - 0.22f * s, cz - 0.22f * s, cx + 0.22f * s, cz + 0.22f * s, 1.05f * s, 1.45f * s, leaf, 0.9f)
    }

    private fun lamp(b: Buf, cx: Float, cz: Float) {
        val pole = lin(0.20f, 0.20f, 0.21f)
        box(b, cx - 0.035f, cz - 0.035f, cx + 0.035f, cz + 0.035f, 0f, 2.05f, pole, 0.7f)
        box(b, cx - 0.09f, cz - 0.09f, cx + 0.09f, cz + 0.09f, 2.0f, 2.14f, lin(1.0f, 0.78f, 0.48f, 1f), 1f)
    }

    /** Cor do papel de parede amarelo-envelhecido, do carpete úmido e das luminárias fluorescentes. */
    private val WALLPAPER = lin(0.80f, 0.74f, 0.46f)
    private val CARPET_DAMP = lin(0.55f, 0.50f, 0.30f)
    private val CEILING_TILE = lin(0.86f, 0.84f, 0.74f)
    private val FLUORESCENT = lin(1.0f, 1.0f, 0.86f, 1f)
    const val BACKROOMS_WALL_H = 1.95f

    /**
     * Corredores liminares Backrooms (ver [BackroomsCorridors]) entre o setor Lumon e a ala
     * Modernismo Tropical: carpete úmido, divisórias com papel de parede, faixa de forro
     * rebaixado no topo das paredes e luminárias fluorescentes a cada 2 células. Sem teto
     * contínuo para manter os cidadãos visíveis pela câmera orbital.
     */
    private fun backrooms(b: Buf, width: Int, tiles: IntArray, objects: List<ObjectView>) {
        for (c in BackroomsCorridors.plan(width, tiles, objects)) {
            val mask = BackroomsCorridors.wallMask(width, tiles, c)
            for ((k, cell) in c.cells.withIndex()) {
                val x = (cell % width).toFloat(); val z = (cell / width).toFloat()
                val j = (hash(cell, k, 17) % 5) * 0.01f
                val floor = floatArrayOf(CARPET_DAMP[0] + j, CARPET_DAMP[1] + j, CARPET_DAMP[2], 0f)
                b.quad(V3(x, 0.012f, z), V3(x, 0.012f, z + 1f), V3(x + 1f, 0.012f, z + 1f), V3(x + 1f, 0.012f, z), floor)
                val m = mask[k]
                val t = 0.05f; val h = BACKROOMS_WALL_H
                if (m and 1 != 0) { box(b, x, z, x + 1f, z + t, 0f, h, WALLPAPER, 0.7f); box(b, x, z, x + 1f, z + 0.22f, h, h + 0.08f, CEILING_TILE, 0.95f) }
                if (m and 2 != 0) { box(b, x + 1f - t, z, x + 1f, z + 1f, 0f, h, WALLPAPER, 0.7f); box(b, x + 0.78f, z, x + 1f, z + 1f, h, h + 0.08f, CEILING_TILE, 0.95f) }
                if (m and 4 != 0) { box(b, x, z + 1f - t, x + 1f, z + 1f, 0f, h, WALLPAPER, 0.7f); box(b, x, z + 0.78f, x + 1f, z + 1f, h, h + 0.08f, CEILING_TILE, 0.95f) }
                if (m and 8 != 0) { box(b, x, z, x + t, z + 1f, 0f, h, WALLPAPER, 0.7f); box(b, x, z, x + 0.22f, z + 1f, h, h + 0.08f, CEILING_TILE, 0.95f) }
                if (k % 2 == 0) box(b, x + 0.3f, z + 0.42f, x + 0.7f, z + 0.58f, h + 0.1f, h + 0.14f, FLUORESCENT, 1f)
            }
        }
    }

    /** Postes de luz (praças e a cada 4 tiles de rua) + janelas acesas → sprites de halation. */
    fun glowSprites(width: Int, tiles: IntArray, objects: List<ObjectView>, maxSprites: Int = 240): GlowSprites {
        val centers = ArrayList<FloatArray>()
        val types = TileType.values()
        for (z in 0 until width) for (x in 0 until width) {
            val t = types[tiles[z * width + x].coerceIn(0, types.size - 1)]
            if (t == TileType.RUA && (x + z) % 4 == 0) centers.add(floatArrayOf(x + 0.08f, 2.1f, z + 0.08f, 1.0f, 0.62f, 0.32f, 1.6f))
            if (t == TileType.CALCADA && (x * 7 + z * 3) % 9 == 0) centers.add(floatArrayOf(x + 0.5f, 2.1f, z + 0.5f, 1.0f, 0.66f, 0.36f, 1.2f))
            if (t == TileType.PRACA && hash(x, z, 5) % 3 == 0) centers.add(floatArrayOf(x + 0.5f, 1.6f, z + 0.5f, 1.0f, 0.72f, 0.42f, 1.2f))
            if (t == TileType.EDIFICIO && hash(x, z, 13) % 4 == 0) centers.add(floatArrayOf(x + 1.0f, buildingHeight(x, z) * 0.55f, z + 1.0f, 1.0f, 0.42f, 0.22f, 1.3f))
        }
        for (c in BackroomsCorridors.plan(width, tiles, objects)) for ((k, cell) in c.cells.withIndex()) if (k % 2 == 0)
            centers.add(floatArrayOf(cell % width + 0.5f, BACKROOMS_WALL_H + 0.12f, cell / width + 0.5f, 0.92f, 1.0f, 0.72f, 0.9f))
        for (o in objects) if (o.type == SmartObjectType.LABORATORIO || o.type == SmartObjectType.USINA_SOLAR)
            centers.add(floatArrayOf(o.x + 0.5f, 1.2f, o.z + 0.5f, 0.35f, 0.85f, 0.8f, 1.1f))
        val n = minOf(centers.size, maxSprites)
        val pos = FloatArray(n * 12); val uv = FloatArray(n * 8); val col = FloatArray(n * 16); val idx = IntArray(n * 6)
        val corners = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        for (s in 0 until n) {
            val c = centers[s]
            for (k in 0 until 4) {
                val v = s * 4 + k
                pos[v * 3] = c[0]; pos[v * 3 + 1] = c[1]; pos[v * 3 + 2] = c[2]
                uv[v * 2] = corners[k * 2]; uv[v * 2 + 1] = corners[k * 2 + 1]
                col[v * 4] = c[3]; col[v * 4 + 1] = c[4]; col[v * 4 + 2] = c[5]; col[v * 4 + 3] = c[6]
            }
            val b = s * 4
            idx[s * 6] = b; idx[s * 6 + 1] = b + 1; idx[s * 6 + 2] = b + 2; idx[s * 6 + 3] = b; idx[s * 6 + 4] = b + 2; idx[s * 6 + 5] = b + 3
        }
        return GlowSprites(pos, uv, col, idx)
    }

    /** Malha do cidadão com skinning rígido: [bones] = índice do osso por vértice ([CitizenRig]). */
    class RiggedMesh(val positions: FloatArray, val colors: FloatArray, val indices: IntArray, val bones: ShortArray)

    /**
     * Cidadão articulado (pose de ligação adulta, 1,42 de altura, rosto em +z): tronco,
     * cabeça, boca, braços e pernas; branco sombreado (a cor vem do tint do material),
     * com a boca escura e emissivo zero.
     */
    fun citizenMesh(): RiggedMesh {
        val b = Buf()
        val boneOf = ArrayList<Short>()
        fun part(bone: Int, f: () -> Unit) { val before = b.p.size / 3; f(); repeat(b.p.size / 3 - before) { boneOf.add(bone.toShort()) } }
        val white = floatArrayOf(1f, 1f, 1f, 0f)
        part(CitizenRig.BONE_LEG_L) { box(b, -0.16f, -0.07f, -0.04f, 0.07f, 0f, 0.55f, white, 0.7f) }
        part(CitizenRig.BONE_LEG_R) { box(b, 0.04f, -0.07f, 0.16f, 0.07f, 0f, 0.55f, white, 0.7f) }
        part(CitizenRig.BONE_TORSO) { box(b, -0.21f, -0.13f, 0.21f, 0.13f, 0.53f, 1.06f, white, 0.8f) }
        part(CitizenRig.BONE_ARM_L) { box(b, -0.33f, -0.055f, -0.22f, 0.055f, 0.56f, 1.02f, white, 0.75f) }
        part(CitizenRig.BONE_ARM_R) { box(b, 0.22f, -0.055f, 0.33f, 0.055f, 0.56f, 1.02f, white, 0.75f) }
        part(CitizenRig.BONE_HEAD) { box(b, -0.15f, -0.15f, 0.15f, 0.15f, 1.10f, 1.40f, white, 0.85f) }
        part(CitizenRig.BONE_MOUTH) { box(b, -0.055f, 0.148f, 0.055f, 0.162f, 1.185f, 1.215f, floatArrayOf(0.05f, 0.03f, 0.03f, 0f), 1f) }
        val m = b.mesh()
        return RiggedMesh(m.positions, m.colors, m.indices, boneOf.toShortArray())
    }
}
