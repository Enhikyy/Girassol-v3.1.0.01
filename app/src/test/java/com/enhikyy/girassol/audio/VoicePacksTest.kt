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
        val dir = VoicePacks.installFrom(root, pack, { ByteArrayInputStream(archive) }) { progress += it }
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
        try { VoicePacks.installFrom(root, pack, { ByteArrayInputStream(archive) }); fail("SHA-256 divergente aceito") }
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
}
