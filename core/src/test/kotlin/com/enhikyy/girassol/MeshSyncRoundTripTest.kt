package com.enhikyy.girassol

import com.enhikyy.girassol.core.forge.LoraAdapterState
import com.enhikyy.girassol.core.knowledge.EmergentLexicon
import com.enhikyy.girassol.core.mesh.MeshSyncProtocol
import com.enhikyy.girassol.core.mesh.MeshSyncProtocol.FrameType
import com.enhikyy.girassol.core.rsi.CmaEsState
import com.enhikyy.girassol.core.rsi.RsiCoordinator
import com.enhikyy.girassol.core.world.Citizen
import com.enhikyy.girassol.core.world.WorldCore
import com.enhikyy.girassol.core.world.WorldSnapshotCodec
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

/**
 * v3.7 — round-trip do MeshSync: pesos LoRA (adaptador do mundo + adaptadores pessoais), matriz
 * de covariância do CMA-ES e léxicos individuais `.grs` atravessam a migração (snapshot
 * diferencial CDC + pacote de migrante, sessão HMAC sobre pipes reais) bit a bit intactos.
 */
@OptIn(ExperimentalSerializationApi::class)
class MeshSyncRoundTripTest {
    private val cbor = Cbor { encodeDefaults = true }

    private fun bits(a: FloatArray) = IntArray(a.size) { java.lang.Float.floatToRawIntBits(a[it]) }
    private fun bits(a: DoubleArray) = LongArray(a.size) { java.lang.Double.doubleToRawLongBits(a[it]) }
    private fun lexBytes(l: EmergentLexicon) = cbor.encodeToByteArray(EmergentLexicon.serializer(), l)

    private fun assertLoraEquals(a: LoraAdapterState, b: LoraAdapterState) {
        assertEquals(a.rank, b.rank); assertEquals(a.inDim, b.inDim); assertEquals(a.outDim, b.outDim)
        assertArrayEquals("LoRA A", bits(a.a), bits(b.a))
        assertArrayEquals("LoRA B", bits(a.b), bits(b.b))
        assertArrayEquals("âncora A (EWC)", bits(a.anchorA), bits(b.anchorA))
        assertArrayEquals("âncora B (EWC)", bits(a.anchorB), bits(b.anchorB))
        assertArrayEquals("Fisher A", bits(a.fisherA), bits(b.fisherA))
        assertArrayEquals("Fisher B", bits(a.fisherB), bits(b.fisherB))
        assertArrayEquals("Adam mA", bits(a.mA), bits(b.mA))
        assertArrayEquals("Adam vB", bits(a.vB), bits(b.vB))
        assertEquals(a.acceptedCycles, b.acceptedCycles)
        assertEquals(a.version, b.version)
    }

    private fun assertCmaEquals(a: CmaEsState, b: CmaEsState) {
        assertEquals(a.n, b.n); assertEquals(a.lambda, b.lambda); assertEquals(a.generation, b.generation)
        assertEquals(java.lang.Double.doubleToRawLongBits(a.sigma), java.lang.Double.doubleToRawLongBits(b.sigma))
        assertArrayEquals("covariância C", bits(a.c), bits(b.c))
        assertArrayEquals("média", bits(a.mean), bits(b.mean))
        assertArrayEquals("autovetores B", bits(a.b), bits(b.b))
        assertArrayEquals("autovalores D", bits(a.d), bits(b.d))
        assertArrayEquals("pc", bits(a.pc), bits(b.pc))
        assertArrayEquals("ps", bits(a.ps), bits(b.ps))
    }

