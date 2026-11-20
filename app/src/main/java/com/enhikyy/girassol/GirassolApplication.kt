package com.enhikyy.girassol

import android.app.Application
import com.enhikyy.girassol.service.GirassolContinuousService
import com.enhikyy.girassol.sim.SimulationHub

class GirassolApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        GirassolContinuousService.createChannel(this)
        SimulationHub.ensureLoaded(this)
    }
}
