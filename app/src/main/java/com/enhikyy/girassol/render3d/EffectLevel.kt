package com.enhikyy.girassol.render3d

/**
 * Níveis de efeito visual "Kodak 500T / Dehancer", do mais caro ao mais seguro.
 * O fallback desce um nível por vez quando um shader falha (sonda isolada ou sentinela
 * de crash) ou quando o FPS medido fica abaixo do piso por tempo prolongado.
 */
enum class EffectLevel(
    val id: Int,
    val label: String,
    val bloomLevels: Int,
    val fxaa: Boolean,
    val fog: Boolean,
    val vignette: Boolean,
    val colorGrading: Boolean,
    val temporalDithering: Boolean,
    val dynamicResolution: Boolean,
    val halationSprites: Boolean,
    val grain: Boolean
) {
    FULL(3, "completo", 6, true, true, true, true, true, true, true, true),
    REDUCED(2, "reduzido", 3, false, false, true, true, true, true, true, true),
    MINIMAL(1, "mínimo", 0, false, false, false, true, false, false, true, true),
    NONE(0, "sem pós", 0, false, false, false, false, false, false, false, false);

    val postProcessing: Boolean get() = bloomLevels > 0 || fxaa || fog || vignette || colorGrading

    fun describe(): String = when (this) {
        FULL -> "bloom 6 níveis + halação + grão + gradação 500T + vinheta + névoa + FXAA"
        REDUCED -> "bloom 3 níveis + halação + grão + gradação 500T + vinheta"
        MINIMAL -> "gradação 500T + halação + grão (sem bloom)"
        NONE -> "cena sem pós-processamento"
    }

    fun lower(): EffectLevel? = entries.firstOrNull { it.id == id - 1 }

    companion object {
        fun fromId(id: Int): EffectLevel? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Governador de FPS (lógica pura, testada em JVM): após o aquecimento, se a média de
 * [windowSeconds] janelas consecutivas de 1 s ficar abaixo de [floorFps], pede para descer
 * um nível. Teto de 30 fps permanece; o piso é 2/3 dele.
 */
class FpsGovernor(
    private val floorFps: Double = 20.0,
    private val windowSeconds: Int = 8,
    private val warmupSeconds: Int = 5
) {
    private var seen = 0
    private val window = ArrayDeque<Double>()

    /** Retorna true quando deve degradar; zera a janela após cada decisão. */
    fun onSecond(fps: Double): Boolean {
        seen++
        if (seen <= warmupSeconds) return false
        window.addLast(fps)
        while (window.size > windowSeconds) window.removeFirst()
        if (window.size == windowSeconds && window.average() < floorFps) {
            window.clear()
            seen = 0 // novo aquecimento após trocar de nível (compilação de shaders)
            return true
        }
        return false
    }

    fun reset() { seen = 0; window.clear() }
}
