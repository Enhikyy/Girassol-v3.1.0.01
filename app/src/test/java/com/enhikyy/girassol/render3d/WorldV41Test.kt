package com.enhikyy.girassol.render3d

import com.enhikyy.girassol.core.world.ActionType
import com.enhikyy.girassol.core.world.AgentView
import com.enhikyy.girassol.core.world.TileType
import com.enhikyy.girassol.hardware.RenderTier
import com.enhikyy.girassol.ui.components.CitizenText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/** v4.1 — níveis de hardware, céu em gradiente, entardecer, janelas reais, salão com luzes pontuais, HUD. */
class WorldV41Test {

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
    fun renderTierUsesGlRendererForEmulatorAndRamOtherwise() {
        val swift = "Android Emulator OpenGL ES Translator (Google SwiftShader)"
        assertEquals(RenderTier.EMULATOR, RenderTier.classify(12288, swift))
        assertEquals(RenderTier.EMULATOR, RenderTier.classify(4096, "Google SwiftShader"))
        assertEquals(RenderTier.EMULATOR, RenderTier.classify(8192, "Android Emulator OpenGL ES Translator (Apple M2)"))
        // v4.1: sem teto de 4 GB — qualquer aparelho real abaixo de 10 GB recebe o perfil completo de 8 GB
        assertEquals(RenderTier.MID, RenderTier.classify(3800, "Mali-G52"))
        assertEquals(RenderTier.MID, RenderTier.classify(7600, "Adreno (TM) 642L"))
        assertEquals(RenderTier.ULTRA, RenderTier.classify(11700, "Adreno (TM) 740"))
        assertFalse(RenderTier.isEmulatorRenderer(null))
        assertFalse(RenderTier.isEmulatorRenderer("Mali-G710"))
        assertEquals(RenderTier.MID, RenderTier.fromId(2))
        assertEquals(RenderTier.MID, RenderTier.fromId(1))
        assertEquals(2048, RenderTier.MID.shadowMapSize); assertEquals(4096, RenderTier.ULTRA.shadowMapSize)
        assertEquals(2048, RenderTier.MID.textureSize); assertEquals(2048, RenderTier.ULTRA.textureSize)
        assertFalse(RenderTier.EMULATOR.pointLights); assertTrue(RenderTier.MID.pointLights)
    }

    @Test
    fun tierProfilesFollowTheSpecification() {
        val u = WorldPreset.URBAN_OVERCAST
        val mid = CinematicProfile.forTier(u, RenderTier.MID, EffectLevel.FULL)
        assertEquals(2048, mid.shadowMapSize); assertTrue(mid.specularIbl); assertTrue(mid.halation > 0f); assertTrue(mid.grain > 0f)
        assertTrue(mid.bloomEnabled); assertTrue(mid.ssaoEnabled); assertTrue(mid.ssrEnabled); assertEquals(2048, mid.textureSize)
        assertEquals(7, mid.bloomLevels); assertEquals(0.30f, mid.bloomStrength, 1e-6f); assertEquals(1.0f, mid.bloomHighlight, 1e-6f)
        assertEquals(0.14f, mid.vignette, 1e-6f)
        val ultra = CinematicProfile.forTier(u, RenderTier.ULTRA, EffectLevel.FULL)
        assertTrue(ultra.ssaoEnabled); assertTrue(ultra.grain > 0f); assertTrue(ultra.ssrEnabled); assertTrue(ultra.specularIbl)
        assertEquals(4096, ultra.shadowMapSize); assertEquals(2048, ultra.textureSize)
        val emu = CinematicProfile.forTier(u, RenderTier.EMULATOR, EffectLevel.FULL)
        assertFalse(emu.castShadows); assertFalse(emu.bloomEnabled); assertEquals(256, emu.textureSize); assertFalse(emu.ceilingPointLights)
        val hall = CinematicProfile.forTier(WorldPreset.LUMON_INTERIOR, RenderTier.MID, EffectLevel.FULL)
        assertTrue(hall.ceilingPointLights)
    }

