package com.enhikyy.girassol.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.enhikyy.girassol.ui.theme.Lumon
import com.enhikyy.girassol.ui.theme.MonoStyle
import java.util.Locale

/** v3.6: geometria editorial — cards/painéis/gavetas 28 dp, inputs/botões 20 dp, cápsulas circulares. */
object Shapes {
    val Card = RoundedCornerShape(28.dp)
    val Control = RoundedCornerShape(20.dp)
    val Capsule = androidx.compose.foundation.shape.CircleShape
}

fun Double.f(d: Int = 2): String = String.format(Locale.ROOT, "%.${d}f", this)

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(Shapes.Card)
            .background(Lumon.Surface)
            .border(1.dp, Lumon.Outline, Shapes.Card)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelSmall, color = Lumon.Teal)
        content()
    }
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = Lumon.Cream) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = Lumon.CreamDim, modifier = Modifier.weight(1f))
        Text(value, style = MonoStyle, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun LabeledBar(label: String, value: Double, color: Color = Lumon.Teal, trailing: String? = null) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
            Text(trailing ?: value.f(2), style = MonoStyle, color = Lumon.Cream)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Lumon.SurfaceHigh)) {
            Box(Modifier.fillMaxWidth(value.toFloat().coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
        }
    }
}

@Composable
fun MetricTile(label: String, value: String, modifier: Modifier = Modifier, accent: Color = Lumon.Sunflower) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Lumon.SurfaceHigh)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Lumon.CreamDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = accent, maxLines = 1)
    }
}

@Composable
fun MetricRow(vararg items: Pair<String, String>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((l, v) in items) MetricTile(l, v, Modifier.weight(1f))
    }
}

@Composable
fun Pill(text: String, color: Color) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = color,
        modifier = Modifier.clip(androidx.compose.foundation.shape.CircleShape).background(color.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
fun Dot(color: Color) {
    Box(Modifier.width(8.dp).height(8.dp).clip(RoundedCornerShape(50)).background(color))
}

@Composable
fun EmptyState(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Lumon.CreamDim, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Seção colapsável limpa (Arquiteto / Sistema): cabeçalho tocável, conteúdo recolhido por padrão. */
@Composable
fun Collapsible(title: String, tag: String, initiallyOpen: Boolean = false, subtitle: String = "", content: @Composable () -> Unit) {
    var open by androidx.compose.runtime.saveable.rememberSaveable(tag) { androidx.compose.runtime.mutableStateOf(initiallyOpen) }
    Column(
        Modifier.fillMaxWidth().clip(Shapes.Card).background(Lumon.Surface)
            .border(1.dp, Lumon.Outline, Shapes.Card)
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.testTag(tag).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Lumon.Cream)
                if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(if (open) "−" else "+", style = MaterialTheme.typography.titleMedium, color = Lumon.Sunflower)
        }
        if (open) Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** Barra compacta de uma linha (rótulo curto + barra fina), para os cards de cidadão. */
@Composable
fun CompactBar(label: String, value: Double, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Lumon.CreamDim, maxLines = 1)
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Lumon.SurfaceHigh)) {
            Box(Modifier.fillMaxWidth(value.toFloat().coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

