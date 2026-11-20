package com.enhikyy.girassol.render3d

import android.graphics.Bitmap
import android.util.Log
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.enhikyy.girassol.core.world.WorldRenderSnapshot
import kotlinx.coroutines.delay
import java.util.SplittableRandom

/** Posições em pixels (coordenadas da área do mundo) dos cidadãos visíveis — usado pelos balões e pelos testes de instrumentação. */
object WorldPickRegistry {
    @Volatile var mode: String = "2D"
    @Volatile var positions: Map<String, Offset> = emptyMap()
}

/**
 * Viewport 3D (Filament num SurfaceView) com câmera orbital: 1 dedo orbita, 2 dedos
 * deslocam, pinça aproxima, giro gira. Toque seleciona um cidadão por raycast 3D.
 * [onFailure] é chamado se o dispositivo não conseguir iniciar o Filament (o chamador cai no Modo Eco 2D).
 */
@Composable
fun Filament3DView(
    snapshot: WorldRenderSnapshot,
    onPick: (String?) -> Unit,
    onFailure: (String) -> Unit,
    onProjected: (Map<String, Offset>) -> Unit,
    modifier: Modifier = Modifier
) {
    var renderer by remember { mutableStateOf<FilamentCityRenderer?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var frameTick by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> renderer?.resume()
                Lifecycle.Event.ON_PAUSE -> renderer?.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            renderer?.destroy()
            renderer = null
            WorldPickRegistry.positions = emptyMap()
        }
    }

    LaunchedEffect(snapshot, renderer) { renderer?.update(snapshot) }
    LaunchedEffect(frameTick, renderer) {
        val r = renderer ?: return@LaunchedEffect
        val (w, h) = r.viewSize()
        val out = HashMap<String, Offset>()
        for ((id, p) in r.agentPositions()) {
            val s = r.camera.project(V3(p.x, p.y + 1.55f, p.z), w, h) ?: continue
            if (s.first in -40f..(w + 40f) && s.second in -40f..(h + 40f)) out[id] = Offset(s.first, s.second)
        }
        WorldPickRegistry.mode = "3D"
        WorldPickRegistry.positions = out
        onProjected(out)
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize().testTag("world_viewport_3d"),
            factory = { ctx ->
                SurfaceView(ctx).also { sv ->
                    val r = FilamentCityRenderer(ctx, sv)
                    try {
                        r.init()
                        var n = 0
                        r.onFrame = { if (++n % 3 == 0) frameTick++ }
                        r.update(snapshot)
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) r.resume()
                        renderer = r
                    } catch (t: Throwable) {
                        Log.w("GirassolFilament", "falha ao iniciar 3D", t)
                        Render3dTelemetry.set(Render3dStats(active = false, error = t.message ?: t.javaClass.simpleName))
                        runCatching { r.destroy() }
                        onFailure(t.message ?: t.javaClass.simpleName)
                    }
                }
            }
        )
        FilmGrainOverlay(Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize()
                .pointerInput(renderer) {
                    val r = renderer ?: return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            val pan = event.calculatePan()
                            if (pressed >= 2) {
                                r.camera.zoom(event.calculateZoom())
                                r.camera.rotate(event.calculateRotation())
                                r.camera.pan(pan.x, pan.y, size.height.toFloat())
                            } else if (pressed == 1) {
                                r.camera.orbit(pan.x, pan.y, size.height.toFloat())
                            }
                            if (pan.getDistance() > 0.5f) event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(renderer) {
                    val r = renderer ?: return@pointerInput
                    detectTapGestures { p -> onPick(r.pick(p.x, p.y)) }
                }
        )
    }
}

/** Granulação de filme 500T: ruído pré-gerado, deslocado ~12×/s, em modo Overlay (preserva o preto OLED). */
@Composable
fun FilmGrainOverlay(modifier: Modifier = Modifier, alpha: Float = 0.22f) {
    val noise: ImageBitmap = remember { grainBitmap(128, 2026L) }
    val brush = remember(noise) { ShaderBrush(ImageShader(noise, TileMode.Repeated, TileMode.Repeated)) }
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(83); frame++ } }
    Canvas(modifier) {
        val f = frame
        val ox = ((f * 37) % 128).toFloat(); val oy = ((f * 71) % 128).toFloat()
        translate(-ox, -oy) {
            drawRect(brush, topLeft = Offset.Zero, size = androidx.compose.ui.geometry.Size(size.width + 128f, size.height + 128f), alpha = alpha,
                blendMode = androidx.compose.ui.graphics.BlendMode.Overlay)
        }
    }
}

private fun grainBitmap(n: Int, seed: Long): ImageBitmap {
    val rnd = SplittableRandom(seed)
    val px = IntArray(n * n)
    for (i in px.indices) {
        // ruído aproximadamente gaussiano (soma de 3 uniformes), levemente quente
        val v = ((rnd.nextInt(256) + rnd.nextInt(256) + rnd.nextInt(256)) / 3).coerceIn(0, 255)
        val r = v; val g = (v * 0.96f).toInt(); val b = (v * 0.88f).toInt()
        px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
}