    @Test
    fun duskPhaseAndSkyGradient() {
        val u = WorldPreset.URBAN_OVERCAST
        assertEquals(TimePhase.AFTERNOON, TimePhase.of(0.60f, u))
        assertEquals(TimePhase.DUSK, TimePhase.of(0.74f, u))
        assertEquals(TimePhase.NIGHT, TimePhase.of(0.80f, u))
        assertEquals(TimePhase.INTERIOR, TimePhase.of(0.74f, WorldPreset.LUMON_INTERIOR))
        val dusk = SkyGradient.of(TimePhase.DUSK)
        assertTrue("horizonte âmbar", dusk.horizon[0] > dusk.horizon[2] * 2f)
        assertTrue("zênite mais frio que o horizonte", dusk.zenith[2] > dusk.zenith[0])
        val day = SkyGradient.of(TimePhase.MORNING)
        assertTrue("dia: zênite azul", day.zenith[2] > day.zenith[0])
        val night = SkyGradient.of(TimePhase.NIGHT)
        assertEquals(1f, night.stars); assertEquals(0f, day.stars)
        // v4.1: zênite noturno #070B14 e horizonte escuro sem névoa cinza
        assertArrayEquals(floatArrayOf(7 / 255f, 11 / 255f, 20 / 255f), night.zenith, 1e-6f)
        assertTrue("horizonte noturno escuro", night.horizon.max() < 0.12f && night.horizon[2] > night.horizon[0])
        // IBL a 12–18 % da luz-chave no Dia e no Entardecer
        for (e in listOf(WorldExposure.DAY, WorldExposure.DUSK)) assertTrue(e.iblLux / e.sunLux in 0.12f..0.18f)
        // sol cinematográfico: 28° no dia, 15° no entardecer
        fun elevDeg(d: FloatArray) = Math.toDegrees(kotlin.math.asin(d[1].toDouble())).toFloat()
        assertEquals(28f, elevDeg(SunCycle.cinematicDir(0.40f, TimePhase.MORNING)), 0.01f)
        assertEquals(15f, elevDeg(SunCycle.cinematicDir(0.74f, TimePhase.DUSK)), 0.01f)
        for (p in TimePhase.entries) assertTrue(SkyGradient.of(p).fog.contentEquals(SkyGradient.of(p).horizon))
        val mid = SkyGradient.lerp(day, night, 0.5f)
        assertEquals((day.zenithI + night.zenithI) / 2, mid.zenithI, 1e-5f)
        assertTrue(WorldExposure.iblLuxFor(TimePhase.DUSK) > WorldExposure.iblLuxFor(TimePhase.NIGHT))
    }

    @Test
    fun buildingsHaveRealRecessedWindowsAndStreetFurniture() {
        val a = UrbanSceneBuilder.build(24, city(), emptyList())
        val mats = (a.parts.map { it.mat } + a.instanced.map { it.mat }).toSet()
        for (m in listOf("window_glass", "reveal", "frame", "sill", "curb", "gutter", "paint_worn", "lamp_head"))
            assertTrue("falta $m em $mats", m in mats)
        val glass = WorldMaterials.spec("window_glass")
        assertEquals(0.06f, glass.rough); assertTrue(glass.window); assertEquals(EmissiveDriver.WINDOWS, glass.emissiveDriver)
        assertEquals(null, WorldMaterials.spec("facade").emissive)
        for (i in 0 until 64) {
            val d = UrbanSceneBuilder.revealDepthFor(UrbanSceneBuilder.Block(i, i * 3, i + 4, i * 3 + 4, 5))
            assertTrue("reentrância $d", d in 0.15f..0.25f)
        }
        val styles = (0 until 40).map { UrbanSceneBuilder.groundStyleFor(UrbanSceneBuilder.Block(it * 2, it * 5, it * 2 + 3, it * 5 + 3, 4)) }.toSet()
        assertEquals(2, styles.size)
        assertTrue(mats.any { it == "wood" })
    }

    @Test
    fun lumonHallHasPillarsCorridorsAndCeilingPointLights() {
        val w = LumonHallBuilder.build(96f)
        val mats = w.parts.map { it.mat }.toSet()
        for (m in listOf("pillar", "carpet_runner", "corridor_wall")) assertTrue("falta $m", m in mats)
        assertEquals((LumonHallBuilder.PODS_X - 1) * (LumonHallBuilder.PODS_Z - 1), LumonHallBuilder.pillars().size)
        val pl = w.pointLights
        val desks = pl.filter { it.lux == LumonHallBuilder.DESK_LUX }
        val corr = pl.filter { it.lux == LumonHallBuilder.CORRIDOR_LUX }
        assertEquals(LumonHallBuilder.PODS_X * LumonHallBuilder.PODS_Z * 2, desks.size)
        assertTrue(corr.isNotEmpty())
        assertTrue("luzes de mesa primeiro (Lite)", pl.take(desks.size).all { it.lux == 350f })
        val d = desks.first()
        assertEquals(LumonHallBuilder.HEIGHT - LumonHallBuilder.DESK_H, d.targetDistM, 1e-5f)
        assertEquals((350.0 * 4 * PI * d.targetDistM * d.targetDistM).toFloat(), d.lumens, 1f)
        assertTrue(pl.all { it.pos.y < LumonHallBuilder.HEIGHT && it.pos.x in 0f..LumonHallBuilder.HALL_W && it.pos.z in 0f..LumonHallBuilder.HALL_D })
    }

