package com.enhikyy.girassol.render3d

import kotlin.math.tan

/** Metros por tile da simulação (a grade lógica continua em tiles; a cena 3D é métrica). */
const val TILE_M = 4f

/** Camadas de visibilidade do Filament (View.setVisibleLayers). */
object WorldLayers {
    const val WORLD = 0x1
    /** Teto do salão Lumon: escondido quando o olho está acima dele (órbita de cima). */
    const val CEILING = 0x2
    /** Cidadão dono do POV: nunca visível para a própria câmera. */
    const val HIDDEN = 0x4
    const val CITIZENS = 0x8
}

/**
 * Material de superfície da cena. [surface] ≥ 0 usa o conjunto procedural C++ (albedo/ORM/normal/
 * emissivo 1024² com mips); −1 = material liso com texturas 1×1 ([rough]/[metal]/[tint]).
 */
data class MatSpec(
    val id: String,
    val surface: Int,
    /** Metros cobertos por uma repetição da textura (u, v). */
    val metersU: Float = 4f,
    val metersV: Float = metersU,
    /** Cor linear multiplicada pelo albedo. */
    val tint: FloatArray = floatArrayOf(1f, 1f, 1f),
    val rough: Float = 0.6f,
    val metal: Float = 0f,
    val normalStrength: Float = 1f,
    val emissive: FloatArray? = null,
    val emissiveDriver: EmissiveDriver = EmissiveDriver.NONE,
    /** v4.1: vidro de janela (u = id da janela; acesa/apagada e 2700–4500 K decididos no shader). */
    val window: Boolean = false,
    /** v4.1: verniz (clear coat) — no asfalto vale só sobre as poças (máscara de rugosidade no shader). */
    val clearCoat: Float = 0f,
    /** v4.1: translucidez da folhagem (subsurface scattering aproximado: luz do sol atravessando a folha). */
    val translucency: Float = 0f,
    /** v4.1: expoente de espalhamento subsuperficial (concentração do brilho contra o sol). */
    val subsurfacePower: Float = 0f
)

/**
 * v4.1 — luz pontual de teto: [lux] de iluminância alvo a [targetDistM] metros abaixo (plano de
 * trabalho). Fluxo do Filament: Φ = E · 4π · d² lúmens (fonte isotrópica, lei do inverso do quadrado).
 */
data class PointLightSpec(val pos: V3, val lux: Float, val targetDistM: Float, val falloffM: Float, val kelvin: Float = 4000f) {
    val lumens: Float get() = lux * 4f * Math.PI.toFloat() * targetDistM * targetDistM
}

/** Como a intensidade emissiva varia com a fase do dia. */
enum class EmissiveDriver { NONE, WINDOWS, BILLBOARD, STREET_LAMP, CEILING, MONITOR, BADGE }

class ScenePart(val mat: String, val mesh: LitMesh, val castShadows: Boolean = true, val layer: Int = WorldLayers.WORLD)

/** Malha repetida por instanciação (RenderableManager.Builder.instances) com guinada comum e deslocamentos de mundo. */
class InstancedPart(val mat: String, val mesh: LitMesh, val yaw: Float, val offsets: List<V3>, val castShadows: Boolean = true,
    val layer: Int = WorldLayers.WORLD)

class WorldScene(
    val preset: WorldPreset,
    val parts: List<ScenePart>,
    val instanced: List<InstancedPart>,
    /** Luzes pontuais noturnas (postes) e centros de halação. */
    val lamps: List<V3>,
    val glows: List<V3>,
    val min: V3,
    val max: V3,
    /** Altura do teto (interior) — a câmera de inclinação fica abaixo dele. */
    val ceilingY: Float?,
    /** Mapeia posição da simulação (metros) para a posição na cena (o interior comprime a cidade no salão). */
    val mapX: (Float) -> Float,
    val mapZ: (Float) -> Float,
    /** Altura do piso caminhável na posição da cena (calçadas +0,15 m). */
    val floorAt: (Float, Float) -> Float,
    /** v4.1: luzes pontuais de teto (interior Lumon: 350 lux nas mesas, 250 lux nos corredores). */
    val pointLights: List<PointLightSpec> = emptyList(),
    /** v4.1: luzes pontuais noturnas quentes (2700–3200 K) de vitrines e janelas residenciais. */
    val nightLights: List<PointLightSpec> = emptyList()
) {
    val vertexCount: Int get() = parts.sumOf { it.mesh.vertexCount } + instanced.sumOf { it.mesh.vertexCount * it.offsets.size }
    val instanceCount: Int get() = instanced.sumOf { it.offsets.size }
}

/**
 * Pose de câmera genérica (órbita, inclinação ou POV) com projeção/raycast equivalentes ao
 * Filament (perspectiva vertical, olho → alvo, +Y para cima). Kotlin puro.
 */
data class CameraPose(val eye: V3, val target: V3, val fovDeg: Float, val near: Float = 0.1f, val far: Float = 900f) {
    private fun basis(): Triple<V3, V3, V3> {
        val f = (target - eye).normalized()
        var r = f.cross(V3(0f, 1f, 0f))
        if (r.length() < 1e-5f) r = V3(1f, 0f, 0f)
        r = r.normalized()
        val u = r.cross(f).normalized()
        return Triple(f, r, u)
    }

    /** Projeta [p] em pixels de uma vista w × h; nulo se atrás da câmera. */
    fun project(p: V3, w: Float, h: Float): Pair<Float, Float>? {
        val (f, r, u) = basis()
        val d = p - eye
        val z = d.dot(f)
        if (z <= near) return null
        val t = tan(Math.toRadians(fovDeg / 2.0).toFloat())
        val aspect = w / h
        val ndcX = d.dot(r) / (z * t * aspect)
        val ndcY = d.dot(u) / (z * t)
        return ((ndcX + 1f) * 0.5f * w) to ((1f - ndcY) * 0.5f * h)
    }

    fun ray(sx: Float, sy: Float, w: Float, h: Float): Ray {
        val (f, r, u) = basis()
        val t = tan(Math.toRadians(fovDeg / 2.0).toFloat())
        val ndcX = 2f * sx / w - 1f
        val ndcY = 1f - 2f * sy / h
        return Ray(eye, (f + r * (ndcX * t * w / h) + u * (ndcY * t)).normalized())
    }
}

/** Seleção por proximidade em tela: o cidadão cujo centro projetado está mais perto do toque (dentro de [slopPx]). */
object ScreenPick {
    fun pick(pose: CameraPose, sx: Float, sy: Float, w: Float, h: Float, centers: Map<String, V3>, slopPx: Float): String? {
        var best: String? = null; var bestD = Float.MAX_VALUE
        for ((id, c) in centers) {
            val s = pose.project(c, w, h) ?: continue
            val dx = s.first - sx; val dy = s.second - sy
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            // tolerância cresce com a proximidade (o corpo ocupa mais pixels perto da câmera)
            val dist = (c - pose.eye).length().coerceAtLeast(0.5f)
            val bodyPx = 1.1f * h / (2f * tan(Math.toRadians(pose.fovDeg / 2.0).toFloat()) * dist)
            if (d <= slopPx + bodyPx && d < bestD) { bestD = d; best = id }
        }
        return best
    }
}
