package com.enhikyy.girassol

import com.enhikyy.girassol.core.cognition.AnokhinFunctionalSystem
import com.enhikyy.girassol.core.knowledge.DocumentFormat
import com.enhikyy.girassol.core.knowledge.DocumentParsers
import com.enhikyy.girassol.core.knowledge.EmergentLexicon
import com.enhikyy.girassol.core.knowledge.ParsedDocument
import com.enhikyy.girassol.core.mesh.MeshSyncProtocol
import com.enhikyy.girassol.core.mesh.MeshSyncProtocol.FrameType
import com.enhikyy.girassol.core.ports.ParityVectors
import com.enhikyy.girassol.core.rsi.CmaEs
import com.enhikyy.girassol.core.rsi.CmaEsState
import com.enhikyy.girassol.core.rsi.PolicySearchSpace
import com.enhikyy.girassol.core.rsi.RsiCoordinator
import com.enhikyy.girassol.core.util.SplitMix64
import com.enhikyy.girassol.core.world.CanonicalEvent
import com.enhikyy.girassol.core.world.CREDIT
import com.enhikyy.girassol.core.world.FOOD
import com.enhikyy.girassol.core.world.MESH_BORDER
import com.enhikyy.girassol.core.world.WorldCore
import com.enhikyy.girassol.core.world.WorldSnapshotCodec
import com.enhikyy.girassol.core.world.WorldStateHashChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

class V35Test {

    // ------------------------------------------------------------ Anokhin

    @Test
    fun anokhinGoldenIsStable() {
        assertEquals(ParityVectors.ANOKHIN_GOLDEN, AnokhinFunctionalSystem.paritySignature())
    }

    @Test
    fun acceptorConfirmsExpectedAndPunishesWorse() {
        val proj = DoubleArray(6) { 0.5 }
        val at = doubleArrayOf(0.2, 0.9, 0.9, 0.9, 0.9, 0.9)
        val ok = AnokhinFunctionalSystem.kotlinCase(proj, 0.98, 0.0, at, proj, true)
        assertEquals(1.0, ok[3], 0.0)
        assertEquals(1.0, ok[2], 0.0)
        val worse = proj.copyOf().also { it[0] = 0.1 }
        val bad = AnokhinFunctionalSystem.kotlinCase(proj, 0.9, 0.0, at, worse, false)
        assertEquals(0.0, bad[3], 0.0)
        assertTrue(bad[1] < 0.0)
        assertTrue("plasticidade ${bad[2]}", bad[2] > 1.5)
        // a motivação dominante (FOME com déficit 0.8) pesa mais que as saciadas
        val w = AnokhinFunctionalSystem.motivationWeights(at)
        assertTrue(w[0] > w[1] * 3)
    }

    @Test
    fun acceptorRunsInsideWorldCoreBeforePlasticity() {
        val core = WorldCore.create(77L)
        repeat(900) { core.step() }
        val alive = core.state.aliveCitizens()
        val conf = alive.sumOf { it.acceptorConfirmations }
        val viol = alive.sumOf { it.acceptorViolations }
        assertTrue("aceitador nunca avaliado", conf + viol > 50)
        assertTrue(core.state.acceptorMeanMismatch > 0.0)
        assertTrue(alive.any { it.acceptorPlasticity > 1.0 })
    }

    // ------------------------------------------------------------ CMA-ES

    @Test
    fun cmaEsGoldenIsStable() {
        assertEquals(ParityVectors.CMA_ES_GOLDEN, CmaEs.paritySignature())
    }

    @Test
    fun cmaEsConvergesOnShiftedEllipsoid() {
        val s = CmaEsState.initial(DoubleArray(6) { 0.5 }, 0.2, 8)
        val rng = SplitMix64(7L)
        repeat(120) {
            val xs = CmaEs.ask(s, rng)
            CmaEs.tell(s, DoubleArray(xs.size) { k -> (0 until 6).sumOf { i -> val d = xs[k][i] - 0.2 - 0.1 * i; (1.0 + i) * d * d } })
        }
        assertTrue("custo ${s.bestCost}", s.bestCost < 1e-4)
        for (i in 0 until 6) assertEquals(0.2 + 0.1 * i, s.bestX[i], 0.02)
    }

