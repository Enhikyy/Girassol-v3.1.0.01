package com.enhikyy.girassol.hardware

/**
 * v4.1 — nível de renderização da Aba Mundo escolhido pelo [HardwareAutoTuner].
 *
 * Hardware-alvo: aparelhos de 8 GB e 12 GB (Snapdragon, Exynos, Dimensity). NÃO há teto de
 * 4 GB: texturas 2048², sombras, pós-processamento completo e D = 256 em todos os aparelhos reais.
 *
 *  - [EMULATOR]: SOMENTE quando GL_RENDERER contém "SwiftShader" ou "Android Emulator"
 *    (execução headless do CI). Variante girassol_world_fallback, sem pós do Filament.
 *  - [MID] (aparelhos de 8 GB — e qualquer aparelho real abaixo de 10 GB): sombras 2048 em 3
 *    cascatas, SSAO + sombras de contato, SSR, bloom 7 níveis, halação, grão 70 mm, texturas 2048².
 *  - [ULTRA] (12 GB+): igual ao MID com sombras 4096 e o dobro de luzes pontuais na rua.
 *
 * [LITE] existe só por compatibilidade de dados salvos (id 1) e é promovido a [MID] em [fromId];
 * [classify] nunca o devolve.
 */
enum class RenderTier(val id: Int, val label: String, val shadowMapSize: Int, val textureSize: Int, val pointLights: Boolean) {
    EMULATOR(0, "Emulador", 1024, 256, false),
    LITE(1, "Alto", 2048, 2048, true),
    MID(2, "Alto (8 GB)", 2048, 2048, true),
    ULTRA(3, "Ultra (12 GB)", 4096, 2048, true);

    companion object {
        /** Limite entre os aparelhos de 8 GB (≈ 7,2–7,7 GB reportados) e os de 12 GB (≈ 11 GB). */
        const val ULTRA_MIN_RAM_MB = 10240L

        /** GL por software do emulador: o único caso em que o perfil simplificado é usado. */
        fun isEmulatorRenderer(glRenderer: String?): Boolean {
            val r = glRenderer ?: return false
            return r.contains("SwiftShader", ignoreCase = true) || r.contains("Android Emulator", ignoreCase = true)
        }

        /** Classificação pura (testada na JVM): renderer do emulador → EMULATOR; senão MID/ULTRA pela RAM. */
        fun classify(totalRamMb: Long, glRenderer: String?): RenderTier = when {
            isEmulatorRenderer(glRenderer) -> EMULATOR
            totalRamMb < ULTRA_MIN_RAM_MB -> MID
            else -> ULTRA
        }

        fun fromId(id: Int): RenderTier? = entries.firstOrNull { it.id == id }?.let { if (it == LITE) MID else it }
    }
}
