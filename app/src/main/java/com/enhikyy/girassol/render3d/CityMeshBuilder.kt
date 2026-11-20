package com.enhikyy.girassol.render3d

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
        return b.mesh()
    }

    /** Postes de luz (praças e a cada 4 tiles de rua) + janelas acesas → sprites de halation. */
    fun glowSprites(width: Int, tiles: IntArray, objects: List<ObjectView>, maxSprites: Int = 900): GlowSprites {
        val centers = ArrayList<FloatArray>()
        val types = TileType.values()
        for (z in 0 until width) for (x in 0 until width) {
            val t = types[tiles[z * width + x].coerceIn(0, types.size - 1)]
            if (t == TileType.RUA && (x + z) % 4 == 0) centers.add(floatArrayOf(x + 0.5f, 2.1f, z + 0.5f, 1.0f, 0.62f, 0.32f, 1.6f))
            if (t == TileType.PRACA && hash(x, z, 5) % 3 == 0) centers.add(floatArrayOf(x + 0.5f, 1.6f, z + 0.5f, 1.0f, 0.72f, 0.42f, 1.2f))
            if (t == TileType.EDIFICIO && hash(x, z, 13) % 4 == 0) centers.add(floatArrayOf(x + 1.0f, buildingHeight(x, z) * 0.55f, z + 1.0f, 1.0f, 0.42f, 0.22f, 1.3f))
        }
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

    /** Caixa unitária (base no chão, centro em x/z) para os cidadãos; branca, sombreada; cor via tint. */
    fun citizenMesh(): CityMesh {
        val b = Buf()
        box(b, -0.22f, -0.22f, 0.22f, 0.22f, 0f, 1.05f, floatArrayOf(1f, 1f, 1f, 0f), 0.7f)
        box(b, -0.16f, -0.16f, 0.16f, 0.16f, 1.12f, 1.42f, floatArrayOf(1f, 1f, 1f, 0f), 0.85f)
        return b.mesh()
    }
}
