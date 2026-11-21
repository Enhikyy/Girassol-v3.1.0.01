package com.enhikyy.girassol.core.render

import com.enhikyy.girassol.core.world.CitizenGrowth
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Esqueleto procedural do cidadão (7 ossos, skinning rígido no Filament) e a pose de cada
 * quadro. Lógica pura (testada na JVM): o renderizador só copia as matrizes.
 *
 * Sincronização exigida pela especificação:
 *  - passada/cadência das pernas e balanço dos braços seguem a velocidade ORCA/RVO2
 *    ([CitizenGrowth.gaitCyclesPerTick]) e a passada da idade;
 *  - digitação em terminal CRT: antebraços à frente com batidas rápidas alternadas;
 *  - amplitude vocal do Pocket-TTS ([amplitude] 0..1): abertura da boca e gesto da cabeça;
 *  - crescimento infantil → adulto: escala total, cabeça maior, membros mais curtos.
 * As matrizes são colunas-maiores (convenção do Filament), em espaço do modelo.
 */
object CitizenRig {
    const val BONE_TORSO = 0
    const val BONE_HEAD = 1
    const val BONE_MOUTH = 2
    const val BONE_ARM_L = 3
    const val BONE_ARM_R = 4
    const val BONE_LEG_L = 5
    const val BONE_LEG_R = 6
    const val BONES = 7

    /** Pivôs na pose de ligação (adulto, altura 1,42). */
    val PIVOTS: Array<FloatArray> = arrayOf(
        floatArrayOf(0f, 0.55f, 0f),        // tronco (quadril)
        floatArrayOf(0f, 1.08f, 0f),        // cabeça (pescoço)
        floatArrayOf(0f, 1.215f, 0.155f),   // boca (lábio superior)
        floatArrayOf(-0.27f, 1.0f, 0f),     // ombro esquerdo
        floatArrayOf(0.27f, 1.0f, 0f),      // ombro direito
        floatArrayOf(-0.10f, 0.55f, 0f),    // quadril esquerdo
        floatArrayOf(0.10f, 0.55f, 0f)      // quadril direito
    )

    class State {
        var phase = 0f       // ciclos de marcha acumulados
        var yaw = 0f         // rad, suavizado
        var typePhase = 0f
        var gesture = 0f
    }

    data class Input(
        val maturity: Float, val vx: Float, val vz: Float, val ticksPerSecond: Float,
        val typing: Boolean, val amplitude: Float, val dt: Float
    )

