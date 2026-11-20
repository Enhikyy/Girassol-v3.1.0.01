package com.enhikyy.girassol.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.enhikyy.girassol.core.world.ActionType
import com.enhikyy.girassol.core.world.DISTRICT_SIZE
import com.enhikyy.girassol.core.world.DistrictType
import com.enhikyy.girassol.core.world.SmartObjectType
import com.enhikyy.girassol.core.world.TileType
import com.enhikyy.girassol.core.world.WorldRenderSnapshot
import com.enhikyy.girassol.ui.theme.Lumon

/**
 * Renderizador isométrico 2.5D em Canvas. O terreno (tiles + edifícios extrudados)
 * é pré-rasterizado num bitmap e só é refeito quando a cidade se expande.
 */
object IsoRenderer {
    const val TW = 16f
    const val TH = 8f
    const val TOP = 18f
    private const val BUILDING_H = 9f

    fun districtColor(t: DistrictType): Color = when (t) {
        DistrictType.MORADIA -> Color(0xFFC9B68E)
        DistrictType.AGRICULTURA -> Color(0xFF7BAA55)
        DistrictType.RECICLAGEM -> Color(0xFF9C8A64)
        DistrictType.ENERGIA -> Lumon.Sunflower
        DistrictType.PESQUISA -> Color(0xFF8F86D6)
        DistrictType.AGORA -> Lumon.Teal
    }

    fun objectColor(t: SmartObjectType): Color = when (t) {
        SmartObjectType.CASA -> Color(0xFFE8DFC8)
        SmartObjectType.HORTA -> Color(0xFF9BD36A)
        SmartObjectType.COMPOSTEIRA -> Color(0xFFB48A5A)
        SmartObjectType.USINA_SOLAR -> Color(0xFFFFD45C)
        SmartObjectType.LABORATORIO -> Color(0xFFB0A6FF)
        SmartObjectType.ESCOLA -> Color(0xFF7FD6F0)
        SmartObjectType.PRACA -> Color(0xFF5FC9BF)
        SmartObjectType.OFICINA -> Color(0xFFE0915F)
    }

    fun actionColor(a: ActionType?): Color = when (a) {
        ActionType.COMER, ActionType.COLHER -> Color(0xFF9BD36A)
        ActionType.DESCANSAR -> Color(0xFF6E8CC9)
        ActionType.BANHO -> Color(0xFF7FD6F0)
        ActionType.RECICLAR, ActionType.CONSTRUIR -> Color(0xFFE0915F)
        ActionType.MANTER_USINA -> Color(0xFFFFD45C)
        ActionType.PESQUISAR, ActionType.ESTUDAR -> Color(0xFFB0A6FF)
        ActionType.SOCIALIZAR, ActionType.CONTRIBUIR -> Color(0xFFF2A0B8)
        ActionType.EXPLORAR -> Color(0xFFE8DFC8)
        ActionType.OCIOSO, null -> Color(0xFFA9A28F)
    }

    fun bitmapWidth(w: Int) = (w * TW).toInt() + 2
    fun bitmapHeight(w: Int) = (w * TH + TOP + 4).toInt()

    fun tileCenter(x: Int, z: Int, w: Int): Pair<Float, Float> =
        ((x - z) * TW / 2f + w * TW / 2f) to ((x + z) * TH / 2f + TH / 2f + TOP)

    private fun shade(c: Int, f: Float): Int {
        val a = (c ushr 24) and 0xFF
        val r = (((c shr 16) and 0xFF) * f).toInt().coerceIn(0, 255)
        val g = (((c shr 8) and 0xFF) * f).toInt().coerceIn(0, 255)
        val b = ((c and 0xFF) * f).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun buildTerrain(s: WorldRenderSnapshot): Bitmap {
        val w = s.widthTiles
        val bmp = Bitmap.createBitmap(bitmapWidth(w), bitmapHeight(w), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Lumon.Background.toArgb())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.6f; color = 0x22000000 }
        val districtType = HashMap<Long, DistrictType>()
        for (d in s.districts) districtType[(d.dx.toLong() shl 32) or (d.dz.toLong() and 0xffffffffL)] = d.type
        val path = Path()
        for (sum in 0..(2 * w - 2)) {
            val xStart = maxOf(0, sum - w + 1)
            val xEnd = minOf(sum, w - 1)
            for (x in xStart..xEnd) {
                val z = sum - x
                val tile = TileType.entries[s.tiles[z * w + x]]
                val dt = districtType[((x / DISTRICT_SIZE).toLong() shl 32) or ((z / DISTRICT_SIZE).toLong() and 0xffffffffL)]
                val (cx, cy) = tileCenter(x, z, w)
                val base = when (tile) {
                    TileType.GRAMA -> if ((x + z) % 2 == 0) 0xFF1F3A33.toInt() else 0xFF1C362F.toInt()
                    TileType.RUA -> 0xFF283236.toInt()
                    TileType.CALCADA -> 0xFF3A4749.toInt()
                    TileType.CAMPO -> if (x % 2 == 0) 0xFF3E5226.toInt() else 0xFF394B22.toInt()
                    TileType.PRACA -> 0xFF4A4535.toInt()
                    TileType.AGUA -> 0xFF1E5A6E.toInt()
                    TileType.EDIFICIO -> (dt?.let { districtColor(it) } ?: Lumon.Cream).toArgb()
                }
                if (tile == TileType.EDIFICIO) {
                    val h = BUILDING_H
                    // face esquerda
                    path.reset(); path.moveTo(cx - TW / 2, cy); path.lineTo(cx, cy + TH / 2); path.lineTo(cx, cy + TH / 2 - h); path.lineTo(cx - TW / 2, cy - h); path.close()
                    paint.color = shade(base, 0.55f); canvas.drawPath(path, paint)
                    // face direita
                    path.reset(); path.moveTo(cx + TW / 2, cy); path.lineTo(cx, cy + TH / 2); path.lineTo(cx, cy + TH / 2 - h); path.lineTo(cx + TW / 2, cy - h); path.close()
                    paint.color = shade(base, 0.75f); canvas.drawPath(path, paint)
                    // topo
                    path.reset(); path.moveTo(cx, cy - TH / 2 - h); path.lineTo(cx + TW / 2, cy - h); path.lineTo(cx, cy + TH / 2 - h); path.lineTo(cx - TW / 2, cy - h); path.close()
                    paint.color = shade(base, 0.95f); canvas.drawPath(path, paint); canvas.drawPath(path, stroke)
                } else {
                    path.reset(); path.moveTo(cx, cy - TH / 2); path.lineTo(cx + TW / 2, cy); path.lineTo(cx, cy + TH / 2); path.lineTo(cx - TW / 2, cy); path.close()
                    paint.color = base; canvas.drawPath(path, paint); canvas.drawPath(path, stroke)
                }
            }
        }
        return bmp
    }
}
