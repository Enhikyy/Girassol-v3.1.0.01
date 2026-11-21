package com.enhikyy.girassol.audio

import java.text.Normalizer

/**
 * v3.8 — fonetizador pt-BR offline (texto → IPA no estilo do espeak-ng `pt-br`) para o Piper.
 *
 * O Sherpa-ONNX ≥ 2.0 deixou de embutir o espeak-ng: `OfflineTtsVitsModelConfig.dataDir` é
 * ignorado e o VITS só sintetiza se receber os fonemas em `GenerationConfig.phonemeCodepoints`.
 * No v3.7 o Piper devolvia áudio vazio em silêncio ("Failed to convert … to token IDs").
 *
 * Regras (ortografia → fonema), por palavra:
 *  - tonicidade: acento gráfico; senão paroxítona para -a/-e/-o(s), -am, -em, -ens; oxítona no resto;
 *  - dígrafos ch/lh/nh/rr/ss/qu/gu/sc/xc; c/g antes de e/i; s intervocálico/coda sonora; r forte;
 *  - palatalização carioca/paulista de t/d diante de [i]; l em coda → [w];
 *  - vogais nasais (ã, õ, -am/-em/-im/-om/-um, V+m/n+C) e ditongos nasais (ão, ãe, õe);
 *  - redução átona final: a → æ, e → y, o → ʊ (convenções do espeak-ng com que o Piper foi treinado).
 * Números (0–999 999) viram extenso. Cada frase (. ! ? ; :) é uma lista de codepoints.
 */
object PtBrPhonemizer {
    private const val STRESS = 'ˈ'
    private const val NASAL = '\u0303'

    /** Símbolos que o modelo conhece (tokens.txt); qualquer outro é descartado antes do VITS. */
    fun sentences(text: String): List<String> {
        val out = ArrayList<String>()
        for (raw in splitSentences(expandNumbers(text))) {
            val sb = StringBuilder()
            val tokens = Regex("[\\p{L}\\-']+|[,]").findAll(raw).map { it.value }.toList()
            for ((i, tok) in tokens.withIndex()) {
                if (tok == ",") { trimEnd(sb); sb.append(", "); continue }
                val next = tokens.getOrNull(i + 1)?.takeIf { it != "," }
                for (part in tok.split('-', '\'').filter { it.isNotEmpty() }) {
                    sb.append(word(part.lowercase(), next?.lowercase())).append(' ')
                }
            }
            trimEnd(sb)
            if (sb.isEmpty()) continue
            val end = raw.trimEnd().lastOrNull()
            sb.append(if (end == '?' || end == '!') end else '.')
            out += sb.toString()
        }
        return out
    }

    fun codepoints(text: String): Array<IntArray> =
        sentences(text).map { s -> s.codePoints().toArray() }.toTypedArray()