    @Test
    fun cmaEsDetExpMatchesMathExp() {
        for (x in listOf(-20.0, -3.3, -0.5, 0.0, 0.1, 1.0, 2.7, 15.0)) assertEquals(Math.exp(x), CmaEs.detExp(x), Math.exp(x) * 1e-13)
    }

    @Test
    fun policySearchSpaceRoundTrips() {
        val g = WorldCore.create(1L).state.policy.copy(locomotionSpeedScale = 1.1)
        val back = PolicySearchSpace.decode(PolicySearchSpace.encode(g), g)
        assertEquals(g.locomotionSpeedScale, back.locomotionSpeedScale, 1e-9)
        assertEquals(g.memRlLearningRate, back.memRlLearningRate, 1e-9)
    }

    @Test
    fun rsiCycleUsesCmaEsAndGate() {
        val core = WorldCore.create(5L)
        repeat(400) { core.step() }
        val rsi = RsiCoordinator()
        val rec = rsi.runCycle(core)
        assertNotNull(core.state.cmaEs)
        assertTrue(core.state.cmaEs!!.generation >= 1)
        assertTrue(rec.rationale.contains("CMA-ES"))
        val policy = core.state.policy
        assertTrue(policy.locomotionSpeedScale in 0.80..1.20)
    }

    // ------------------------------------------------------------ cadeia SHA-256

    @Test
    fun hashChainVerifiesAndDetectsTampering() {
        val core = WorldCore.create(11L)
        val events = ArrayList<CanonicalEvent>()
        repeat(300) { events += core.step().events }
        val ok = WorldStateHashChain.verifyHead(events, core.state)
        assertTrue(ok.reason, ok.ok)
        assertEquals(core.state.lastEventHash, ok.headHash)
        val i = events.size / 2
        val tampered = events.toMutableList().also { it[i] = it[i].copy(payload = it[i].payload + ";x=1") }
        val bad = WorldStateHashChain.verify(tampered)
        assertFalse(bad.ok)
        assertEquals(events[i].seq, bad.firstBadSeq)
        val gap = events.toMutableList().also { it.removeAt(i) }
        assertFalse(WorldStateHashChain.verify(gap).ok)
    }

    @Test
    fun deterministicReplayKeepsChain() {
        val a = WorldCore.create(42L); val b = WorldCore.create(42L)
        repeat(500) { a.step(); b.step() }
        assertEquals(a.state.lastEventHash, b.state.lastEventHash)
    }

    // ------------------------------------------------------------ Processo 19: parsers

    @Test
    fun txtAndLatin1() {
        val d = DocumentParsers.parse("notas.txt", "Olá, mundo do girassol.\r\nSegunda linha.".toByteArray())
        assertEquals(DocumentFormat.TXT, d.format)
        assertTrue(d.text.contains("Segunda linha"))
        val latin = DocumentParsers.parse("antigo.txt", "ação e coração".toByteArray(Charsets.ISO_8859_1))
        assertTrue(latin.text.contains("ação"))
    }

