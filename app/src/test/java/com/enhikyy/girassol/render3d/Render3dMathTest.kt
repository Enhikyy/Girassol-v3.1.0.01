package com.enhikyy.girassol.render3d

import com.enhikyy.girassol.core.world.TileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class Render3dMathTest {
    private val w = 800f
    private val h = 1200f

    @Test
    fun projectAndRayAreInverse() {
        val cam = OrbitCamera(targetX = 20f, targetZ = 20f, yaw = 0.6f, pitch = 0.7f, distance = 35f)
        val p = V3(23.5f, 0.8f, 17.5f)
        val (sx, sy) = cam.project(p, w, h)!!
        val ray = cam.ray(sx, sy, w, h)
        // o ponto deve estar sobre o raio
        val t = (p - ray.origin).dot(ray.dir)
        val closest = ray.origin + ray.dir * t
        assertTrue("distância ${(closest - p).length()}", (closest - p).length() < 1e-3f)
    }

    @Test
    fun pickWithSlopToleratesNearMiss() {
        val cam = OrbitCamera(targetX = 20f, targetZ = 20f, yaw = 0.78f, pitch = 0.8f, distance = 30f)
        val agents = mapOf("a" to V3(20.5f, 0f, 20.5f))
        val (sx, sy) = cam.project(V3(20.5f, Raycast.AGENT_HEIGHT * 0.5f, 20.5f), w, h)!!
        assertNull(Raycast.pickWithSlop(cam, sx + 400f, sy, w, h, agents, 40f))
        assertEquals("a", Raycast.pickWithSlop(cam, sx + 30f, sy + 20f, w, h, agents, 60f))
    }

    @Test
    fun targetProjectsToScreenCenter() {
        val cam = OrbitCamera(targetX = 10f, targetZ = 30f)
        val (sx, sy) = cam.project(cam.target(), w, h)!!
        assertEquals(w / 2, sx, 0.01f)
        assertEquals(h / 2, sy, 0.01f)
    }

    @Test
    fun pickSelectsNearestCitizenUnderFinger() {
        val cam = OrbitCamera(targetX = 20f, targetZ = 20f, yaw = 0.78f, pitch = 0.8f, distance = 30f)
        val agents = mapOf("a" to V3(20.5f, 0f, 20.5f), "b" to V3(26.5f, 0f, 14.5f), "c" to V3(10.5f, 0f, 30.5f))
        for ((id, p) in agents) {
            val (sx, sy) = cam.project(V3(p.x, 0.8f, p.z), w, h)!!
            assertEquals(id, Raycast.pick(cam.ray(sx, sy, w, h), agents))
        }
        assertNull(Raycast.pick(cam.ray(2f, 2f, w, h), agents))
    }

    @Test
    fun occludedCitizenLosesToNearerOne() {
        val cam = OrbitCamera(targetX = 0f, targetZ = 0f, yaw = 0f, pitch = 0.2f, distance = 20f)
        // dois cidadãos alinhados com o olho: o mais próximo deve vencer
        val agents = mapOf("perto" to V3(0f, 0f, 4f), "longe" to V3(0f, 0f, -4f))
        val (sx, sy) = cam.project(V3(0f, 0.9f, 4f), w, h)!!
        assertEquals("perto", Raycast.pick(cam.ray(sx, sy, w, h), agents))
    }

    @Test
    fun gesturesStayWithinBounds() {
        val cam = OrbitCamera()
        cam.boundsW = 48f
        repeat(50) { cam.zoom(1.5f) }
        assertEquals(cam.minDistance, cam.distance, 1e-4f)
        repeat(50) { cam.zoom(0.5f) }
        assertEquals(cam.maxDistance, cam.distance, 1e-4f)
        repeat(100) { cam.orbit(0f, 500f, h) }
        assertTrue(cam.pitch <= 1.45f)
        repeat(100) { cam.pan(5000f, 5000f, h) }
        assertTrue(cam.targetX in 0f..48f && cam.targetZ in 0f..48f)
    }

    @Test
    fun cityMeshIsDeterministicAndOutwardFacing() {
        val width = 12
        val tiles = IntArray(width * width) { i -> if ((i / width) % 4 == 1 && i % 3 != 0) TileType.EDIFICIO.ordinal else if (i % width == 5) TileType.RUA.ordinal else TileType.GRAMA.ordinal }
        val a = CityMeshBuilder.build(width, tiles, emptyList())
        val b = CityMeshBuilder.build(width, tiles, emptyList())
        assertTrue(a.positions.contentEquals(b.positions) && a.colors.contentEquals(b.colors) && a.indices.contentEquals(b.indices))
        assertEquals(0, a.indices.size % 3)
        assertTrue(a.indices.all { it in 0 until a.vertexCount })
        // prédio isolado: toda face vertical aponta para fora do centro do prédio; horizontais para cima
        val single = IntArray(width * width) { TileType.GRAMA.ordinal }.also { it[6 * width + 6] = TileType.EDIFICIO.ordinal }
        val m = CityMeshBuilder.build(width, single, emptyList())
        val center = V3(6.5f, 0f, 6.5f)
        var bad = 0
        for (t in 0 until m.indices.size / 3) {
            val p = IntArray(3) { m.indices[t * 3 + it] }.map { V3(m.positions[it * 3], m.positions[it * 3 + 1], m.positions[it * 3 + 2]) }
            val n = (p[1] - p[0]).cross(p[2] - p[0])
            val c = (p[0] + p[1] + p[2]) * (1f / 3f)
            val out = if (abs(n.y) > 1e-6f) V3(0f, 1f, 0f) else (c - center).let { V3(it.x, 0f, it.z) }
            if (n.dot(out) <= 0f) bad++
        }
        assertEquals("faces invertidas", 0, bad)
        // emissivo (janelas) existe e está em [0,1]
        val emissive = (0 until m.vertexCount).map { m.colors[it * 4 + 3] }
        assertTrue(emissive.any { it > 0.5f } && emissive.all { it in 0f..1f })
    }

    @Test
    fun glowSpritesHaveFourVerticesSharingCenter() {
        val width = 8
        val tiles = IntArray(width * width) { TileType.RUA.ordinal }
        val g = CityMeshBuilder.glowSprites(width, tiles, emptyList())
        assertTrue(g.count > 0)
        for (s in 0 until g.count) for (k in 1 until 4) for (c in 0 until 3)
            assertEquals(g.positions[s * 12 + c], g.positions[s * 12 + k * 3 + c], 0f)
    }

    @Test
    fun renderModePolicyFallsBackToEco() {
        assertEquals(RenderMode.MODE_3D, RenderModePolicy.effective(RenderMode.MODE_3D, 0, null, true).mode)
        assertEquals(RenderMode.ECO_2D, RenderModePolicy.effective(RenderMode.MODE_3D, 3, null, true).mode)
        assertEquals(RenderMode.ECO_2D, RenderModePolicy.effective(RenderMode.MODE_3D, 0, null, false).mode)
        assertEquals(RenderMode.ECO_2D, RenderModePolicy.effective(RenderMode.MODE_3D, 0, "sem GLES3", true).mode)
        assertEquals(RenderMode.ECO_2D, RenderModePolicy.effective(RenderMode.ECO_2D, 0, null, true).mode)
    }
}
