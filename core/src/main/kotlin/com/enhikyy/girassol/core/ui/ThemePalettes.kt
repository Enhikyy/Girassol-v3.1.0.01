package com.enhikyy.girassol.core.ui

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Paleta de um dos 15 Temas Nativos (ARGB). `surface`/`surfaceHigh` são translúcidas (vidro
 * sobre o mundo 3D); todo papel de texto (`text`, `textDim`, `primary`, `secondary`,
 * `tertiary`, `danger`, `ok`) precisa de contraste WCAG 2.x AA >= 4,5:1 sobre essas superfícies
 * compostas no pior fundo possível — ver [Wcag.worstCaseContrast].
 */
data class ThemePalette(
    val key: String,
    val label: String,
    val dark: Boolean,
    val background: Long,
    val surface: Long,
    val surfaceHigh: Long,
    val outline: Long,
    val text: Long,
    val textDim: Long,
    val primary: Long,
    val secondary: Long,
    val tertiary: Long,
    val danger: Long,
    val ok: Long
) {
    /** Papéis usados como cor de tipografia (Instrument Sans / JetBrains Mono). */
    fun textRoles(): Map<String, Long> = linkedMapOf(
        "text" to text, "textDim" to textDim, "primary" to primary, "secondary" to secondary,
        "tertiary" to tertiary, "danger" to danger, "ok" to ok
    )
}

/** Contraste WCAG 2.x (luminância relativa sRGB) com composição alfa sobre o fundo. */
object Wcag {
    const val AA_NORMAL = 4.5

