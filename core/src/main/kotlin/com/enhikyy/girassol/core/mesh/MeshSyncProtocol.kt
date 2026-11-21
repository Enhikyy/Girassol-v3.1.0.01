package com.enhikyy.girassol.core.mesh

import com.enhikyy.girassol.core.util.Sha256
import com.enhikyy.girassol.core.util.toHex
import com.enhikyy.girassol.core.world.Citizen
import com.enhikyy.girassol.core.world.WorldSnapshotCodec
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * MeshSync LAN — protocolo entre dois aparelhos Girassol na mesma rede Wi-Fi, sem internet.
 *
 *  - Descoberta: NSD/mDNS `_girassol._tcp` + beacon UDP (no app; aqui só o formato do beacon).
 *  - Autenticação mútua por desafio-resposta HMAC-SHA256 sobre uma chave derivada do PIN de
 *    pareamento (PBKDF2-HMAC-SHA256, 60 000 iterações). O PIN nunca trafega.
 *  - Cada quadro após o handshake carrega HMAC-SHA256(chave_sessão, seq ‖ tipo ‖ payload),
 *    com seq monotônico (anti-replay).
 *  - Snapshot diferencial `.grs`: o CBOR bruto do mundo é fatiado por *content-defined
 *    chunking* (hash rolante Gear); só os blocos ausentes no receptor são enviados (gzip), e
 *    o mundo remontado é verificado pelo SHA-256 do GRS3 antes de ser aceito.
 *  - Migração de cidadãos: o cidadão inteiro (genoma, memórias, habilidades, adaptador) e
 *    seus saldos viajam num [MigrationPacket] assinado.
 */
@OptIn(ExperimentalSerializationApi::class)
object MeshSyncProtocol {
    const val SERVICE_TYPE = "_girassol._tcp."
    const val BEACON_PORT = 47653
    const val PROTOCOL_VERSION = 1
    const val PBKDF2_ITERATIONS = 60_000
    const val MAX_FRAME = 64 shl 20
    private val SALT = "girassol-meshsync-v1".toByteArray()
    private val cbor = Cbor { ignoreUnknownKeys = true; encodeDefaults = true }

    enum class FrameType(val code: Int) {
        HELLO(1), CHALLENGE(2), RESPONSE(3), MANIFEST(10), WANT(11), BLOCKS(12), SNAPSHOT_OK(13),
        MIGRANT(20), MIGRANT_ACK(21), BYE(30), ERROR(31);
        companion object { fun of(c: Int) = entries.firstOrNull { it.code == c } ?: throw MeshSyncException("tipo de quadro $c desconhecido") }
    }

    class MeshSyncException(msg: String) : Exception(msg)

    // ------------------------------------------------------------ criptografia

    fun deriveKey(pin: String): ByteArray {
        require(pin.length >= 6) { "PIN de pareamento precisa de ≥ 6 dígitos" }
        val spec = PBEKeySpec(pin.toCharArray(), SALT, PBKDF2_ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) { mac.update(intBytes(p.size)); mac.update(p) }
        return mac.doFinal()
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)