    /** Avança [s] e escreve 7 matrizes 4×4 (112 floats) em [out]. Retorna a matriz raiz (16 floats). */
    fun pose(s: State, inp: Input, out: FloatArray): FloatArray {
        require(out.size >= BONES * 16)
        val p = CitizenGrowth.proportions(inp.maturity)
        val cyclesPerTick = CitizenGrowth.gaitCyclesPerTick(inp.vx.toDouble(), inp.vz.toDouble(), p)
        val tps = inp.ticksPerSecond.coerceIn(0f, 240f)
        s.phase = (s.phase + cyclesPerTick * tps * inp.dt) % 1024f
        val speed = kotlin.math.sqrt(inp.vx * inp.vx + inp.vz * inp.vz)
        val walking = (speed * tps / 0.6f).coerceIn(0f, 1f)          // 0 parado … 1 andando
        if (speed > 1e-4f) {
            val target = kotlin.math.atan2(inp.vx, inp.vz)
            var d = target - s.yaw
            while (d > PI) d -= (2 * PI).toFloat(); while (d < -PI) d += (2 * PI).toFloat()
            s.yaw += d * (inp.dt * 8f).coerceAtMost(1f)
        }
        s.typePhase = (s.typePhase + inp.dt * 9f) % 1024f
        val amp = inp.amplitude.coerceIn(0f, 1f)
        s.gesture += (amp - s.gesture) * (inp.dt * 10f).coerceAtMost(1f)

        val swing = (sin(s.phase * 2.0 * PI).toFloat()) * 0.62f * walking
        val bob = kotlin.math.abs(sin(s.phase * 2.0 * PI).toFloat()) * 0.03f * walking

        val limb = p.limbScale
        val armSwingL = -swing * 0.8f
        val armSwingR = swing * 0.8f
        val armL = if (inp.typing) -1.15f + 0.08f * sin(s.typePhase * 2.0 * PI).toFloat() else armSwingL
        val armR = if (inp.typing) -1.15f + 0.08f * sin((s.typePhase + 0.5f) * 2.0 * PI).toFloat() else armSwingR
        val headNod = s.gesture * 0.12f * sin(s.typePhase * 1.3).toFloat() + if (inp.typing) 0.18f else 0f
        val headYaw = s.gesture * 0.10f * sin(s.typePhase * 0.7).toFloat()

        fun bone(bone: Int, rotX: Float, rotY: Float, sx: Float, sy: Float, sz: Float, lift: Float): FloatArray {
            val pv = PIVOTS[bone]
            return Mat4.mul(Mat4.translate(pivotX(bone, p), pivotY(bone, p) + lift, pv[2]),
                Mat4.mul(Mat4.rotY(rotY), Mat4.mul(Mat4.rotX(rotX),
                    Mat4.mul(Mat4.scale(sx, sy, sz), Mat4.translate(-pv[0], -pv[1], -pv[2])))))
        }
        val torso = bone(BONE_TORSO, 0f, 0f, p.torsoWidth, 1f, p.torsoWidth, bob)
        val head = bone(BONE_HEAD, headNod, headYaw, p.headScale, p.headScale, p.headScale, bob)
        // boca = cabeça · abertura vertical em torno do lábio superior (amplitude do Pocket-TTS)
        val mp = PIVOTS[BONE_MOUTH]
        val mouth = Mat4.mul(head, Mat4.mul(Mat4.translate(mp[0], mp[1], mp[2]),
            Mat4.mul(Mat4.scale(1f, 1f + 3.2f * s.gesture, 1f), Mat4.translate(-mp[0], -mp[1], -mp[2]))))
        val bones = arrayOf(torso, head, mouth,
            bone(BONE_ARM_L, armL, 0f, 1f, limb, 1f, bob),
            bone(BONE_ARM_R, armR, 0f, 1f, limb, 1f, bob),
            bone(BONE_LEG_L, swing, 0f, 1f, limb, 1f, 0f),
            bone(BONE_LEG_R, -swing, 0f, 1f, limb, 1f, 0f))
        for (i in 0 until BONES) bones[i].copyInto(out, i * 16)

        // raiz: escala global da idade + guinada pela direção da velocidade
        return Mat4.mul(Mat4.rotY(s.yaw), Mat4.scale(p.height, p.height, p.height))
    }

    /** Pivôs reposicionados pelas proporções: pernas mais curtas abaixam quadril, tronco e cabeça. */
    private fun legLen(p: CitizenGrowth.Proportions) = 0.55f * p.limbScale
    private fun pivotX(bone: Int, p: CitizenGrowth.Proportions): Float = when (bone) {
        BONE_ARM_L, BONE_ARM_R, BONE_LEG_L, BONE_LEG_R -> PIVOTS[bone][0] * p.torsoWidth
        else -> PIVOTS[bone][0]
    }
    private fun pivotY(bone: Int, p: CitizenGrowth.Proportions): Float {
        val hip = legLen(p)
        val neck = hip + 0.53f
        return when (bone) {
            BONE_TORSO, BONE_LEG_L, BONE_LEG_R -> hip
            BONE_ARM_L, BONE_ARM_R -> hip + 0.45f
            BONE_HEAD -> neck
            BONE_MOUTH -> neck + (PIVOTS[BONE_MOUTH][1] - PIVOTS[BONE_HEAD][1]) * p.headScale
            else -> PIVOTS[bone][1]
        }
    }
}

/** Matrizes 4×4 colunas-maiores mínimas (convenção do Filament). */
object Mat4 {
    fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    fun translate(x: Float, y: Float, z: Float) = identity().also { it[12] = x; it[13] = y; it[14] = z }
    fun scale(x: Float, y: Float, z: Float) = identity().also { it[0] = x; it[5] = y; it[10] = z }
    fun rotX(a: Float): FloatArray { val c = cos(a); val s = sin(a); return identity().also { it[5] = c; it[6] = s; it[9] = -s; it[10] = c } }
    fun rotY(a: Float): FloatArray { val c = cos(a); val s = sin(a); return identity().also { it[0] = c; it[2] = -s; it[8] = s; it[10] = c } }
    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (c in 0 until 4) for (row in 0 until 4) {
            var acc = 0f
            for (k in 0 until 4) acc += a[k * 4 + row] * b[c * 4 + k]
            r[c * 4 + row] = acc
        }
        return r
    }
    fun transform(m: FloatArray, x: Float, y: Float, z: Float) = floatArrayOf(
        m[0] * x + m[4] * y + m[8] * z + m[12], m[1] * x + m[5] * y + m[9] * z + m[13], m[2] * x + m[6] * y + m[10] * z + m[14])
}