    private fun assertCitizenMindEquals(a: Citizen, b: Citizen) {
        assertArrayEquals("adaptador pessoal (LoRA do cidadão)", bits(a.personalAdapter), bits(b.personalAdapter))
        assertArrayEquals("léxico .grs de ${a.name}", lexBytes(a.lexicon), lexBytes(b.lexicon))
        assertEquals(a.lexicon.entries.map { it.glyph to it.phrase }, b.lexicon.entries.map { it.glyph to it.phrase })
        assertEquals(java.lang.Double.doubleToRawLongBits(a.lexicon.mutationRate), java.lang.Double.doubleToRawLongBits(b.lexicon.mutationRate))
        assertEquals(a.replay.size, b.replay.size)
        fun per(c: Citizen) = c.replay.map { e -> listOf(e.action, e.tick, java.lang.Float.floatToRawIntBits(e.reward), e.reflexFeatures.toList(), java.lang.Double.doubleToRawLongBits(e.priority)) }
        assertEquals("buffer PER", per(a), per(b))
        assertEquals(java.lang.Double.doubleToRawLongBits(a.irtTheta), java.lang.Double.doubleToRawLongBits(b.irtTheta))
    }

    private fun pipePair(): Pair<Pair<PipedInputStream, PipedOutputStream>, Pair<PipedInputStream, PipedOutputStream>> {
        val aIn = PipedInputStream(1 shl 22); val bOut = PipedOutputStream(aIn)
        val bIn = PipedInputStream(1 shl 22); val aOut = PipedOutputStream(bIn)
        return (aIn to aOut) to (bIn to bOut)
    }

    @Test
    fun loraCmaEsAndLexiconsSurviveMeshSync() {
        val origin = WorldCore.create(37L, 24)
        repeat(1600) { origin.step() }
        // geração real do CMA-ES (ask/tell persistido no WorldState) antes da migração
        RsiCoordinator(replayTicks = 120, cmaReplayTicks = 40).runCycle(origin)
        repeat(200) { origin.step() }
        val st = origin.state
        assertNotNull("CMA-ES ausente", st.cmaEs)
        assertTrue("CMA-ES sem geração", st.cmaEs!!.generation >= 1)
        val cmaOffDiag = (0 until st.cmaEs!!.n).sumOf { i -> (0 until st.cmaEs!!.n).filter { it != i }.sumOf { j -> Math.abs(st.cmaEs!!.c[i * st.cmaEs!!.n + j]) } }
        assertTrue("covariância ainda é a identidade", cmaOffDiag > 0.0)
        assertTrue("LoRA sem pesos", st.lora.a.any { it != 0f })
        val withLex = st.aliveCitizens().filter { it.lexicon.observedWords > 0 }
        assertTrue("nenhum léxico individual povoado", withLex.isNotEmpty())
        assertTrue("adaptadores pessoais não treinados", st.aliveCitizens().any { c -> c.personalAdapter.any { it != 0f } })

        // --- 1) snapshot .grs diferencial (CDC + blocos gzip) por sessão autenticada
        val key = MeshSyncProtocol.deriveKey("246810")
        val raw = WorldSnapshotCodec.encodeRaw(st)
        val manifest = MeshSyncProtocol.manifestOf(raw, st.seed, st.tick)
        val emigrant = st.aliveCitizens().maxByOrNull { it.lexicon.entries.size * 1000L + it.lexicon.observedWords }!!
        val emigrantBefore = cbor.decodeFromByteArray(Citizen.serializer(), cbor.encodeToByteArray(Citizen.serializer(), emigrant))
        val packet = origin.emigrate(emigrant.id, "A")!!

        val (a, b) = pipePair()
        var received: ByteArray? = null
        var migrant: MeshSyncProtocol.MigrationPacket? = null
        var err: Throwable? = null
        val receiver = thread {
            runCatching {
                val s = MeshSyncProtocol.Session(b.first, b.second, key, "B", false)
                s.handshake()
                val m = s.expect(FrameType.MANIFEST) { MeshSyncProtocol.decodeManifest(it) }
                s.send(FrameType.WANT, MeshSyncProtocol.encodeWant(MeshSyncProtocol.Want(MeshSyncProtocol.missing(m, null))))
                val blocks = s.expect(FrameType.BLOCKS) { MeshSyncProtocol.decodeBlocks(it) }
                received = MeshSyncProtocol.assemble(m, null, blocks)
                s.send(FrameType.SNAPSHOT_OK, ByteArray(0))
                migrant = s.expect(FrameType.MIGRANT) { MeshSyncProtocol.decodeMigrant(it) }
                s.send(FrameType.MIGRANT_ACK, ByteArray(0))
            }.onFailure { err = it }
        }
        val s = MeshSyncProtocol.Session(a.first, a.second, key, "A", true)
        s.handshake()
        s.send(FrameType.MANIFEST, MeshSyncProtocol.encodeFrame(manifest))
        val w = s.expect(FrameType.WANT) { MeshSyncProtocol.decodeWant(it) }
        s.send(FrameType.BLOCKS, MeshSyncProtocol.encodeBlocks(MeshSyncProtocol.packBlocks(raw, manifest, w.hashes)))
        s.expect(FrameType.SNAPSHOT_OK) { it }
        s.send(FrameType.MIGRANT, MeshSyncProtocol.encodeMigrant(packet))
        s.expect(FrameType.MIGRANT_ACK) { it }
        receiver.join(20000)
        if (err != null) throw AssertionError("receptor falhou", err)

        // --- 2) mundo restaurado: LoRA, CMA-ES e cada léxico individual idênticos
        val restored = WorldSnapshotCodec.decode(received!!)
        val original = WorldSnapshotCodec.decodeRaw(raw)
        assertEquals(WorldSnapshotCodec.stateHash(original), WorldSnapshotCodec.stateHash(restored))
        assertLoraEquals(original.lora, restored.lora)
        assertCmaEquals(original.cmaEs!!, restored.cmaEs!!)
        assertEquals(original.citizens.size, restored.citizens.size)
        for ((x, y) in original.citizens.zip(restored.citizens)) {
            assertEquals(x.id, y.id)
            assertCitizenMindEquals(x, y)
        }

        // --- 3) migrante: adaptador pessoal + léxico + PER/IRT chegam intactos e seguem vivos no destino
        val m = migrant!!
        assertCitizenMindEquals(emigrantBefore, m.citizen)
        val dest = WorldCore.create(91L, 24)
        val admitted = dest.admitMigrant(m)
        assertCitizenMindEquals(emigrantBefore, admitted)
        // ida e volta: o .grs do destino preserva o léxico do imigrante
        val back = WorldSnapshotCodec.decode(WorldSnapshotCodec.encode(dest.state)).citizen(admitted.id)!!
        assertCitizenMindEquals(emigrantBefore, back)
        repeat(240) { dest.step() }
        assertTrue(dest.state.citizen(admitted.id)!!.lexicon.observedWords >= emigrantBefore.lexicon.observedWords)
    }

