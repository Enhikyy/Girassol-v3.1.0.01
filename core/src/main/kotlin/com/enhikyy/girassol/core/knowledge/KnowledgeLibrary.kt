package com.enhikyy.girassol.core.knowledge

import com.enhikyy.girassol.core.memory.EpisodicIndex
import com.enhikyy.girassol.core.util.Sha256
import kotlinx.serialization.Serializable

@Serializable
data class KnowledgeDocument(
    val id: String,
    val title: String,
    val format: DocumentFormat,
    val sha256: String,
    val chars: Int,
    val chunks: Int,
    val ingestedTick: Long,
    var reads: Int = 0
)

@Serializable
data class KnowledgeChunk(
    val id: Long,
    val docId: String,
    val ordinal: Int,
    val text: String,
    /** SimHash bit[256] (mesmo índice binário da memória episódica). */
    val bits: LongArray,
    var reads: Int = 0
)

/**
 * Biblioteca de Conhecimento da biosfera (Processo 19). Documentos são divididos em trechos
 * de até [CHUNK_CHARS] caracteres em fronteiras de frase, indexados por SimHash e lidos pelos
 * cidadãos que estudam/pesquisam — cada leitura vira uma memória SEMÂNTICA verificada e
 * alimenta o [EmergentLexicon]. O conteúdo entra no mundo apenas via evento canônico
 * `CONHECIMENTO` (o hash do documento entra na cadeia SHA-256).
 */
@Serializable
class KnowledgeShelf(
    val documents: MutableList<KnowledgeDocument> = mutableListOf(),
    val chunks: MutableList<KnowledgeChunk> = mutableListOf(),
    var nextChunkId: Long = 1L
) {
    companion object {
        const val CHUNK_CHARS = 480
        const val MAX_CHUNKS = 2400
        const val MAX_CHUNKS_PER_DOC = 600

        fun chunk(text: String, maxChars: Int = CHUNK_CHARS): List<String> {
            val out = ArrayList<String>()
            for (para in text.split(Regex("\\n\\s*\\n"))) {
                val p = para.replace('\n', ' ').trim()
                if (p.length < 12) continue
                if (p.length <= maxChars) { out += p; continue }
                val sentences = p.split(Regex("(?<=[.!?…;])\\s+"))
                val cur = StringBuilder()
                for (s in sentences) {
                    if (cur.isNotEmpty() && cur.length + 1 + s.length > maxChars) { out += cur.toString(); cur.setLength(0) }
                    if (s.length > maxChars) {
                        var k = 0
                        while (k < s.length) { val e = minOf(s.length, k + maxChars); out += s.substring(k, e); k = e }
                    } else { if (cur.isNotEmpty()) cur.append(' '); cur.append(s) }
                }
                if (cur.isNotEmpty()) out += cur.toString()
            }
            return out
        }

        fun bitsOf(text: String): LongArray = EpisodicIndex.simHash(EpisodicIndex.features(text, emptyList(), emptyList()))
    }

    fun contains(sha: String) = documents.any { it.sha256 == sha }

    /** Insere o documento (idempotente pelo SHA-256). Retorna o registro ou null se repetido. */
    fun add(doc: ParsedDocument, tick: Long): KnowledgeDocument? {
        val sha = Sha256.hex(doc.text)
        if (contains(sha) || doc.text.isBlank()) return null
        val id = "DOC-${sha.take(10)}"
        val parts = chunk(doc.text).take(MAX_CHUNKS_PER_DOC)
        parts.forEachIndexed { i, t -> chunks += KnowledgeChunk(nextChunkId++, id, i, t, bitsOf(t)) }
        // orçamento global: descarta os trechos menos lidos (mais antigos em empate)
        if (chunks.size > MAX_CHUNKS) {
            val drop = chunks.sortedWith(compareBy<KnowledgeChunk> { it.reads }.thenBy { it.id }).take(chunks.size - MAX_CHUNKS).map { it.id }.toSet()
            chunks.removeAll { it.id in drop }
        }
        val rec = KnowledgeDocument(id, doc.title.take(80), doc.format, sha, doc.text.length, parts.size, tick)
        documents += rec
        return rec
    }

    /** Recuperação por Hamming (empate: id menor) — determinística. */
    fun nearest(query: String, k: Int = 1): List<KnowledgeChunk> {
        if (chunks.isEmpty()) return emptyList()
        val q = bitsOf(query)
        return chunks.sortedWith(compareBy<KnowledgeChunk> { EpisodicIndex.hamming(q, it.bits) }.thenBy { it.reads }.thenBy { it.id }).take(k)
    }

    fun title(docId: String) = documents.firstOrNull { it.id == docId }?.title ?: docId
}

