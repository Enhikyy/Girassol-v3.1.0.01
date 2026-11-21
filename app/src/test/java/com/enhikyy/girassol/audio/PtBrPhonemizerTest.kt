package com.enhikyy.girassol.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.8 — fonetizador pt-BR do Piper. As saídas douradas foram conferidas por ida e volta
 * Piper (faber-medium) → Whisper tiny: as frases voltam com as mesmas palavras.
 */
class PtBrPhonemizerTest {
    /** Símbolos de 1 codepoint do tokens.txt do vits-piper-pt_BR-faber-medium-int8. */
    private val modelSymbols = "\u005f\u005e\u0024\u0020\u0021\u0027\u0028\u0029\u002c\u002d\u002e\u003a\u003b\u003f\u0061\u0062\u0063\u0064\u0065\u0066\u0068\u0069\u006a\u006b\u006c\u006d\u006e\u006f\u0070\u0071\u0072\u0073\u0074\u0075\u0076\u0077\u0078\u0079\u007a\u00e6\u00e7\u00f0\u00f8\u0127\u014b\u0153\u01c0\u01c1\u01c2\u01c3\u0250\u0251\u0252\u0253\u0254\u0255\u0256\u0257\u0258\u0259\u025a\u025b\u025c\u025e\u025f\u0260\u0261\u0262\u0263\u0264\u0265\u0266\u0267\u0268\u026a\u026b\u026c\u026d\u026e\u026f\u0270\u0271\u0272\u0273\u0274\u0275\u0276\u0278\u0279\u027a\u027b\u027d\u027e\u0280\u0281\u0282\u0283\u0284\u0288\u0289\u028a\u028b\u028c\u028d\u028e\u028f\u0290\u0291\u0292\u0294\u0295\u0298\u0299\u029b\u029c\u029d\u029f\u02a1\u02a2\u02b2\u02c8\u02cc\u02d0\u02d1\u02de\u03b2\u03b8\u03c7\u1d7b\u2c71\u0030\u0031\u0032\u0033\u0034\u0035\u0036\u0037\u0038\u0039\u0327\u0303\u032a\u032f\u0329\u02b0\u02e4\u03b5\u2193\u0023\u0022\u2191".codePoints().toArray().toSet()

    private val words = mapOf(
            "casa" to "kˈazæ",
            "chave" to "ʃˈavy",
            "carro" to "kˈaxʊ",
            "filho" to "fˈiljʊ",
            "ninho" to "nˈiɲʊ",
            "queijo" to "kˈeɪʒʊ",
            "guerra" to "ɡˈexæ",
            "água" to "ˈaɡwæ",
            "pão" to "pˈɐ̃ʊ̃",
            "mãe" to "mˈɐ̃y",
            "cantar" to "kɐ̃ŋtˈar",
            "tempo" to "tˈeɪmpʊ",
            "leite" to "lˈeɪtʃy",
            "gente" to "ʒˈeɪŋtʃy",
            "cidade" to "sidˈadʒy",
            "tia" to "tʃˈiæ",
            "sol" to "sˈow",
            "azul" to "azˈuw",
            "nação" to "nasˈɐ̃ʊ̃",
            "coração" to "koɾasˈɐ̃ʊ̃",
            "assim" to "asˈiŋ",
            "feliz" to "felˈis",
            "também" to "tɐ̃mbˈeɪŋ",
            "você" to "vosˈe",
            "olhar" to "oljˈar",
            "trabalho" to "trabˈaljʊ",
            "homem" to "ˈomeɪŋ",
            "família" to "famˈiliæ",
            "crianças" to "kriˈɐ̃ŋsæs",
            "rio" to "xˈiʊ"
    )

    private val sentences = mapOf(
            "Eu preciso de comida e água para a minha família." to "ˈeʊ presˈizʊ dʒi komˈidæ i ˈaɡwæ pˈaɾæ a mˈiɲæ famˈiliæ.",
            "O sol nasceu e as crianças foram para a escola." to "ʊ sˈow nasˈeʊ i as kriˈɐ̃ŋsæs fˈoɾɐ̃ʊ̃ pˈaɾæ a eskˈolæ.",
            "Tenho 25 anos e moro na cidade há 3 dias." to "tˈeɲʊ vˈiŋtʃy i sˈiŋkʊ ˈanʊz i mˈoɾʊ na sidˈadʒy ˈa trˈez dʒˈiæs.",
            "Vamos construir uma casa nova perto do rio?" to "vˈamʊs koŋstruˈir ˈumæ kˈazæ nˈovæ pˈertʊ dʊ xˈiʊ?"
    )

    @Test
    fun wordsMatchGolden() {
        for ((w, ipa) in words) assertEquals(w, ipa, PtBrPhonemizer.word(w))
    }

    @Test
    fun sentencesMatchGolden() {
        for ((t, ipa) in sentences) assertEquals(listOf(ipa), PtBrPhonemizer.sentences(t))
    }

    @Test
    fun everyCodepointIsAModelToken() {
        val text = (words.keys + sentences.keys).joinToString(". ") + " Árvore, exceção! Qual é o seu nome? Ação; ônibus: 1.250 e 3,5."
        for (s in PtBrPhonemizer.codepoints(text)) for (cp in s)
            assertTrue("U+%04X fora do tokens.txt".format(cp), cp in modelSymbols)
    }

    @Test
    fun splitsSentencesAndKeepsQuestionMarks() {
        val s = PtBrPhonemizer.sentences("Bom dia! Você vem? Sim.")
        assertEquals(3, s.size)
        assertTrue(s[0].endsWith("!")); assertTrue(s[1].endsWith("?")); assertTrue(s[2].endsWith("."))
        assertTrue(PtBrPhonemizer.sentences("... !!! 🌻").isEmpty())
    }

    @Test
    fun numbersAreSpelledOut() {
        assertEquals("vinte e cinco", PtBrPhonemizer.spell(25))
        assertEquals("cem", PtBrPhonemizer.spell(100))
        assertEquals("cento e um", PtBrPhonemizer.spell(101))
        assertEquals("mil duzentos e cinquenta", PtBrPhonemizer.spell(1250))
        assertEquals("dois mil e vinte e seis", PtBrPhonemizer.spell(2026))
        assertEquals(PtBrPhonemizer.sentences("vinte e cinco"), PtBrPhonemizer.sentences("25"))
        assertEquals(PtBrPhonemizer.sentences("mil duzentos e cinquenta"), PtBrPhonemizer.sentences("1.250"))
        assertEquals(PtBrPhonemizer.sentences("três vírgula cinco"), PtBrPhonemizer.sentences("3,5"))
    }

    @Test
    fun stressFollowsAccentAndParoxytoneRules() {
        assertEquals("kˈazæ", PtBrPhonemizer.word("casa"))      // paroxítona -a
        assertEquals("kɐ̃ŋtˈar", PtBrPhonemizer.word("cantar"))  // oxítona -r
        assertEquals("ʃˈavy", PtBrPhonemizer.word("chave"))
        assertTrue(PtBrPhonemizer.word("próximo").startsWith("prˈɔ"))  // proparoxítona por acento
        assertEquals(1, PtBrPhonemizer.word("família").count { it == 'ˈ' })
        assertEquals(0, PtBrPhonemizer.word("de").count { it == 'ˈ' })  // clítico átono
    }
}
