package com.enhikyy.girassol.render3d

import com.enhikyy.girassol.core.render.CitizenRig
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * v4.0 — cidadão articulado (tronco, cabeça, boca, braços e pernas como renderables
 * separados, filhos de uma raiz). Cada osso recebe a matriz de [CitizenRig.pose] direto no
 * TransformManager — sem skinning (que estoura o limite de uniformes em GPUs modestas).
 * Malhas na pose de ligação do rig (adulto = 1,42 unidade) com roupas sóbrias.
 */
object CitizenModel {
    /** Escala unidade-do-rig → metros: olho do adulto (1,245 u) a 1,68 m. */
    const val SCALE = 1.35f
    const val EYE_RIG_Y = 1.245f
    const val EYE_HEIGHT_M = EYE_RIG_Y * SCALE   // ≈ 1,68 m

    enum class Slot { SKIN, SHIRT, TROUSERS, SHOES, HAIR, BADGE }

    class BonePart(val slot: Slot, val mesh: LitMesh)

    /** Malhas por osso (índice = CitizenRig.BONE_*). */
    val bones: List<List<BonePart>> by lazy { buildBones() }

    private fun buildBones(): List<List<BonePart>> {
        fun parts(vararg p: Pair<Slot, LitMeshBuilder.() -> Unit>): List<BonePart> =
            p.map { (s, f) -> BonePart(s, LitMeshBuilder().apply(f).build()) }
        val torso = parts(
            Slot.SHIRT to {
                chamferBox(V3(-0.20f, 0.68f, -0.115f), V3(0.20f, 1.05f, 0.115f), 0.05f)
                chamferBox(V3(-0.24f, 0.93f, -0.11f), V3(0.24f, 1.04f, 0.11f), 0.045f) // ombros
                // gola
                cylinder(0f, 0f, 1.03f, 1.07f, 0.065f, segments = 10)
            },
            Slot.TROUSERS to { chamferBox(V3(-0.19f, 0.52f, -0.11f), V3(0.19f, 0.70f, 0.11f), 0.04f) },
            Slot.SHOES to { box(V3(-0.192f, 0.675f, -0.112f), V3(0.192f, 0.705f, 0.112f)) }, // cinto
            Slot.SKIN to { cylinder(0f, 0f, 1.05f, 1.12f, 0.045f, segments = 8) },          // pescoço
            Slot.BADGE to { rect(V3(0.06f, 0.92f, 0.117f), V3(0.09f, 0f, 0f), V3(0f, 0.045f, 0f)) }
        )
        val head = parts(
            Slot.SKIN to {
                sphere(V3(0f, 1.225f, 0f), 0.112f, 0.138f, 0.122f, rings = 10, segments = 14)
                chamferBox(V3(-0.012f, 1.19f, 0.105f), V3(0.012f, 1.235f, 0.14f), 0.008f)   // nariz
                sphere(V3(-0.112f, 1.225f, 0f), 0.018f, 0.032f, 0.022f, rings = 4, segments = 6)  // orelhas
                sphere(V3(0.112f, 1.225f, 0f), 0.018f, 0.032f, 0.022f, rings = 4, segments = 6)
            },
            Slot.HAIR to { sphere(V3(0f, 1.262f, -0.018f), 0.118f, 0.118f, 0.124f, rings = 8, segments = 14) },
            Slot.SHOES to {   // olhos (escuros)
                sphere(V3(-0.040f, 1.245f, 0.108f), 0.014f, 0.010f, 0.010f, rings = 3, segments = 6)
                sphere(V3(0.040f, 1.245f, 0.108f), 0.014f, 0.010f, 0.010f, rings = 3, segments = 6)
            }
        )
        val mouth = parts(Slot.SHOES to { box(V3(-0.030f, 1.200f, 0.110f), V3(0.030f, 1.214f, 0.128f)) })
        fun arm(x: Float) = parts(
            Slot.SHIRT to { cylinder(x, 0f, 0.66f, 1.01f, 0.048f, 0.056f, segments = 10) },
            Slot.SKIN to { sphere(V3(x, 0.615f, 0.01f), 0.042f, 0.055f, 0.036f, rings = 5, segments = 8) }
        )
        fun leg(x: Float) = parts(
            Slot.TROUSERS to { cylinder(x, 0f, 0.085f, 0.56f, 0.060f, 0.074f, segments = 10) },
            Slot.SHOES to { chamferBox(V3(x - 0.05f, 0f, -0.06f), V3(x + 0.05f, 0.09f, 0.14f), 0.025f) }
        )
        val p = CitizenRig.PIVOTS
        return listOf(torso, head, mouth, arm(p[CitizenRig.BONE_ARM_L][0]), arm(p[CitizenRig.BONE_ARM_R][0]),
            leg(p[CitizenRig.BONE_LEG_L][0]), leg(p[CitizenRig.BONE_LEG_R][0]))
    }

