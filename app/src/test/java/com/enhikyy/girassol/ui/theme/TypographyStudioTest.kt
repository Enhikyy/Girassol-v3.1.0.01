package com.enhikyy.girassol.ui.theme
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.enhikyy.girassol.core.ui.NativeThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
@RunWith(RobolectricTestRunner::class)
class TypographyStudioTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Test fun fifteenThemesAndKodakPaletteRemainStable() {
        assertEquals(15, NativeThemes.ALL.size)
        val kodak = NativeThemes.byKey("KODAK_500T")
        assertEquals("OLED Kodak 70mm", kodak.label)
        assertEquals(0xFF000000L, kodak.background)
        assertEquals(0xE6140C08L, kodak.surface)
        assertEquals(0xFFF3E2C8L, kodak.text)
        NativeThemes.ALL.forEach { palette ->
            ThemeController.select(context, palette)
            ThemeController.load(context)
            assertEquals(palette.key, ThemeController.palette.key)
        }
    }
    @Test fun typographyPreferencesPersistQuantizedValues() {
        TypographyStudioController.applyPreset(context, "reading")
        TypographyStudioController.update(context, TypographyStudioController.preferences.copy(scale=1.17f, weight=625, tracking=0.26f, lineHeight=1.47f))
        TypographyStudioController.load(context)
        assertEquals(1.15f, TypographyStudioController.preferences.scale, 0.001f)
        assertEquals(650, TypographyStudioController.preferences.weight)
        assertEquals(0.3f, TypographyStudioController.preferences.tracking, 0.001f)
        assertEquals(1.45f, TypographyStudioController.preferences.lineHeight, 0.001f)
        TypographyStudioController.saveCustomPreset(context)
        TypographyStudioController.load(context)
        assertEquals("custom", TypographyStudioController.preferences.preset)
        assertTrue(TypographyStudioController.presets.any { it.key == "custom" })
    }
    @Test fun variationCacheIsBounded() {
        repeat(300) { TypographyFontCache.settings(300f + it, 75f + it % 70) }
        assertTrue(TypographyFontCache.size() <= 64)
    }
}