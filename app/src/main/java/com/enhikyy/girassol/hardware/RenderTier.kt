package com.enhikyy.girassol.hardware

/**
 * v4.1 — nível de renderização da Aba Mundo escolhido pelo [HardwareAutoTuner].
 *
 *  - [EMULATOR]: SOMENTE quando GL_RENDERER contém "SwiftShader" ou "Android Emulator"
 *    (variante girassol_world_fallback, unlit, sem pós do Filament);
 *  - [LITE] (≤ 4–6 GB): mipmaps na GPU, sombras 1024, LUT 3D Kodak 70 mm, bloom em 1/4 da
 *    resolução, sem SSAO, texturas procedurais 512²;
 *  - [MID] (8 GB): sombras 2048, bloom + halação quente, IBL especular, texturas 1024²;
 *  - [ULTRA] (12 GB+): SSAO, grão Kodak, reflexos completos (SSR), texturas 1024².
 * Luzes pontuais do teto do salão Lumon existem nos níveis 1–3 (LITE/MID/ULTRA).
 */
enum class RenderTier(val id: Int, val label: String, val shadowMapSize: Int, val textureSize: Int, val pointLights: Boolean) {
    EMULATOR(0, "Emulador", 1024, 256, false),
    LITE(1, "Lite", 1024, 512, true),
    MID(2, "Mid", 2048, 1024, true),
    ULTRA(3, "Ultra", 2048, 1024, true);

    companion object {
        /** GL por software do emulador: o único caso em que o fallback unlit é usado. */
        fun isEmulatorRenderer(glRenderer: String?): Boolean {
            val r = glRenderer ?: return false
            return r.contains("SwiftShader", ignoreCase = true) || r.contains("Android Emulator", ignoreCase = true)
        }

        /** Classificação pura (testada na JVM): renderer do emulador → EMULATOR; senão pela RAM total. */
        fun classify(totalRamMb: Long, glRenderer: String?): RenderTier = when {
            isEmulatorRenderer(glRenderer) -> EMULATOR
            totalRamMb < 6144 -> LITE
            totalRamMb < 10240 -> MID
            else -> ULTRA
        }

        fun fromId(id: Int): RenderTier? = entries.firstOrNull { it.id == id }
    }
}