    /** Paleta determinística por id (pele, camisa, calça, sapato, cabelo). */
    data class Palette(val skin: FloatArray, val shirt: FloatArray, val trousers: FloatArray, val shoes: FloatArray, val hair: FloatArray)

    fun palette(id: String): Palette {
        val h = id.hashCode().let { it xor (it ushr 16) } and 0x7fffffff
        val m = WorldMaterials
        return Palette(m.SKIN[h % m.SKIN.size], m.SHIRTS[(h / 7) % m.SHIRTS.size], m.TROUSERS[(h / 61) % m.TROUSERS.size],
            m.SHOES[(h / 409) % m.SHOES.size], m.HAIR[(h / 2003) % m.HAIR.size])
    }

    /** Disco de sombra de contato (quad 1 × 1 m no chão, UV 0..1). */
    fun contactShadowMesh(): LitMesh = LitMeshBuilder().apply {
        val base = vertexCount
        val up = V3(0f, 1f, 0f); val tg = V3(1f, 0f, 0f)
        vertex(V3(-0.5f, 0f, 0.5f), tg, up, 0f, 0f); vertex(V3(0.5f, 0f, 0.5f), tg, up, 1f, 0f)
        vertex(V3(0.5f, 0f, -0.5f), tg, up, 1f, 1f); vertex(V3(-0.5f, 0f, -0.5f), tg, up, 0f, 1f)
        tri(base, base + 1, base + 2); tri(base, base + 2, base + 3)
    }.build()
}

/**
 * v4.0 — câmera em primeira pessoa (POV) presa aos olhos do cidadão: olho a 1,68 m (escala
 * pela altura da idade), segue a guinada do rig, balanço de cabeça sincronizado com a passada
 * e arrasto livre de guinada/arfagem (±60°). Kotlin puro — testado na JVM.
 */
class FirstPersonCamera {
    companion object {
        const val FOV_DEG = 62f
        val MAX_PITCH = Math.toRadians(60.0).toFloat()
        const val BOB_M = 0.032f
    }
    var yawOffset = 0f; private set
    var pitch = -0.10f; private set

    fun drag(dxPx: Float, dyPx: Float, viewH: Float) {
        val k = 2.4f / viewH.coerceAtLeast(1f)
        yawOffset -= dxPx * k
        while (yawOffset > PI) yawOffset -= (2 * PI).toFloat(); while (yawOffset < -PI) yawOffset += (2 * PI).toFloat()
        pitch = (pitch + dyPx * k).coerceIn(-MAX_PITCH, MAX_PITCH)
    }

    fun reset() { yawOffset = 0f; pitch = -0.10f }

    /** Balanço vertical da cabeça: 2 picos por ciclo de marcha, proporcional ao quanto está andando. */
    fun bob(gaitPhase: Float, walking: Float): Float = BOB_M * walking.coerceIn(0f, 1f) * abs(sin(gaitPhase * 2.0 * PI).toFloat())

    /**
     * Pose do olho. [feet] = posição dos pés (cena, m); [heading] = guinada do rig (frente =
     * (sin, 0, cos)); [heightScale] = altura relativa da idade (adulto = 1).
     */
    fun pose(feet: V3, heading: Float, heightScale: Float, gaitPhase: Float, walking: Float, ceilingY: Float? = null): CameraPose {
        val yaw = heading + yawOffset
        val fwd = V3(sin(heading), 0f, cos(heading))
        var eyeY = feet.y + CitizenModel.EYE_HEIGHT_M * heightScale + bob(gaitPhase, walking)
        if (ceilingY != null) eyeY = eyeY.coerceAtMost(ceilingY - 0.2f)
        val eye = V3(feet.x, eyeY, feet.z) + fwd * (0.11f * heightScale)
        val dir = V3(sin(yaw) * cos(pitch), sin(pitch), cos(yaw) * cos(pitch))
        return CameraPose(eye, eye + dir * 10f, FOV_DEG, near = 0.06f)
    }
}
