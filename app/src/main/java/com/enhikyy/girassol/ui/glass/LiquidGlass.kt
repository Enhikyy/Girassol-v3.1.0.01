package com.enhikyy.girassol.ui.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.enhikyy.girassol.ui.theme.Lumon
import com.enhikyy.girassol.ui.theme.ThemeController
import com.enhikyy.girassol.ui.glass.GlassPerformanceController
import com.enhikyy.girassol.ui.glass.GlassTier
import com.enhikyy.girassol.ui.glass.LocalGlassTier

/**
 * v3.9 — Liquid Glass nativo.
 *
 * Técnica (a mesma de bibliotecas como Haze): o conteúdo da tela é gravado num [GraphicsLayer]
 * (um RenderNode). Cada superfície de vidro desenha esse mesmo RenderNode, deslocado para a sua
 * posição, dentro de uma camada própria com `RenderEffect` = desfoque gaussiano encadeado a um
 * `RuntimeShader` AGSL que refrata as bordas (lente + leve dispersão cromática). Como RenderNodes
 * são referenciados e não copiados, o vidro acompanha o conteúdo de trás a cada quadro sem
 * regravação. Por cima: gradiente translúcido em camadas, borda especular direcional de 1 dp e
 * sombra ambiente profunda. minSdk 33 ⇒ AGSL sempre disponível.
 */

/** Molas padrão de toda a interface (Liquid Glass). */
object GlassMotion {
    const val DAMPING = 0.82f
    const val STIFFNESS = 360f
    fun <T> spec(): SpringSpec<T> = spring(dampingRatio = DAMPING, stiffness = STIFFNESS)
    @Suppress("unused") val NoBounce = Spring.DampingRatioNoBouncy
}

@Stable
class GlassBackdrop internal constructor() {
    internal var layer: GraphicsLayer? = null
    internal var origin by mutableStateOf(Offset.Zero)
}

val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = if (GlassPerformanceController.tier != GlassTier.LOW) rememberGraphicsLayer() else null
    return remember { GlassBackdrop() }.also { it.layer = layer }
}

/** Marca o conteúdo que fica ATRÁS do vidro. Nunca coloque superfícies com backdrop dentro dele. */
fun Modifier.glassBackdropSource(backdrop: GlassBackdrop): Modifier = this
    .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
    .drawWithContent {
        if (GlassPerformanceController.tier == GlassTier.LOW) { drawContent(); return@drawWithContent }
        val l = backdrop.layer
        if (l == null) { drawContent(); return@drawWithContent }
        l.record(size = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))) {
            this@drawWithContent.drawContent()
        }
        drawLayer(l)
    }

/** Host: [background] vira fonte do vidro; [content] fica por cima e pode usar `liquidGlass`. */
@Composable
fun GlassHost(
    modifier: Modifier = Modifier,
    background: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val backdrop = rememberGlassBackdrop()
    CompositionLocalProvider(LocalGlassTier provides GlassPerformanceController.tier) {
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
            Box(modifier) {
                Box(Modifier.matchParentSize().glassBackdropSource(backdrop)) {
                    CompositionLocalProvider(LocalGlassBackdrop provides null) { background() }
                }
                content()
            }
        }
    }
}

/** Intensidade visual do vidro. */
enum class GlassTone { CLEAR, REGULAR, DENSE }

private const val REFRACTION_AGSL = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float band;
uniform float strength;

float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r, r);
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0, 0.0))) - r;
}

