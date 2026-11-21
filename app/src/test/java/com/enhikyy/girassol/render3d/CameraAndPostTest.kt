package com.enhikyy.girassol.render3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v3.9 — câmera 3D: começa elevada (Y > 0), isométrica (~42° para baixo) olhando para o centro
 * da cena; gestos extremos nunca atravessam o terreno. O pós-processo não inverte a imagem.
 */
class CameraAndPostTest {
    @Test
    fun cameraStartsElevatedLookingAtSceneCenter() {
        val cam = OrbitCamera()
        cam.frameCity(64)
        val eye = cam.eye()
        assertTrue("olho abaixo do chão: $eye", eye.y > 0f)
        assertTrue("olho baixo demais: $eye", eye.y >= OrbitCamera.MIN_EYE_HEIGHT)
        val t = cam.target()
        assertEquals(32f, t.x, 0.5f); assertEquals(32f, t.z, 0.5f)
        val fwd = (t - eye).normalized()
        assertTrue("câmera não olha para baixo: $fwd", fwd.y < -0.3f)
        val pitchDeg = Math.toDegrees(cam.pitch.toDouble())
        assertTrue("pitch fora do isométrico: $pitchDeg", pitchDeg in 30.0..60.0)
        val (sx, sy) = checkNotNull(cam.project(t, 1080f, 2400f))
        assertEquals(540f, sx, 2f); assertEquals(1200f, sy, 2f)
        // "para cima" na tela = mais longe no mundo (imagem não invertida): um ponto além do alvo sobe na tela
        val far = t + V3(fwd.x, 0f, fwd.z).normalized() * 6f
        val (_, farY) = checkNotNull(cam.project(far, 1080f, 2400f))
        assertTrue("projeção invertida verticalmente", farY < sy)
    }

    @Test
    fun extremeGesturesNeverCrossTheTerrain() {
        val cam = OrbitCamera(); cam.frameCity(64)
        repeat(200) { cam.orbit(0f, -4000f, 2400f); cam.zoom(8f); cam.pan(3000f, 3000f, 2400f) }
        assertTrue(cam.eye().y >= OrbitCamera.MIN_EYE_HEIGHT - 1e-3f)
        assertTrue(cam.pitch >= OrbitCamera.MIN_PITCH - 1e-4f)
        repeat(200) { cam.orbit(0f, 4000f, 2400f); cam.zoom(0.05f) }
        assertTrue(cam.pitch <= OrbitCamera.MAX_PITCH + 1e-4f)
        assertTrue(cam.eye().y > 0f)
    }

    @Test
    fun focusAnimatesTowardsCitizen() {
        val cam = OrbitCamera(); cam.frameCity(64)
        cam.focusOn(10f, 50f, 14f)
        repeat(240) { cam.animate(1f / 60f) }
        assertEquals(10f, cam.targetX, 0.3f); assertEquals(50f, cam.targetZ, 0.3f)
        assertTrue(cam.eye().y > 0f)
    }

    @Test
    fun postProcessDoesNotFlipUv() {
        val mat = listOf(File("src/main/materials/girassol_dehancer_post.mat"), File("app/src/main/materials/girassol_dehancer_post.mat"))
            .first { it.exists() }.readText()
        assertTrue("girassol_dehancer_post.mat precisa de flipUV : false (imagem invertida em GPU real)",
            Regex("""flipUV\s*:\s*false""").containsMatchIn(mat))
    }

    @Test
    fun sunCycleIsContinuousAndNightIsDark() {
        val noon = SunCycle.at(0.5f); val midnight = SunCycle.at(0f)
        assertTrue(noon.daylight > 0.9f && midnight.daylight < 0.05f)
        assertTrue(midnight.emissive > noon.emissive)
        var prev = SunCycle.at(0f)
        for (i in 1..480) {
            val s = SunCycle.at(i / 480f)
            assertTrue("salto de luz no passo $i", kotlin.math.abs(s.daylight - prev.daylight) < 0.05f)
            prev = s
        }
        // tick 0 do núcleo = 06:00 (amanhecer), coerente com o SimClock
        assertEquals(0.25f, SunCycle.dayFraction(0), 1e-4f)
    }
}
