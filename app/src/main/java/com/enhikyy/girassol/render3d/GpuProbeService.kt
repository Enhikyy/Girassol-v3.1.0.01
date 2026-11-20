package com.enhikyy.girassol.render3d

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.enhikyy.girassol.core.world.WorldCore
import com.enhikyy.girassol.core.world.WorldRenderSnapshot
import java.io.File

/**
 * Sonda de GPU no processo isolado ":gpuprobe" (ver AndroidManifest). Renderiza a cidade
 * fora da tela no nível pedido; se o shader abortar o processo, só este processo morre.
 * Escreve o resultado em files/gpuprobe/result-<nonce>.txt e se encerra.
 */
class GpuProbeService : Service() {
    companion object {
        const val EXTRA_LEVEL = "level"
        const val EXTRA_NONCE = "nonce"
        private const val TAG = "GirassolGpuProbe"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val level = EffectLevel.fromId(intent?.getIntExtra(EXTRA_LEVEL, 0) ?: 0) ?: EffectLevel.NONE
        val nonce = intent?.getStringExtra(EXTRA_NONCE) ?: "x"
        Thread({
            val t0 = SystemClock.elapsedRealtime()
            val result = runCatching { probe(level) }
            val ms = SystemClock.elapsedRealtime() - t0
            val text = result.fold(
                { "status=ok\nlevel=${level.id}\nms=$ms\nmsg=$it\n" },
                { "status=fail\nlevel=${level.id}\nms=$ms\nmsg=${it.javaClass.simpleName}: ${it.message}\n" })
            Log.i(TAG, "nível ${level.label}: ${text.replace('\n', ' ')}")
            val dir = File(filesDir, "gpuprobe").apply { mkdirs() }
            val tmp = File(dir, "result-$nonce.part")
            tmp.writeText(text)
            tmp.renameTo(File(dir, "result-$nonce.txt"))
            stopSelf()
            Process.killProcess(Process.myPid()) // libera GPU/RAM imediatamente
        }, "girassol-gpuprobe").start()
        return START_NOT_STICKY
    }

    private fun probe(level: EffectLevel): String {
        val snap = WorldRenderSnapshot.of(WorldCore.create(2026L).state)
        val r = FilamentCityRenderer(applicationContext, null, level)
        try {
            r.initHeadless(320, 240)
            r.update(snap)
            val frames = r.renderHeadless(36)
            return "$frames quadros"
        } finally {
            r.destroy()
        }
    }
}