half4 main(float2 coord) {
    float2 c = size * 0.5;
    float2 p = coord - c;
    float d = sdRoundRect(p, c, radius);
    float t = clamp(1.0 + d / band, 0.0, 1.0);
    float2 e = float2(1.0, 0.0);
    float2 g = float2(
        sdRoundRect(p + e.xy, c, radius) - sdRoundRect(p - e.xy, c, radius),
        sdRoundRect(p + e.yx, c, radius) - sdRoundRect(p - e.yx, c, radius));
    float gl = max(length(g), 0.0001);
    g = g / gl;
    float k = t * t * strength;
    float2 uv = coord - g * k;
    half4 base = content.eval(uv);
    half r = content.eval(uv - g * k * 0.12).r;
    half b = content.eval(uv + g * k * 0.12).b;
    return half4(r, base.g, b, base.a);
}
"""

private fun cornerRadiusOf(outline: Outline, size: Size): Float = when (outline) {
    is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x.coerceAtMost(minOf(size.width, size.height) / 2f)
    is Outline.Rectangle -> 0f
    is Outline.Generic -> minOf(size.width, size.height) / 2f
}

/**
 * Superfície Liquid Glass. Com [backdrop] = true e um [GlassHost] acima, refrata e desfoca o
 * conteúdo real atrás; sem host (ex.: cartões dentro de listas) cai para o vidro "sólido"
 * translúcido com a mesma borda especular e sombra — nunca desenha a si mesmo recursivamente.
 */
fun Modifier.liquidGlass(
    shape: Shape = CircleShape,
    tone: GlassTone = GlassTone.REGULAR,
    elevation: Dp = 16.dp,
    blur: Dp = 18.dp,
    refraction: Dp = 10.dp,
    tint: Color = Color.Unspecified,
    backdrop: Boolean = true
): Modifier = composed {
    val tier = LocalGlassTier.current
    val requestedHost = if (backdrop && !LocalInspectionMode.current) LocalGlassBackdrop.current else null
    val host = requestedHost.takeIf { tier != GlassTier.LOW }
    val glassLayer = if (tier != GlassTier.LOW) rememberGraphicsLayer() else null
    val holder = remember { PositionHolder() }
    val dark = ThemeController.palette.dark
    val base = if (tint != Color.Unspecified) tint else Lumon.Surface
    val alphaTop = when (tone) { GlassTone.CLEAR -> 0.22f; GlassTone.REGULAR -> 0.42f; GlassTone.DENSE -> 0.70f }
    val alphaBottom = when (tone) { GlassTone.CLEAR -> 0.12f; GlassTone.REGULAR -> 0.28f; GlassTone.DENSE -> 0.58f }
    // sem backdrop real, o vidro precisa de mais corpo para manter contraste
    val boost = if (host == null) 0.22f else 0f
    val sheen = if (dark) Color.White else Color(0xFFFFFFFF)
    val shade = if (dark) Color.Black else Color(0xFF1B1B1F)
    this
        .shadow(if (tier == GlassTier.LOW) 0.dp else elevation, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.55f), spotColor = Color.Black.copy(alpha = 0.65f))
        .then(if (host != null) Modifier.onGloballyPositioned { holder.pos = it.positionInRoot() } else Modifier)
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = Path().apply {
                when (outline) {
                    is Outline.Rectangle -> addRect(outline.rect)
                    is Outline.Rounded -> addRoundRect(outline.roundRect)
                    is Outline.Generic -> addPath(outline.path)
                }
            }
            val radius = cornerRadiusOf(outline, size)
            val strokePx = if (tier == GlassTier.LOW) 1f else 1.dp.toPx()
            if (host != null && glassLayer != null && size.width >= 1f && size.height >= 1f) {
                val blurPx = (if (tier == GlassTier.MID) blur.value.coerceAtMost(10f) else blur.value).dp.toPx()
                val blurFx = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
                glassLayer.renderEffect = if (tier == GlassTier.HIGH) {
                    val shader = RuntimeShader(REFRACTION_AGSL).apply {
                        setFloatUniform("size", size.width, size.height)
                        setFloatUniform("radius", radius)
                        setFloatUniform("band", (refraction.toPx() * 2.2f).coerceAtLeast(1f))
                        setFloatUniform("strength", refraction.toPx())
                    }
                    val refractFx = RenderEffect.createRuntimeShaderEffect(shader, "content")
                    RenderEffect.createChainEffect(refractFx, blurFx).asComposeRenderEffect()
                } else blurFx.asComposeRenderEffect()
            }
            val fill = if (tier == GlassTier.LOW) {
                val oled = if (dark) Color.Black else base
                Brush.verticalGradient(0f to oled.copy(alpha = 0.58f), 0.55f to oled.copy(alpha = 0.48f), 1f to oled.copy(alpha = 0.38f))
            } else Brush.verticalGradient(
                0f to base.copy(alpha = (alphaTop + boost).coerceAtMost(0.94f)),
                0.55f to base.copy(alpha = ((alphaTop + alphaBottom) / 2 + boost).coerceAtMost(0.94f)),
                1f to base.copy(alpha = (alphaBottom + boost).coerceAtMost(0.94f))
            )
            val gloss = Brush.verticalGradient(
                0f to sheen.copy(alpha = if (dark) 0.10f else 0.30f),
                0.45f to Color.Transparent,
                1f to shade.copy(alpha = if (dark) 0.10f else 0.04f)
            )
            // borda especular direcional: luz vinda do alto à esquerda
            val rim = Brush.linearGradient(
                0f to sheen.copy(alpha = 0.62f),
                0.35f to sheen.copy(alpha = 0.10f),
                0.7f to sheen.copy(alpha = 0.05f),
                1f to sheen.copy(alpha = 0.30f),
                start = Offset.Zero, end = Offset(size.width, size.height)
            )
            onDrawWithContent {
                val src = host?.layer
                if (host != null && src != null && glassLayer != null) {
                    val off = holder.pos - host.origin
                    glassLayer.record(size = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))) {
                        translate(-off.x, -off.y) { drawLayer(src) }
                    }
                    clipPath(path) { drawLayer(glassLayer) }
                }
                drawPath(path, fill)
                drawPath(path, gloss)
                drawPath(path, rim, style = Stroke(width = strokePx))
                drawContent()
            }
        }
}

private class PositionHolder {
    var pos by mutableStateOf(Offset.Zero)
}

/** Superfície pronta (Box) com vidro. */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    tone: GlassTone = GlassTone.REGULAR,
    elevation: Dp = 16.dp,
    backdrop: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier.liquidGlass(shape = shape, tone = tone, elevation = elevation, backdrop = backdrop), content = content)
}
