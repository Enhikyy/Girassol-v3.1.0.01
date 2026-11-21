package com.enhikyy.girassol.ui.theme

import android.content.Context
import android.util.LruCache
import kotlin.math.round
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.enhikyy.girassol.R
import com.enhikyy.girassol.core.ui.NativeThemes
import com.enhikyy.girassol.core.ui.ThemePalette

/**
 * 15 Temas Nativos (paletas em `core.ui.NativeThemes`, auditadas por WCAG AA >= 4,5:1 sobre as
 * superfícies translúcidas no `ThemeContrastTest`). `Lumon.*` continua sendo a API das telas,
 * agora lida do tema ativo (estado Compose → recomposição ao trocar de tema).
 */
object ThemeController {
    private const val PREFS = "girassol_theme"
    var palette by mutableStateOf(NativeThemes.DEFAULT)
        private set

    fun load(context: Context) {
        palette = NativeThemes.byKey(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("theme", null))
    }

    fun select(context: Context, p: ThemePalette) {
        palette = p
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("theme", p.key).apply()
    }
}

private fun c(argb: Long) = Color(argb.toInt())

object Lumon {
    private val p get() = ThemeController.palette
    val Background get() = c(p.background)
    val Surface get() = c(p.surface or 0xFF000000L)
    val SurfaceHigh get() = c(p.surfaceHigh or 0xFF000000L)
    val Outline get() = c(p.outline)
    val Teal get() = c(p.primary)
    val TealDim get() = c(p.primary).copy(alpha = 0.45f)
    val Cream get() = c(p.text)
    val CreamDim get() = c(p.textDim)
    val Sunflower get() = c(p.secondary)
    val Danger get() = c(p.danger)
    val Ok get() = c(p.ok)
    val Violet get() = c(p.tertiary)
    val Halation = Color(0xFFFF5A2A)
    /** Vidro translúcido sobre o mundo 3D (alfa validado no teste de contraste). */
    val Glass get() = c(p.surface)
    val GlassHigh get() = c(p.surfaceHigh)
}

