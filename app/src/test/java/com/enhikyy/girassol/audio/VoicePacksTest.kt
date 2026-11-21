package com.enhikyy.girassol.audio

import com.enhikyy.girassol.llm.LlmQuantPolicy
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/** v3.7: pacotes de voz opcionais — gate por perfil, SHA-256 em duas camadas e extração segura. */
class VoicePacksTest {
    private val mb = 1024L * 1024

    private fun tarBz2(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bos)).use { tar ->
            for ((name, data) in entries) {
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong() })
                tar.write(data); tar.closeArchiveEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun sha(b: ByteArray) = VoicePacks.sha256(ByteArrayInputStream(b))

    /** Servidor HTTP em memória que honra Range (206) e, opcionalmente, derruba a conexão. */
    private class FakeServer(val data: ByteArray, val dropAfter: List<Int> = emptyList(), val honorRange: Boolean = true) {
        val offsets = mutableListOf<Long>()
        var calls = 0
        fun open(url: String, offset: Long): VoicePacks.Opened {
            offsets += offset
            val drop = dropAfter.getOrNull(calls++)
            val start = if (honorRange) offset.toInt() else 0
            val end = if (drop != null) minOf(data.size, start + drop) else data.size
            val slice = data.copyOfRange(start, end)
            val input = object : java.io.InputStream() {
                var i = 0
                override fun read(): Int = if (i < slice.size) slice[i++].toInt() and 0xFF
                    else if (drop != null) throw java.net.SocketException("conexão redefinida") else -1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (i >= slice.size) { if (drop != null) throw java.net.SocketException("conexão redefinida"); return -1 }
                    val n = minOf(len, slice.size - i); System.arraycopy(slice, i, b, off, n); i += n; return n
                }
            }
            return VoicePacks.Opened(input, if (honorRange) offset else 0L) {}
        }
    }

    private fun simplePack(archive: ByteArray, model: ByteArray) = VoicePacks.Pack("rt", "Retomada", VoicePacks.Kind.ASR, "https://exemplo/rt.tar.bz2",
        archive.size.toLong(), sha(archive), "pk", mapOf("model.onnx" to sha(model)), listOf("model.onnx"))

    @Test fun backendsOpcionaisSoNosPerfis8e12Gb() {
        assertFalse("SM-A226BR (3631 MB) não pode receber Piper/Whisper", VoicePacks.allowedFor(LlmQuantPolicy.profileFor(3631 * mb)))
        assertTrue(VoicePacks.allowedFor(LlmQuantPolicy.profileFor(8 * 1024 * mb)))
        assertTrue(VoicePacks.allowedFor(LlmQuantPolicy.profileFor(12 * 1024 * mb)))
    }

    @Test fun pacotesOficiaisTemHashesFixados() {
        for (p in VoicePacks.ALL) {
            assertTrue(p.url.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/"))
            assertEquals(64, p.archiveSha256.length)
            assertTrue(p.files.isNotEmpty() && p.files.values.all { it.length == 64 && it.all { c -> c in "0123456789abcdef" } })
        }
        assertEquals(VoicePacks.Kind.TTS, VoicePacks.PIPER_PT_BR.kind)
        assertEquals(VoicePacks.Kind.ASR, VoicePacks.WHISPER_TINY.kind)
    }

    @Test fun instalaVerificaEExtraiSoOsArquivosNecessarios() {
        val model = ByteArray(70_000) { (it * 31 + 7).toByte() }
        val tokens = "a 0\nb 1\n".toByteArray()
        val archive = tarBz2(mapOf("pk/model.onnx" to model, "pk/tokens.txt" to tokens, "pk/espeak-ng-data/pt_dict" to byteArrayOf(1, 2, 3), "pk/test_wavs/0.wav" to ByteArray(100)))
        val pack = VoicePacks.Pack("teste", "Teste", VoicePacks.Kind.TTS, "https://exemplo/pk.tar.bz2", archive.size.toLong(), sha(archive), "pk",
            mapOf("model.onnx" to sha(model), "tokens.txt" to sha(tokens)), listOf("model.onnx", "tokens.txt", "espeak-ng-data/"))
        val root = Files.createTempDirectory("vp").toFile()
        val progress = mutableListOf<Float>()
        val dir = VoicePacks.installFrom(root, pack, { _, _ -> VoicePacks.Opened(ByteArrayInputStream(archive), 0L) {} }, progress = { progress += it })
        assertTrue(VoicePacks.isInstalled(dir, pack))
        assertNull(VoicePacks.verifyFiles(dir, pack))
        assertTrue(File(dir, "espeak-ng-data/pt_dict").isFile)
        assertFalse("arquivos fora de keepPrefixes não são extraídos", File(dir, "test_wavs/0.wav").exists())
        assertEquals(1f, progress.last())
        // adulteração pós-instalação é detectada pela verificação por arquivo
        File(dir, "model.onnx").appendBytes(byteArrayOf(0))
        assertTrue(VoicePacks.verifyFiles(dir, pack)!!.contains("model.onnx"))
        root.deleteRecursively()
    }

    @Test fun hashDoPacoteDivergenteRejeita() {
        val archive = tarBz2(mapOf("pk/model.onnx" to ByteArray(10)))
        val pack = VoicePacks.Pack("ruim", "Ruim", VoicePacks.Kind.ASR, "https://exemplo/x", archive.size.toLong(), "0".repeat(64), "pk",
            mapOf("model.onnx" to "0".repeat(64)), listOf("model.onnx"))
        val root = Files.createTempDirectory("vp").toFile()
        try { VoicePacks.installFrom(root, pack, { _, _ -> VoicePacks.Opened(ByteArrayInputStream(archive), 0L) {} }, sleep = {}); fail("SHA-256 divergente aceito") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("SHA-256")) }
        assertFalse(File(root, "ruim").exists())
        assertTrue(root.listFiles()!!.isEmpty())
        root.deleteRecursively()
    }

    @Test fun pathTraversalBloqueado() {
        val archive = tarBz2(mapOf("pk/../../fora.txt" to ByteArray(4)))
        val root = Files.createTempDirectory("vp").toFile()
        val dest = File(root, "d").apply { mkdirs() }
        try { VoicePacks.extractTarBz2(File(root, "a.tar.bz2").apply { writeBytes(archive) }, dest, "pk", listOf("")); fail("traversal aceito") }
        catch (_: IllegalStateException) {}
        assertFalse(File(root.parentFile, "fora.txt").exists())
        root.deleteRecursively()
    }

    @Test fun downloadInterrompidoRetomaComRangeERecuo() {
        val model = ByteArray(300_000) { (it * 131 + 17).toByte() }
        val archive = tarBz2(mapOf("pk/model.onnx" to model))
        val pack = simplePack(archive, model)
        val root = Files.createTempDirectory("vp").toFile()
        // 1ª conexão cai após 1/3, 2ª após mais 1/3, 3ª termina
        val third = archive.size / 3
        val server = FakeServer(archive, dropAfter = listOf(third, third))
        val sleeps = mutableListOf<Long>()
        val retries = mutableListOf<Int>()
        val dir = VoicePacks.installFrom(root, pack, server::open, onRetry = { a, _ -> retries += a }, sleep = { sleeps += it })
        assertEquals(listOf(0L, third.toLong(), 2L * third), server.offsets)
        assertEquals(listOf(1, 2), retries)
        assertEquals(listOf(1_000L, 2_000L), sleeps)
        assertNull(VoicePacks.verifyFiles(dir, pack))
        assertFalse(".part removido após instalar", VoicePacks.partFile(root, pack).exists())
        root.deleteRecursively()
    }

    @Test fun partDeExecucaoAnteriorERetomado() {
        val model = ByteArray(120_000) { (it * 7 + 3).toByte() }
        val archive = tarBz2(mapOf("pk/model.onnx" to model))
        val pack = simplePack(archive, model)
        val root = Files.createTempDirectory("vp").toFile().apply { mkdirs() }
        VoicePacks.partFile(root, pack).writeBytes(archive.copyOfRange(0, archive.size / 2))
        assertTrue(VoicePacks.partFraction(root, pack) in 0.45f..0.55f)
        val server = FakeServer(archive)
        VoicePacks.installFrom(root, pack, server::open, sleep = {})
        assertEquals(listOf(archive.size / 2L), server.offsets)
        root.deleteRecursively()
    }

    @Test fun servidorSemRangeRecomecaDoZero() {
        val model = ByteArray(50_000) { (it * 3).toByte() }
        val archive = tarBz2(mapOf("pk/model.onnx" to model))
        val pack = simplePack(archive, model)
        val root = Files.createTempDirectory("vp").toFile().apply { mkdirs() }
        VoicePacks.partFile(root, pack).writeBytes(archive.copyOfRange(0, 1000))
        val dir = VoicePacks.installFrom(root, pack, FakeServer(archive, honorRange = false)::open, sleep = {})
        assertNull(VoicePacks.verifyFiles(dir, pack))
        root.deleteRecursively()
    }

    @Test fun redeIndisponivelFalhaComMensagemClaraEGuardaParcial() {
        val model = ByteArray(80_000) { (it * 5).toByte() }
        val archive = tarBz2(mapOf("pk/model.onnx" to model))
        val pack = simplePack(archive, model)
        val root = Files.createTempDirectory("vp").toFile()
        val server = FakeServer(archive, dropAfter = List(VoicePacks.MAX_ATTEMPTS) { 100 })
        try { VoicePacks.installFrom(root, pack, server::open, sleep = {}); fail("deveria falhar") }
        catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("após ${VoicePacks.MAX_ATTEMPTS} tentativas"))
            assertTrue(VoicePacks.describe(e).contains("download parcial foi guardado"))
        }
        assertEquals(VoicePacks.MAX_ATTEMPTS * 100L, VoicePacks.partFile(root, pack).length())
        assertTrue(VoicePacks.describe(java.net.UnknownHostException("github.com")).contains("sem conexão"))
        root.deleteRecursively()
    }

    @Test fun corrupcaoNoTransitoRebaixaUmaVez() {
        val model = ByteArray(40_000) { (it * 11).toByte() }
        val archive = tarBz2(mapOf("pk/model.onnx" to model))
        val pack = simplePack(archive, model)
        val root = Files.createTempDirectory("vp").toFile()
        val corrupt = archive.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        var n = 0
        val dir = VoicePacks.installFrom(root, pack, { _, _ -> VoicePacks.Opened(ByteArrayInputStream(if (n++ == 0) corrupt else archive), 0L) {} }, sleep = {})
        assertEquals(2, n)
        assertNull(VoicePacks.verifyFiles(dir, pack))
        root.deleteRecursively()
    }
}
