package com.enhikyy.girassol.render3d

import com.enhikyy.girassol.core.world.TileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** v4.0 — testes JVM da Aba Mundo de alta fidelidade (presets, POV, tangentes, instanciação, builders). */
class WorldV4Test {

    /** Cidade sintética 24 × 24: ruas a cada 6 tiles, calçadas ao lado, quarteirões de prédios, uma praça. */
    private fun city(w: Int = 24): IntArray = IntArray(w * w) { i ->
        val x = i % w; val z = i / w
        when {
            x % 6 == 0 || z % 6 == 0 -> TileType.RUA.ordinal
            x % 6 == 1 || x % 6 == 5 || z % 6 == 1 || z % 6 == 5 -> TileType.CALCADA.ordinal
            x in 13..16 && z in 13..16 -> TileType.PRACA.ordinal
            else -> TileType.EDIFICIO.ordinal
        }
    }

    @Test
    fun cinematicProfileMatchesSpecification() {
        val u = CinematicProfile.URBAN; val i = CinematicProfile.INTERIOR
        assertEquals(1.0f, u.resolutionScale); assertTrue(u.fxaa && u.taa)
        assertTrue(u.ssrEnabled); assertEquals(0.1f, u.ssrThickness); assertEquals(0.01f, u.ssrBias)
        assertEquals(12.0f, u.ssrMaxDistance); assertEquals(2.0f, u.ssrStride)
        assertTrue(u.castShadows); assertEquals(2048, u.shadowMapSize); assertEquals(3, u.shadowCascades)
        assertTrue(u.screenSpaceContactShadows); assertEquals(16, u.contactShadowSteps)
        assertEquals(1.0f, u.ssaoResolution); assertEquals(0.05f, u.ssaoBilateralThreshold)
        assertEquals(0.85f, u.ssaoRadius); assertEquals(1.40f, u.ssaoIntensity)
        assertEquals(0.55f, i.ssaoRadius); assertEquals(1.65f, i.ssaoIntensity)
        assertEquals(1.08f, u.contrast); assertEquals(1.14f, u.saturation); assertEquals(1.05f, i.saturation)
        assertTrue(u.shadowsTint.contentEquals(floatArrayOf(0.94f, 1.01f, 1.02f, 0.0f)))
        assertEquals(0.24f, u.bloomStrength); assertEquals(0.18f, i.bloomStrength)
        assertEquals(6, u.bloomLevels); assertEquals(512, u.bloomResolution); assertEquals(1.0f, u.bloomHighlight)
        assertEquals(256, u.iblFaceSize)
        assertTrue(u.fog && !i.fog)
        // degradação monotônica por nível de efeito
        val none = CinematicProfile.forLevel(WorldPreset.URBAN_OVERCAST, EffectLevel.NONE)
        assertFalse(none.ssrEnabled || none.ssaoEnabled || none.taa || none.castShadows)
    }

    @Test
    fun timePhasesCoverTheDay() {
        assertEquals(TimePhase.MORNING, TimePhase.of(0.33f, WorldPreset.URBAN_OVERCAST))
        assertEquals(TimePhase.AFTERNOON, TimePhase.of(0.68f, WorldPreset.URBAN_OVERCAST))
        assertEquals(TimePhase.NIGHT, TimePhase.of(0.90f, WorldPreset.URBAN_OVERCAST))
        assertEquals(TimePhase.NIGHT, TimePhase.of(0.10f, WorldPreset.URBAN_OVERCAST))
        assertEquals(TimePhase.INTERIOR, TimePhase.of(0.33f, WorldPreset.LUMON_INTERIOR))
        // janelas acendem de manhã → tarde → noite
        val m = FilamentCityRenderer.phaseLight(TimePhase.MORNING); val a = FilamentCityRenderer.phaseLight(TimePhase.AFTERNOON)
        val n = FilamentCityRenderer.phaseLight(TimePhase.NIGHT)
        assertTrue(m.windows < a.windows && a.windows < n.windows)
        assertTrue(n.lampLumens > 0f && m.lampLumens == 0f)
    }

