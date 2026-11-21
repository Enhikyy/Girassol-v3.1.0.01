package com.enhikyy.girassol.core.render

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Leitor KTX 2.0 mínimo e estrito para as texturas PBR procedurais do Girassol
 * (`scripts/gen_pbr_ktx2.py`): 2D, 1 face, sem camadas, sem supercompressão,
 * `VK_FORMAT_R8G8B8A8_UNORM` (37) ou `_SRGB` (43). Qualquer outro arquivo é rejeitado com
 * mensagem clara em vez de produzir lixo na GPU.
 */
object Ktx2 {
    val IDENTIFIER = byteArrayOf(0xAB.toByte(), 0x4B, 0x54, 0x58, 0x20, 0x32, 0x30, 0xBB.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    const val VK_FORMAT_R8G8B8A8_UNORM = 37
    const val VK_FORMAT_R8G8B8A8_SRGB = 43

    class Level(val width: Int, val height: Int, val rgba: ByteArray)
    class Image(val vkFormat: Int, val width: Int, val height: Int, val levels: List<Level>) {
        val srgb get() = vkFormat == VK_FORMAT_R8G8B8A8_SRGB
    }

    fun parse(bytes: ByteArray): Image {
        require(bytes.size >= 80) { "KTX2: arquivo curto (${bytes.size} bytes)" }
        for (i in IDENTIFIER.indices) require(bytes[i] == IDENTIFIER[i]) { "KTX2: identificador inválido" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.position(12)
        val vkFormat = b.int; val typeSize = b.int
        val w = b.int; val h = b.int; val depth = b.int
        val layers = b.int; val faces = b.int; val levelCount = b.int; val scheme = b.int
        require(vkFormat == VK_FORMAT_R8G8B8A8_UNORM || vkFormat == VK_FORMAT_R8G8B8A8_SRGB) { "KTX2: vkFormat $vkFormat não suportado (use RGBA8)" }
        require(typeSize == 1 && depth == 0 && layers == 0 && faces == 1) { "KTX2: só texturas 2D simples" }
        require(scheme == 0) { "KTX2: supercompressão $scheme não suportada" }
        require(w in 1..8192 && h in 1..8192) { "KTX2: dimensões inválidas ${w}x$h" }
        val n = if (levelCount == 0) 1 else levelCount
        require(n <= 14) { "KTX2: levelCount $n" }
        b.position(80)
        val levels = ArrayList<Level>(n)
        for (i in 0 until n) {
            val off = b.long; val len = b.long; val ulen = b.long
            val lw = maxOf(1, w shr i); val lh = maxOf(1, h shr i)
            val expect = lw.toLong() * lh * 4
            require(len == expect && ulen == expect) { "KTX2: nível $i com $len bytes (esperado $expect)" }
            require(off >= 0 && off + len <= bytes.size) { "KTX2: nível $i fora do arquivo" }
            levels += Level(lw, lh, bytes.copyOfRange(off.toInt(), (off + len).toInt()))
        }
        return Image(vkFormat, w, h, levels)
    }
}
