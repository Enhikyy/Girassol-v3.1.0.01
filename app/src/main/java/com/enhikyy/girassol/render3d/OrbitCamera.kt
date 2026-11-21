package com.enhikyy.girassol.render3d

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Vetor 3D mínimo (Kotlin puro, testável na JVM). */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = V3(x * s, y * s, z * s)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(dot(this))
    fun normalized(): V3 { val l = length(); return if (l < 1e-9f) this else this * (1f / l) }
}

data class Ray(val origin: V3, val dir: V3)

/**
 * Câmera orbital: alvo no plano do chão, guinada/arfagem/distância. Fornece a mesma
 * geometria para o Filament (lookAt + perspectiva) e para o raycast de seleção.
 */
class OrbitCamera(
    var targetX: Float = 0f,
    var targetZ: Float = 0f,
    var yaw: Float = 0.78f,
    var pitch: Float = DEFAULT_PITCH,
    var distance: Float = 42f,
    val fovDeg: Float = 42f
) {
    companion object {
        /** v3.9: perspectiva isométrica elevada de ~42° para baixo (estilo Cities: Skylines 2). */
        val DEFAULT_PITCH = Math.toRadians(42.0).toFloat()
        /** Arfagem mínima (~24°): a câmera nunca desce ao nível das ruas nem atravessa o terreno. */
        val MIN_PITCH = Math.toRadians(24.0).toFloat()
        val MAX_PITCH = Math.toRadians(82.0).toFloat()
        const val MIN_EYE_HEIGHT = 2.5f
    }

    val minDistance = 6f
    val maxDistance = 140f
    var boundsW: Float = Float.MAX_VALUE

    // foco animado (toque num balão / atalho da cápsula): molas críticas por quadro
    private var goalX = Float.NaN
    private var goalZ = Float.NaN
    private var goalDistance = Float.NaN

    /** Agenda um voo suave até (x, z) com a distância indicada. */
    fun focusOn(x: Float, z: Float, dist: Float = 16f) {
        goalX = x.coerceIn(0f, boundsW); goalZ = z.coerceIn(0f, boundsW)
        goalDistance = dist.coerceIn(minDistance, maxDistance)
    }

    val animating: Boolean get() = !goalX.isNaN()

    /** Avança a animação de foco; retorna true enquanto ainda está se movendo. */
    fun animate(dtSeconds: Float): Boolean {
        if (goalX.isNaN()) return false
        val k = (dtSeconds * 5.5f).coerceIn(0f, 1f)
        targetX += (goalX - targetX) * k
        targetZ += (goalZ - targetZ) * k
        distance += (goalDistance - distance) * k
        if (kotlin.math.abs(goalX - targetX) < 0.01f && kotlin.math.abs(goalZ - targetZ) < 0.01f && kotlin.math.abs(goalDistance - distance) < 0.02f) {
            targetX = goalX; targetZ = goalZ; distance = goalDistance
            goalX = Float.NaN; goalZ = Float.NaN; goalDistance = Float.NaN
        }
        enforceLimits()
        return true
    }

    /** Mantém o olho acima do terreno e a arfagem dentro dos limites. */
    fun enforceLimits() {
        pitch = pitch.coerceIn(MIN_PITCH, MAX_PITCH)
        distance = distance.coerceIn(minDistance, maxDistance)
        if (distance * sin(pitch) < MIN_EYE_HEIGHT) pitch = kotlin.math.asin((MIN_EYE_HEIGHT / distance).coerceAtMost(1f)).coerceAtMost(MAX_PITCH)
    }

    /** Enquadramento inicial: centro da cidade, olho elevado olhando para baixo. */
    fun frameCity(widthTiles: Int) {
        targetX = widthTiles / 2f; targetZ = widthTiles / 2f
        yaw = 0.78f; pitch = DEFAULT_PITCH
        distance = (widthTiles * 0.9f).coerceIn(18f, 110f)
        enforceLimits()
    }

    fun target() = V3(targetX, 0f, targetZ)

    fun eye(): V3 {
        val cp = cos(pitch)
        return V3(targetX + distance * cp * sin(yaw), distance * sin(pitch), targetZ + distance * cp * cos(yaw))
    }

    private fun basis(): Triple<V3, V3, V3> {
        val f = (target() - eye()).normalized()
        val r = f.cross(V3(0f, 1f, 0f)).normalized()
        val u = r.cross(f).normalized()
        return Triple(f, r, u)
    }

    /** Um dedo: orbita (guinada/arfagem). */
    fun orbit(dxPx: Float, dyPx: Float, viewH: Float) {
        val k = 3.2f / viewH.coerceAtLeast(1f)
        yaw -= dxPx * k
        pitch = (pitch + dyPx * k).coerceIn(MIN_PITCH, MAX_PITCH)
        cancelFocus()
        enforceLimits()
    }

    private fun cancelFocus() { goalX = Float.NaN; goalZ = Float.NaN; goalDistance = Float.NaN }

    /** Dois dedos: desloca o alvo no plano do chão, proporcional à distância. */
    fun pan(dxPx: Float, dyPx: Float, viewH: Float) {
        val (_, r, _) = basis()
        val fwdGround = V3(-sin(yaw), 0f, -cos(yaw))
        val k = distance * 1.6f / viewH.coerceAtLeast(1f)
        val d = r * (-dxPx * k) + fwdGround * (dyPx * k)
        targetX = (targetX + d.x).coerceIn(0f, boundsW)
        targetZ = (targetZ + d.z).coerceIn(0f, boundsW)
        cancelFocus()
    }

    fun zoom(factor: Float) {
        if (factor <= 0f) return
        distance = (distance / factor).coerceIn(minDistance, maxDistance)
        cancelFocus()
        enforceLimits()
    }

    fun rotate(degrees: Float) { yaw -= Math.toRadians(degrees.toDouble()).toFloat() }

    /** Raio do olho através do pixel (sx, sy) de uma vista w × h. */
    fun ray(sx: Float, sy: Float, w: Float, h: Float): Ray {
        val (f, r, u) = basis()
        val ndcX = 2f * sx / w - 1f
        val ndcY = 1f - 2f * sy / h
        val t = tan(Math.toRadians(fovDeg / 2.0).toFloat())
        val aspect = w / h
        val dir = (f + r * (ndcX * t * aspect) + u * (ndcY * t)).normalized()
        return Ray(eye(), dir)
    }

    /** Projeção de um ponto do mundo para pixels; null se estiver atrás da câmera. */
    fun project(p: V3, w: Float, h: Float): Pair<Float, Float>? {
        val (f, r, u) = basis()
        val d = p - eye()
        val z = d.dot(f)
        if (z <= 0.05f) return null
        val t = tan(Math.toRadians(fovDeg / 2.0).toFloat())
        val aspect = w / h
        val ndcX = d.dot(r) / (z * t * aspect)
        val ndcY = d.dot(u) / (z * t)
        return Pair((ndcX + 1f) * 0.5f * w, (1f - ndcY) * 0.5f * h)
    }
}