    @Test
    fun physicalExposureIsSyncedAndLegibleInEveryBand() {
        assertEquals(LightBand.DAY, WorldExposure.band(0.40f, WorldPreset.URBAN_OVERCAST))
        assertEquals(LightBand.DAY, WorldExposure.band(0.62f, WorldPreset.URBAN_OVERCAST))
        assertEquals(LightBand.DUSK, WorldExposure.band(0.74f, WorldPreset.URBAN_OVERCAST))
        assertEquals(LightBand.NIGHT, WorldExposure.band(0.90f, WorldPreset.URBAN_OVERCAST))
        assertEquals(LightBand.INTERIOR, WorldExposure.band(0.40f, WorldPreset.LUMON_INTERIOR))
        // câmeras pedidas: f/16 1/125 ISO100, f/11 1/60 ISO100, f/4 1/30 ISO400
        assertEquals(14.97f, WorldExposure.DAY.ev100, 0.02f)
        assertEquals(12.83f, WorldExposure.DUSK.ev100, 0.02f)
        assertEquals(6.91f, WorldExposure.NIGHT.ev100, 0.02f)
        for (b in LightBand.values()) {
            val e = WorldExposure.setting(b)
            assertTrue("$b: luz nunca zera", e.sunLux > 0f && e.iblLux > 0f)
            // difuso de albedo 0,5 entre 0,15 e 1,0 antes do tonemapping: nem preto nem estourado
            assertTrue("$b: sol ${e.diffuseSun}", e.diffuseSun in 0.15f..1.0f)
            assertTrue("$b: céu ${e.diffuseSky}", e.diffuseSky in 0.03f..1.0f)
        }
    }

    @Test
    fun tangentFrameRoundTripsNormalAndTangent() {
        val cases = listOf(V3(0f, 1f, 0f) to V3(1f, 0f, 0f), V3(0f, 0f, 1f) to V3(1f, 0f, 0f), V3(-1f, 0f, 0f) to V3(0f, 0f, 1f),
            V3(0.3f, 0.8f, -0.52f).normalized() to TangentFrame.anyPerpendicular(V3(0.3f, 0.8f, -0.52f).normalized()))
        val q = FloatArray(4)
        for ((n, t) in cases) {
            TangentFrame.pack(t, n, q, 0)
            assertTrue("w ≥ 0", q[3] >= 0f)
            val rn = TangentFrame.rotate(q, 0, V3(0f, 0f, 1f)); val rt = TangentFrame.rotate(q, 0, V3(1f, 0f, 0f))
            assertTrue("normal $n → $rn", (rn - n).length() < 1e-3f)
            assertTrue("tangente $t → $rt", (rt - t).length() < 1e-3f)
        }
    }

    @Test
    fun povEyeIsAtCitizenHeightAndFollowsHeading() {
        assertEquals(1.68f, CitizenModel.EYE_HEIGHT_M, 0.01f)
        val cam = FirstPersonCamera()
        val feet = V3(10f, 0.15f, 20f)
        val heading = 0.7f
        val p = cam.pose(feet, heading, 1f, gaitPhase = 0f, walking = 0f)
        assertEquals(0.15f + 1.68f, p.eye.y, 0.02f)
        val dir = (p.target - p.eye).normalized()
        val flat = V3(dir.x, 0f, dir.z).normalized()
        assertTrue("segue a guinada", (flat - V3(sin(heading), 0f, cos(heading))).length() < 1e-3f)
        // balanço só quando anda, limitado
        val bobbing = (0 until 20).map { cam.bob(it / 20f, 1f) }
        assertTrue(bobbing.max() in 0.02f..0.04f && cam.bob(0.25f, 0f) == 0f)
        // arrasto: arfagem limitada a ±60°
        repeat(100) { cam.drag(0f, 4000f, 1000f) }
        assertTrue(abs(cam.pitch) <= FirstPersonCamera.MAX_PITCH + 1e-4f)
        // teto do interior limita o olho
        val under = cam.pose(V3(0f, 0f, 0f), 0f, 1.6f, 0f, 0f, ceilingY = 1.9f)
        assertTrue(under.eye.y <= 1.7f + 1e-4f)
    }