    @Test
    fun kodakCityHasMasonryFireEscapesBranchedTreesAndWarmNightLights() {
        val a = UrbanSceneBuilder.build(24, city(), emptyList())
        val mats = a.parts.map { it.mat }.toSet()
        for (m in listOf("brick", "fire_escape", "leaf", "leaf_light")) assertTrue("falta $m em $mats", m in mats)
        // parque: árvores ramificadas (casca, três tons de folha)
        val park = UrbanSceneBuilder.build(24, IntArray(24 * 24) { TileType.GRAMA.ordinal }, emptyList())
        val pm = park.parts.map { it.mat }.toSet()
        for (m in listOf("bark", "leaf", "leaf_light", "leaf_dark")) assertTrue("falta $m em $pm", m in pm)
        val kinds = (0 until 60).map { UrbanSceneBuilder.facadeKindFor(UrbanSceneBuilder.Block(it * 3, it * 7, it * 3 + 3, it * 7 + 3, 5)) }.toSet()
        assertEquals(UrbanSceneBuilder.FacadeKind.entries.toSet(), kinds)
        // luzes noturnas quentes (vitrines 3200 K, janelas 3000 K) — a Noite usa a MESMA malha do Dia
        val night = a.nightLights
        assertTrue("luzes noturnas: ${night.size}", night.size > 20)
        assertTrue(night.all { it.kelvin in 2700f..3200f && it.lumens > 0f && it.falloffM > 0f })
        assertTrue(night.any { it.kelvin == UrbanSceneBuilder.SHOPFRONT_KELVIN } && night.any { it.kelvin == UrbanSceneBuilder.WINDOW_KELVIN })
        // poças com verniz e folhagem translúcida
        assertEquals(1f, WorldMaterials.spec("asphalt").clearCoat)
        assertTrue(WorldMaterials.spec("leaf").translucency > 0f && WorldMaterials.spec("leaf").subsurfacePower > 1f)
        // mesma grade → mesma cena (determinismo)
        val b = UrbanSceneBuilder.build(24, city(), emptyList())
        assertEquals(a.nightLights.size, b.nightLights.size)
        assertEquals(a.parts.sumOf { it.mesh.indices.size }, b.parts.sumOf { it.mesh.indices.size })
    }

    @Test
    fun severanceHallHasExposedBeamsAndSuspendedDucts() {
        val w = LumonHallBuilder.build(96f)
        val byMat = w.parts.associateBy { it.mat }
        for (m in listOf("beam_concrete", "duct", "duct_hanger", "diffuser", "carpet", "coffer")) assertTrue("falta $m", m in byMat)
        assertEquals(WorldLayers.CEILING, byMat.getValue("duct").layer)
        assertTrue(byMat.getValue("beam_concrete").castShadows)
        assertTrue("duto acima da cabeça", LumonHallBuilder.DUCT_TOP - LumonHallBuilder.DUCT_H > 2.2f)
        assertEquals(LumonHallBuilder.PODS_Z, LumonHallBuilder.ductLines().size)
    }

    @Test
    fun hudSaysEatingAtHomeOnlyWhenInside() {
        val base = AgentView("a", "Ana", 3, 3, ActionType.COMER, 0.5, 0.5, 0, "")
        assertEquals("Comendo em casa", CitizenText.status(base.copy(insideHome = true), "em casa"))
        assertEquals("Indo para casa → Comer", CitizenText.status(base.copy(insideHome = false), "na praça"))
        assertEquals("Estudando na biblioteca · na biblioteca", CitizenText.status(base.copy(action = ActionType.ESTUDAR), "na biblioteca"))
        assertTrue(CitizenText.seated(base.copy(insideHome = true)))
        assertFalse(CitizenText.seated(base))
        assertTrue(CitizenText.seated(base.copy(action = ActionType.PESQUISAR, typing = true)))
    }

    @Test
    fun citizenMeshesAreSmoothWithThighAndShin() {
        val bones = CitizenModel.bones
        assertEquals(9, bones.size)
        // malhas lisas: o tronco tem bem mais vértices que uma caixa chanfrada
        assertTrue(bones[0].first().mesh.vertexCount > 100)
        for (b in bones) for (p in b) {
            // quaternions de tangente (normal+tangente) unitários em todos os vértices
            val q = p.mesh.tangents
            for (i in 0 until p.mesh.vertexCount) {
                val len = kotlin.math.sqrt(q[i * 4] * q[i * 4] + q[i * 4 + 1] * q[i * 4 + 1] + q[i * 4 + 2] * q[i * 4 + 2] + q[i * 4 + 3] * q[i * 4 + 3])
                assertTrue("quaternion $len", abs(len - 1f) < 1e-2f)
            }
        }
    }
}
