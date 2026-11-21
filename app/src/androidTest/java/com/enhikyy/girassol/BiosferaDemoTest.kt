package com.enhikyy.girassol

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.enhikyy.girassol.render3d.RenderMode
import com.enhikyy.girassol.render3d.RenderModePolicy
import com.enhikyy.girassol.render3d.WorldPickRegistry
import com.enhikyy.girassol.sim.SimulationHub
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Roteiro de demonstração (não roda no CI normal): só executa com `-e demo true`.
 * Sem ComposeTestRule de propósito — a regra de teste congela o relógio de quadros do Compose
 * enquanto o teste dorme; aqui o app roda exatamente como no aparelho. O host
 * (scripts/biosfera_demo.sh) toca na aba, faz os gestos e grava capturas/vídeo.
 */
@RunWith(AndroidJUnit4::class)
class BiosferaDemoTest {
    private fun mark(step: String) = android.util.Log.i("BiosferaDemo", "PASSO $step")

    @Test
    fun biosfera3dTour() {
        assumeTrue("roteiro de demonstração: use -e demo true", InstrumentationRegistry.getArguments().getString("demo") == "true")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        RenderModePolicy.set(ctx, RenderMode.MODE_3D)
        ActivityScenario.launch(MainActivity::class.java).use {
            val t0 = System.currentTimeMillis()
            while (!SimulationHub.status.value.loaded && System.currentTimeMillis() - t0 < 90_000) Thread.sleep(250)
            runBlocking {
                SimulationHub.spawnFounder("iara.gguf", "Iara Peroba", "F")
                SimulationHub.spawnFounder("livia.gguf", "Lívia Aroeira", "F")
                SimulationHub.spawnFounder("orlando.gguf", "Orlando Aroeira", "M")
                SimulationHub.spawnFounder("gael.gguf", "Gael Jacarandá", "M")
            }
            mark("POVOADO")
            Thread.sleep(60_000)             // host: aba Biosfera, capturas, órbita e inclinação
            SimulationHub.setSpeed(8)
            mark("VELOCIDADE_8X")
            Thread.sleep(40_000)             // ~13 h de mundo: amanhecer → dia → entardecer
            val dm = ctx.resources.displayMetrics
            val p = WorldPickRegistry.positions.values.minByOrNull {
                kotlin.math.hypot(it.x - dm.widthPixels / 2f, it.y - dm.heightPixels * 0.45f)
            }
            mark("CARTAO ${p?.x?.toInt() ?: -1} ${p?.y?.toInt() ?: -1}")
            Thread.sleep(15_000)
            SimulationHub.setSpeed(1)
            mark("FIM")
        }
    }
}