/** Seleção de cidadãos por raycast 3D contra caixas alinhadas aos eixos (método das placas). */
object Raycast {
    const val AGENT_HALF_WIDTH = 0.42f
    const val AGENT_HEIGHT = 1.7f

    fun intersectAabb(ray: Ray, min: V3, max: V3): Float? {
        var tMin = Float.NEGATIVE_INFINITY
        var tMax = Float.POSITIVE_INFINITY
        val o = floatArrayOf(ray.origin.x, ray.origin.y, ray.origin.z)
        val d = floatArrayOf(ray.dir.x, ray.dir.y, ray.dir.z)
        val lo = floatArrayOf(min.x, min.y, min.z)
        val hi = floatArrayOf(max.x, max.y, max.z)
        for (i in 0 until 3) {
            if (kotlin.math.abs(d[i]) < 1e-8f) {
                if (o[i] < lo[i] || o[i] > hi[i]) return null
            } else {
                var t1 = (lo[i] - o[i]) / d[i]
                var t2 = (hi[i] - o[i]) / d[i]
                if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
                tMin = maxOf(tMin, t1)
                tMax = minOf(tMax, t2)
                if (tMin > tMax) return null
            }
        }
        if (tMax < 0f) return null
        return if (tMin >= 0f) tMin else tMax
    }

    /** Retorna o id do cidadão mais próximo atingido pelo raio (posições = centro da base). */
    fun pick(ray: Ray, agents: Map<String, V3>): String? {
        var best: String? = null
        var bestT = Float.MAX_VALUE
        for ((id, p) in agents) {
            val t = intersectAabb(ray,
                V3(p.x - AGENT_HALF_WIDTH, p.y, p.z - AGENT_HALF_WIDTH),
                V3(p.x + AGENT_HALF_WIDTH, p.y + AGENT_HEIGHT, p.z + AGENT_HALF_WIDTH)) ?: continue
            if (t < bestT) { bestT = t; best = id }
        }
        return best
    }

    /**
     * Raycast com tolerância de toque: se o raio não cruza nenhuma caixa, escolhe o cidadão cujo
     * centro projetado está mais perto do dedo, dentro de [slopPx] (alvos pequenos em telas de celular).
     */
    fun pickWithSlop(camera: OrbitCamera, sx: Float, sy: Float, w: Float, h: Float, agents: Map<String, V3>, slopPx: Float): String? {
        pick(camera.ray(sx, sy, w, h), agents)?.let { return it }
        var best: String? = null
        var bestD = slopPx * slopPx
        for ((id, p) in agents) {
            val s = camera.project(V3(p.x, p.y + AGENT_HEIGHT * 0.5f, p.z), w, h) ?: continue
            val dx = s.first - sx; val dy = s.second - sy
            val d = dx * dx + dy * dy
            if (d <= bestD) { bestD = d; best = id }
        }
        return best
    }

    /** Interseção com o plano do chão (y = 0). */
    fun groundHit(ray: Ray): V3? {
        if (kotlin.math.abs(ray.dir.y) < 1e-6f) return null
        val t = -ray.origin.y / ray.dir.y
        return if (t < 0f) null else ray.origin + ray.dir * t
    }
}
