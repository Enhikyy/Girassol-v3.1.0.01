package com.enhikyy.girassol.render3d

import com.enhikyy.girassol.hardware.RenderTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** v4.0 — ambientes da Aba Mundo, selecionáveis em tempo real. */
enum class WorldPreset(val label: String) {
    /** Blocos urbanos modulares sob céu encoberto: asfalto molhado com SSR, fachadas de concreto/teal. */
    URBAN_OVERCAST("Urbano"),
    /** Grande salão Lumon: teto em caixotões emissivos, carpete esmeralda, estações de trabalho. */
    LUMON_INTERIOR("Interior")
}

/** v4.0 — modos de câmera da barra flutuante. */
enum class CameraMode(val label: String) {
    ORBIT("Órbita"),
    TILT("Inclinação"),
    FIRST_PERSON_POV("POV 1ª Pessoa"),
    TOP_2D("2D")
}

/** Fase do dia que seleciona o IBL procedural (cubemap + SH) e a exposição. */
enum class TimePhase(val label: String, val skyPreset: Int) {
    MORNING("Manhã", 0), AFTERNOON("Tarde", 1), DUSK("Entardecer", 1), NIGHT("Noite", 2), INTERIOR("Interior", 3);

    companion object {
        /**
         * [dayFraction] 0 = meia-noite (ver [SunCycle.dayFraction]). Manhã 05:30–13:00, tarde até
         * 16:48, entardecer 16:48–18:43 (v4.1: céu âmbar/rosado e janelas acendendo), depois noite.
         */
        fun of(dayFraction: Float, preset: WorldPreset): TimePhase = when {
            preset == WorldPreset.LUMON_INTERIOR -> INTERIOR
            dayFraction < 0.23f || dayFraction >= 0.78f -> NIGHT
            dayFraction < 0.54f -> MORNING
            dayFraction < 0.70f -> AFTERNOON
            else -> DUSK
        }
    }
}

/**
 * Parâmetros do pipeline cinematográfico (valores da especificação v4.0). Puro — testado na JVM
 * e aplicado à View do Filament por [FilamentCityRenderer].
 */
