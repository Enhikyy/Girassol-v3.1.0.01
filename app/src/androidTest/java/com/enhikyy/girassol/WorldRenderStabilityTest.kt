package com.enhikyy.girassol

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.enhikyy.girassol.core.world.WorldCore
import com.enhikyy.girassol.core.world.WorldRenderSnapshot
import com.enhikyy.girassol.nativebridge.ProceduralAssetsNative
import com.enhikyy.girassol.render3d.CameraMode
import com.enhikyy.girassol.render3d.EffectLevel
import com.enhikyy.girassol.render3d.FilamentCityRenderer
import com.enhikyy.girassol.render3d.WorldPreset
import com.enhikyy.girassol.render3d.WorldViewState
import com.enhikyy.girassol.render3d.isSoftwareGl
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * v4.0 — estabilidade da Aba Mundo no aparelho/emulador:
 *  1. gerações JNI repetidas (texturas 1024²/512² e IBL) não vazam heap nativo;
 *  2. o renderizador Filament sobrevive a trocas de ambiente (Urbano ↔ Interior) e de câmera
 *     (Órbita / Inclinação / POV / 2D) com o motor vivo, em ciclos completos de criação/destruição.
 */
@RunWith(AndroidJUnit4::class)
class WorldRenderStabilityTest {
    private val mb = 1024L * 1024L

    @After
    fun resetView() {
        WorldViewState.setPreset(WorldPreset.URBAN_OVERCAST)
        WorldViewState.setCamera(CameraMode.ORBIT)
        WorldViewState.setPovCitizen(null)
    }

    private fun direct(bytes: Long): ByteBuffer = ByteBuffer.allocateDirect(bytes.toInt()).order(ByteOrder.nativeOrder())

    private fun settleHeap(): Long {
        repeat(3) { System.gc(); System.runFinalization(); Thread.sleep(50) }
        return Debug.getNativeHeapAllocatedSize()
    }

    @Test
    fun repeatedNativeGenerationDoesNotLeak() {
        assumeTrue("biblioteca nativa ausente", ProceduralAssetsNative.isLoaded)
        val size = 512
        val chain = ProceduralAssetsNative.nativeMipChainBytes(size)
        assertTrue(chain > size.toLong() * size * 4)
        val albedo = direct(size.toLong() * size * 4); val emissive = direct(size.toLong() * size * 4)
        val orm = direct(chain); val normal = direct(chain)
        val face = 32
        val cube = direct(ProceduralAssetsNative.nativeCubemapBytes(face))
        val sh = FloatArray(27)
        fun round(i: Int) {
            for (k in 0 until ProceduralAssetsNative.SURFACE_COUNT) {
                assertTrue(ProceduralAssetsNative.nativeGenerateSurface(k, size, 7L + i, albedo, orm, normal, emissive))
            }
            for (p in 0..3) assertTrue(ProceduralAssetsNative.nativeGenerateIbl(p, 0.3f, 0.6f, 0.2f, 0.7f, face, 16, cube, sh))
        }
        round(0) // aquecimento (pools do alocador, TLS)
        val before = settleHeap()
        for (i in 1..6) round(i)
        val delta = settleHeap() - before
        android.util.Log.i("WorldStability", "delta heap nativo (6 rodadas JNI) = ${delta / 1024} KiB")
        assertTrue("heap nativo cresceu ${delta / 1024} KiB", delta < 4 * mb)
        // determinismo: mesma semente → mesmos bytes
        ProceduralAssetsNative.nativeGenerateSurface(ProceduralAssetsNative.WET_ASPHALT, size, 99L, albedo, orm, normal, emissive)
        val a = ByteArray(4096).also { albedo.rewind(); albedo.get(it) }
        ProceduralAssetsNative.nativeGenerateSurface(ProceduralAssetsNative.WET_ASPHALT, size, 99L, albedo, orm, normal, emissive)
        val b = ByteArray(4096).also { albedo.rewind(); albedo.get(it) }
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun rendererSurvivesPresetAndCameraSwitching() {
        assumeTrue("Filament indisponível", FilamentCityRenderer.ensureFilament())
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val snap = WorldRenderSnapshot.of(WorldCore.create(42L, 6).state)
        val combos = listOf(
            WorldPreset.URBAN_OVERCAST to CameraMode.ORBIT,
            WorldPreset.URBAN_OVERCAST to CameraMode.TILT,
            WorldPreset.URBAN_OVERCAST to CameraMode.FIRST_PERSON_POV,
            WorldPreset.LUMON_INTERIOR to CameraMode.FIRST_PERSON_POV,
            WorldPreset.LUMON_INTERIOR to CameraMode.ORBIT,
            WorldPreset.LUMON_INTERIOR to CameraMode.TOP_2D,
            WorldPreset.URBAN_OVERCAST to CameraMode.TILT
        )
        // SwiftShader (emulador) não aguenta o pipeline completo em laço: lá o teste cobre cena,
        // materiais, IBL, câmeras e trocas de ambiente no nível mínimo; em GPU real, no completo.
        val level = if (isSoftwareGl()) EffectLevel.MINIMAL else EffectLevel.FULL
        val heaps = ArrayList<Long>()
        for (cycle in 0 until 3) {
            val r = FilamentCityRenderer(ctx, null, level)
            try {
                r.initHeadless(160, 120)
                r.tpsOverride = 4f
                for ((preset, cam) in combos) {
                    WorldViewState.setPreset(preset)
                    WorldViewState.setCamera(cam)
                    r.update(snap)
                    val before = r.capturedFrames
                    repeat(2) { r.captureFrame(0.05f, 0.45f, read = false) }
                    assertTrue("quadros concluídos ($preset/$cam)", r.capturedFrames == before + 2)
                    val pose = r.currentPose()
                    assertTrue("pose finita ($preset/$cam)", pose.eye.x.isFinite() && pose.eye.y.isFinite() && pose.eye.z.isFinite())
                }
            } finally {
                r.destroy()
            }
            heaps += settleHeap()
            android.util.Log.i("WorldStability", "ciclo $cycle (${level.name}) concluído")
        }
        val growth = heaps.last() - heaps.first()
        android.util.Log.i("WorldStability", "heap nativo por ciclo = ${heaps.map { it / mb }} MiB (Δ ${growth / 1024} KiB)")
        assertTrue("heap nativo cresceu ${growth / mb} MiB entre ciclos", growth < 24 * mb)
    }
}
