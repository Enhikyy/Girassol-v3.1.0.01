package com.enhikyy.girassol.render3d

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Quaternions de espaço tangente no formato do Filament (atributo TANGENTS, FLOAT4).
 * Porta de `mat3f::packTangentFrame` / `SurfaceOrientation`: q codifica a base
 * (t, n × t, n), com w positivo e viés mínimo 1/32767 (o sinal de w carrega a reflexão).
 */
object TangentFrame {
    private const val BIAS = 1f / 32767f

    /** Empacota a base de [t] (tangente, direção +u) e [n] (normal); escreve xyzw em [out] a partir de [off]. */
    fun pack(t: V3, n: V3, out: FloatArray, off: Int, bitangent: V3? = null) {
        val nn = n.normalized()
        // Gram-Schmidt: t ⟂ n
        var tt = (t - nn * nn.dot(t)).normalized()
        if (tt.length() < 0.5f) tt = anyPerpendicular(nn)
        val b = nn.cross(tt)
        // matriz de colunas (t, b, n) → quaternion
        val m00 = tt.x; val m10 = tt.y; val m20 = tt.z
        val m01 = b.x; val m11 = b.y; val m21 = b.z
        val m02 = nn.x; val m12 = nn.y; val m22 = nn.z
        val tr = m00 + m11 + m22
        var x: Float; var y: Float; var z: Float; var w: Float
        if (tr > 0f) {
            val s = 0.5f / sqrt(tr + 1f)
            w = 0.25f / s; x = (m21 - m12) * s; y = (m02 - m20) * s; z = (m10 - m01) * s
        } else if (m00 > m11 && m00 > m22) {
            val s = 2f * sqrt(1f + m00 - m11 - m22)
            w = (m21 - m12) / s; x = 0.25f * s; y = (m01 + m10) / s; z = (m02 + m20) / s
        } else if (m11 > m22) {
            val s = 2f * sqrt(1f + m11 - m00 - m22)
            w = (m02 - m20) / s; x = (m01 + m10) / s; y = 0.25f * s; z = (m12 + m21) / s
        } else {
            val s = 2f * sqrt(1f + m22 - m00 - m11)
            w = (m10 - m01) / s; x = (m02 + m20) / s; y = (m12 + m21) / s; z = 0.25f * s
        }
        val l = sqrt(x * x + y * y + z * z + w * w)
        x /= l; y /= l; z /= l; w /= l
        if (w < 0f) { x = -x; y = -y; z = -z; w = -w }
        if (w < BIAS) {
            w = BIAS
            val f = sqrt(1f - BIAS * BIAS)
            x *= f; y *= f; z *= f
        }
        // reflexão (n × t)·b ≤ 0 → w negativo (como no Filament)
        if (bitangent != null && b.dot(bitangent) <= 0f) { x = -x; y = -y; z = -z; w = -w }
        out[off] = x; out[off + 1] = y; out[off + 2] = z; out[off + 3] = w
    }

    /** Rotaciona [v] pelo quaternion (x, y, z, w) — usado pelos testes e pelo decodificador. */
    fun rotate(q: FloatArray, off: Int, v: V3): V3 {
        val qx = q[off]; val qy = q[off + 1]; val qz = q[off + 2]; val qw = q[off + 3]
        // v' = v + 2w(q×v) + 2 q×(q×v)
        val cx = qy * v.z - qz * v.y; val cy = qz * v.x - qx * v.z; val cz = qx * v.y - qy * v.x
        val ccx = qy * cz - qz * cy; val ccy = qz * cx - qx * cz; val ccz = qx * cy - qy * cx
        return V3(v.x + 2f * (qw * cx + ccx), v.y + 2f * (qw * cy + ccy), v.z + 2f * (qw * cz + ccz))
    }

    fun anyPerpendicular(n: V3): V3 = (if (kotlin.math.abs(n.y) < 0.9f) V3(0f, 1f, 0f) else V3(1f, 0f, 0f)).cross(n).normalized()
}

/** Malha iluminada: POSITION f3, TANGENTS f4 (quaternion), UV0 f2 (em metros; o material aplica uvScale). */
class LitMesh(val positions: FloatArray, val tangents: FloatArray, val uvs: FloatArray, val indices: IntArray) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3

    fun bounds(): Pair<V3, V3> {
        if (vertexCount == 0) return V3(0f, 0f, 0f) to V3(0f, 0f, 0f)
        var a = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        var b = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until vertexCount) for (k in 0 until 3) {
            val v = positions[i * 3 + k]
            if (v < a[k]) a[k] = v; if (v > b[k]) b[k] = v
        }
        return V3(a[0], a[1], a[2]) to V3(b[0], b[1], b[2])
    }

    /** Normal (decodificada do quaternion) do vértice [i]. */
    fun normalAt(i: Int): V3 = TangentFrame.rotate(tangents, i * 4, V3(0f, 0f, 1f))
}

