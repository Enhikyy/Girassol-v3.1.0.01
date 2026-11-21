package com.enhikyy.girassol.ui

import com.enhikyy.girassol.core.world.LineageNode
import com.enhikyy.girassol.ui.screens.LineageLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.9 — Árvore Geracional sem sobreposição de nomes entre 360 dp e 412 dp (e tablets). */
class LineageLayoutTest {
    private fun tree(): List<LineageNode> {
        val g0 = listOf("Iara Peroba", "Lívia Aroeira", "Orlando Aroeira", "Gael Jacarandá", "Adão", "Eva").mapIndexed { i, n ->
            LineageNode("c$i", n, 0, emptyList(), true, "m$i.gguf")
        }
        val g1 = (0 until 5).map { LineageNode("k$it", "Filho Longuíssimo Nome $it", 1, listOf("c${it % 6}", "c${(it + 1) % 6}"), it != 2, null) }
        val g2 = (0 until 3).map { LineageNode("n$it", "Neta $it", 2, listOf("k$it", "k${it + 1}"), true, null) }
        return g0 + g1 + g2
    }

    @Test
    fun noOverlapAcrossPhoneWidths() {
        val nodes = tree()
        // largura útil da árvore = largura da tela − margens da lista (12 dp × 2) − padding do cartão (16 dp × 2)
        for (screen in listOf(360f, 375f, 380f, 393f, 400f, 412f, 600f, 840f)) {
            val w = screen - 24f - 32f
            val r = LineageLayout.compute(nodes, w, showGhost = true)
            val boxes = r.boxes.values.toList() + listOfNotNull(r.ghost)
            assertTrue(boxes.size == nodes.size + 1)
            for (i in boxes.indices) {
                val a = boxes[i]
                assertTrue("nó fora da área em ${screen}dp: $a", a.x >= -0.01f && a.x + a.w <= w + 0.01f)
                assertTrue("nó estreito demais em ${screen}dp: ${a.w}", a.w >= 120f)
                for (j in i + 1 until boxes.size) assertFalse("sobreposição em ${screen}dp: $a × ${boxes[j]}", a.overlaps(boxes[j]))
            }
            assertTrue(r.heightDp >= boxes.maxOf { it.y + it.h } - 0.01f)
        }
    }
}
