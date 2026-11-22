package com.enhikyy.girassol

import android.app.Application
import android.util.Log
import com.enhikyy.girassol.render3d.GpuCalibration
import com.enhikyy.girassol.render3d.RenderModePolicy
import com.enhikyy.girassol.service.GirassolContinuousService
import com.enhikyy.girassol.sim.SimulationHub
import com.enhikyy.girassol.system.MemoryGuard
import com.girassol.brain.GirassolV8

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
        com.enhikyy.girassol.synaptic.SynapticBudgetEngine.start(this)
        // Núcleo cortical v8: autoteste de integridade bit a bit (âncora FNV-1a,
        // axiomas A4/C3). Nunca bloqueia o boot — se a biblioteca não estiver
        // presente neste build/ABI o app segue em modo degradado e o córtex v8
        // permanece desativado até que initBrain() seja chamado com sucesso.
        runCatching {
            val selfTestOk = GirassolV8.selfTestPassed()
            Log.i(
                TAG,
                "GirassolV8 core=${GirassolV8.coreVersion()} " +
                    "self-test=${if (selfTestOk) "OK" else "FALHOU"}"
            )
        }.onFailure { throwable ->
            Log.w(TAG, "GirassolV8 indisponível neste build — córtex v8 desativado", throwable)
        }
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

    private companion object {
        const val TAG: String = "GirassolApp"
    }
}
