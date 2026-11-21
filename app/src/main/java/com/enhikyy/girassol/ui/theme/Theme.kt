package com.enhikyy.girassol.ui.theme

import android.content.Context
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

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun instrument(weight: FontWeight) =
    Font(R.font.instrument_sans, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight), FontVariation.width(100f)))

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun jetbrains(weight: FontWeight) =
    Font(R.font.jetbrains_mono, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

/** Instrument Sans (UI) e JetBrains Mono (telemetria/terminais), ambas OFL 1.1, variáveis. */
val InstrumentSans = FontFamily(instrument(FontWeight.Normal), instrument(FontWeight.Medium), instrument(FontWeight.SemiBold), instrument(FontWeight.Bold))
val JetBrainsMono = FontFamily(jetbrains(FontWeight.Normal), jetbrains(FontWeight.Medium), jetbrains(FontWeight.Bold))

private val base = Typography()
private fun TextStyle.ui() = copy(fontFamily = InstrumentSans)
private val typography = Typography(
    displayLarge = base.displayLarge.ui(), displayMedium = base.displayMedium.ui(), displaySmall = base.displaySmall.ui(),
    headlineLarge = base.headlineLarge.ui(), headlineMedium = base.headlineMedium.ui(), headlineSmall = base.headlineSmall.ui(),
    titleLarge = base.titleLarge.ui().copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
    titleMedium = base.titleMedium.ui().copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.ui(),
    bodyLarge = base.bodyLarge.ui(), bodyMedium = base.bodyMedium.ui(), bodySmall = base.bodySmall.ui(),
    labelLarge = base.labelLarge.ui(), labelMedium = base.labelMedium.ui(),
    labelSmall = base.labelSmall.ui().copy(letterSpacing = 1.2.sp)
)

val MonoStyle get() = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp)

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
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
