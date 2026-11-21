package com.enhikyy.girassol

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.enhikyy.girassol.render3d.CameraMode
import com.enhikyy.girassol.render3d.EffectLevel
import com.enhikyy.girassol.render3d.GpuCalibration
import com.enhikyy.girassol.render3d.RenderMode
import com.enhikyy.girassol.render3d.SunCycle
import com.enhikyy.girassol.render3d.WorldPreset
import com.enhikyy.girassol.render3d.WorldViewState
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
        // demonstração com o pipeline cinematográfico completo (o governador de FPS fica desligado)
        GpuCalibration.forceLevel(ctx, EffectLevel.FULL)
        GpuCalibration.ensure(ctx, force = true)
        WorldViewState.setPreset(WorldPreset.URBAN_OVERCAST)
        WorldViewState.setCamera(CameraMode.ORBIT)
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
            // o host comanda cada passo: am broadcast -a girassol.demo.STEP --es step <8x|1x|pick|2d|3d|urban|interior|orbit|tilt|pov|cam2d|ate:<fração>|fim>
            val steps = java.util.concurrent.LinkedBlockingQueue<String>()
            val rx = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context?, i: android.content.Intent?) { i?.getStringExtra("step")?.let(steps::put) }
            }
            ctx.registerReceiver(rx, android.content.IntentFilter("girassol.demo.STEP"), android.content.Context.RECEIVER_EXPORTED)
            try {
                val dm = ctx.resources.displayMetrics
                while (true) {
                    val step = steps.poll(8, java.util.concurrent.TimeUnit.MINUTES) ?: break
                    if (step.startsWith("ate:")) {
                        // avança o relógio da simulação até a fração do dia pedida e volta a 8×
                        val target = step.removePrefix("ate:").toFloat()
                        SimulationHub.setSpeed(60)
                        val t1 = System.currentTimeMillis()
                        while (System.currentTimeMillis() - t1 < 180_000) {
                            val tick = SimulationHub.render.value?.tick ?: break
                            val f = SunCycle.dayFraction(tick)
                            val d = ((f - target) % 1f + 1f) % 1f
                            if (d < 0.03f) break
                            Thread.sleep(40)
                        }
                        SimulationHub.setSpeed(8)
                        mark("OK_$step"); continue
                    }
                    when (step) {
                        "8x" -> SimulationHub.setSpeed(8)
                        "1x" -> SimulationHub.setSpeed(1)
                        "2d" -> RenderModePolicy.set(ctx, RenderMode.ECO_2D)
                        "3d" -> RenderModePolicy.set(ctx, RenderMode.MODE_3D)
                        "urban" -> WorldViewState.setPreset(WorldPreset.URBAN_OVERCAST)
                        "interior" -> WorldViewState.setPreset(WorldPreset.LUMON_INTERIOR)
                        "orbit" -> WorldViewState.setCamera(CameraMode.ORBIT)
                        "tilt" -> WorldViewState.setCamera(CameraMode.TILT)
                        "pov" -> WorldViewState.enterPov(null)
                        "cam2d" -> { WorldViewState.setCamera(CameraMode.TOP_2D); RenderModePolicy.set(ctx, RenderMode.ECO_2D) }
                        "pick" -> {
                            val p = WorldPickRegistry.positions.values.minByOrNull {
                                kotlin.math.hypot(it.x - dm.widthPixels / 2f, it.y - dm.heightPixels * 0.45f)
                            }
                            mark("CARTAO ${p?.x?.toInt() ?: -1} ${p?.y?.toInt() ?: -1}"); continue
                        }
                    }
                    mark("OK_$step")
                    if (step == "fim") break
                }
            } finally {
                ctx.unregisterReceiver(rx); SimulationHub.setSpeed(1)
                WorldViewState.setPreset(WorldPreset.URBAN_OVERCAST); WorldViewState.setCamera(CameraMode.ORBIT)
                GpuCalibration.forceLevel(ctx, null)
            }
        }
    }
}
