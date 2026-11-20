package com.enhikyy.girassol.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Paleta OLED "Kodak 70 mm": preto absoluto (pixels apagados em OLED, menor consumo 24/7),
 * creme de emulsão, ouro girassol, teal Lumon e o vermelho-alaranjado da halação 500T.
 */
object Lumon {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF0B0B0A)
    val SurfaceHigh = Color(0xFF161512)
    val Outline = Color(0xFF2A2722)
    val Teal = Color(0xFF5FC9BF)
    val TealDim = Color(0xFF24615C)
    val Cream = Color(0xFFEDE3CC)
    val CreamDim = Color(0xFF9A9384)
    val Sunflower = Color(0xFFE7B53C)
    val Danger = Color(0xFFE5603F)
    val Ok = Color(0xFF7BC67E)
    val Violet = Color(0xFF9C8FE0)
    val Halation = Color(0xFFFF5A2A)
    val Glass = Color(0xE60B0B0A)
}

private val scheme = darkColorScheme(
    primary = Lumon.Teal,
    onPrimary = Color(0xFF00201D),
    secondary = Lumon.Sunflower,
    onSecondary = Color(0xFF2A1C00),
    tertiary = Lumon.Violet,
    background = Lumon.Background,
    onBackground = Lumon.Cream,
    surface = Lumon.Surface,
    onSurface = Lumon.Cream,
    surfaceVariant = Lumon.SurfaceHigh,
    onSurfaceVariant = Lumon.CreamDim,
    surfaceContainer = Lumon.Surface,
    surfaceContainerHigh = Lumon.SurfaceHigh,
    outline = Lumon.Outline,
    error = Lumon.Danger
)

private val base = Typography()
private val typography = Typography(
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(letterSpacing = 1.2.sp)
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

@Composable
fun GirassolTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
