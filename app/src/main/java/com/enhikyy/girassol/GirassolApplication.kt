package com.enhikyy.girassol

import android.app.Application
import com.enhikyy.girassol.render3d.GpuCalibration
import com.enhikyy.girassol.render3d.RenderModePolicy
import com.enhikyy.girassol.service.GirassolContinuousService
import com.enhikyy.girassol.sim.SimulationHub
import com.enhikyy.girassol.system.MemoryGuard

class GirassolApplication : Application() {
    /** Processo isolado da sonda de GPU: não carrega o mundo, o LLM nem o serviço. */
    private val isProbeProcess: Boolean by lazy { getProcessName().endsWith(GpuCalibration.PROBE_PROCESS_SUFFIX) }

    override fun onCreate() {
        super.onCreate()
        if (isProbeProcess) return
        GirassolContinuousService.createChannel(this)
        RenderModePolicy.load(this)
        SimulationHub.ensureLoaded(this)
        MemoryGuard.start(this)
    }

    /** Sob pressão de memória o modelo GGUF é descarregado antes que o Low Memory Killer aja. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (isProbeProcess) return
        MemoryGuard.onTrimMemory(level)
        SimulationHub.onTrimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (isProbeProcess) return
        MemoryGuard.onLowMemory()
    }
}