    fun nonce(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun intBytes(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun longBytes(v: Long) = ByteArray(8) { (v ushr (56 - 8 * it)).toByte() }

    /** Beacon UDP: "GIRASSOL1|<deviceId>|<porta TCP>|<nome>" (sem segredos). */
    fun beacon(deviceId: String, tcpPort: Int, name: String) = "GIRASSOL$PROTOCOL_VERSION|$deviceId|$tcpPort|${name.take(40)}".toByteArray()

    fun parseBeacon(bytes: ByteArray, len: Int = bytes.size): Triple<String, Int, String>? {
        val p = String(bytes, 0, len, Charsets.UTF_8).split('|')
        if (p.size < 4 || p[0] != "GIRASSOL$PROTOCOL_VERSION") return null
        return Triple(p[1], p[2].toIntOrNull() ?: return null, p[3])
    }

    // ------------------------------------------------------------ sessão autenticada

    /**
     * Canal autenticado sobre um par de streams (TCP no app; pipes nos testes).
     * Papel `initiator` = quem conecta. Após [handshake], use [send]/[receive].
     */
    class Session(private val input: InputStream, private val output: OutputStream, private val pairingKey: ByteArray,
                  val deviceId: String, private val initiator: Boolean) {
        private val din = DataInputStream(input)
        private val dout = DataOutputStream(output)
        private var sessionKey: ByteArray? = null
        private var sendSeq = 0L
        private var recvSeq = 0L
        var peerId: String = ""; private set

        private fun rawSend(type: FrameType, payload: ByteArray, mac: ByteArray) {
            dout.writeByte(type.code); dout.writeInt(payload.size); dout.write(payload); dout.write(mac); dout.flush()
        }

        private fun rawReceive(): Triple<FrameType, ByteArray, ByteArray> {
            val t = try { din.readUnsignedByte() } catch (e: EOFException) { throw MeshSyncException("conexão encerrada") }
            val len = din.readInt()
            if (len < 0 || len > MAX_FRAME) throw MeshSyncException("quadro de $len bytes rejeitado")
            val payload = ByteArray(len); din.readFully(payload)
            val mac = ByteArray(32); din.readFully(mac)
            return Triple(FrameType.of(t), payload, mac)
        }

        fun handshake() {
            val zero = ByteArray(32)
            if (initiator) {
                val na = nonce()
                rawSend(FrameType.HELLO, cbor.encodeToByteArray(Hello.serializer(), Hello(PROTOCOL_VERSION, deviceId, na)), zero)
                val (t, p, mac) = rawReceive()
                if (t != FrameType.CHALLENGE) throw MeshSyncException("esperado CHALLENGE, recebido $t")
                val ch = cbor.decodeFromByteArray(Challenge.serializer(), p)
                val expect = hmac(pairingKey, "B".toByteArray(), na, ch.nonce, deviceId.toByteArray(), ch.deviceId.toByteArray())
                if (!constantTimeEquals(expect, ch.proof) || !constantTimeEquals(mac, zero)) throw MeshSyncException("par não autenticado (PIN diferente?)")
                val proof = hmac(pairingKey, "A".toByteArray(), ch.nonce, na, ch.deviceId.toByteArray(), deviceId.toByteArray())
                rawSend(FrameType.RESPONSE, proof, zero)
                peerId = ch.deviceId
                sessionKey = hmac(pairingKey, "S".toByteArray(), na, ch.nonce)
            } else {
                val (t, p, _) = rawReceive()
                if (t != FrameType.HELLO) throw MeshSyncException("esperado HELLO, recebido $t")
                val hello = cbor.decodeFromByteArray(Hello.serializer(), p)
                if (hello.version != PROTOCOL_VERSION) throw MeshSyncException("versão de protocolo ${hello.version} incompatível")
                val nb = nonce()
                val proof = hmac(pairingKey, "B".toByteArray(), hello.nonce, nb, hello.deviceId.toByteArray(), deviceId.toByteArray())
                rawSend(FrameType.CHALLENGE, cbor.encodeToByteArray(Challenge.serializer(), Challenge(deviceId, nb, proof)), zero)
                val (t2, resp, _) = rawReceive()
                if (t2 != FrameType.RESPONSE) throw MeshSyncException("esperado RESPONSE, recebido $t2")
                val expect = hmac(pairingKey, "A".toByteArray(), nb, hello.nonce, deviceId.toByteArray(), hello.deviceId.toByteArray())
                if (!constantTimeEquals(expect, resp)) throw MeshSyncException("par não autenticado (PIN diferente?)")
                peerId = hello.deviceId
                sessionKey = hmac(pairingKey, "S".toByteArray(), hello.nonce, nb)
            }
        }

        fun send(type: FrameType, payload: ByteArray) {
            val key = sessionKey ?: throw MeshSyncException("handshake pendente")
            val seq = ++sendSeq
            rawSend(type, payload, hmac(key, longBytes(seq), byteArrayOf(type.code.toByte()), payload))
        }

        fun receive(): Pair<FrameType, ByteArray> {
            val key = sessionKey ?: throw MeshSyncException("handshake pendente")
            val (t, p, mac) = rawReceive()
            val seq = ++recvSeq
            if (!constantTimeEquals(hmac(key, longBytes(seq), byteArrayOf(t.code.toByte()), p), mac)) throw MeshSyncException("HMAC do quadro inválido (adulterado ou repetido)")
            if (t == FrameType.ERROR) throw MeshSyncException("par reportou erro: ${p.toString(Charsets.UTF_8)}")
            return t to p
        }

        inline fun <reified T> expect(type: FrameType, decode: (ByteArray) -> T): T {
            val (t, p) = receive()
            if (t != type) throw MeshSyncException("esperado $type, recebido $t")
            return decode(p)
        }
    }

    @Serializable class Hello(val version: Int, val deviceId: String, val nonce: ByteArray)
    @Serializable class Challenge(val deviceId: String, val nonce: ByteArray, val proof: ByteArray)

    // ------------------------------------------------------------ snapshot diferencial

    @Serializable class ChunkRef(val hash: String, val offset: Int, val length: Int)
    @Serializable class Manifest(val worldSeed: Long, val tick: Long, val totalBytes: Int, val sha256: String, val chunks: List<ChunkRef>)
    @Serializable class Want(val hashes: List<String>)
    @Serializable class Blocks(val hashes: List<String>, val gz: ByteArray)

    private val GEAR = LongArray(256).also { var x = 0x9E3779B97F4A7C15uL.toLong(); for (i in 0 until 256) { x += -0x61c8864680b583ebL; var z = x; z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L; z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L; it[i] = z xor (z ushr 31) } }

    /** Content-defined chunking (Gear): blocos de 2–64 KiB, média ~8 KiB; deslocamentos não propagam. */
    fun cdcChunks(data: ByteArray, minSize: Int = 2048, maxSize: Int = 65536, maskBits: Int = 13): List<ChunkRef> {
        val mask = (1L shl maskBits) - 1
        val out = ArrayList<ChunkRef>()
        var start = 0
        var h = 0L
        var i = 0
        while (i < data.size) {
            h = (h shl 1) + GEAR[data[i].toInt() and 0xFF]
            val len = i - start + 1
            if ((len >= minSize && (h and mask) == 0L) || len >= maxSize) {
                out += ChunkRef(Sha256.bytes(data.copyOfRange(start, i + 1)).toHex(), start, len)
                start = i + 1; h = 0L
            }
            i++
        }
        if (start < data.size) out += ChunkRef(Sha256.bytes(data.copyOfRange(start, data.size)).toHex(), start, data.size - start)
        return out
    }

    fun manifestOf(raw: ByteArray, seed: Long, tick: Long) = Manifest(seed, tick, raw.size, Sha256.bytes(raw).toHex(), cdcChunks(raw))

    /** Blocos que o receptor não possui (a partir do seu snapshot-base, se houver). */
    fun missing(manifest: Manifest, base: ByteArray?): List<String> {
        val have = base?.let { b -> cdcChunks(b).map { it.hash }.toHashSet() } ?: hashSetOf()
        return manifest.chunks.map { it.hash }.filter { it !in have }.distinct()
    }

    fun packBlocks(raw: ByteArray, manifest: Manifest, want: List<String>): Blocks {
        val byHash = manifest.chunks.associateBy { it.hash }
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gz -> val d = DataOutputStream(gz); for (h in want) { val c = byHash[h] ?: continue; d.writeInt(c.length); d.write(raw, c.offset, c.length) } }
        return Blocks(want.filter { it in byHash }, bos.toByteArray())
    }

    /** Remonta o CBOR bruto e o devolve como `.grs` (GRS3 + SHA-256) validado. */
    fun assemble(manifest: Manifest, base: ByteArray?, blocks: Blocks): ByteArray {
        val pool = HashMap<String, ByteArray>()
        base?.let { b -> for (c in cdcChunks(b)) pool[c.hash] = b.copyOfRange(c.offset, c.offset + c.length) }
        DataInputStream(GZIPInputStream(ByteArrayInputStream(blocks.gz))).use { d ->
            for (h in blocks.hashes) { val n = d.readInt(); val buf = ByteArray(n); d.readFully(buf); pool[h] = buf }
        }
        val out = ByteArrayOutputStream(manifest.totalBytes)
        for (c in manifest.chunks) {
            val part = pool[c.hash] ?: throw MeshSyncException("bloco ${c.hash.take(12)} ausente")
            if (Sha256.bytes(part).toHex() != c.hash) throw MeshSyncException("bloco ${c.hash.take(12)} corrompido")
            out.write(part)
        }
        val raw = out.toByteArray()
        if (Sha256.bytes(raw).toHex() != manifest.sha256) throw MeshSyncException("SHA-256 do snapshot remontado não confere")
        val state = WorldSnapshotCodec.decodeRaw(raw)
        return WorldSnapshotCodec.encode(state)
    }

    fun encodeFrame(m: Manifest) = cbor.encodeToByteArray(Manifest.serializer(), m)
    fun decodeManifest(b: ByteArray) = cbor.decodeFromByteArray(Manifest.serializer(), b)
    fun encodeWant(w: Want) = cbor.encodeToByteArray(Want.serializer(), w)
    fun decodeWant(b: ByteArray) = cbor.decodeFromByteArray(Want.serializer(), b)
    fun encodeBlocks(b: Blocks) = cbor.encodeToByteArray(Blocks.serializer(), b)
    fun decodeBlocks(b: ByteArray) = cbor.decodeFromByteArray(Blocks.serializer(), b)

    // ------------------------------------------------------------ migração

    @Serializable
    class MigrationPacket(
        val originDevice: String,
        val originSeed: Long,
        val originTick: Long,
        val citizen: Citizen,
        /** Saldos (recurso → quantidade) que acompanham o migrante. */
        val wallet: Map<String, Double>,
        val sha256: String
    )

    fun citizenDigest(c: Citizen, wallet: Map<String, Double>): String =
        Sha256.bytes(cbor.encodeToByteArray(Citizen.serializer(), c) + wallet.toSortedMap().toString().toByteArray()).toHex()

    fun encodeMigrant(p: MigrationPacket) = cbor.encodeToByteArray(MigrationPacket.serializer(), p)

    fun decodeMigrant(b: ByteArray): MigrationPacket {
        val p = cbor.decodeFromByteArray(MigrationPacket.serializer(), b)
        if (citizenDigest(p.citizen, p.wallet) != p.sha256) throw MeshSyncException("pacote de migração adulterado")
        return p
    }
}
