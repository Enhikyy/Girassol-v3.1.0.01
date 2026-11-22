package com.enhikyy.girassol.render3d

import com.enhikyy.girassol.core.render.CitizenRig
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * v4.1 — cidadão de superfícies lisas (seções elípticas e cápsulas, sem caixas) com coxa e
 * canela separadas para a flexão do joelho e a postura sentada.
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
                // tronco liso por seções elípticas: cintura → peito → ombros → gola
                loft(listOf(
                    LitMeshBuilder.Section(0.66f, 0.168f, 0.100f),
                    LitMeshBuilder.Section(0.78f, 0.160f, 0.104f),
                    LitMeshBuilder.Section(0.90f, 0.192f, 0.116f, cz = 0.004f),
                    LitMeshBuilder.Section(0.98f, 0.212f, 0.112f),
                    LitMeshBuilder.Section(1.035f, 0.200f, 0.096f),
                    LitMeshBuilder.Section(1.065f, 0.085f, 0.072f)
                ), segments = 18, capBottom = false, capTop = true)
            },
            Slot.TROUSERS to {
                loft(listOf(
                    LitMeshBuilder.Section(0.50f, 0.160f, 0.098f),
                    LitMeshBuilder.Section(0.58f, 0.172f, 0.104f),
                    LitMeshBuilder.Section(0.70f, 0.170f, 0.102f)
                ), segments = 18, capBottom = true, capTop = false)
            },
            Slot.SHOES to {   // cinto
                loft(listOf(LitMeshBuilder.Section(0.672f, 0.174f, 0.106f), LitMeshBuilder.Section(0.704f, 0.172f, 0.105f)),
                    segments = 18, capBottom = false, capTop = false)
            },
            Slot.SKIN to { capsule(V3(0f, 1.04f, 0f), V3(0f, 1.13f, 0f), 0.046f, 0.044f, segments = 10, capRings = 2) },
            Slot.BADGE to { rect(V3(0.06f, 0.90f, 0.121f), V3(0.08f, 0f, 0f), V3(0f, 0.042f, 0f)) }
        )
        val head = parts(
            Slot.SKIN to {
                sphere(V3(0f, 1.225f, 0f), 0.112f, 0.138f, 0.122f, rings = 14, segments = 20)
                capsule(V3(0f, 1.235f, 0.112f), V3(0f, 1.198f, 0.132f), 0.012f, 0.016f, segments = 8, capRings = 2) // nariz
                sphere(V3(-0.112f, 1.225f, 0f), 0.018f, 0.032f, 0.022f, rings = 5, segments = 8)  // orelhas
                sphere(V3(0.112f, 1.225f, 0f), 0.018f, 0.032f, 0.022f, rings = 5, segments = 8)
            },
            Slot.HAIR to { dome(V3(0f, 1.236f, -0.012f), 0.119f, 0.135f, 0.130f, upper = true, rings = 7, segments = 20) },
            Slot.SHOES to {   // olhos (escuros)
                sphere(V3(-0.040f, 1.245f, 0.108f), 0.014f, 0.010f, 0.010f, rings = 4, segments = 8)
                sphere(V3(0.040f, 1.245f, 0.108f), 0.014f, 0.010f, 0.010f, rings = 4, segments = 8)
            }
        )
        val mouth = parts(Slot.SHOES to { capsule(V3(-0.026f, 1.207f, 0.118f), V3(0.026f, 1.207f, 0.118f), 0.007f, segments = 6, capRings = 2) })
        fun arm(x: Float) = parts(
            Slot.SHIRT to { capsule(V3(x, 1.0f, 0f), V3(x, 0.665f, 0.005f), 0.057f, 0.045f, segments = 12, capRings = 3) },
            Slot.SKIN to { sphere(V3(x, 0.612f, 0.012f), 0.040f, 0.056f, 0.034f, rings = 6, segments = 10) }
        )
        // coxa (osso da perna, quadril → joelho) e canela (osso do joelho) separadas para flexão
        fun thigh(x: Float) = parts(
            Slot.TROUSERS to { capsule(V3(x, 0.56f, 0f), V3(x, CitizenRig.KNEE_Y, 0.004f), 0.074f, 0.062f, segments = 12, capRings = 3) }
        )
        fun shin(x: Float) = parts(
            Slot.TROUSERS to { capsule(V3(x, CitizenRig.KNEE_Y, 0.004f), V3(x, 0.10f, 0f), 0.061f, 0.050f, segments = 12, capRings = 3) },
            Slot.SHOES to {
                capsule(V3(x, 0.05f, -0.035f), V3(x, 0.045f, 0.105f), 0.050f, 0.044f, segments = 10, capRings = 3)
                chamferBox(V3(x - 0.052f, 0f, -0.085f), V3(x + 0.052f, 0.022f, 0.150f), 0.01f)   // sola
            }
        )
        val p = CitizenRig.PIVOTS
        return listOf(torso, head, mouth, arm(p[CitizenRig.BONE_ARM_L][0]), arm(p[CitizenRig.BONE_ARM_R][0]),
            thigh(p[CitizenRig.BONE_LEG_L][0]), thigh(p[CitizenRig.BONE_LEG_R][0]),
            shin(p[CitizenRig.BONE_SHIN_L][0]), shin(p[CitizenRig.BONE_SHIN_R][0]))
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
