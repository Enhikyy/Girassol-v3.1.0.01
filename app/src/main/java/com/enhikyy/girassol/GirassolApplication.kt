package com.enhikyy.girassol

import android.app.Application
import com.enhikyy.girassol.render3d.RenderModePolicy
import com.enhikyy.girassol.service.GirassolContinuousService
import com.enhikyy.girassol.sim.SimulationHub

class GirassolApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        GirassolContinuousService.createChannel(this)
        RenderModePolicy.load(this)
        SimulationHub.ensureLoaded(this)
    }

    /** Sob pressão de memória o modelo GGUF é descarregado antes que o Low Memory Killer aja. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        SimulationHub.onTrimMemory(level)
    }
}