data class CinematicProfile(
    val resolutionScale: Float = 1.0f,
    val fxaa: Boolean = true,
    val taa: Boolean = true,
    // SSR
    val ssrEnabled: Boolean = true,
    val ssrThickness: Float = 0.1f,
    val ssrBias: Float = 0.01f,
    val ssrMaxDistance: Float = 12.0f,
    val ssrStride: Float = 2.0f,
    // sombras da luz direcional
    val castShadows: Boolean = true,
    val shadowMapSize: Int = 2048,
    val shadowCascades: Int = 3,
    val screenSpaceContactShadows: Boolean = true,
    val contactShadowSteps: Int = 16,
    // SSAO
    val ssaoEnabled: Boolean = true,
    val ssaoResolution: Float = 1.0f,
    val ssaoBilateralThreshold: Float = 0.05f,
    val ssaoRadius: Float,
    val ssaoIntensity: Float,
    val ssaoHighQuality: Boolean = true,
    // gradação
    val contrast: Float = 1.08f,
    val saturation: Float,
    val shadowsTint: FloatArray = floatArrayOf(0.94f, 1.01f, 1.02f, 0.0f),
    // bloom
    val bloomEnabled: Boolean = true,
    val bloomStrength: Float,
    val bloomLevels: Int = BLOOM_LEVELS,
    val bloomResolution: Int = 512,
    /** Pedido da especificação; o Filament 1.75 exige highlight ≥ 10 (ver [effectiveBloomHighlight]). */
    val bloomHighlight: Float = 1.0f,
    // IBL
    val iblFaceSize: Int = 256,
    // névoa (somente urbano)
    val fog: Boolean,
    // v4.1 — recursos por nível de hardware (RenderTier)
    /** Bloom em 1/4 da altura do viewport (Lite). */
    val bloomQuarterRes: Boolean = false,
    /** Cubemap de reflexo especular do IBL (Mid/Ultra); Lite usa só a irradiância SH. */
    val specularIbl: Boolean = true,
    /** Gradação "Kodak 70 mm" (curvas + mixer de canais assados na LUT 3D do Filament). */
    val kodakLut: Boolean = true,
    /** Halação vermelho-alaranjada da passada girassol_dehancer_post (0 = desligada). */
    val halation: Float = HALATION,
    /** Grão de prata 70 mm da passada girassol_dehancer_post (0 = desligado). */
    val grain: Float = GRAIN,
    /** Vinheta óptica da passada girassol_dehancer_post (14 % nas bordas, lente prime 50/70 mm). */
    val vignette: Float = VIGNETTE,
    /** Resolução das texturas procedurais PBR (2048² nos aparelhos de 8/12 GB; 256² só no emulador). */
    val textureSize: Int = 2048,
    /** Luzes pontuais do teto do salão Lumon. */
    val ceilingPointLights: Boolean = true
) {
    /** O Filament 1.75 rejeita/clampa highlight < 10; o limiar real fica no bright-pass da passada girassol_dehancer_post. */
    val effectiveBloomHighlight: Float get() = maxOf(bloomHighlight, FILAMENT_MIN_HIGHLIGHT)

    companion object {
        const val FILAMENT_MIN_HIGHLIGHT = 10f
        /** BloomOptions da especificação Kodak 70 mm: 7 níveis, força 0,30, highlight 1,0, mistura ADD. */
        const val BLOOM_LEVELS = 7
        const val BLOOM_STRENGTH = 0.30f
        const val HALATION = 0.22f
        const val GRAIN = 0.040f
        const val VIGNETTE = 0.14f
        val URBAN = CinematicProfile(ssaoRadius = 0.85f, ssaoIntensity = 1.40f, saturation = 1.10f, bloomStrength = BLOOM_STRENGTH, fog = true,
            contrast = 1.12f)
        val INTERIOR = CinematicProfile(ssaoRadius = 0.60f, ssaoIntensity = 1.80f, saturation = 1.04f, bloomStrength = BLOOM_STRENGTH, fog = false,
            contrast = 1.12f)

        fun of(p: WorldPreset) = if (p == WorldPreset.URBAN_OVERCAST) URBAN else INTERIOR

        /** Perfil efetivo após a degradação por [EffectLevel] (GPU fraca, FPS baixo, sentinela de crash). */
        fun forLevel(p: WorldPreset, l: EffectLevel): CinematicProfile {
            val base = of(p)
            return when (l) {
                EffectLevel.FULL -> base
                EffectLevel.REDUCED -> base.copy(taa = false, ssrEnabled = false, ssaoResolution = 0.5f, ssaoHighQuality = false,
                    shadowMapSize = 1024, shadowCascades = 2, contactShadowSteps = 8, bloomLevels = 4, bloomResolution = 256, iblFaceSize = 128)
                EffectLevel.MINIMAL -> base.copy(taa = false, fxaa = false, ssrEnabled = false, ssaoEnabled = false, shadowMapSize = 1024,
                    shadowCascades = 1, screenSpaceContactShadows = false, bloomEnabled = false, iblFaceSize = 64, fog = false)
                EffectLevel.NONE -> base.copy(taa = false, fxaa = false, ssrEnabled = false, ssaoEnabled = false, castShadows = false,
                    screenSpaceContactShadows = false, bloomEnabled = false, iblFaceSize = 32, fog = false)
            }
        }

        /**
         * v4.1 — perfil efetivo = degradação por [EffectLevel] (FPS/térmica/sentinela) × [RenderTier].
         * Aparelhos reais de 8 GB (MID) e 12 GB (ULTRA) recebem o pipeline COMPLETO — SSAO pleno,
         * sombras de contato, SSR, bloom 7 níveis, halação, grão 70 mm, IBL especular e texturas
         * 2048² — sem teto de RAM; o ULTRA só sobe as sombras para 4096. O perfil simplificado
         * existe exclusivamente para o EMULATOR (GL_RENDERER SwiftShader / Android Emulator).
         */
        fun forTier(p: WorldPreset, tier: RenderTier, l: EffectLevel): CinematicProfile {
            val b = forLevel(p, l)
            val post = l != EffectLevel.NONE
            return when (tier) {
                RenderTier.EMULATOR -> b.copy(castShadows = false, screenSpaceContactShadows = false, ssaoEnabled = false, ssrEnabled = false,
                    bloomEnabled = false, taa = false, fxaa = false, specularIbl = false, kodakLut = false, halation = 0f, grain = 0f,
                    textureSize = tier.textureSize, ceilingPointLights = false, iblFaceSize = minOf(b.iblFaceSize, 64), fog = false)
                RenderTier.LITE, RenderTier.MID -> b.copy(shadowMapSize = if (l == EffectLevel.FULL) RenderTier.MID.shadowMapSize else b.shadowMapSize,
                    halation = if (post) HALATION else 0f, grain = if (post) GRAIN else 0f, vignette = if (post) VIGNETTE else 0f,
                    textureSize = RenderTier.MID.textureSize)
                RenderTier.ULTRA -> b.copy(shadowMapSize = if (l == EffectLevel.FULL) RenderTier.ULTRA.shadowMapSize else b.shadowMapSize,
                    halation = if (post) HALATION else 0f, grain = if (post) GRAIN else 0f, vignette = if (post) VIGNETTE else 0f,
                    textureSize = tier.textureSize)
            }
        }
    }
}

/**
 * Estado global da vista do mundo (ambiente, câmera e cidadão do POV). Observado pela
 * [com.enhikyy.girassol.ui.screens.WorldScreen] e pelo renderizador; também comandável pelo
 * roteiro de demonstração instrumentado.
 */
object WorldViewState {
    private val _preset = MutableStateFlow(WorldPreset.URBAN_OVERCAST)
    val preset: StateFlow<WorldPreset> = _preset.asStateFlow()
    private val _camera = MutableStateFlow(CameraMode.ORBIT)
    val camera: StateFlow<CameraMode> = _camera.asStateFlow()
    private val _povCitizen = MutableStateFlow<String?>(null)
    val povCitizen: StateFlow<String?> = _povCitizen.asStateFlow()
    private val _pov = MutableStateFlow<PovTelemetry?>(null)
    /** Telemetria do POV (HUD diegético e minimapa), publicada pelo renderizador ~10×/s. */
    val pov: StateFlow<PovTelemetry?> = _pov.asStateFlow()

    fun setPreset(p: WorldPreset) { _preset.value = p }
    fun setCamera(m: CameraMode) { _camera.value = m }
    /** Entra no POV de [citizenId] (ou do mais próximo do centro da tela, se nulo). */
    fun enterPov(citizenId: String?) { _povCitizen.value = citizenId; _camera.value = CameraMode.FIRST_PERSON_POV }
    fun setPovCitizen(id: String?) { _povCitizen.value = id }
    internal fun publishPov(t: PovTelemetry?) { if (_pov.value != t) _pov.value = t }
}

/** Telemetria do POV: posição em metros, guinada, setor e vizinhos para o minimapa. */
data class PovTelemetry(
    val citizenId: String,
    val name: String,
    val xM: Float,
    val zM: Float,
    val headingDeg: Float,
    val sector: String,
    val goal: String,
    val thought: String,
    /** Vizinhos (dx, dz) em metros relativos ao cidadão, já no referencial do mundo. */
    val neighbours: List<Pair<Float, Float>>,
    val worldSizeM: Float
)
