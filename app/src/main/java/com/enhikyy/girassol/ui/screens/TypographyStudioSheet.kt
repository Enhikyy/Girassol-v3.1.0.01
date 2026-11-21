package com.enhikyy.girassol.ui.screens
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.enhikyy.girassol.ui.theme.Lumon
import com.enhikyy.girassol.ui.theme.TypographyStudioController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypographyStudioSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = TypographyStudioController.preferences
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Lumon.Surface, contentColor = Lumon.Cream,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), modifier = Modifier.testTag("typography_studio_sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Typography Studio", style = MaterialTheme.typography.headlineSmall, color = Lumon.Cream)
            Text("Presets", style = MaterialTheme.typography.labelLarge, color = Lumon.CreamDim)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TypographyStudioController.presets.forEach { preset ->
                    FilterChip(selected = prefs.preset == preset.key,
                        onClick = { TypographyStudioController.applyPreset(context, preset.key) },
                        label = { Text(preset.label, maxLines = 1) },
                        modifier = Modifier.testTag("typography_preset_\${preset.key}"))
                }
            }
            TypographySlider(context, "Escala", prefs.scale, 0.85f..1.30f, 8, "typography_scale") { TypographyStudioController.update(context, prefs.copy(scale = it)) }
            TypographySlider(context, "Peso", prefs.weight.toFloat(), 300f..700f, 7, "typography_weight") { TypographyStudioController.update(context, prefs.copy(weight = it.toInt())) }
            TypographySlider(context, "Tracking", prefs.tracking, -0.5f..1.5f, 19, "typography_tracking") { TypographyStudioController.update(context, prefs.copy(tracking = it)) }
            TypographySlider(context, "Altura de linha", prefs.lineHeight, 1.1f..1.6f, 9, "typography_line_height") { TypographyStudioController.update(context, prefs.copy(lineHeight = it)) }
            Text("Aa  Girassol — Kodak 70mm", style = MaterialTheme.typography.headlineMedium, color = Lumon.Sunflower)
            Text("Prévia em tempo real. As alterações são gravadas localmente.", style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
        }
    }
}
@Composable
private fun TypographySlider(context: Context, label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, tag: String, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label · \${"%.2f".format(value)}", style = MaterialTheme.typography.labelLarge, color = Lumon.Cream)
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps,
            onValueChangeFinished = { TypographyStudioController.persist(context) },
            modifier = Modifier.fillMaxWidth().testTag(tag))
    }
}
