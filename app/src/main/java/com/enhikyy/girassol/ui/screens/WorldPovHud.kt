package com.enhikyy.girassol.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enhikyy.girassol.render3d.PovTelemetry
import kotlin.math.cos
import kotlin.math.sin

/** Paleta do HUD diegético em OLED escuro (preto puro, ciano fósforo, âmbar de alerta). */
private object Oled {
    val Panel = Color(0xE6000000)
    val Edge = Color(0x5534E0F0)
    val Cyan = Color(0xFF5FE6F2)
    val Amber = Color(0xFFFFB547)
    val Dim = Color(0xFF7C8A8C)
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
}

/** HUD diegético do POV: setor e coordenadas, hora simulada, objetivo e pensamento atual. */
@Composable
fun PovHud(t: PovTelemetry, simTime: String, modifier: Modifier = Modifier) {
    Column(
        modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(10.dp)).background(Oled.Panel)
            .border(1.dp, Oled.Edge, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp).testTag("pov_hud"),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text("POV · ${t.name}", style = Oled.mono.copy(fontSize = 12.sp), color = Oled.Amber, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("SETOR ${t.sector.uppercase()}", style = Oled.mono, color = Oled.Cyan, maxLines = 1)
        Text("X %06.1f  Z %06.1f  %03.0f°".format(t.xM, t.zM, t.headingDeg), style = Oled.mono, color = Oled.Cyan, maxLines = 1,
            modifier = Modifier.testTag("pov_hud_coords"))
        Text("T $simTime", style = Oled.mono, color = Oled.Dim, maxLines = 1)
        Text("META ${t.goal}", style = Oled.mono, color = Oled.Amber, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (t.thought.isNotBlank()) Text("» ${t.thought}", style = Oled.mono, color = Oled.Dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Minimapa (radar 48 m) com o norte do mundo girado para a direção do olhar. */
@Composable
fun PovMinimap(t: PovTelemetry, modifier: Modifier = Modifier) {
    Canvas(modifier.size(116.dp).clip(RoundedCornerShape(12.dp)).background(Oled.Panel).border(1.dp, Oled.Edge, RoundedCornerShape(12.dp))
        .testTag("pov_minimap")) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2 - 6f
        val k = r / 48f
        drawCircle(Oled.Edge, r, c, style = Stroke(1f))
        drawCircle(Oled.Edge, r / 2, c, style = Stroke(1f))
        // olhar para cima: gira o mundo por −heading (frente do rig = +Z do mundo com guinada 0)
        rotate(t.headingDeg + 180f, c) {
            // limites do mundo
            val ox = c.x - t.xM * k; val oz = c.y - t.zM * k
            drawRect(Oled.Dim.copy(alpha = 0.35f), topLeft = Offset(ox, oz), size = androidx.compose.ui.geometry.Size(t.worldSizeM * k, t.worldSizeM * k),
                style = Stroke(1f))
            for ((dx, dz) in t.neighbours) drawCircle(Oled.Cyan, 3f, Offset(c.x + dx * k, c.y + dz * k))
        }
        // cursor do próprio cidadão (sempre apontando para cima)
        val p = Path().apply {
            moveTo(c.x, c.y - 9f); lineTo(c.x - 6f, c.y + 6f); lineTo(c.x, c.y + 3f); lineTo(c.x + 6f, c.y + 6f); close()
        }
        drawPath(p, Oled.Amber)
        val a = Math.toRadians(-90.0).toFloat()
        drawLine(Oled.Amber.copy(alpha = 0.4f), c, Offset(c.x + cos(a) * r, c.y + sin(a) * r), 1f)
    }
}
