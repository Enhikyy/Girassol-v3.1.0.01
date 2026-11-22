package com.enhikyy.girassol

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.enhikyy.girassol.core.world.WorldCore
import com.enhikyy.girassol.core.world.WorldRenderSnapshot
import com.enhikyy.girassol.render3d.WorldGpu
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
    const val W = 360
    const val H = 640
    const val FPS = 15f

    private class Segment(
        val name: String, val preset: WorldPreset, val camera: CameraMode, val day: Float,
        val frames: Int, val dragX: Float = 0f, val dragY: Float = 0f, val stills: Map<Int, String> = emptyMap()
    )

    /**
     * v4.1 — roteiro: Dia, Entardecer, Noite, POV na rua, Interior Severance (+ POV no salão).
     * Fração do dia: 0,36 manhã · 0,74 entardecer (TimePhase.DUSK) · 0,90 noite.
     */
    private val script = listOf(
        Segment("dia", WorldPreset.URBAN_OVERCAST, CameraMode.ORBIT, 0.36f, 26, dragX = 7f,
            stills = mapOf(14 to "01_dia", 25 to "01b_dia_orbita")),
        Segment("inclinacao", WorldPreset.URBAN_OVERCAST, CameraMode.TILT, 0.42f, 18, dragX = 4f,
            stills = mapOf(17 to "01c_dia_inclinacao")),
        Segment("entardecer", WorldPreset.URBAN_OVERCAST, CameraMode.ORBIT, 0.74f, 18, dragX = 3f,
            stills = mapOf(17 to "02_entardecer")),
        Segment("noite", WorldPreset.URBAN_OVERCAST, CameraMode.ORBIT, 0.90f, 18, dragX = 3f,
            stills = mapOf(17 to "03_noite")),
        Segment("pov_rua", WorldPreset.URBAN_OVERCAST, CameraMode.FIRST_PERSON_POV, 0.62f, 26, dragX = -4f,
            stills = mapOf(12 to "04b_cidadaos_andando", 25 to "04_pov")),
        Segment("interior", WorldPreset.LUMON_INTERIOR, CameraMode.TILT, 0.45f, 22, dragX = 5f,
            stills = mapOf(21 to "05_interior_severance")),
        Segment("interior_pov", WorldPreset.LUMON_INTERIOR, CameraMode.FIRST_PERSON_POV, 0.45f, 22, dragX = 5f,
            stills = mapOf(21 to "05b_interior_pov"))
    )

    fun run(ctx: Context, level: EffectLevel, mark: (String) -> Unit) {
        val tag = level.name.lowercase()
        val out = File(ctx.getExternalFilesDir(null), "captura_$tag").apply { deleteRecursively(); mkdirs() }
        val frames = File(out, "quadros").apply { mkdirs() }
        if (!FilamentCityRenderer.ensureFilament()) { mark("CAPTURA_OK_$tag 0 -"); return }
        // mundo determinístico com cidadãos em movimento (ORCA + navegação do núcleo)
        val core = WorldCore.create(2026L, 12)
        repeat(30) { core.step() }
        val r = FilamentCityRenderer(ctx, null, level)
        var n = 0
        val t0 = System.currentTimeMillis()
        try {
            WorldViewState.setPreset(WorldPreset.URBAN_OVERCAST)
            WorldViewState.setCamera(CameraMode.ORBIT)
            // emulador (SwiftShader): o envio de texturas derruba o tradutor GL; bisseção por nível —
            // mínimo sem superfícies nem IBL, reduzido com IBL SH3, completo com tudo
            if (com.enhikyy.girassol.render3d.isSoftwareGl()) {
                WorldGpu.allowSurfaces = level == EffectLevel.FULL
                WorldGpu.allowIbl = level != EffectLevel.MINIMAL
            }
            mark("CAPTURA_INICIO_$tag superficies=${WorldGpu.allowSurfaces} ibl=${WorldGpu.allowIbl}")
            r.initCapture(W, H)
            r.tpsOverride = 4f
            r.update(WorldRenderSnapshot.of(core.state))
            val dt = 1f / FPS
            val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val row = ByteArray(W * 4)
            val flipped = ByteBuffer.allocateDirect(W * H * 4)
            // com o pós do Filament ligado o readPixels sai com origem embaixo; no SwiftShader (pós
            // desligado, cena direto no alvo de captura) o quadro já sai de cima para baixo
            val flipRows = !com.enhikyy.girassol.render3d.isSoftwareGl()
            for (seg in script) {
                WorldViewState.setPreset(seg.preset)
                if (seg.camera == CameraMode.FIRST_PERSON_POV) WorldViewState.enterPov(null) else WorldViewState.setCamera(seg.camera)
                val ts = System.currentTimeMillis()
                for (i in 0 until seg.frames) {
                    // 1 tick do núcleo a cada 4 quadros (≈ 4 ticks/s de simulação a 15 quadros/s)
                    if (i % 4 == 0) { core.step(); r.update(WorldRenderSnapshot.of(core.state)) }
                    if (seg.dragX != 0f || seg.dragY != 0f) r.onDrag(seg.dragX, seg.dragY, H.toFloat())
                    val tf = System.currentTimeMillis()
                    val px = r.captureFrame(dt, seg.day, read = true) ?: continue
                    if (n < 3 || n % 10 == 0) Log.i(TAG, "[$tag] quadro $n: ${System.currentTimeMillis() - tf} ms")
                    // readPixels: origem embaixo → inverte as linhas (exceto SwiftShader, ver flipRows)
                    flipped.clear()
                    for (k in 0 until H) { val y = if (flipRows) H - 1 - k else k; px.position(y * W * 4); px.get(row); flipped.put(row) }
                    flipped.flip(); bmp.copyPixelsFromBuffer(flipped)
                    File(frames, "f_%04d.png".format(n++)).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    seg.stills[i]?.let { name ->
                        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        mark("CAPTURA_MEDIA $tag $name ${meanRgb(px)}")
                        // diagnóstico: o mesmo quadro sem a passada analógica (dehancer) — isola cena × pós
                        if (name == "01_dia" || name == "05_interior_severance") {
                            r.setPostBypass(true)
                            r.captureFrame(0f, seg.day, read = true)?.let { raw ->
                                mark("CAPTURA_MEDIA $tag ${name}_sem_pos ${meanRgb(raw)}")
                                flipped.clear()
                                for (k in 0 until H) { val y = if (flipRows) H - 1 - k else k; raw.position(y * W * 4); raw.get(row); flipped.put(row) }
                                flipped.flip(); bmp.copyPixelsFromBuffer(flipped)
                                File(out, "diag_${name}_sem_pos.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            }
                            r.setPostBypass(false)
                        }
                    }
                }
                val ms = (System.currentTimeMillis() - ts) / seg.frames.coerceAtLeast(1)
                Log.i(TAG, "[$tag] segmento ${seg.name}: ${seg.frames} quadros, ${ms} ms/quadro (${seg.preset.name}/${seg.camera.name}/dia ${seg.day})")
                mark("CAPTURA_SEGMENTO $tag ${seg.name} ${ms}ms")
            }
            Log.i(TAG, "[$tag] captura concluída: $n quadros ${W}x$H em ${(System.currentTimeMillis() - t0) / 1000} s")
        } catch (t: Throwable) {
            Log.e(TAG, "captura interrompida", t)
        } finally {
            r.destroy()
            WorldViewState.setPreset(WorldPreset.URBAN_OVERCAST); WorldViewState.setCamera(CameraMode.ORBIT); WorldViewState.setPovCitizen(null)
        }
        mark("CAPTURA_OK_$tag $n ${out.absolutePath}")
    }

    /** Média RGB (0–255) de um quadro RGBA8 — detecta quadros estourados/pretos no log. */
    private fun meanRgb(px: ByteBuffer): String {
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        var i = 0
        val lim = px.limit()
        while (i + 3 < lim) { r += px.get(i).toInt() and 0xff; g += px.get(i + 1).toInt() and 0xff; b += px.get(i + 2).toInt() and 0xff; n++; i += 4 * 7 }
        return if (n == 0L) "0 0 0" else "${r / n} ${g / n} ${b / n}"
    }
}