data class TypographyPreferences(val scale: Float = 1f, val weight: Int = 400, val tracking: Float = 0f, val lineHeight: Float = 1.25f, val preset: String = "kodak")
data class TypographyPreset(val key: String, val label: String, val scale: Float, val weight: Int, val tracking: Float, val lineHeight: Float)
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
object TypographyFontCache {
    private val cache = LruCache<String, FontVariation.Settings>(64)
    private val families = LruCache<Int, FontFamily>(32)
    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    fun settings(weight: Float, width: Float = 100f): FontVariation.Settings {
        val w = (round(weight / 25f) * 25f).coerceIn(300f, 800f)
        val wd = (round(width / 5f) * 5f).coerceIn(75f, 125f)
        val key = "$w:$wd"
        return cache.get(key) ?: FontVariation.Settings(FontVariation.weight(w.toInt()), FontVariation.width(wd)).also { cache.put(key, it) }
    }
    fun family(weight: Float): FontFamily {
        val quantized = (round(weight / 25f) * 25f).toInt().coerceIn(300, 800)
        return families.get(quantized) ?: FontFamily(
            Font(R.font.instrument_sans, FontWeight(quantized), variationSettings = settings(quantized.toFloat()))
        ).also { families.put(quantized, it) }
    }
    fun size(): Int = cache.size()
    fun familySize(): Int = families.size()
}
object TypographyStudioController {
    private const val PREFS = "girassol_typography"
    private val defaults = TypographyPreferences()
    var preferences by mutableStateOf(defaults)
        private set
    private val builtInPresets = listOf(
        TypographyPreset("kodak", "Kodak Editorial", 1.00f, 450, 0.15f, 1.32f),
        TypographyPreset("swiss", "Swiss Modern", 0.98f, 500, -0.10f, 1.20f),
        TypographyPreset("terminal", "Terminal", 0.94f, 450, 0.35f, 1.18f),
        TypographyPreset("reading", "Leitura", 1.12f, 400, 0.05f, 1.48f),
        TypographyPreset("compact", "Compacto", 0.90f, 450, -0.15f, 1.12f),
        TypographyPreset("accessibility", "Acessível", 1.20f, 600, 0.10f, 1.50f)
    )
    private var customPreset by mutableStateOf<TypographyPreset?>(null)
        private set
    val presets: List<TypographyPreset> get() = builtInPresets + listOfNotNull(customPreset)
    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        preferences = TypographyPreferences(
            p.getFloat("scale", defaults.scale).coerceIn(0.85f, 1.30f),
            p.getInt("weight", defaults.weight).coerceIn(300, 700),
            p.getFloat("tracking", defaults.tracking).coerceIn(-0.5f, 1.5f),
            p.getFloat("line_height", defaults.lineHeight).coerceIn(1.1f, 1.6f),
            p.getString("preset", defaults.preset) ?: defaults.preset
        )
        customPreset = if (p.getBoolean("custom_enabled", false)) {
            TypographyPreset("custom", "Meu preset", preferences.scale, preferences.weight, preferences.tracking, preferences.lineHeight)
        } else null
    }
    fun update(context: Context, value: TypographyPreferences, persist: Boolean = true) {
        val safe = value.copy(
            scale = quantize(value.scale, 0.05f, 0.85f, 1.30f),
            weight = (round(value.weight / 50f) * 50f).toInt().coerceIn(300, 700),
            tracking = quantize(value.tracking, 0.1f, -0.5f, 1.5f),
            lineHeight = quantize(value.lineHeight, 0.05f, 1.1f, 1.6f)
        )
        preferences = safe
        if (persist) persist(context)
    }
    fun applyPreset(context: Context, key: String) {
        val p = presets.firstOrNull { it.key == key } ?: return
        update(context, TypographyPreferences(p.scale, p.weight, p.tracking, p.lineHeight, p.key))
    }
    fun saveCustomPreset(context: Context) {
        val p = preferences.copy(preset = "custom")
        preferences = p
        customPreset = TypographyPreset("custom", "Meu preset", p.scale, p.weight, p.tracking, p.lineHeight)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("custom_enabled", true).apply()
        persist(context)
    }
    fun persist(context: Context) {
        val p = preferences
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("scale", p.scale).putInt("weight", p.weight).putFloat("tracking", p.tracking)
            .putFloat("line_height", p.lineHeight).putString("preset", p.preset).apply()
    }
    fun reset(context: Context) = update(context, defaults)
    private fun quantize(v: Float, step: Float, min: Float, max: Float) = (round(v / step) * step).coerceIn(min, max)
}
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun instrument(weight: FontWeight) = Font(R.font.instrument_sans, weight, variationSettings = TypographyFontCache.settings(weight.weight.toFloat()))
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun jetbrains(weight: FontWeight) = Font(R.font.jetbrains_mono, weight, variationSettings = TypographyFontCache.settings(weight.weight.toFloat()))
val InstrumentSans = FontFamily(instrument(FontWeight.Normal), instrument(FontWeight.Medium), instrument(FontWeight.SemiBold), instrument(FontWeight.Bold))
val JetBrainsMono = FontFamily(jetbrains(FontWeight.Normal), jetbrains(FontWeight.Medium), jetbrains(FontWeight.Bold))
private val base = Typography()
private fun TextStyle.ui(p: TypographyPreferences) = copy(
    fontFamily = TypographyFontCache.family(p.weight.toFloat()), fontSize = fontSize * p.scale, fontWeight = FontWeight(p.weight),
    letterSpacing = (letterSpacing.value + p.tracking).sp, lineHeight = lineHeight * p.lineHeight
)
private fun typographyFor(p: TypographyPreferences) = Typography(
    displayLarge = base.displayLarge.ui(p), displayMedium = base.displayMedium.ui(p), displaySmall = base.displaySmall.ui(p),
    headlineLarge = base.headlineLarge.ui(p), headlineMedium = base.headlineMedium.ui(p), headlineSmall = base.headlineSmall.ui(p),
    titleLarge = base.titleLarge.ui(p), titleMedium = base.titleMedium.ui(p), titleSmall = base.titleSmall.ui(p),
    bodyLarge = base.bodyLarge.ui(p), bodyMedium = base.bodyMedium.ui(p), bodySmall = base.bodySmall.ui(p),
    labelLarge = base.labelLarge.ui(p), labelMedium = base.labelMedium.ui(p), labelSmall = base.labelSmall.ui(p)
)
val MonoStyle get() = TextStyle(
    fontFamily = JetBrainsMono, fontSize = 12.sp * TypographyStudioController.preferences.scale,
    fontWeight = FontWeight(TypographyStudioController.preferences.weight),
    letterSpacing = TypographyStudioController.preferences.tracking.sp,
    lineHeight = 16.sp * TypographyStudioController.preferences.lineHeight
)

@Composable
fun GirassolTheme(content: @Composable () -> Unit) {
    val p = ThemeController.palette
    val scheme = if (p.dark) darkColorScheme(
        primary = Lumon.Teal, onPrimary = Lumon.Background, secondary = Lumon.Sunflower, onSecondary = Lumon.Background,
        tertiary = Lumon.Violet, background = Lumon.Background, onBackground = Lumon.Cream, surface = Lumon.Surface,
        onSurface = Lumon.Cream, surfaceVariant = Lumon.SurfaceHigh, onSurfaceVariant = Lumon.CreamDim,
        surfaceContainer = Lumon.Surface, surfaceContainerHigh = Lumon.SurfaceHigh, outline = Lumon.Outline, error = Lumon.Danger
    ) else lightColorScheme(
        primary = Lumon.Teal, onPrimary = Lumon.Background, secondary = Lumon.Sunflower, onSecondary = Lumon.Background,
        tertiary = Lumon.Violet, background = Lumon.Background, onBackground = Lumon.Cream, surface = Lumon.Surface,
        onSurface = Lumon.Cream, surfaceVariant = Lumon.SurfaceHigh, onSurfaceVariant = Lumon.CreamDim,
        surfaceContainer = Lumon.Surface, surfaceContainerHigh = Lumon.SurfaceHigh, outline = Lumon.Outline, error = Lumon.Danger
    )
    MaterialTheme(colorScheme = scheme, typography = typographyFor(TypographyStudioController.preferences), content = content)
}
