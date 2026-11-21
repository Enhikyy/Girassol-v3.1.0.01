package com.enhikyy.girassol.knowledge

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.enhikyy.girassol.core.knowledge.DocumentFormat
import com.enhikyy.girassol.core.knowledge.DocumentParsers
import com.enhikyy.girassol.core.knowledge.ParsedDocument
import com.enhikyy.girassol.sim.SimulationHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Processo 19 — ingestão offline via SAF: TXT, EPUB, PDF, Parquet e áudio (SenseVoice). */
object LibraryIngest {
    const val MAX_DOC_BYTES = 64L shl 20
    val MIME_TYPES = arrayOf("text/plain", "application/epub+zip", "application/pdf", "application/octet-stream",
        "application/vnd.apache.parquet", "audio/*", "*/*")
    private val AUDIO_EXT = setOf("wav", "mp3", "m4a", "aac", "ogg", "opus", "flac", "3gp", "amr", "webm")

    private fun displayName(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "documento"

    private fun isAudio(context: Context, uri: Uri, name: String): Boolean =
        context.contentResolver.getType(uri)?.startsWith("audio/") == true || name.substringAfterLast('.', "").lowercase() in AUDIO_EXT

    suspend fun ingest(context: Context, uri: Uri, onProgress: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        val name = displayName(context, uri)
        runCatching {
            val doc = if (isAudio(context, uri, name)) {
                onProgress("Transcrevendo $name (SenseVoice-Small)…")
                val text = AudioTranscriber.transcribe(context, uri) { p -> onProgress("Transcrevendo $name: ${(p * 100).toInt()}%") }
                if (text.isBlank()) error("transcrição vazia")
                ParsedDocument(name.substringBeforeLast('.'), DocumentFormat.AUDIO, text)
            } else {
                val bytes = context.contentResolver.openInputStream(uri)?.use { ins ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(1 shl 16); var total = 0L
                    while (true) { val n = ins.read(buf); if (n < 0) break; total += n
                        if (total > MAX_DOC_BYTES) error("documento maior que ${MAX_DOC_BYTES shr 20} MB"); out.write(buf, 0, n) }
                    out.toByteArray()
                } ?: error("não foi possível abrir o arquivo")
                onProgress("Lendo $name…")
                DocumentParsers.parse(name, bytes)
            }
            SimulationHub.ingestDocument(doc)
        }.getOrElse { "Falha ao ingerir $name: ${it.message}" }
    }
}