    private fun channel(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(rgb: Long): Double {
        val r = ((rgb shr 16) and 0xFF).toInt(); val g = ((rgb shr 8) and 0xFF).toInt(); val b = (rgb and 0xFF).toInt()
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    /** Compõe uma cor ARGB (alfa < 1) sobre um fundo opaco; arredonda como o compositor (8 bits). */
    fun composite(argb: Long, backdrop: Long): Long {
        val a = ((argb ushr 24) and 0xFF) / 255.0
        fun ch(shift: Int): Long {
            val f = (argb shr shift) and 0xFF; val b = (backdrop shr shift) and 0xFF
            return Math.round(f * a + b * (1 - a)).coerceIn(0, 255)
        }
        return 0xFF000000L or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun contrast(fg: Long, bg: Long): Double {
        val l1 = luminance(fg); val l2 = luminance(bg)
        return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
    }

    /** Fundos possíveis atrás do vidro: o fundo do tema, preto puro e branco puro (cena 3D). */
    fun backdrops(p: ThemePalette) = listOf(p.background, 0xFF000000L, 0xFFFFFFFFL)

    /** Pior contraste do texto sobre uma superfície translúcida em qualquer fundo. */
    fun worstCaseContrast(text: Long, surface: Long, p: ThemePalette): Double =
        backdrops(p).minOf { bd -> contrast(composite(text, 0xFF000000L or bd), composite(surface, bd)) }

    data class Check(val theme: String, val role: String, val surface: String, val ratio: Double) {
        val passesAA get() = ratio >= AA_NORMAL
    }

    fun audit(p: ThemePalette): List<Check> = p.textRoles().flatMap { (role, c) ->
        listOf("surface" to p.surface, "surfaceHigh" to p.surfaceHigh, "background" to (0xFF000000L or p.background)).map { (sn, s) ->
            Check(p.key, role, sn, worstCaseContrast(c, s, p))
        }
    }
}

object NativeThemes {
    val ALL: List<ThemePalette> = listOf(
        ThemePalette("LUMON_OLED", "Lumon OLED", dark = true,
            background = 0xFF000000, surface = 0xE60B0B0A, surfaceHigh = 0xF0161512, outline = 0xFF2A2722,
            text = 0xFFEDE3CC, textDim = 0xFF9A9384, primary = 0xFF5FC9BF, secondary = 0xFFE7B53C,
            tertiary = 0xFF9C8FE0, danger = 0xFFE66343, ok = 0xFF7BC67E),
        ThemePalette("KODAK_500T", "OLED Kodak 70mm", dark = true,
            background = 0xFF000000, surface = 0xE6140C08, surfaceHigh = 0xF01E140E, outline = 0xFF3A2A1E,
            text = 0xFFF3E2C8, textDim = 0xFFB49A80, primary = 0xFFFF7A4A, secondary = 0xFFE7B53C,
            tertiary = 0xFFD9A3FF, danger = 0xFFFF6040, ok = 0xFF8FD18F),
        ThemePalette("MODERNISMO_TROPICAL", "Modernismo Tropical", dark = true,
            background = 0xFF04100B, surface = 0xE60A1A13, surfaceHigh = 0xF012241B, outline = 0xFF24402F,
            text = 0xFFEAF2DF, textDim = 0xFF9FB59E, primary = 0xFF7FD6A3, secondary = 0xFFF2C14E,
            tertiary = 0xFFE58FB0, danger = 0xFFFF7A5C, ok = 0xFF9BE07F),
        ThemePalette("BACKROOMS", "Backrooms", dark = true,
            background = 0xFF0E0C04, surface = 0xE61B1808, surfaceHigh = 0xF026220C, outline = 0xFF48401A,
            text = 0xFFF4EBC0, textDim = 0xFFBDB281, primary = 0xFFE8D25C, secondary = 0xFFF0A848,
            tertiary = 0xFFA3C6FF, danger = 0xFFFF6A4A, ok = 0xFFA6D982),
        ThemePalette("SEVERANCE_CRT", "Severance CRT", dark = true,
            background = 0xFF000805, surface = 0xE6031410, surfaceHigh = 0xF0071F18, outline = 0xFF123A2E,
            text = 0xFFC8F5E2, textDim = 0xFF7FB8A2, primary = 0xFF4FE0B0, secondary = 0xFFE7C65C,
            tertiary = 0xFF8FB8FF, danger = 0xFFFF6E5A, ok = 0xFF7BE38A),
        ThemePalette("TERMINAL_AMBAR", "Terminal Âmbar", dark = true,
            background = 0xFF0A0500, surface = 0xE6160D02, surfaceHigh = 0xF0221505, outline = 0xFF40280A,
            text = 0xFFFFD9A0, textDim = 0xFFC4A070, primary = 0xFFFFB000, secondary = 0xFFFFD27A,
            tertiary = 0xFFE0A0FF, danger = 0xFFFF6A3D, ok = 0xFFB8E07A),
        ThemePalette("OCEANO_PROFUNDO", "Oceano Profundo", dark = true,
            background = 0xFF020814, surface = 0xE6081426, surfaceHigh = 0xF00E1E34, outline = 0xFF1E3352,
            text = 0xFFDCEBFF, textDim = 0xFF93A8C4, primary = 0xFF6BC7FF, secondary = 0xFFF5C55A,
            tertiary = 0xFFB79CFF, danger = 0xFFFF7A6B, ok = 0xFF7FDBA0),
        ThemePalette("IPE_ROXO", "Ipê Roxo", dark = true,
            background = 0xFF0A0410, surface = 0xE6160B20, surfaceHigh = 0xF020122E, outline = 0xFF3B2552,
            text = 0xFFF0E2FF, textDim = 0xFFAE9CC4, primary = 0xFFC79BFF, secondary = 0xFFF5C34F,
            tertiary = 0xFF7FD9C8, danger = 0xFFFF7A8A, ok = 0xFF8FDB8F),
        ThemePalette("AURORA_BOREAL", "Aurora Boreal", dark = true,
            background = 0xFF02060A, surface = 0xE6091218, surfaceHigh = 0xF0101C24, outline = 0xFF203440,
            text = 0xFFE2F4F0, textDim = 0xFF97B0AC, primary = 0xFF6FF0C0, secondary = 0xFFB8A4FF,
            tertiary = 0xFFF5D06A, danger = 0xFFFF7A6B, ok = 0xFF8BE68B),
        ThemePalette("CERRADO", "Cerrado", dark = true,
            background = 0xFF0C0804, surface = 0xE61A120A, surfaceHigh = 0xF0261A0F, outline = 0xFF47321E,
            text = 0xFFF2E4D0, textDim = 0xFFB8A18A, primary = 0xFFE8A35A, secondary = 0xFFC9D46A,
            tertiary = 0xFF8EC5E8, danger = 0xFFFF6E52, ok = 0xFF9BD483),
        ThemePalette("ALTO_CONTRASTE", "Alto Contraste", dark = true,
            background = 0xFF000000, surface = 0xF5000000, surfaceHigh = 0xFF101010, outline = 0xFFFFFFFF,
            text = 0xFFFFFFFF, textDim = 0xFFE0E0E0, primary = 0xFF00FFFF, secondary = 0xFFFFFF00,
            tertiary = 0xFFFF99FF, danger = 0xFFFF8080, ok = 0xFF80FF80),
        ThemePalette("GIRASSOL_DIA", "Girassol Dia", dark = false,
            background = 0xFFFBF6EA, surface = 0xE6FFFDF6, surfaceHigh = 0xF0F3ECDA, outline = 0xFFCFC4AA,
            text = 0xFF1E1A12, textDim = 0xFF5A5244, primary = 0xFF0B6B63, secondary = 0xFF845600,
            tertiary = 0xFF5B4BB0, danger = 0xFFB3261E, ok = 0xFF2E6B31),
        ThemePalette("SEPIA_ARQUIVO", "Sépia Arquivo", dark = false,
            background = 0xFFF3E9D6, surface = 0xE6FBF4E6, surfaceHigh = 0xF0EBDDC2, outline = 0xFFC2AE8A,
            text = 0xFF2A2016, textDim = 0xFF5E4E3A, primary = 0xFF7A3E10, secondary = 0xFF6B5200,
            tertiary = 0xFF4A3F8A, danger = 0xFFA12A1A, ok = 0xFF355E2A),
        ThemePalette("BRUTALISMO_CONCRETO", "Brutalismo Concreto", dark = false,
            background = 0xFFDCDCD8, surface = 0xE6EEEEEA, surfaceHigh = 0xF0E2E2DE, outline = 0xFFA8A8A2,
            text = 0xFF151515, textDim = 0xFF45454A, primary = 0xFF1F4E8C, secondary = 0xFF8A3B00,
            tertiary = 0xFF5A2F8A, danger = 0xFFA8201A, ok = 0xFF22602A),
        ThemePalette("BURLE_MARX", "Burle Marx", dark = false,
            background = 0xFFEAF3E6, surface = 0xE6F7FCF4, surfaceHigh = 0xF0E0EDD9, outline = 0xFF9DBB92,
            text = 0xFF10231A, textDim = 0xFF3F5A48, primary = 0xFF0F5E3A, secondary = 0xFF8A4A00,
            tertiary = 0xFF6A2F7A, danger = 0xFFA62A1E, ok = 0xFF1F5E2A)
    )

    val DEFAULT: ThemePalette get() = ALL.first()
    fun byKey(key: String?): ThemePalette = ALL.firstOrNull { it.key == key } ?: DEFAULT
}