    @Test
    fun urbanSceneIsDeterministicWithBuildingsLampsAndInstancing() {
        val w = 24; val tiles = city(w)
        val a = UrbanSceneBuilder.build(w, tiles, emptyList())
        val b = UrbanSceneBuilder.build(w, tiles, emptyList())
        assertEquals(a.vertexCount, b.vertexCount)
        assertEquals(a.parts.map { it.mat }, b.parts.map { it.mat })
        val mats = a.parts.map { it.mat }.toSet()
        for (m in listOf("asphalt", "sidewalk", "facade", "wire", "barrier_orange")) assertTrue("falta $m em $mats", m in mats)
        assertTrue("postes", a.lamps.isNotEmpty())
        assertTrue("instâncias", a.instanceCount > 0 && a.instanced.all { it.offsets.size in 1..4096 })
        for (p in a.parts) {
            assertEquals(0, p.mesh.indices.size % 3)
            assertTrue(p.mesh.indices.all { it in 0 until p.mesh.vertexCount })
            assertEquals(p.mesh.vertexCount * 4, p.mesh.tangents.size)
            assertEquals(p.mesh.vertexCount * 2, p.mesh.uvs.size)
        }
        // todos os materiais existem no catálogo
        for (m in mats + a.instanced.map { it.mat }) assertNotNull(WorldMaterials.spec(m))
        // prédios de 3 a 8 andares
        assertTrue((0 until 50).all { UrbanSceneBuilder.floorsFor(it, it * 3) in 3..8 })
        // asfalto molhado: SSR exige material liso nas poças
        assertTrue(a.max.y >= 3 * UrbanSceneBuilder.FLOOR_H)
    }

    @Test
    fun lumonHallHasCofferedCeilingCarpetAndWorkstations() {
        val s = LumonHallBuilder.build(24 * TILE_M)
        val mats = s.parts.map { it.mat }.toSet()
        for (m in listOf("carpet", "coffer", "beam", "partition", "desk")) assertTrue("falta $m", m in mats)
        assertEquals(LumonHallBuilder.HEIGHT, s.ceilingY)
        assertTrue(s.parts.filter { it.mat == "coffer" || it.mat == "beam" }.all { it.layer == WorldLayers.CEILING })
        val st = LumonHallBuilder.stations()
        assertEquals(LumonHallBuilder.PODS_X * LumonHallBuilder.PODS_Z * 4, st.size)
        val monitors = s.instanced.filter { it.mat == "monitor_screen" }.sumOf { it.offsets.size }
        assertEquals(st.size, monitors)
        assertTrue(s.instanced.all { it.offsets.size <= 64 })
        // a cidade é mapeada para dentro do salão
        val x = s.mapX(24 * TILE_M); val z = s.mapZ(0f)
        assertTrue(x < LumonHallBuilder.HALL_W && z > 0f)
    }

    @Test
    fun specColoursAreInTheCatalog() {
        assertEquals(0x6E767A, WorldMaterials.FACADE_CONCRETE)
        assertEquals(0x1D5961, WorldMaterials.FACADE_TEAL)
        assertEquals(0x133024, WorldMaterials.CARPET_DARK); assertEquals(0x1B3F30, WorldMaterials.CARPET_LIGHT)
        assertEquals(0xD6DBD7, WorldMaterials.PARTITION_OFFWHITE)
        assertEquals(0.05f, WorldMaterials.spec("water").rough)
    }

    @Test
    fun citizenModelHasAllBonesAndDeterministicPalette() {
        assertEquals(com.enhikyy.girassol.core.render.CitizenRig.BONES, CitizenModel.bones.size)
        assertTrue(CitizenModel.bones.all { b -> b.isNotEmpty() && b.all { it.mesh.vertexCount > 0 } })
        val p1 = CitizenModel.palette("adao"); val p2 = CitizenModel.palette("adao")
        assertTrue(p1.shirt.contentEquals(p2.shirt) && p1.skin.contentEquals(p2.skin))
    }

    @Test
    fun screenPickFindsNearestProjectedCitizen() {
        val pose = CameraPose(V3(0f, 1.7f, -5f), V3(0f, 1.2f, 10f), 62f)
        val centers = mapOf("a" to V3(0f, 1f, 5f), "b" to V3(4f, 1f, 5f))
        val (sx, sy) = pose.project(centers.getValue("a"), 800f, 1200f)!!
        assertEquals("a", ScreenPick.pick(pose, sx + 5f, sy, 800f, 1200f, centers, 40f))
    }
}