/** Construtor incremental de [LitMesh] (primitivas de arquitetura com UVs métricas). */
class LitMeshBuilder {
    private var p = FloatArray(1024); private var np = 0
    private var t = FloatArray(1024); private var nt = 0
    private var uv = FloatArray(1024); private var nuv = 0
    private var idx = IntArray(1024); private var ni = 0
    val vertexCount: Int get() = np / 3
    val isEmpty: Boolean get() = ni == 0

    private fun ensure() {
        if (np + 3 > p.size) p = p.copyOf(p.size * 2)
        if (nt + 4 > t.size) t = t.copyOf(t.size * 2)
        if (nuv + 2 > uv.size) uv = uv.copyOf(uv.size * 2)
    }

    fun vertex(pos: V3, tangent: V3, normal: V3, u: Float, v: Float): Int {
        ensure()
        p[np++] = pos.x; p[np++] = pos.y; p[np++] = pos.z
        TangentFrame.pack(tangent, normal, t, nt); nt += 4
        uv[nuv++] = u; uv[nuv++] = v
        return np / 3 - 1
    }

    fun tri(a: Int, b: Int, c: Int) {
        if (ni + 3 > idx.size) idx = idx.copyOf(idx.size * 2)
        idx[ni++] = a; idx[ni++] = b; idx[ni++] = c
    }