    private fun trimEnd(sb: StringBuilder) { while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1) }

    private fun splitSentences(t: String): List<String> {
        val r = ArrayList<String>()
        val cur = StringBuilder()
        for (c in t) {
            cur.append(c)
            if (c == '.' || c == '!' || c == '?' || c == ';' || c == ':' || c == '\n') { r += cur.toString(); cur.setLength(0) }
        }
        if (cur.isNotBlank()) r += cur.toString()
        return r.filter { s -> s.any { it.isLetter() } }
    }

    // ------------------------------------------------------------------ números por extenso
    private val units = listOf("zero", "um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito", "nove", "dez",
        "onze", "doze", "treze", "catorze", "quinze", "dezesseis", "dezessete", "dezoito", "dezenove")
    private val tens = listOf("", "", "vinte", "trinta", "quarenta", "cinquenta", "sessenta", "setenta", "oitenta", "noventa")
    private val hundreds = listOf("", "cento", "duzentos", "trezentos", "quatrocentos", "quinhentos", "seiscentos", "setecentos", "oitocentos", "novecentos")

    fun spell(n: Int): String {
        require(n in 0..999_999)
        if (n < 20) return units[n]
        if (n < 100) return tens[n / 10] + if (n % 10 != 0) " e " + units[n % 10] else ""
        if (n == 100) return "cem"
        if (n < 1000) return hundreds[n / 100] + if (n % 100 != 0) " e " + spell(n % 100) else ""
        val th = n / 1000; val rest = n % 1000
        val head = if (th == 1) "mil" else spell(th) + " mil"
        return when {
            rest == 0 -> head
            rest < 100 || rest % 100 == 0 -> "$head e ${spell(rest)}"
            else -> "$head ${spell(rest)}"
        }
    }

    private fun expandNumbers(t: String): String = Regex("\\d+([.,]\\d+)?").replace(t) { m ->
        val v = m.value
        val sep = v.indexOfFirst { it == '.' || it == ',' }
        if (sep < 0) v.toLongOrNull()?.takeIf { it <= 999_999 }?.let { spell(it.toInt()) } ?: v.map { units[it - '0'] }.joinToString(" ")
        else {
            val a = v.substring(0, sep); val b = v.substring(sep + 1)
            // "1.000" (milhar) vs "3,5" (decimal)
            if (v[sep] == '.' && b.length == 3) (a + b).toLongOrNull()?.takeIf { it <= 999_999 }?.let { spell(it.toInt()) } ?: v
            else listOfNotNull(a.toIntOrNull()?.takeIf { it <= 999_999 }?.let(::spell), "vírgula",
                b.toIntOrNull()?.takeIf { it <= 999_999 }?.let(::spell)).joinToString(" ")
        }
    }

    // ------------------------------------------------------------------ palavra
    private val vowels = "aeiouáéíóúâêôãõàü"
    private fun isV(c: Char?) = c != null && c in vowels
    private fun base(c: Char): Char = when (c) {
        'á', 'â', 'ã', 'à' -> 'a'; 'é', 'ê' -> 'e'; 'í' -> 'i'; 'ó', 'ô', 'õ' -> 'o'; 'ú', 'ü' -> 'u'; else -> c
    }
    private val voicedOnsets = setOf('b', 'd', 'g', 'v', 'z', 'j', 'm', 'n', 'l', 'r')

    private data class Nucleus(val start: Int, val end: Int) // [start, end) em w

    /** Núcleos silábicos: ditongos decrescentes (a/e/o + i/u átono) contam como um. */
    private fun nuclei(w: String): List<Nucleus> {
        val r = ArrayList<Nucleus>()
        var i = 0
        while (i < w.length) {
            val c = w[i]
            val prev = w.getOrNull(i - 1)
            // u mudo de qu/gu antes de e/i, e "u" de qua/gua (semivogal do onset)
            if (c == 'u' && (prev == 'q' || prev == 'g') && isV(w.getOrNull(i + 1))) { i++; continue }
            if (!isV(c)) { i++; continue }
            var j = i + 1
            val n = w.getOrNull(j)
            val bc = base(c)
            val glide = n != null && (n == 'i' || n == 'u') && bc in "aeo" && !(n == 'i' && w.getOrNull(j + 1).let { it == 'r' || it == 'l' || it == 'z' || it == 'm' || it == 'n' } && j + 2 >= w.length)
            if (glide) j++
            else if ((c == 'ã' || c == 'õ') && (n == 'o' || n == 'e')) j++
            else if (bc == 'u' && n == 'i' && j + 1 == w.length) j++     // "fui", "azuis"
            r += Nucleus(i, j)
            i = j
        }
        return r
    }

    private fun stressIndex(w: String, ns: List<Nucleus>): Int {
        if (ns.isEmpty()) return -1
        for ((k, n) in ns.withIndex()) for (p in n.start until n.end) if (w[p] in "áéíóúâêô") return k
        for ((k, n) in ns.withIndex()) for (p in n.start until n.end) if (w[p] == 'ã' || w[p] == 'õ') return k
        if (ns.size == 1) return 0
        val paroxytone = Regex("([aeo]s?|[ae]m|ens)$").containsMatchIn(w)
        return if (paroxytone) ns.size - 2 else ns.size - 1
    }

    private fun isFunctionWord(w: String) = w in setOf("o", "a", "os", "as", "de", "do", "da", "dos", "das", "e", "em", "no", "na",
        "nos", "nas", "um", "uns", "que", "se", "me", "te", "lhe", "por", "com", "ao", "aos", "mas", "nem", "ou")

    fun word(w0: String, next: String? = null): String {
        val w = Normalizer.normalize(w0, Normalizer.Form.NFC).filter { it.isLetter() }
        if (w.isEmpty()) return ""
        val ns = nuclei(w)
        val stressed = if (isFunctionWord(w)) -1 else stressIndex(w, ns)
        val nucleusAt = HashMap<Int, Int>().apply { ns.forEachIndexed { k, n -> put(n.start, k) } }
        val sb = StringBuilder()
        var i = 0
        fun at(k: Int) = w.getOrNull(k)
        val nextFirst = next?.firstOrNull()
        while (i < w.length) {
            val c = w[i]
            val k = nucleusAt[i]
            if (k != null) {
                val n = ns[k]
                if (k == stressed) sb.append(STRESS)
                i = vowelGroup(w, n, k == stressed, k == ns.lastIndex, k > stressed && stressed >= 0, sb)
                continue
            }
            val nx = at(i + 1)
            val nxBase = nx?.let(::base)
            val front = nxBase == 'e' || nxBase == 'i'
            when (c) {
                'c' -> when {
                    nx == 'h' -> { sb.append('ʃ'); i++ }
                    front -> sb.append('s')
                    else -> sb.append('k')
                }
                'ç' -> sb.append('s')
                'q' -> {
                    // qu + e/i → k ; qu + a/o → kw
                    val after = at(i + 2)?.let(::base)
                    sb.append(if (nx == 'u' && (after == 'a' || after == 'o')) "kw" else "k")
                    if (nx == 'u') i++
                }
                'g' -> when {
                    nx == 'u' && at(i + 2)?.let(::base).let { it == 'e' || it == 'i' } -> { sb.append('ɡ'); i++ }
                    nx == 'u' && at(i + 2)?.let(::base).let { it == 'a' || it == 'o' } -> { sb.append("ɡw"); i++ }
                    front -> sb.append('ʒ')
                    else -> sb.append('ɡ')
                }
                'j' -> sb.append('ʒ')
                'l' -> when {
                    nx == 'h' -> { sb.append("lj"); i++ }
                    isV(nx) -> sb.append('l')
                    else -> sb.append('w')                     // l em coda
                }
                'n' -> when {
                    nx == 'h' -> { sb.append('ɲ'); i++ }
                    else -> sb.append('n')
                }
                'm' -> sb.append('m')
                'r' -> when {
                    nx == 'r' -> { sb.append('x'); i++ }
                    i == 0 -> sb.append('x')
                    at(i - 1).let { it == 'n' || it == 'l' || it == 's' } -> sb.append('x')
                    isV(nx) && !isV(at(i - 1)) -> sb.append('r')   // grupo consonantal: "três", "criança"
                    isV(nx) -> sb.append('ɾ')
                    else -> sb.append('r')                     // coda: "cantar", "porta"
                }
                's' -> when {
                    nx == 's' -> { sb.append('s'); i++ }
                    nx == 'c' && front.not() && at(i + 2)?.let(::base).let { it == 'e' || it == 'i' } -> { sb.append('s'); i++ }
                    i == 0 -> sb.append('s')
                    isV(at(i - 1)) && isV(nx) -> sb.append('z')
                    nx == null -> sb.append(if (nextFirst != null && (isV(nextFirst) || nextFirst in voicedOnsets)) 'z' else 's')
                    nx in voicedOnsets -> sb.append('z')
                    else -> sb.append('s')
                }
                'z' -> sb.append(if (nx == null) (if (nextFirst != null && isV(nextFirst)) 'z' else 's') else 'z')
                'x' -> when {
                    i == 1 && at(0) == 'e' && isV(nx) -> sb.append('z')
                    nx == 'c' && at(i + 2)?.let(::base).let { it == 'e' || it == 'i' } -> { sb.append('s'); i++ }
                    i > 0 && nx == null -> sb.append("ks")
                    else -> sb.append('ʃ')
                }
                't', 'd' -> {
                    val palatal = nxBase == 'i' || isFinalE(w, i + 1, ns, stressed)
                    sb.append(if (palatal) (if (c == 't') "tʃ" else "dʒ") else c.toString())
                }
                'h' -> Unit
                'k' -> sb.append('k')
                'w' -> sb.append('w')
                'y' -> sb.append('i')
                'b', 'f', 'p', 'v' -> sb.append(c)
                else -> if (c.isLetter()) sb.append(base(c))
            }
            i++
        }
        return sb.toString()
    }

    /** O "e" em [p] é átono final (-e/-es) e soa [i]? */
    private fun isFinalE(w: String, p: Int, ns: List<Nucleus>, stressed: Int): Boolean {
        if (w.getOrNull(p) != 'e') return false
        val k = ns.indexOfFirst { it.start == p }
        if (k < 0 || k == stressed || k != ns.lastIndex) return false
        val tail = w.substring(p + 1)
        return tail.isEmpty() || tail == "s"
    }

    /** Escreve o núcleo [n] e devolve o índice seguinte (consumindo m/n nasalizantes). */
    private fun vowelGroup(w: String, n: Nucleus, stressed: Boolean, last: Boolean, postTonic: Boolean, sb: StringBuilder): Int {
        val g = w.substring(n.start, n.end)
        var end = n.end
        val after = w.getOrNull(end)
        val after2 = w.getOrNull(end + 1)
        // m/n em coda (antes de consoante ou fim) nasaliza
        val nasalCoda = (after == 'm' || after == 'n') && (after2 == null || (!isV(after2) && after2 != 'h'))
        val wordEndAfterNasal = nasalCoda && (after2 == null || (after2 == 's' && end + 2 == w.length))
        val finalTail = w.substring(end)
        // monossílabos átonos ("a", "na", "de", "o") não recebem a redução do fim de palavra
        val atEnd = last && (finalTail.isEmpty() || finalTail == "s") && (n.start > 0 || w.length > 2 || stressed) &&
            !(w.length <= 3 && !stressed && w.count { isV(it) } == 1)
        fun vowel(c: Char): String {
            val b = base(c)
            return when {
                c == 'é' -> "ɛ"; c == 'ó' -> "ɔ"; c == 'ê' -> "e"; c == 'ô' -> "o"
                b == 'a' && !stressed && atEnd -> "æ"
                b == 'e' && !stressed && atEnd -> "y"
                b == 'o' && !stressed && atEnd -> "ʊ"
                b == 'e' && postTonic -> "i"
                b == 'o' && postTonic -> "ʊ"
                b == 'e' && !stressed && last && w.count { isV(it) } == 1 -> "i"   // "de", "e", "que"
                b == 'o' && !stressed && last && w.count { isV(it) } == 1 -> "ʊ"   // "o", "do", "no"
                else -> b.toString()
            }
        }
        when {
            g == "ão" -> sb.append("ɐ").append(NASAL).append("ʊ").append(NASAL)
            g == "ãe" -> sb.append("ɐ").append(NASAL).append("y")
            g == "õe" -> sb.append("o").append(NASAL).append("y")
            g.length == 1 && g[0] == 'ã' -> sb.append("ɐ").append(NASAL)
            g.length == 1 && g[0] == 'õ' -> sb.append("o").append(NASAL)
            nasalCoda && g.length == 1 -> {
                val b = base(g[0])
                when {
                    b == 'a' && wordEndAfterNasal && after == 'm' && !stressed -> sb.append("ɐ").append(NASAL).append("ʊ").append(NASAL)
                    b == 'a' -> sb.append("ɐ").append(NASAL).append(if (after2 == null || after == 'm' && (after2 == 'p' || after2 == 'b')) "" else "ŋ")
                    b == 'e' -> sb.append("eɪ").append(if (after == 'm' && (after2 == 'p' || after2 == 'b')) "" else "ŋ")
                    b == 'i' -> sb.append("iŋ")
                    b == 'o' -> sb.append(if (g[0] == 'ó') "ɔ" else "o").append("ŋ")
                    b == 'u' -> sb.append("u").append(NASAL).append("ŋ")
                    else -> sb.append(b)
                }
                // consome o m/n; mantém m antes de p/b ("tempo")
                end++
                if (after == 'm' && (after2 == 'p' || after2 == 'b')) sb.append('m')
            }
            g.length == 2 -> {
                sb.append(vowel(g[0]))
                sb.append(when (g[1]) { 'i' -> "ɪ"; 'u' -> if (base(g[0]) == 'o') "" else "ʊ"; else -> vowel(g[1]) })
            }
            else -> for (c in g) sb.append(vowel(c))
        }
        return end
    }
}
