package com.enhikyy.girassol.core.knowledge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/** Formatos aceitos pelo Processo 19 (biblioteca offline). Áudio chega já transcrito. */
enum class DocumentFormat(val label: String) { TXT("TXT"), EPUB("EPUB"), PDF("PDF"), PARQUET("Parquet"), AUDIO("Áudio (SenseVoice)") }

class ParsedDocument(val title: String, val format: DocumentFormat, val text: String)

/**
 * Extratores 100 % offline e sem dependências nativas (JVM pura: roda no aparelho e nos
 * testes). Limitações documentadas: PDF sem CMaps/ToUnicode de fontes compostas (extrai os
 * operadores Tj/TJ/'/" de streams FlateDecode ou sem filtro); Parquet só colunas BYTE_ARRAY
 * (texto) com páginas v1/v2, codificação PLAIN ou dicionário e compressão
 * UNCOMPRESSED/SNAPPY/GZIP.
 */
object DocumentParsers {
    const val MAX_TEXT_CHARS = 2_000_000

    fun detect(name: String, head: ByteArray): DocumentFormat? {
        val lower = name.lowercase()
        return when {
            head.size >= 4 && head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'D'.code.toByte() && head[3] == 'F'.code.toByte() -> DocumentFormat.PDF
            head.size >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "PAR1" -> DocumentFormat.PARQUET
            lower.endsWith(".epub") -> DocumentFormat.EPUB
            lower.endsWith(".txt") || lower.endsWith(".md") -> DocumentFormat.TXT
            lower.endsWith(".pdf") -> DocumentFormat.PDF
            lower.endsWith(".parquet") -> DocumentFormat.PARQUET
            head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> DocumentFormat.EPUB
            else -> null
        }
    }

    fun parse(name: String, bytes: ByteArray, format: DocumentFormat? = detect(name, bytes.copyOf(minOf(8, bytes.size)))): ParsedDocument {
        val f = format ?: DocumentFormat.TXT
        val title = name.substringAfterLast('/').substringBeforeLast('.').ifBlank { name }
        val text = when (f) {
            DocumentFormat.TXT, DocumentFormat.AUDIO -> decodeText(bytes)
            DocumentFormat.EPUB -> Epub.extract(bytes)
            DocumentFormat.PDF -> Pdf.extract(bytes)
            DocumentFormat.PARQUET -> Parquet.extractText(bytes)
        }
        return ParsedDocument(title, f, normalize(text).take(MAX_TEXT_CHARS))
    }