/**
 * Léxico comprimido emergente: os cidadãos cunham, por fusões tipo BPE sobre bigramas de
 * palavras frequentes nas falas e leituras, novas "palavras-glifo" curtas (ex.:
 * "colher horta" → "cohó"). A comunicação comprimida reduz o número de símbolos por fala;
 * a taxa de compressão é uma métrica cultural da cidade. Totalmente determinístico.
 */
@Serializable
class EmergentLexicon(
    val pairCounts: MutableMap<String, Int> = mutableMapOf(),
    val entries: MutableList<LexiconEntry> = mutableListOf(),
    var observedWords: Long = 0L,
    var compressedWords: Long = 0L
) {
    @Serializable
    data class LexiconEntry(val glyph: String, val phrase: String, val coinedTick: Long, val coinerId: String?, var uses: Int = 0)

    companion object {
        const val MAX_PAIRS = 4096
        const val MAX_ENTRIES = 256
        const val MIN_COUNT = 6
        private val STOP = setOf("a", "o", "e", "de", "da", "do", "em", "um", "uma", "que", "para", "por", "com", "os", "as", "no", "na", "se", "eu")

        fun words(text: String): List<String> = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 && it !in STOP }

        /** Glifo = sílaba inicial de cada palavra (até 3 letras), acento na última vogal. */
        fun coin(phrase: String): String {
            val parts = phrase.split(' ')
            val sb = StringBuilder()
            for (w in parts) {
                var cut = 1
                while (cut < w.length && cut < 3 && w[cut] !in "aeiouáéíóúâêôãõ") cut++
                sb.append(w.substring(0, minOf(w.length, cut + 1)))
            }
            return sb.toString()
        }
    }

    fun observe(text: String) {
        val orig = words(text)
        val w = applyGlyphs(orig)
        observedWords += orig.size
        if (w.size < orig.size) {
            compressedWords += (orig.size - w.size)
            for (e in entries) if (w.contains(e.glyph)) e.uses++
        }
        for (i in 0 until w.size - 1) {
            val k = w[i] + " " + w[i + 1]
            pairCounts[k] = (pairCounts[k] ?: 0) + 1
        }
        if (pairCounts.size > MAX_PAIRS) {
            val keep = pairCounts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(MAX_PAIRS / 2)
            pairCounts.clear(); for (e in keep) pairCounts[e.key] = e.value
        }
    }

    private fun applyGlyphs(words: List<String>): List<String> {
        if (entries.isEmpty()) return words
        var cur = words
        for (e in entries) {
            val (a, b) = e.phrase.split(' ').let { it[0] to it.getOrElse(1) { "" } }
            val out = ArrayList<String>(cur.size)
            var i = 0
            while (i < cur.size) {
                if (i + 1 < cur.size && cur[i] == a && cur[i + 1] == b) { out += e.glyph; i += 2 } else { out += cur[i]; i++ }
            }
            cur = out
        }
        return cur
    }

    /** Uma fusão por chamada (consolidação diária). Retorna a entrada cunhada, se houver. */
    fun consolidate(tick: Long, coinerId: String?): LexiconEntry? {
        if (entries.size >= MAX_ENTRIES) return null
        val best = pairCounts.entries.filter { it.value >= MIN_COUNT && entries.none { e -> e.phrase == it.key } }
            .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key }) ?: return null
        var glyph = coin(best.key)
        var n = 2
        while (entries.any { it.glyph == glyph }) glyph = coin(best.key) + n++
        val e = LexiconEntry(glyph, best.key, tick, coinerId)
        entries += e
        pairCounts.remove(best.key)
        return e
    }

    /** Versão comprimida da fala (pura: não altera o estado; segura para a UI). */
    fun compress(text: String): String = applyGlyphs(words(text)).joinToString(" ")

    /** Fração de palavras economizadas pelos glifos sobre tudo o que foi observado. */
    fun compressionRatio(): Double = if (observedWords == 0L) 0.0 else compressedWords.toDouble() / observedWords.toDouble()
}
