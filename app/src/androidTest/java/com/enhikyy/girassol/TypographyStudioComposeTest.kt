package com.enhikyy.girassol
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.enhikyy.girassol.ui.glass.GlassTier
import com.enhikyy.girassol.ui.glass.LocalGlassTier
import com.enhikyy.girassol.ui.glass.liquidGlass
import com.enhikyy.girassol.ui.screens.TypographyStudioSheet
import com.enhikyy.girassol.ui.theme.GirassolTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class TypographyStudioComposeTest {
    @get:Rule val compose = createComposeRule()
    @Test fun sheetRendersControlsAndPresetActions() {
        compose.setContent { GirassolTheme { TypographyStudioSheet(onDismiss = {}) } }
        compose.onNodeWithText("Typography Studio").assertIsDisplayed()
        listOf("typography_scale","typography_weight","typography_tracking","typography_line_height").forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
        compose.onNodeWithTag("typography_preset_kodak").performClick()
        compose.onNodeWithTag("typography_save_preset").assertIsDisplayed().performClick()
    }
    @Test fun liquidGlassComposesAcrossAllHardwareTiers() {
        var tier by mutableStateOf(GlassTier.LOW)
        compose.setContent {
            CompositionLocalProvider(LocalGlassTier provides tier) {
                Box(Modifier.size(180.dp).liquidGlass(backdrop = false).testTag("glass_tier_surface"))
                androidx.compose.material3.Text(tier.name)
            }
        }
        listOf(GlassTier.LOW, GlassTier.MID, GlassTier.HIGH).forEach {
            compose.runOnUiThread { tier = it }
            compose.waitForIdle()
            compose.onNodeWithText(it.name).assertIsDisplayed()
            compose.onNodeWithTag("glass_tier_surface").assertIsDisplayed()
        }
    }
}