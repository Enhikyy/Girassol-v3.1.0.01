package com.enhikyy.girassol

import com.enhikyy.girassol.core.render.BackroomsCorridors
import com.enhikyy.girassol.core.render.CitizenRig
import com.enhikyy.girassol.core.render.Ktx2
import com.enhikyy.girassol.core.render.Mat4
import com.enhikyy.girassol.core.world.SmartObjectType
import com.enhikyy.girassol.core.world.TileType
import com.enhikyy.girassol.core.world.WorldCore
import com.enhikyy.girassol.core.world.WorldRenderSnapshot
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Ktx2TexturesTest {
    private fun asset(name: String): File {
        val candidates = listOf(File("../app/src/main/assets/textures/$name"), File("app/src/main/assets/textures/$name"))
        return candidates.first { it.exists() }
    }

    @Test fun pbrTexturesAreValidKtx2WithFullMipChain() {
        for (n in listOf("concreto_brutalista.ktx2", "carpete_backrooms.ktx2")) {
            val img = Ktx2.parse(asset(n).readBytes())
            assertEquals(Ktx2.VK_FORMAT_R8G8B8A8_UNORM, img.vkFormat)
            assertEquals(256, img.width); assertEquals(9, img.levels.size)
            assertEquals(1, img.levels.last().width)
            // rugosidade (alfa) alta: concreto e carpete são foscos
            val a = img.levels.last().rgba[3].toInt() and 0xff
            assertTrue("$n rugosidade média $a", a > 180)
        }
    }

    @Test fun texturesMatchCommittedChecksums() {
        val sums = listOf(File("../licenses/TEXTURES.sha256"), File("licenses/TEXTURES.sha256")).first { it.exists() }
        for (line in sums.readLines().filter { it.isNotBlank() }) {
            val (hash, path) = line.split(Regex("\\s+"), limit = 2)
            val f = listOf(File("../$path"), File(path)).first { it.exists() }
            val got = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(path, hash, got)
        }
    }

    @Test fun rejectsForeignKtx() {
        val bad = ByteArray(128); Ktx2.IDENTIFIER.copyInto(bad); bad[12] = 99
        assertTrue(runCatching { Ktx2.parse(bad) }.isFailure)
        assertTrue(runCatching { Ktx2.parse(ByteArray(10)) }.isFailure)
    }
}

class CitizenRigTest {
    private fun run(maturity: Float, vx: Float, typing: Boolean = false, amp: Float = 0f, frames: Int = 30): Pair<FloatArray, FloatArray> {
        val s = CitizenRig.State(); val out = FloatArray(CitizenRig.BONES * 16)
        var root = FloatArray(16)
        repeat(frames) { root = CitizenRig.pose(s, CitizenRig.Input(maturity, vx, 0f, 10f, typing, amp, 1f / 30f), out) }
        return out to root
    }

    private fun footZ(bones: FloatArray, bone: Int): Float =
        Mat4.transform(bones.copyOfRange(bone * 16, bone * 16 + 16), 0f, 0f, 0f).let { it[2] }

    @Test fun childIsSmallerWithBiggerHeadThanAdult() {
        val (_, child) = run(0f, 0f); val (_, adult) = run(1f, 0f)
        assertTrue("altura infantil ${child[5]} vs ${adult[5]}", child[5] < adult[5] * 0.6f)
        val (cb, _) = run(0f, 0f); val (ab, _) = run(1f, 0f)
        assertTrue(cb[CitizenRig.BONE_HEAD * 16 + 5] > ab[CitizenRig.BONE_HEAD * 16 + 5])
    }

    @Test fun legsStayStillWhenIdleAndSwingWithRvoSpeed() {
        val (idle, _) = run(1f, 0f)
        assertTrue(abs(footZ(idle, CitizenRig.BONE_LEG_L)) < 1e-4f)
        var maxSwing = 0f
        val s = CitizenRig.State(); val out = FloatArray(CitizenRig.BONES * 16)
        repeat(90) { CitizenRig.pose(s, CitizenRig.Input(1f, 0.08f, 0f, 10f, false, 0f, 1f / 30f), out); maxSwing = maxOf(maxSwing, abs(footZ(out, CitizenRig.BONE_LEG_L))) }
        assertTrue("balanço da perna $maxSwing", maxSwing > 0.1f)
        // cadência proporcional à velocidade: o dobro da velocidade ⇒ o dobro da fase
        val a = CitizenRig.State(); val b = CitizenRig.State()
        repeat(10) { CitizenRig.pose(a, CitizenRig.Input(1f, 0.04f, 0f, 10f, false, 0f, 0.033f), out); CitizenRig.pose(b, CitizenRig.Input(1f, 0.08f, 0f, 10f, false, 0f, 0.033f), out) }
        assertEquals(a.phase * 2f, b.phase, 1e-3f)
    }

    @Test fun mouthOpensWithVoiceAmplitudeAndArmsReachForwardWhenTyping() {
        val (quiet, _) = run(1f, 0f, amp = 0f); val (loud, _) = run(1f, 0f, amp = 1f)
        assertTrue(loud[CitizenRig.BONE_MOUTH * 16 + 5] > quiet[CitizenRig.BONE_MOUTH * 16 + 5] * 2f)
        val (typing, _) = run(1f, 0f, typing = true)
        val hand = Mat4.transform(typing.copyOfRange(CitizenRig.BONE_ARM_L * 16, CitizenRig.BONE_ARM_L * 16 + 16), -0.27f, 0.55f, 0f)
        assertTrue("mão à frente no teclado CRT: z=${hand[2]}", hand[2] > 0.25f)
    }
}

class BackroomsCorridorsTest {
    @Test fun corridorsLinkLumonLabsToTropicalWingDeterministically() {
        val s = WorldRenderSnapshot.of(WorldCore.create(42L, 24).state)
        val a = BackroomsCorridors.plan(s.widthTiles, s.tiles, s.objects)
        val b = BackroomsCorridors.plan(s.widthTiles, s.tiles, s.objects)
        assertTrue("nenhum corredor", a.isNotEmpty())
        assertEquals(a.map { it.cells }, b.map { it.cells })
        val byId = s.objects.associateBy { it.id }
        for (c in a) {
            assertEquals(SmartObjectType.LABORATORIO, byId.getValue(c.fromId).type)
            assertTrue(byId.getValue(c.toId).type in setOf(SmartObjectType.PRACA, SmartObjectType.HORTA))
            for (k in 1 until c.cells.size) {
                val p = c.cells[k - 1]; val q = c.cells[k]
                assertEquals(1, abs(p % s.widthTiles - q % s.widthTiles) + abs(p / s.widthTiles - q / s.widthTiles))
                assertTrue(TileType.entries[s.tiles[q]].walkable)
            }
            val mask = BackroomsCorridors.wallMask(s.widthTiles, s.tiles, c)
            assertEquals(0, mask.first()); assertEquals(0, mask.last())
            assertTrue(mask.any { it != 0 })
        }
    }
}
