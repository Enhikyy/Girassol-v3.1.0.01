package com.enhikyy.girassol.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Contêiner do arquivo `.grs` exportado/importado pelo app.
 *
 *     [snapshot do mundo GRS3 + SHA-256 (WorldSnapshotCodec)]
 *     [bloco GRSB do Orçamento Sináptico]           (opcional)
 *     [u64 LE tamanho do bloco GRSB] ["GSB1"]       (rodapé de 12 bytes)
 *
 * Sem o rodapé o arquivo é um `.grs` clássico (compatível com versões anteriores e com o
 * MeshSync). O bloco GRSB é validado aqui mesmo (cabeçalho "GRSB", versão, D ∈ {64,128,256},
 * tamanho do payload e FNV-1a 64 de cabeçalho+payload) antes de chegar ao código nativo.
 */
object GirassolRecordCodec {
    private val FOOTER_MAGIC = byteArrayOf('G'.code.toByte(), 'S'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())
    private val GRSB_MAGIC = byteArrayOf('G'.code.toByte(), 'R'.code.toByte(), 'S'.code.toByte(), 'B'.code.toByte())
    const val FOOTER_BYTES = 12
    const val GRSB_HEADER_BYTES = 24
    const val GRSB_VERSION = 1
    private const val FNV_OFFSET = -0x340d631b7bdddcdbL   // 1469598103934665603 (0xcbf29ce484222325)
    private const val FNV_PRIME = 1099511628211L

    data class Decoded(val world: ByteArray, val synaptic: ByteArray?) {
        override fun equals(other: Any?): Boolean {
            if (other !is Decoded || !world.contentEquals(other.world)) return false
            val a = synaptic; val b = other.synaptic
            return if (a == null || b == null) a == null && b == null else a.contentEquals(b)
        }
        override fun hashCode(): Int = 31 * world.contentHashCode() + (synaptic?.contentHashCode() ?: 0)
    }

    /** Informações do bloco GRSB já validado. */
    data class GrsbInfo(val dim: Int, val version: Int, val payloadBytes: Long, val checksum: Long) {
        val checksumHex: String get() = java.lang.Long.toUnsignedString(checksum, 16).padStart(16, '0')
    }

    fun fnv1a64(data: ByteArray, from: Int = 0, to: Int = data.size): Long {
        var h = FNV_OFFSET
        for (i in from until to) {
            h = h xor (data[i].toLong() and 0xFF)
            h *= FNV_PRIME
        }
        return h
    }

    /** Valida um bloco GRSB isolado; lança IllegalArgumentException com o motivo. */
    fun inspectGrsb(blob: ByteArray): GrsbInfo {
        require(blob.size >= GRSB_HEADER_BYTES + 8) { "bloco GRSB curto (${blob.size} B)" }
        require(blob.copyOfRange(0, 4).contentEquals(GRSB_MAGIC)) { "bloco sem cabeçalho GRSB" }
        val bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        val version = bb.getInt(4)
        val dim = bb.getInt(8)
        val payload = bb.getLong(16)
        require(version == GRSB_VERSION) { "versão GRSB $version não suportada" }
        require(dim == 64 || dim == 128 || dim == 256) { "dimensão D=$dim inválida" }
        require(payload > 0 && GRSB_HEADER_BYTES + payload + 8 == blob.size.toLong()) { "tamanho do payload inconsistente" }
        val stored = bb.getLong(GRSB_HEADER_BYTES + payload.toInt())
        val computed = fnv1a64(blob, 0, GRSB_HEADER_BYTES + payload.toInt())
        require(stored == computed) { "checksum FNV-1a 64 não confere" }
        return GrsbInfo(dim, version, payload, stored)
    }

    fun encode(world: ByteArray, synaptic: ByteArray?): ByteArray {
        if (synaptic == null || synaptic.isEmpty()) return world
        inspectGrsb(synaptic)
        val out = ByteBuffer.allocate(world.size + synaptic.size + FOOTER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        out.put(world).put(synaptic).putLong(synaptic.size.toLong()).put(FOOTER_MAGIC)
        return out.array()
    }

    fun decode(bytes: ByteArray): Decoded {
        if (bytes.size < FOOTER_BYTES + GRSB_HEADER_BYTES + 8) return Decoded(bytes, null)
        val tail = bytes.copyOfRange(bytes.size - 4, bytes.size)
        if (!tail.contentEquals(FOOTER_MAGIC)) return Decoded(bytes, null)
        val len = ByteBuffer.wrap(bytes, bytes.size - FOOTER_BYTES, 8).order(ByteOrder.LITTLE_ENDIAN).long
        require(len > 0 && len <= bytes.size - FOOTER_BYTES) { "rodapé GSB1 com tamanho inválido" }
        val start = bytes.size - FOOTER_BYTES - len.toInt()
        val blob = bytes.copyOfRange(start, bytes.size - FOOTER_BYTES)
        inspectGrsb(blob)
        return Decoded(bytes.copyOfRange(0, start), blob)
    }
}
