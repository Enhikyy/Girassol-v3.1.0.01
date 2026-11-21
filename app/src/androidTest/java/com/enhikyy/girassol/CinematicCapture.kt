package com.enhikyy.girassol

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.enhikyy.girassol.core.world.WorldCore
import com.enhikyy.girassol.core.world.WorldRenderSnapshot
import com.enhikyy.girassol.render3d.CameraMode
import com.enhikyy.girassol.render3d.EffectLevel
import com.enhikyy.girassol.render3d.FilamentCityRenderer
import com.enhikyy.girassol.render3d.WorldPreset
import com.enhikyy.girassol.render3d.WorldViewState
import java.io.File
import java.nio.ByteBuffer

/**
 * Captura cinematográfica da Aba Mundo v4.0 com o pipeline COMPLETO (SSR, SSAO, sombras em
 * cascata + contato, TAA/FXAA, bloom, ACES, IBL 256² e pós `girassol_dehancer_post`).
 *
 * O emulador do CI usa GL por software (SwiftShader), que não sustenta esse pipeline em tempo
 * real; por isso cada quadro é renderizado fora da tela pelo mesmo [FilamentCityRenderer] do app,
 * com espera explícita da GPU, lido com `Renderer.readPixels` e salvo em PNG. O host monta o vídeo
 * com ffmpeg a partir da sequência (15 quadros/s) — nada é desenhado fora do Filament.
 */
object CinematicCapture {
    private const val TAG = "BiosferaCaptura"
    const val W = 540
    const val H = 960
    const val FPS = 15f

    private class Segment(
        val name: String, val preset: WorldPreset, val camera: CameraMode, val day: Float,
        val frames: Int, val dragX: Float = 0f, val dragY: Float = 0f, val stills: Map<Int, String> = emptyMap()
    )

    private val script = listOf(
        Segment("orbita_manha", WorldPreset.URBAN_OVERCAST, CameraMode.ORBIT, 0.36f, 40, dragX = 7f,
            stills = mapOf(20 to "01_biosfera_3d", 39 to "04_orbita")),
        Segment("inclinacao", WorldPreset.URBAN_OVERCAST, CameraMode.TILT, 0.42f, 30, dragX = 4f,
            stills = mapOf(29 to "05_inclinacao")),
        Segment("manha_8x", WorldPreset.URBAN_OVERCAST, CameraMode.ORBIT, 0.33f, 22, dragX = 3f, stills = mapOf(21 to "06_8x_manha")),
        Segment("tarde_8x", WorldPreset.URBAN_OVERCAST, CameraMode.ORBIT, 0.68f, 22, dragX = 3f, stills = mapOf(21 to "07_8x_tarde")),
        Segment("noite_8x", WorldPreset.URBAN_OVERCAST, CameraMode.ORBIT, 0.90f, 26, dragX = 3f, stills = mapOf(25 to "08_8x_noite")),
        Segment("pov_rua", WorldPreset.URBAN_OVERCAST, CameraMode.FIRST_PERSON_POV, 0.62f, 40, dragX = -4f,
            stills = mapOf(18 to "02_cidadaos_andando", 39 to "10_pov_primeira_pessoa")),
        Segment("interior", WorldPreset.LUMON_INTERIOR, CameraMode.TILT, 0.45f, 34, dragX = 5f, stills = mapOf(33 to "11_interior_brutalista")),
        Segment("interior_pov", WorldPreset.LUMON_INTERIOR, CameraMode.FIRST_PERSON_POV, 0.45f, 34, dragX = 5f,
            stills = mapOf(33 to "12_interior_pov"))
    )

    fun run(ctx: Context, mark: (String) -> Unit) {
        val out = File(ctx.getExternalFilesDir(null), "captura").apply { deleteRecursively(); mkdirs() }
        val frames = File(out, "quadros").apply { mkdirs() }
        if (!FilamentCityRenderer.ensureFilament()) { mark("CAPTURA_FALHOU filament"); return }
        // mundo determinístico com cidadãos em movimento (ORCA + navegação do núcleo)
        val core = WorldCore.create(2026L, 12)
        repeat(30) { core.step() }
        val r = FilamentCityRenderer(ctx, null, EffectLevel.FULL)
        var n = 0
        val t0 = System.currentTimeMillis()
        try {
            WorldViewState.setPreset(WorldPreset.URBAN_OVERCAST)
            WorldViewState.setCamera(CameraMode.ORBIT)
            r.initCapture(W, H)
            r.tpsOverride = 4f
            r.update(WorldRenderSnapshot.of(core.state))
            val dt = 1f / FPS
            val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val row = ByteArray(W * 4)
            val flipped = ByteBuffer.allocateDirect(W * H * 4)
            for (seg in script) {
                WorldViewState.setPreset(seg.preset)
                if (seg.camera == CameraMode.FIRST_PERSON_POV) WorldViewState.enterPov(null) else WorldViewState.setCamera(seg.camera)
                val ts = System.currentTimeMillis()
                for (i in 0 until seg.frames) {
                    // 1 tick do núcleo a cada 4 quadros (≈ 4 ticks/s de simulação a 15 quadros/s)
                    if (i % 4 == 0) { core.step(); r.update(WorldRenderSnapshot.of(core.state)) }
                    if (seg.dragX != 0f || seg.dragY != 0f) r.onDrag(seg.dragX, seg.dragY, H.toFloat())
                    val px = r.captureFrame(dt, seg.day, read = true) ?: continue
                    // readPixels: origem embaixo → inverte as linhas
                    flipped.clear()
                    for (y in H - 1 downTo 0) { px.position(y * W * 4); px.get(row); flipped.put(row) }
                    flipped.flip(); bmp.copyPixelsFromBuffer(flipped)
                    File(frames, "f_%04d.png".format(n++)).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    seg.stills[i]?.let { name -> File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                }
                val ms = (System.currentTimeMillis() - ts) / seg.frames.coerceAtLeast(1)
                Log.i(TAG, "segmento ${seg.name}: ${seg.frames} quadros, ${ms} ms/quadro (${seg.preset.name}/${seg.camera.name}/dia ${seg.day})")
                mark("CAPTURA_SEGMENTO ${seg.name} ${ms}ms")
            }
            Log.i(TAG, "captura concluída: $n quadros ${W}x$H em ${(System.currentTimeMillis() - t0) / 1000} s")
        } catch (t: Throwable) {
            Log.e(TAG, "captura interrompida", t)
        } finally {
            r.destroy()
            WorldViewState.setPreset(WorldPreset.URBAN_OVERCAST); WorldViewState.setCamera(CameraMode.ORBIT); WorldViewState.setPovCitizen(null)
        }
        mark("CAPTURA_OK $n ${out.absolutePath}")
    }
}
