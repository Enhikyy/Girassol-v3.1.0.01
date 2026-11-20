package com.enhikyy.girassol.render3d

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RenderMode(val label: String) { MODE_3D("3D Filament"), ECO_2D("Modo Eco 2D") }

data class EffectiveRender(val mode: RenderMode, val reason: String)

/**
 * Política de modo de renderização. O 3D só roda com a aba Mundo em primeiro plano, sem
 * alerta térmico severo e com o Filament funcional; caso contrário vale o Modo Eco 2D
 * (isométrico em Canvas), que é o modo de operação 24/7 em segundo plano.
 */
object RenderModePolicy {
    private const val PREFS = "girassol_render"
    private val _preferred = MutableStateFlow(RenderMode.MODE_3D)
    val preferred: StateFlow<RenderMode> = _preferred.asStateFlow()

    /** Status térmico do PowerManager a partir do qual o 3D é suspenso (3 = SEVERE). */
    const val THERMAL_ECO_STATUS = 3

    fun load(context: Context) {
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("mode", RenderMode.MODE_3D.name)
        _preferred.value = runCatching { RenderMode.valueOf(v ?: "") }.getOrDefault(RenderMode.MODE_3D)
    }

    fun set(context: Context, mode: RenderMode) {
        _preferred.value = mode
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("mode", mode.name).apply()
    }

    fun effective(preferred: RenderMode, thermalStatus: Int, filamentFailed: String?, foreground: Boolean): EffectiveRender = when {
        preferred == RenderMode.ECO_2D -> EffectiveRender(RenderMode.ECO_2D, "escolhido manualmente")
        !foreground -> EffectiveRender(RenderMode.ECO_2D, "segundo plano")
        thermalStatus >= THERMAL_ECO_STATUS -> EffectiveRender(RenderMode.ECO_2D, "alerta térmico (status $thermalStatus)")
        filamentFailed != null -> EffectiveRender(RenderMode.ECO_2D, "3D indisponível: $filamentFailed")
        else -> EffectiveRender(RenderMode.MODE_3D, "primeiro plano")
    }
}
