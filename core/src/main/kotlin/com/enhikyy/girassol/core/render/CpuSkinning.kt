package com.enhikyy.girassol.core.render

/**
 * Skinning rígido na CPU (1 osso por vértice, peso 1) para a malha do cidadão.
 *
 * O skinning de GPU do Filament adiciona ao vertex shader um bloco de uniformes de ossos que
 * estoura `GL_MAX_VERTEX_UNIFORM_VECTORS` (256) em GPUs modestas e no SwiftShader, derrubando
 * o processo no link do programa. A malha do cidadão tem só ~170 vértices, então transformá-la
 * na CPU a cada quadro custa microssegundos e funciona em qualquer GPU.
 *
 * [bones] usa o layout de [CitizenRig.pose]: [CitizenRig.BONES] matrizes 4×4 coluna-maior.
 */
object CpuSkinning {
    fun skin(positions: FloatArray, boneOf: ShortArray, bones: FloatArray, out: FloatArray) {
        require(out.size >= positions.size) { "saída menor que a malha" }
        val n = positions.size / 3
        for (i in 0 until n) {
            val o = boneOf[i].toInt() * 16
            val x = positions[i * 3]; val y = positions[i * 3 + 1]; val z = positions[i * 3 + 2]
            out[i * 3] = bones[o] * x + bones[o + 4] * y + bones[o + 8] * z + bones[o + 12]
            out[i * 3 + 1] = bones[o + 1] * x + bones[o + 5] * y + bones[o + 9] * z + bones[o + 13]
            out[i * 3 + 2] = bones[o + 2] * x + bones[o + 6] * y + bones[o + 10] * z + bones[o + 14]
        }
    }
}