    /**
     * Retângulo origem + a·[ex] + b·[ey] (a, b ∈ [0, 1]); normal = ex × ey (anti-horário visto de fora).
     * UV local em metros a partir de ([u0], [v0]) — ou projetada no plano do mundo se [worldUv].
     */
    fun rect(o: V3, ex: V3, ey: V3, u0: Float = 0f, v0: Float = 0f, worldUv: Boolean = false) {
        val n = ex.cross(ey).normalized()
        val lx = ex.length(); val ly = ey.length()
        val ux = ex * (1f / lx); val uy = ey * (1f / ly)
        val corners = arrayOf(o, o + ex, o + ex + ey, o + ey)
        val ab = arrayOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f)
        val base = vertexCount
        for (k in 0 until 4) {
            val c = corners[k]
            val u = if (worldUv) c.dot(ux) else u0 + ab[k].first * lx
            val v = if (worldUv) c.dot(uy) else v0 + ab[k].second * ly
            vertex(c, ux, n, u, v)
        }
        tri(base, base + 1, base + 2); tri(base, base + 2, base + 3)
    }

    /** Quadrilátero planar arbitrário a-b-c-d (anti-horário visto de fora); UV projetada nos eixos (b−a, n×(b−a)). */
    fun quad(a: V3, b: V3, c: V3, d: V3) {
        val n = (b - a).cross(d - a).normalized()
        val ux = (b - a).normalized(); val uy = n.cross(ux)
        val base = vertexCount
        for (q in arrayOf(a, b, c, d)) vertex(q, ux, n, q.dot(ux), q.dot(uy))
        tri(base, base + 1, base + 2); tri(base, base + 2, base + 3)
    }

    /** Caixa alinhada aos eixos com normais por face (UVs no mundo, contínuas entre caixas vizinhas). */
    fun box(min: V3, max: V3, top: Boolean = true, bottom: Boolean = false, sides: Boolean = true) {
        val dx = max.x - min.x; val dy = max.y - min.y; val dz = max.z - min.z
        if (dx <= 0f || dy <= 0f || dz <= 0f) return
        if (sides) {
            rect(V3(max.x, min.y, max.z), V3(0f, 0f, -dz), V3(0f, dy, 0f), u0 = -max.z, v0 = min.y)
            rect(V3(min.x, min.y, min.z), V3(0f, 0f, dz), V3(0f, dy, 0f), u0 = min.z, v0 = min.y)
            rect(V3(min.x, min.y, max.z), V3(dx, 0f, 0f), V3(0f, dy, 0f), u0 = min.x, v0 = min.y)
            rect(V3(max.x, min.y, min.z), V3(-dx, 0f, 0f), V3(0f, dy, 0f), u0 = -max.x, v0 = min.y)
        }
        if (top) rect(V3(min.x, max.y, max.z), V3(dx, 0f, 0f), V3(0f, 0f, -dz), u0 = min.x, v0 = -max.z)
        if (bottom) rect(V3(min.x, min.y, min.z), V3(dx, 0f, 0f), V3(0f, 0f, dz), u0 = min.x, v0 = min.z)
    }

    /** Caixa com as arestas superiores chanfradas em 45° ([c] metros) — meios-fios, divisórias, mesas. */
    fun chamferBox(min: V3, max: V3, c: Float) {
        val cc = c.coerceAtMost(minOf(max.x - min.x, max.z - min.z, max.y - min.y) * 0.45f)
        val yS = max.y - cc
        box(min, V3(max.x, yS, max.z), top = false)
        val a = V3(min.x, yS, max.z); val b = V3(max.x, yS, max.z); val bb = V3(max.x, yS, min.z); val aa = V3(min.x, yS, min.z)
        val ta = V3(min.x + cc, max.y, max.z - cc); val tb = V3(max.x - cc, max.y, max.z - cc)
        val tbb = V3(max.x - cc, max.y, min.z + cc); val taa = V3(min.x + cc, max.y, min.z + cc)
        quad(a, b, tb, ta)          // +Z
        quad(b, bb, tbb, tb)        // +X
        quad(bb, aa, taa, tbb)      // −Z
        quad(aa, a, ta, taa)        // −X
        quad(ta, tb, tbb, taa)      // topo
    }

    /** Prisma de [segments] lados (cilindro com normais suaves), eixo vertical. */
    fun cylinder(cx: Float, cz: Float, y0: Float, y1: Float, r0: Float, r1: Float = r0, segments: Int = 10, caps: Boolean = true) {
        val base = vertexCount
        for (i in 0..segments) {
            val a = (2.0 * PI * i / segments).toFloat()
            val n = V3(sin(a), 0f, cos(a))
            val tg = V3(0f, 1f, 0f).cross(n)
            val u = a * maxOf(r0, r1)
            vertex(V3(cx + n.x * r0, y0, cz + n.z * r0), tg, n, u, y0)
            vertex(V3(cx + n.x * r1, y1, cz + n.z * r1), tg, n, u, y1)
        }
        for (i in 0 until segments) {
            val k = base + i * 2
            tri(k, k + 2, k + 3); tri(k, k + 3, k + 1)
        }
        if (caps) {
            val up = V3(0f, 1f, 0f)
            val c0 = vertex(V3(cx, y1, cz), V3(1f, 0f, 0f), up, cx, -cz)
            val ring = IntArray(segments) { i ->
                val a = (2.0 * PI * i / segments).toFloat()
                val x = cx + sin(a) * r1; val z = cz + cos(a) * r1
                vertex(V3(x, y1, z), V3(1f, 0f, 0f), up, x, -z)
            }
            for (i in 0 until segments) tri(c0, ring[i], ring[(i + 1) % segments])
        }
    }

    /** Segmento cilíndrico fino entre dois pontos quaisquer (fios aéreos, corrimãos, pernas de cadeira). */
    fun tube(a: V3, b: V3, r: Float, segments: Int = 6) {
        val axis = (b - a).normalized()
        val side = TangentFrame.anyPerpendicular(axis)
        val side2 = axis.cross(side)
        val base = vertexCount
        val len = (b - a).length()
        for (i in 0..segments) {
            val ang = (2.0 * PI * i / segments).toFloat()
            val n = side * cos(ang) + side2 * sin(ang)
            vertex(a + n * r, axis, n, 0f, ang * r)
            vertex(b + n * r, axis, n, len, ang * r)
        }
        for (i in 0 until segments) {
            val k = base + i * 2
            tri(k, k + 2, k + 3); tri(k, k + 3, k + 1)
        }
    }

    /** Esfera UV (cabeça, luminárias). */
    fun sphere(c: V3, rx: Float, ry: Float, rz: Float, rings: Int = 8, segments: Int = 12) {
        val base = vertexCount
        for (i in 0..rings) {
            val th = (PI * i / rings).toFloat()
            for (j in 0..segments) {
                val ph = (2.0 * PI * j / segments).toFloat()
                val n = V3(sin(th) * sin(ph), cos(th), sin(th) * cos(ph))
                val tg = V3(cos(ph), 0f, -sin(ph))
                vertex(V3(c.x + n.x * rx, c.y + n.y * ry, c.z + n.z * rz), tg, V3(n.x / rx, n.y / ry, n.z / rz).normalized(),
                    ph * rx, th * ry)
            }
        }
        val row = segments + 1
        for (i in 0 until rings) for (j in 0 until segments) {
            val k = base + i * row + j
            tri(k, k + row, k + row + 1); tri(k, k + row + 1, k + 1)
        }
    }

    /** Retângulo com UV explícita (u0..u1, v0..v1) — vidros de janela codificam o id da janela em u. */
    fun rectUv(o: V3, ex: V3, ey: V3, u0: Float, u1: Float, v0: Float, v1: Float) {
        val n = ex.cross(ey).normalized()
        val ux = ex.normalized()
        val base = vertexCount
        vertex(o, ux, n, u0, v0); vertex(o + ex, ux, n, u1, v0); vertex(o + ex + ey, ux, n, u1, v1); vertex(o + ey, ux, n, u0, v1)
        tri(base, base + 1, base + 2); tri(base, base + 2, base + 3)
    }

    /** Seção elíptica horizontal de um loft: centro (cx, y, cz), semi-eixos rx (X) e rz (Z). */
    data class Section(val y: Float, val rx: Float, val rz: Float, val cx: Float = 0f, val cz: Float = 0f)

    /**
     * Superfície lisa por seções elípticas empilhadas em Y (tronco, quadril, coxas) com normais
     * suaves (derivadas analíticas da elipse + inclinação entre seções) e tampas opcionais.
     */
    fun loft(sections: List<Section>, segments: Int = 16, capBottom: Boolean = true, capTop: Boolean = true) {
        require(sections.size >= 2) { "loft exige 2+ seções" }
        val base = vertexCount
        val row = segments + 1
        for ((si, s) in sections.withIndex()) {
            val prev = sections[maxOf(si - 1, 0)]; val next = sections[minOf(si + 1, sections.size - 1)]
            val dy = (next.y - prev.y).let { if (kotlin.math.abs(it) < 1e-6f) 1e-6f else it }
            for (j in 0..segments) {
                val a = (2.0 * PI * j / segments).toFloat()
                val sa = sin(a); val ca = cos(a)
                val pos = V3(s.cx + sa * s.rx, s.y, s.cz + ca * s.rz)
                // normal da elipse no plano + inclinação pela variação do raio ao longo de Y
                val drx = (next.rx - prev.rx) / dy; val drz = (next.rz - prev.rz) / dy
                val dcx = (next.cx - prev.cx) / dy; val dcz = (next.cz - prev.cz) / dy
                val nPlane = V3(sa / s.rx.coerceAtLeast(1e-4f), 0f, ca / s.rz.coerceAtLeast(1e-4f)).normalized()
                val slope = -(nPlane.x * (sa * drx + dcx) + nPlane.z * (ca * drz + dcz))
                val n = V3(nPlane.x, slope, nPlane.z).normalized()
                val tg = V3(ca, 0f, -sa)
                vertex(pos, tg, n, a * maxOf(s.rx, s.rz), s.y)
            }
        }
        for (i in 0 until sections.size - 1) for (j in 0 until segments) {
            val k = base + i * row + j
            tri(k, k + 1, k + row + 1); tri(k, k + row + 1, k + row)
        }
        fun cap(s: Section, up: Boolean) {
            val n = V3(0f, if (up) 1f else -1f, 0f)
            val c0 = vertex(V3(s.cx, s.y, s.cz), V3(1f, 0f, 0f), n, s.cx, s.cz)
            val ring = IntArray(segments) { j ->
                val a = (2.0 * PI * j / segments).toFloat()
                val x = s.cx + sin(a) * s.rx; val z = s.cz + cos(a) * s.rz
                vertex(V3(x, s.y, z), V3(1f, 0f, 0f), n, x, z)
            }
            for (j in 0 until segments) if (up) tri(c0, ring[j], ring[(j + 1) % segments]) else tri(c0, ring[(j + 1) % segments], ring[j])
        }
        if (capBottom) cap(sections.first(), up = false)
        if (capTop) cap(sections.last(), up = true)
    }

    /** Cápsula lisa entre [a] e [b] (raios [ra] → [rb]) com calotas hemisféricas — membros e dedos. */
    fun capsule(a: V3, b: V3, ra: Float, rb: Float = ra, segments: Int = 12, capRings: Int = 4) {
        val axis = (b - a).let { if (it.length() < 1e-6f) V3(0f, 1f, 0f) else it.normalized() }
        val s1 = TangentFrame.anyPerpendicular(axis); val s2 = axis.cross(s1)
        val len = (b - a).length()
        val base = vertexCount
        val row = segments + 1
        // anéis: calota inferior (capRings), corpo (2), calota superior (capRings)
        val rings = ArrayList<Triple<V3, Float, Float>>() // centro, raio, cos da latitude (eixo)
        for (i in capRings downTo 1) {
            val th = (PI / 2 * i / capRings).toFloat()
            rings += Triple(a - axis * (ra * sin(th)), ra * cos(th), -sin(th))
        }
        rings += Triple(a, ra, 0f); rings += Triple(b, rb, 0f)
        for (i in 1..capRings) {
            val th = (PI / 2 * i / capRings).toFloat()
            rings += Triple(b + axis * (rb * sin(th)), rb * cos(th), sin(th))
        }
        for ((ri, ring) in rings.withIndex()) {
            val (c, r, ax) = ring
            val radial = kotlin.math.sqrt((1f - ax * ax).coerceAtLeast(0f))
            for (j in 0..segments) {
                val ang = (2.0 * PI * j / segments).toFloat()
                val dir = s1 * cos(ang) + s2 * sin(ang)
                val n = (dir * radial + axis * ax).normalized()
                vertex(c + dir * r, axis, if (n.length() < 1e-5f) axis * ax else n, ang * maxOf(ra, rb), ri.toFloat() / rings.size * (len + ra + rb))
            }
        }
        for (i in 0 until rings.size - 1) for (j in 0 until segments) {
            val k = base + i * row + j
            tri(k, k + row, k + row + 1); tri(k, k + row + 1, k + 1)
        }
    }

    /** Meia-esfera: [upper] = calota de cima (cúpula), senão a de baixo (difusor de luminária). */
    fun dome(c: V3, rx: Float, ry: Float, rz: Float, upper: Boolean, rings: Int = 6, segments: Int = 14) {
        val base = vertexCount
        for (i in 0..rings) {
            val th = (PI / 2 * i / rings).toFloat() + if (upper) 0f else (PI / 2).toFloat()
            for (j in 0..segments) {
                val ph = (2.0 * PI * j / segments).toFloat()
                val n = V3(sin(th) * sin(ph), cos(th), sin(th) * cos(ph))
                vertex(V3(c.x + n.x * rx, c.y + n.y * ry, c.z + n.z * rz), V3(cos(ph), 0f, -sin(ph)),
                    V3(n.x / rx, n.y / ry, n.z / rz).normalized(), ph * rx, th * ry)
            }
        }
        val row = segments + 1
        for (i in 0 until rings) for (j in 0 until segments) {
            val k = base + i * row + j
            tri(k, k + row, k + row + 1); tri(k, k + row + 1, k + 1)
        }
        // tampa plana fechando a meia-esfera
        val n = V3(0f, if (upper) -1f else 1f, 0f)
        val c0 = vertex(c, V3(1f, 0f, 0f), n, c.x, c.z)
        val ring = IntArray(segments) { j ->
            val ph = (2.0 * PI * j / segments).toFloat()
            vertex(V3(c.x + sin(ph) * rx, c.y, c.z + cos(ph) * rz), V3(1f, 0f, 0f), n, c.x + sin(ph) * rx, c.z + cos(ph) * rz)
        }
        for (j in 0 until segments) if (upper) tri(c0, ring[(j + 1) % segments], ring[j]) else tri(c0, ring[j], ring[(j + 1) % segments])
    }

    /** Tubo por uma polilinha (braço curvo do poste, galhos) com juntas esféricas suaves. */
    fun polyTube(points: List<V3>, r: Float, segments: Int = 8) {
        for (i in 0 until points.size - 1) tube(points[i], points[i + 1], r, segments)
        for (i in 1 until points.size - 1) sphere(points[i], r, r, r, rings = 3, segments = segments)
    }

    fun append(m: LitMesh) {
        val base = vertexCount
        for (i in 0 until m.vertexCount) {
            ensure()
            p[np++] = m.positions[i * 3]; p[np++] = m.positions[i * 3 + 1]; p[np++] = m.positions[i * 3 + 2]
            for (k in 0 until 4) t[nt++] = m.tangents[i * 4 + k]
            uv[nuv++] = m.uvs[i * 2]; uv[nuv++] = m.uvs[i * 2 + 1]
        }
        for (i in 0 until m.indices.size / 3) tri(m.indices[i * 3] + base, m.indices[i * 3 + 1] + base, m.indices[i * 3 + 2] + base)
    }

    fun build(): LitMesh = LitMesh(p.copyOf(np), t.copyOf(nt), uv.copyOf(nuv), idx.copyOf(ni))
}
