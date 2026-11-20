package com.enhikyy.girassol.core.world

import com.enhikyy.girassol.core.util.Sha256
import com.enhikyy.girassol.core.util.toHex
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Codec de snapshot `world_snapshot.bin`: CBOR (kotlinx.serialization) + GZIP,
 * com cabeçalho mágico e SHA-256 do payload para validação na restauração.
 */
@OptIn(ExperimentalSerializationApi::class)
object WorldSnapshotCodec {
    private val cbor = Cbor { ignoreUnknownKeys = true; encodeDefaults = true }
    private val MAGIC = byteArrayOf('G'.code.toByte(), 'R'.code.toByte(), 'S'.code.toByte(), '3'.code.toByte())

    fun encodeRaw(state: WorldState): ByteArray = cbor.encodeToByteArray(WorldState.serializer(), state)

    fun decodeRaw(bytes: ByteArray): WorldState = cbor.decodeFromByteArray(WorldState.serializer(), bytes)

    fun stateHash(state: WorldState): String = Sha256.bytes(encodeRaw(state)).toHex()

    fun encode(state: WorldState): ByteArray {
        val raw = encodeRaw(state)
        val digest = Sha256.bytes(raw)
        val bos = ByteArrayOutputStream()
        bos.write(MAGIC)
        bos.write(digest)
        GZIPOutputStream(bos).use { it.write(raw) }
        return bos.toByteArray()
    }

    /** Decodifica e valida o SHA-256; lança [IllegalStateException] se corrompido. */
    fun decode(bytes: ByteArray): WorldState {
        check(bytes.size > 36 && bytes.copyOfRange(0, 4).contentEquals(MAGIC)) { "snapshot sem cabeçalho GRS3" }
        val digest = bytes.copyOfRange(4, 36)
        val raw = GZIPInputStream(ByteArrayInputStream(bytes, 36, bytes.size - 36)).use { it.readBytes() }
        check(Sha256.bytes(raw).contentEquals(digest)) { "SHA-256 do snapshot não confere" }
        return decodeRaw(raw)
    }

    fun deepCopy(state: WorldState): WorldState = decodeRaw(encodeRaw(state))
}