    @Test
    fun rejectsOversizedManifestBeforeAllocatingSnapshotBuffer() {
        val manifest = MeshSyncProtocol.Manifest(
            worldSeed = 1L,
            tick = 0L,
            totalBytes = MeshSyncProtocol.MAX_FRAME + 1,
            sha256 = "0".repeat(64),
            chunks = emptyList()
        )
        val failure = runCatching {
            MeshSyncProtocol.assemble(manifest, null, MeshSyncProtocol.Blocks(emptyList(), byteArrayOf()))
        }.exceptionOrNull()
        assertTrue("manifesto acima de MAX_FRAME deve ser rejeitado", failure is MeshSyncProtocol.MeshSyncException)
    }

    @Test
    fun rejectsManifestWithDiscontinuousChunkOffsets() {
        val manifest = MeshSyncProtocol.Manifest(
            worldSeed = 1L,
            tick = 0L,
            totalBytes = 2,
            sha256 = "0".repeat(64),
            chunks = listOf(MeshSyncProtocol.ChunkRef("1".repeat(64), 1, 1))
        )
        val failure = runCatching {
            MeshSyncProtocol.assemble(manifest, null, MeshSyncProtocol.Blocks(emptyList(), byteArrayOf()))
        }.exceptionOrNull()
        assertTrue("offsets descontínuos devem ser rejeitados", failure is MeshSyncProtocol.MeshSyncException)
    }
}