    @Test
    fun epubSpineOrder() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun put(name: String, s: String) { z.putNextEntry(ZipEntry(name)); z.write(s.toByteArray()); z.closeEntry() }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", """<?xml version="1.0"?><container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""")
            put("OEBPS/content.opf", """<package><manifest><item id="c2" href="cap2.xhtml"/><item id="c1" href="cap1.xhtml"/></manifest><spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>""")
            put("OEBPS/cap1.xhtml", "<html><body><h1>Capítulo 1</h1><p>O sol nasce &amp; o girassol gira.</p><script>x()</script></body></html>")
            put("OEBPS/cap2.xhtml", "<html><body><p>Capítulo dois termina.</p></body></html>")
        }
        val d = DocumentParsers.parse("livro.epub", bos.toByteArray())
        assertEquals(DocumentFormat.EPUB, d.format)
        assertTrue(d.text, d.text.contains("o girassol gira"))
        assertFalse(d.text.contains("x()"))
        assertTrue(d.text.indexOf("Capítulo 1") < d.text.indexOf("Capítulo dois"))
    }

    @Test
    fun pdfFlateAndHex() {
        val content = "BT /F1 12 Tf 72 720 Td (Fotoss\\355ntese das plantas) Tj 0 -14 Td [(Coop) -20 (era\\(c\\)ao)] TJ T* <0048006F006A0065> Tj ET"
        val def = Deflater(); def.setInput(content.toByteArray(Charsets.ISO_8859_1)); def.finish()
        val buf = ByteArray(4096); val n = def.deflate(buf); def.end()
        val bos = ByteArrayOutputStream()
        bos.write("%PDF-1.4\n1 0 obj\n<< /Length $n /Filter /FlateDecode >>\nstream\n".toByteArray())
        bos.write(buf, 0, n)
        bos.write("\nendstream\nendobj\ntrailer\n<<>>\n%%EOF\n".toByteArray())
        val d = DocumentParsers.parse("artigo.pdf", bos.toByteArray())
        assertEquals(DocumentFormat.PDF, d.format)
        assertTrue(d.text, d.text.contains("Fotossíntese das plantas"))
        assertTrue(d.text, d.text.contains("Coopera(c)ao"))
        assertTrue(d.text, d.text.contains("Hoje"))
    }

    @Test
    fun parquetFixtures() {
        for (name in listOf("amostra_snappy.parquet", "amostra_gzip_v2.parquet", "amostra_plain.parquet")) {
            val bytes = javaClass.getResourceAsStream("/knowledge/$name")!!.readBytes()
            val d = DocumentParsers.parse(name, bytes)
            assertEquals(name, DocumentFormat.PARQUET, d.format)
            assertTrue("$name: ${d.text.take(200)}", d.text.contains("O girassol segue o sol pela manhã."))
            assertTrue("$name", d.text.contains("Fotossíntese"))
            assertTrue("$name", d.text.contains("Cidadãos que cooperam colhem mais."))
        }
    }

    // ------------------------------------------------------------ biblioteca + léxico

    @Test
    fun knowledgeIngestionFeedsSemanticMemory() {
        val core = WorldCore.create(21L)
        val text = (1..40).joinToString(" ") { "Capítulo $it. A cooperação nos comuns melhora a colheita e o estudo da energia solar fortalece a usina." }
        val rec = core.ingestKnowledge(ParsedDocument("Manual da Biosfera", DocumentFormat.TXT, text))
        assertNotNull(rec)
        assertNull("idempotente por SHA-256", core.ingestKnowledge(ParsedDocument("cópia", DocumentFormat.TXT, text)))
        repeat(1500) { core.step() }
        val reads = core.state.knowledge.chunks.sumOf { it.reads }
        assertTrue("ninguém leu", reads > 0)
        assertTrue(core.state.citizens.any { c -> c.memories.any { it.tags.contains("LIVRO") } || c.memories.isEmpty() })
        assertTrue(core.state.recentEvents.any { it.type == "CONHECIMENTO" || it.type == "LEITURA" } || reads > 0)
    }

    @Test
    fun lexiconCoinsGlyphsAndCompresses() {
        val lex = EmergentLexicon()
        repeat(20) { lex.observe("vamos colher alimento na horta comunitária agora") }
        val e = lex.consolidate(100L, "C1")
        assertNotNull(e)
        val c = lex.compress("vamos colher alimento na horta comunitária agora")
        assertTrue(c, c.split(' ').size < 7)
        repeat(10) { lex.observe("vamos colher alimento na horta comunitária agora") }
        assertTrue(lex.compressionRatio() > 0.0)
        // determinístico
        val lex2 = EmergentLexicon()
        repeat(20) { lex2.observe("vamos colher alimento na horta comunitária agora") }
        assertEquals(e!!.phrase, lex2.consolidate(100L, "C1")!!.phrase)
    }

    @Test
    fun lexiconEmergesInWorld() {
        val core = WorldCore.create(3L)
        repeat(2400) { core.step() }
        assertTrue("sem fala observada", core.state.lexicon.observedWords > 0)
    }

    // ------------------------------------------------------------ MeshSync

    private fun pipePair(): Pair<Pair<PipedInputStream, PipedOutputStream>, Pair<PipedInputStream, PipedOutputStream>> {
        val aIn = PipedInputStream(1 shl 20); val bOut = PipedOutputStream(aIn)
        val bIn = PipedInputStream(1 shl 20); val aOut = PipedOutputStream(bIn)
        return (aIn to aOut) to (bIn to bOut)
    }

    @Test
    fun meshHandshakeRejectsWrongPin() {
        val (a, b) = pipePair()
        val ka = MeshSyncProtocol.deriveKey("246810"); val kb = MeshSyncProtocol.deriveKey("135791")
        var serverErr: Throwable? = null
        val t = thread { runCatching { MeshSyncProtocol.Session(b.first, b.second, kb, "B", false).handshake() }.onFailure { serverErr = it; b.second.close() } }
        try { MeshSyncProtocol.Session(a.first, a.second, ka, "A", true).handshake(); fail("handshake deveria falhar") } catch (_: Exception) {}
        a.second.close(); t.join(5000)
        assertNotNull(serverErr)
    }

    @Test
    fun meshDeltaSnapshotAndMigration() {
        val key = MeshSyncProtocol.deriveKey("246810")
        val origin = WorldCore.create(8L)
        repeat(300) { origin.step() }
        val base = WorldSnapshotCodec.encodeRaw(origin.state)
        repeat(40) { origin.step() }
        val raw = WorldSnapshotCodec.encodeRaw(origin.state)
        val manifest = MeshSyncProtocol.manifestOf(raw, origin.state.seed, origin.state.tick)
        val want = MeshSyncProtocol.missing(manifest, base)
        assertTrue("delta deveria reaproveitar blocos (${want.size}/${manifest.chunks.size})", want.size < manifest.chunks.size || manifest.chunks.size <= 2)

        val (a, b) = pipePair()
        var received: ByteArray? = null
        var migrant: MeshSyncProtocol.MigrationPacket? = null
        val receiver = thread {
            val s = MeshSyncProtocol.Session(b.first, b.second, key, "B", false)
            s.handshake()
            val m = s.expect(FrameType.MANIFEST) { MeshSyncProtocol.decodeManifest(it) }
            s.send(FrameType.WANT, MeshSyncProtocol.encodeWant(MeshSyncProtocol.Want(MeshSyncProtocol.missing(m, base))))
            val blocks = s.expect(FrameType.BLOCKS) { MeshSyncProtocol.decodeBlocks(it) }
            received = MeshSyncProtocol.assemble(m, base, blocks)
            s.send(FrameType.SNAPSHOT_OK, ByteArray(0))
            migrant = s.expect(FrameType.MIGRANT) { MeshSyncProtocol.decodeMigrant(it) }
            s.send(FrameType.MIGRANT_ACK, ByteArray(0))
        }
        val s = MeshSyncProtocol.Session(a.first, a.second, key, "A", true)
        s.handshake()
        assertEquals("B", s.peerId)
        s.send(FrameType.MANIFEST, MeshSyncProtocol.encodeFrame(manifest))
        val w = s.expect(FrameType.WANT) { MeshSyncProtocol.decodeWant(it) }
        s.send(FrameType.BLOCKS, MeshSyncProtocol.encodeBlocks(MeshSyncProtocol.packBlocks(raw, manifest, w.hashes)))
        s.expect(FrameType.SNAPSHOT_OK) { it }
        val emigrant = origin.state.aliveCitizens().first()
        val credits = origin.state.ledger.getBalance(emigrant.id, CREDIT)
        val packet = origin.emigrate(emigrant.id, "A")!!
        assertFalse(origin.state.citizen(emigrant.id)!!.alive)
        assertEquals(credits, origin.state.ledger.getBalance(MESH_BORDER, CREDIT), 1e-6)
        s.send(FrameType.MIGRANT, MeshSyncProtocol.encodeMigrant(packet))
        s.expect(FrameType.MIGRANT_ACK) { it }
        receiver.join(10000)

        val restored = WorldSnapshotCodec.decode(received!!)
        assertEquals(WorldSnapshotCodec.stateHash(WorldSnapshotCodec.decodeRaw(raw)), WorldSnapshotCodec.stateHash(restored))

        val dest = WorldCore.create(99L)
        val before = dest.state.aliveCitizens().size
        val admitted = dest.admitMigrant(migrant!!)
        assertEquals(before + 1, dest.state.aliveCitizens().size)
        assertEquals(emigrant.name, admitted.name)
        assertEquals(credits, dest.state.ledger.getBalance(admitted.id, CREDIT), 1e-6)
        assertTrue(dest.state.ledger.verifyZeroSumConservation(CREDIT))
        assertTrue(dest.state.ledger.verifyZeroSumConservation(FOOD))
        repeat(200) { dest.step() }
        assertTrue(WorldStateHashChain.verify(dest.state.recentEvents.toList(), dest.state.recentEvents.first().prevHash).ok)
    }

    @Test
    fun meshFrameTamperingDetected() {
        val key = MeshSyncProtocol.deriveKey("246810")
        val raw = "pacote".toByteArray()
        val packet = MeshSyncProtocol.MigrationPacket("A", 1L, 1L, WorldCore.create(1L).state.aliveCitizens().first(), mapOf(CREDIT to 3.0), "0".repeat(64))
        try { MeshSyncProtocol.decodeMigrant(MeshSyncProtocol.encodeMigrant(packet)); fail("digest inválido aceito") } catch (_: MeshSyncProtocol.MeshSyncException) {}
        assertEquals(32, MeshSyncProtocol.hmac(key, raw).size)
        assertNotNull(MeshSyncProtocol.parseBeacon(MeshSyncProtocol.beacon("dev1", 5000, "Pixel")))
        assertNull(MeshSyncProtocol.parseBeacon("OUTRO|x|1|y".toByteArray()))
    }
}

class CitizenGrowthTest {
    @Test
    fun childGrowsIntoAdult() {
        val baby = com.enhikyy.girassol.core.world.CitizenGrowth.proportions(com.enhikyy.girassol.core.world.CitizenGrowth.maturity(0.0, false))
        val kid = com.enhikyy.girassol.core.world.CitizenGrowth.proportions(com.enhikyy.girassol.core.world.CitizenGrowth.maturity(4.0, false))
        val adult = com.enhikyy.girassol.core.world.CitizenGrowth.proportions(com.enhikyy.girassol.core.world.CitizenGrowth.maturity(20.0, false))
        assertTrue(baby.height < kid.height && kid.height < adult.height)
        assertTrue(baby.headScale > adult.headScale)
        assertEquals(1f, adult.height, 1e-6f)
        assertEquals(1f, com.enhikyy.girassol.core.world.CitizenGrowth.maturity(0.0, true), 0f)
        assertTrue(com.enhikyy.girassol.core.world.CitizenGrowth.gaitCyclesPerTick(0.3, 0.4, baby) > com.enhikyy.girassol.core.world.CitizenGrowth.gaitCyclesPerTick(0.3, 0.4, adult))
    }
}

class ThemeContrastTest {
    @org.junit.Test
    fun quinzeTemasNativosPassamWcagAA() {
        val themes = com.enhikyy.girassol.core.ui.NativeThemes.ALL
        org.junit.Assert.assertEquals(15, themes.size)
        org.junit.Assert.assertEquals(15, themes.map { it.key }.toSet().size)
        val fails = themes.flatMap { com.enhikyy.girassol.core.ui.Wcag.audit(it) }.filterNot { it.passesAA }
        org.junit.Assert.assertTrue("abaixo de 4,5:1: " + fails.joinToString { "${it.theme}.${it.role}/${it.surface}=${"%.2f".format(it.ratio)}" }, fails.isEmpty())
        themes.forEach { t -> org.junit.Assert.assertTrue("${t.key}: superfície deve ser translúcida", (t.surface ushr 24) < 0xFF) }
    }

    @org.junit.Test
    fun calculadoraWcagConfereComValoresConhecidos() {
        val w = com.enhikyy.girassol.core.ui.Wcag
        org.junit.Assert.assertEquals(21.0, w.contrast(0xFFFFFFFFL, 0xFF000000L), 1e-9)
        org.junit.Assert.assertEquals(1.0, w.contrast(0xFF777777L, 0xFF777777L), 1e-9)
        // #767676 sobre branco é o cinza AA clássico (4,54:1)
        org.junit.Assert.assertEquals(4.54, w.contrast(0xFF767676L, 0xFFFFFFFFL), 0.01)
        // alfa 0x80 (128/255) de preto sobre branco → #7F7F7F
        org.junit.Assert.assertEquals(0xFF7F7F7FL, w.composite(0x80000000L, 0xFFFFFFFFL))
    }
}
