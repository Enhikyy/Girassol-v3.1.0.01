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

/** Paleta inspirada na estética "Lumon": petróleo profundo, creme e acentos de girassol. */
object Lumon {
    val Background = Color(0xFF0B1A1E)
    val Surface = Color(0xFF12262B)
    val SurfaceHigh = Color(0xFF1A3238)
    val Outline = Color(0xFF2C4A50)
    val Teal = Color(0xFF5FC9BF)
    val TealDim = Color(0xFF2F7F78)
    val Cream = Color(0xFFE8DFC8)
    val CreamDim = Color(0xFFA9A28F)
    val Sunflower = Color(0xFFE7B53C)
    val Danger = Color(0xFFE06B5F)
    val Ok = Color(0xFF7BC67E)
    val Violet = Color(0xFF9C8FE0)
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