    /** UTF-8 estrito; se inválido, Windows-1252/ISO-8859-1 (arquivos antigos em português). */
    fun decodeText(bytes: ByteArray): String {
        var start = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) start = 3
        val dec = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try { dec.decode(ByteBuffer.wrap(bytes, start, bytes.size - start)).toString() }
        catch (_: CharacterCodingException) { String(bytes, start, bytes.size - start, Charsets.ISO_8859_1) }
    }

    fun normalize(s: String): String = s.replace("\r\n", "\n").replace('\r', '\n')
        .replace(Regex("[\\t\\u00A0 ]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()

    // ------------------------------------------------------------------ EPUB

    object Epub {
        fun extract(bytes: ByteArray): String {
            val files = LinkedHashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (!e.isDirectory) files[e.name] = zin.readBytes()
                }
            }
            val container = files["META-INF/container.xml"]?.toString(Charsets.UTF_8)
            val opfPath = container?.let { Regex("full-path=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
            val ordered = mutableListOf<String>()
            val opf = opfPath?.let { files[it]?.toString(Charsets.UTF_8) }
            if (opf != null) {
                val base = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                val manifest = HashMap<String, String>()
                for (m in Regex("<item\\b[^>]*>").findAll(opf)) {
                    val tag = m.value
                    val id = Regex("\\bid=\"([^\"]+)\"").find(tag)?.groupValues?.get(1) ?: continue
                    val href = Regex("\\bhref=\"([^\"]+)\"").find(tag)?.groupValues?.get(1) ?: continue
                    manifest[id] = base + java.net.URLDecoder.decode(href, "UTF-8")
                }
                for (m in Regex("<itemref\\b[^>]*idref=\"([^\"]+)\"").findAll(opf)) manifest[m.groupValues[1]]?.let { ordered += it }
            }
            if (ordered.isEmpty()) ordered += files.keys.filter { it.endsWith(".xhtml") || it.endsWith(".html") || it.endsWith(".htm") }.sorted()
            return ordered.mapNotNull { files[it] }.joinToString("\n\n") { Html.toText(it.toString(Charsets.UTF_8)) }
        }
    }

    object Html {
        private val entities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
            "ccedil" to "ç", "atilde" to "ã", "otilde" to "õ", "aacute" to "á", "eacute" to "é", "iacute" to "í", "oacute" to "ó",
            "uacute" to "ú", "acirc" to "â", "ecirc" to "ê", "ocirc" to "ô", "agrave" to "à", "mdash" to "—", "ndash" to "–", "hellip" to "…")

        fun toText(html: String): String {
            var s = html.replace(Regex("(?is)<(script|style|head)\\b.*?</\\1>"), " ")
            s = s.replace(Regex("(?i)<(br|/p|/div|/h[1-6]|/li|/tr)\\b[^>]*>"), "\n")
            s = s.replace(Regex("<[^>]+>"), " ")
            s = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(s) { m ->
                val k = m.groupValues[1]
                when {
                    k.startsWith("#x") -> k.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                    k.startsWith("#") -> k.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                    else -> entities[k] ?: m.value
                }
            }
            return s.lines().joinToString("\n") { it.trim().replace(Regex(" {2,}"), " ") }
        }
    }

    // ------------------------------------------------------------------ PDF

    object Pdf {
        fun extract(bytes: ByteArray): String {
            val latin = String(bytes, Charsets.ISO_8859_1)
            val out = StringBuilder()
            var idx = 0
            while (true) {
                val s = latin.indexOf("stream", idx)
                if (s < 0) break
                if (s >= 3 && latin.regionMatches(s - 3, "end", 0, 3)) { idx = s + 6; continue }
                var dataStart = s + 6
                if (dataStart < latin.length && latin[dataStart] == '\r') dataStart++
                if (dataStart < latin.length && latin[dataStart] == '\n') dataStart++
                val e = latin.indexOf("endstream", dataStart)
                if (e < 0) break
                val dictStart = latin.lastIndexOf("<<", s).coerceAtLeast(0)
                val dict = latin.substring(dictStart, s)
                var raw = bytes.copyOfRange(dataStart, e)
                val flate = dict.contains("/FlateDecode")
                val otherFilter = dict.contains("/Filter") && !flate
                val isImage = dict.contains("/Image") || dict.contains("/XObject") || dict.contains("/FontFile")
                if (!otherFilter && !isImage) {
                    if (flate) raw = inflate(raw) ?: ByteArray(0)
                    val text = contentText(String(raw, Charsets.ISO_8859_1))
                    if (text.isNotBlank()) out.append(text).append("\n\n")
                }
                idx = e + 9
            }
            return out.toString()
        }

        private fun inflate(data: ByteArray): ByteArray? = runCatching {
            val inf = Inflater()
            inf.setInput(data)
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(16384)
            var guard = 0
            while (!inf.finished() && guard++ < 100_000) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                bos.write(buf, 0, n)
            }
            inf.end()
            bos.toByteArray()
        }.getOrNull()

        /** Interpreta operadores de texto de um content stream (BT … ET). */
        fun contentText(cs: String): String {
            val sb = StringBuilder()
            var i = 0
            val n = cs.length
            val pending = StringBuilder()
            fun flushWord() { if (pending.isNotEmpty()) { sb.append(pending); pending.setLength(0) } }
            while (i < n) {
                val ch = cs[i]
                when {
                    ch == '(' -> { val (str, end) = literal(cs, i); pending.append(str); i = end }
                    ch == '<' && i + 1 < n && cs[i + 1] != '<' -> {
                        val end = cs.indexOf('>', i).let { if (it < 0) n - 1 else it }
                        pending.append(hexString(cs.substring(i + 1, end))); i = end + 1
                    }
                    ch == ']' -> i++
                    ch == '[' -> i++
                    ch == '-' || ch.isDigit() || ch == '.' -> {
                        var j = i + 1
                        while (j < n && (cs[j].isDigit() || cs[j] == '.')) j++
                        // em TJ, deslocamentos grandes (≤ −200) indicam espaço entre palavras
                        val v = cs.substring(i, j).toDoubleOrNull() ?: 0.0
                        if (v <= -200.0 && pending.isNotEmpty() && pending.last() != ' ') pending.append(' ')
                        i = j
                    }
                    ch.isLetter() || ch == '\'' || ch == '"' || ch == '*' -> {
                        var j = i + 1
                        while (j < n && (cs[j].isLetter() || cs[j] == '*')) j++
                        when (cs.substring(i, j)) {
                            "Tj", "TJ" -> flushWord()
                            "'", "\"" -> { sb.append('\n'); flushWord() }
                            "Td", "TD", "T*", "Tm" -> { flushWord(); if (sb.isNotEmpty() && sb.last() != '\n' && sb.last() != ' ') sb.append(' ') }
                            "ET" -> { flushWord(); sb.append('\n') }
                            else -> {}
                        }
                        i = j
                    }
                    else -> i++
                }
            }
            flushWord()
            return sb.toString().replace(Regex(" +\n"), "\n")
        }

        private fun literal(cs: String, start: Int): Pair<String, Int> {
            val sb = StringBuilder()
            var depth = 1
            var i = start + 1
            while (i < cs.length && depth > 0) {
                val c = cs[i]
                when (c) {
                    '\\' -> {
                        i++
                        if (i >= cs.length) break
                        when (val e = cs[i]) {
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t'); 'b', 'f' -> {}
                            '(', ')', '\\' -> sb.append(e)
                            in '0'..'7' -> {
                                var j = i; var v = 0
                                while (j < cs.length && j < i + 3 && cs[j] in '0'..'7') { v = v * 8 + (cs[j] - '0'); j++ }
                                sb.append(v.toChar()); i = j - 1
                            }
                            else -> sb.append(e)
                        }
                    }
                    '(' -> { depth++; sb.append(c) }
                    ')' -> { depth--; if (depth > 0) sb.append(c) }
                    else -> sb.append(c)
                }
                i++
            }
            return decodePdfString(sb.toString()) to i
        }

        private fun hexString(h: String): String {
            val clean = h.filter { it.isLetterOrDigit() }
            val padded = if (clean.length % 2 == 1) clean + "0" else clean
            val b = ByteArray(padded.length / 2) { padded.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            return decodePdfString(String(b, Charsets.ISO_8859_1))
        }

        /** UTF-16BE com BOM (FE FF) → Unicode; caso contrário PDFDocEncoding ≈ Latin-1. */
        private fun decodePdfString(s: String): String {
            if (s.length >= 2 && s[0] == '\u00FE' && s[1] == '\u00FF') {
                val b = s.substring(2).toByteArray(Charsets.ISO_8859_1)
                return String(b, Charsets.UTF_16BE)
            }
            // UTF-16BE sem BOM (comum em strings hex de fontes Identity-H com CIDs = Unicode BMP)
            if (s.length >= 4 && s.length % 2 == 0 && (s.indices step 2).all { s[it] == '\u0000' }) {
                return String(s.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_16BE)
            }
            return s
        }
    }

    // ------------------------------------------------------------------ Parquet

    object Parquet {
        private const val BYTE_ARRAY = 6

        class Column(val path: String, val type: Int, val optional: Boolean)

        /** Concatena, linha a linha, os valores de todas as colunas de texto (BYTE_ARRAY). */
        fun extractText(bytes: ByteArray, maxRows: Int = 50_000): String {
            val cols = readColumns(bytes)
            if (cols.isEmpty()) return ""
            val rows = cols.values.maxOf { it.size }.coerceAtMost(maxRows)
            val sb = StringBuilder()
            for (r in 0 until rows) {
                val line = cols.values.mapNotNull { it.getOrNull(r) }.filter { it.isNotBlank() }.joinToString(" — ")
                if (line.isNotBlank()) sb.append(line).append('\n')
            }
            return sb.toString()
        }

        /** Mapa coluna → valores (texto UTF-8; nulos viram ""). */
        fun readColumns(bytes: ByteArray): LinkedHashMap<String, MutableList<String>> {
            require(bytes.size >= 12 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "PAR1" &&
                String(bytes, bytes.size - 4, 4, Charsets.ISO_8859_1) == "PAR1") { "arquivo Parquet inválido (magia PAR1)" }
            val metaLen = ByteBuffer.wrap(bytes, bytes.size - 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
            require(metaLen in 1..(bytes.size - 12)) { "rodapé Parquet inválido" }
            val meta = Thrift(bytes, bytes.size - 8 - metaLen).readStruct()
            @Suppress("UNCHECKED_CAST")
            val schema = (meta[2] as? List<Map<Int, Any?>>).orEmpty()
            val leaves = ArrayList<Column>()
            for (el in schema.drop(1)) {
                val children = (el[5] as? Int) ?: 0
                if (children > 0) continue
                leaves += Column((el[4] as? ByteArray)?.toString(Charsets.UTF_8) ?: "col", (el[1] as? Int) ?: -1, (el[3] as? Int) == 1)
            }
            val out = LinkedHashMap<String, MutableList<String>>()
            @Suppress("UNCHECKED_CAST")
            val rowGroups = (meta[4] as? List<Map<Int, Any?>>).orEmpty()
            for (rg in rowGroups) {
                @Suppress("UNCHECKED_CAST")
                val chunks = (rg[1] as? List<Map<Int, Any?>>).orEmpty()
                for ((ci, chunk) in chunks.withIndex()) {
                    val col = leaves.getOrNull(ci) ?: continue
                    if (col.type != BYTE_ARRAY) continue
                    @Suppress("UNCHECKED_CAST")
                    val md = chunk[3] as? Map<Int, Any?> ?: continue
                    val codec = (md[4] as? Int) ?: 0
                    val numValues = ((md[5] as? Long) ?: 0L).toInt()
                    val dataOff = (md[9] as? Long) ?: continue
                    val dictOff = md[11] as? Long
                    val start = if (dictOff != null && dictOff > 0 && dictOff < dataOff) dictOff else dataOff
                    out.getOrPut(col.path) { mutableListOf() } += readChunk(bytes, start.toInt(), numValues, codec, col.optional)
                }
            }
            return out
        }

        private fun readChunk(bytes: ByteArray, start: Int, numValues: Int, codec: Int, optional: Boolean): List<String> {
            var pos = start
            var dict: List<String> = emptyList()
            val values = ArrayList<String>(numValues)
            var guard = 0
            while (values.size < numValues && pos < bytes.size - 8 && guard++ < 100_000) {
                val t = Thrift(bytes, pos)
                val header = t.readStruct()
                pos = t.pos
                val type = (header[1] as? Int) ?: 0
                val uncompressed = (header[2] as? Int) ?: 0
                val compressed = (header[3] as? Int) ?: 0
                val pageEnd = pos + compressed
                require(pageEnd <= bytes.size) { "página Parquet além do fim do arquivo" }
                when (type) {
                    2 -> { // DICTIONARY_PAGE
                        @Suppress("UNCHECKED_CAST") val dh = header[7] as? Map<Int, Any?>
                        val n = (dh?.get(1) as? Int) ?: 0
                        val page = decompress(bytes.copyOfRange(pos, pageEnd), codec, uncompressed)
                        dict = plainByteArrays(page, 0, n).first
                    }
                    0 -> { // DATA_PAGE v1: [níveis rep/def com prefixo de 4 bytes] + valores
                        @Suppress("UNCHECKED_CAST") val dh = header[5] as? Map<Int, Any?> ?: emptyMap()
                        val n = (dh[1] as? Int) ?: 0
                        val enc = (dh[2] as? Int) ?: 0
                        val page = decompress(bytes.copyOfRange(pos, pageEnd), codec, uncompressed)
                        var p = 0
                        var defs: IntArray? = null
                        if (optional) {
                            val len = ByteBuffer.wrap(page, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                            defs = rleHybrid(page, 4, 4 + len, 1, n)
                            p = 4 + len
                        }
                        values += decodeValues(page, p, page.size, n, enc, dict, defs)
                    }
                    3 -> { // DATA_PAGE_V2: níveis nunca comprimidos, valores opcionalmente
                        @Suppress("UNCHECKED_CAST") val dh = header[8] as? Map<Int, Any?> ?: emptyMap()
                        val n = (dh[1] as? Int) ?: 0
                        val enc = (dh[4] as? Int) ?: 0
                        val defLen = (dh[5] as? Int) ?: 0
                        val repLen = (dh[6] as? Int) ?: 0
                        val isCompressed = (dh[7] as? Boolean) ?: true
                        val raw = bytes.copyOfRange(pos, pageEnd)
                        val defs = if (optional && defLen > 0) rleHybrid(raw, repLen, repLen + defLen, 1, n) else null
                        val body = raw.copyOfRange(repLen + defLen, raw.size)
                        val page = if (isCompressed) decompress(body, codec, uncompressed - repLen - defLen) else body
                        values += decodeValues(page, 0, page.size, n, enc, dict, defs)
                    }
                    else -> {}
                }
                pos = pageEnd
            }
            return values
        }

        private fun decodeValues(page: ByteArray, from: Int, to: Int, n: Int, enc: Int, dict: List<String>, defs: IntArray?): List<String> {
            val present = defs?.count { it == 1 } ?: n
            val vals: List<String> = when (enc) {
                0 -> plainByteArrays(page, from, present).first
                2, 8 -> { // PLAIN_DICTIONARY / RLE_DICTIONARY: 1 byte de largura + RLE híbrido
                    val bw = page[from].toInt() and 0xFF
                    rleHybrid(page, from + 1, to, bw, present).map { dict.getOrElse(it) { "" } }
                }
                else -> throw IllegalArgumentException("codificação Parquet $enc não suportada")
            }
            if (defs == null) return vals
            val out = ArrayList<String>(n)
            var k = 0
            for (d in defs) out += if (d == 1) vals.getOrElse(k++) { "" } else ""
            return out
        }

        private fun plainByteArrays(b: ByteArray, from: Int, n: Int): Pair<List<String>, Int> {
            var p = from
            val out = ArrayList<String>(n)
            repeat(n) {
                if (p + 4 > b.size) return out to p
                val len = ByteBuffer.wrap(b, p, 4).order(ByteOrder.LITTLE_ENDIAN).int
                p += 4
                if (len < 0 || p + len > b.size) return out to p
                out += String(b, p, len, Charsets.UTF_8); p += len
            }
            return out to p
        }

        /** Codificação híbrida RLE/bit-packing do Parquet. */
        fun rleHybrid(b: ByteArray, from: Int, to: Int, bitWidth: Int, count: Int): IntArray {
            val out = IntArray(count)
            var o = 0
            var p = from
            val byteW = (bitWidth + 7) / 8
            while (o < count && p < to) {
                var header = 0L; var shift = 0
                while (p < to) { val v = b[p++].toInt() and 0xFF; header = header or ((v and 0x7F).toLong() shl shift); shift += 7; if (v and 0x80 == 0) break }
                if (header and 1L == 0L) { // RLE
                    val run = (header ushr 1).toInt()
                    var v = 0
                    for (i in 0 until byteW) v = v or ((b[p + i].toInt() and 0xFF) shl (8 * i))
                    p += byteW
                    for (i in 0 until run) { if (o >= count) break; out[o++] = v }
                } else { // bit-packed: grupos de 8 valores
                    val groups = (header ushr 1).toInt()
                    val total = groups * 8
                    var bitPos = 0L
                    val base = p
                    for (i in 0 until total) {
                        var v = 0
                        for (k in 0 until bitWidth) {
                            val bit = bitPos + k
                            val byte = base + (bit / 8).toInt()
                            if (byte < to && (b[byte].toInt() ushr (bit % 8).toInt()) and 1 == 1) v = v or (1 shl k)
                        }
                        bitPos += bitWidth
                        if (o < count) out[o++] = v
                    }
                    p = base + groups * bitWidth
                }
            }
            return out
        }

        private fun decompress(data: ByteArray, codec: Int, uncompressed: Int): ByteArray = when (codec) {
            0 -> data
            1 -> Snappy.decompress(data)
            2 -> GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
            else -> throw IllegalArgumentException("compressão Parquet $codec não suportada (use SNAPPY, GZIP ou nenhuma)")
        }.also { require(uncompressed <= 0 || it.size == uncompressed || codec == 0) { "tamanho descomprimido inesperado" } }
    }

    /** Decodificador Snappy (formato bruto, sem framing) — usado pelo Parquet. */
    object Snappy {
        fun decompress(src: ByteArray): ByteArray {
            var p = 0
            var len = 0L; var shift = 0
            while (true) { val v = src[p++].toInt() and 0xFF; len = len or ((v and 0x7F).toLong() shl shift); shift += 7; if (v and 0x80 == 0) break }
            require(len in 0..(256L shl 20)) { "snappy: tamanho inválido" }
            val out = ByteArray(len.toInt())
            var o = 0
            while (p < src.size) {
                val tag = src[p++].toInt() and 0xFF
                when (tag and 3) {
                    0 -> {
                        var l = tag ushr 2
                        if (l >= 60) { val nb = l - 59; l = 0; for (i in 0 until nb) l = l or ((src[p++].toInt() and 0xFF) shl (8 * i)) }
                        l += 1
                        System.arraycopy(src, p, out, o, l); p += l; o += l
                    }
                    else -> {
                        val (l, off) = when (tag and 3) {
                            1 -> (((tag ushr 2) and 7) + 4) to (((tag ushr 5) shl 8) or (src[p++].toInt() and 0xFF))
                            2 -> { val off = (src[p].toInt() and 0xFF) or ((src[p + 1].toInt() and 0xFF) shl 8); p += 2; ((tag ushr 2) + 1) to off }
                            else -> { var off = 0; for (i in 0 until 4) off = off or ((src[p + i].toInt() and 0xFF) shl (8 * i)); p += 4; ((tag ushr 2) + 1) to off }
                        }
                        require(off in 1..o) { "snappy: deslocamento inválido" }
                        for (i in 0 until l) { out[o] = out[o - off]; o++ }
                    }
                }
            }
            require(o == out.size) { "snappy: saída truncada" }
            return out
        }
    }

    /** Leitor do protocolo Thrift Compact (metadados Parquet): struct → mapa id→valor. */
    class Thrift(private val b: ByteArray, var pos: Int) {
        private fun byte(): Int = b[pos++].toInt() and 0xFF
        private fun varint(): Long { var r = 0L; var s = 0; while (true) { val v = byte(); r = r or ((v and 0x7F).toLong() shl s); s += 7; if (v and 0x80 == 0) return r } }
        private fun zigzag(): Long { val v = varint(); return (v ushr 1) xor -(v and 1) }

        fun readStruct(): Map<Int, Any?> {
            val out = HashMap<Int, Any?>()
            var last = 0
            while (true) {
                val h = byte()
                if (h == 0) return out
                val type = h and 0x0F
                val delta = h ushr 4
                val id = if (delta != 0) last + delta else zigzag().toInt()
                last = id
                out[id] = readValue(type)
            }
        }

        private fun readValue(type: Int): Any? = when (type) {
            1 -> true
            2 -> false
            3 -> b[pos++].toInt()
            4, 5 -> zigzag().toInt()
            6 -> zigzag()
            7 -> { val v = ByteBuffer.wrap(b, pos, 8).order(ByteOrder.LITTLE_ENDIAN).double; pos += 8; v }
            8 -> { val n = varint().toInt(); val v = b.copyOfRange(pos, pos + n); pos += n; v }
            9, 10 -> {
                val h = byte()
                var size = h ushr 4
                val et = h and 0x0F
                if (size == 15) size = varint().toInt()
                List(size) { if (et == 1 || et == 2) (byte() == 1) else readValue(et) }
            }
            11 -> {
                val size = varint().toInt()
                if (size == 0) emptyMap<Any?, Any?>() else {
                    val kv = byte(); val kt = kv ushr 4; val vt = kv and 0x0F
                    (0 until size).associate { readValue(kt) to readValue(vt) }
                }
            }
            12 -> readStruct()
            else -> throw IllegalArgumentException("thrift: tipo $type desconhecido")
        }
    }
}
