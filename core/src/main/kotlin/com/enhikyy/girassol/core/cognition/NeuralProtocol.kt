package com.enhikyy.girassol.core.cognition

import com.enhikyy.girassol.core.ports.NeuralPrompt
import com.enhikyy.girassol.core.util.fmt
import com.enhikyy.girassol.core.world.ActionType

/**
 * Protocolo do motor neural local: prompt + gramática GBNF obrigatória. A gramática
 * restringe a saída a um objeto JSON com exatamente três campos e com a ação limitada
 * às opções válidas calculadas pelo WorldCore neste tick — o modelo nunca consegue
 * propor uma ação fora do espaço que o mundo aceita.
 */
object NeuralProtocol {
    const val MAX_TEXT = 72
    /** Caracteres permitidos nos textos livres (sem aspas, barras ou controle). */
    private const val TEXT_CLASS = "[a-zA-Z0-9 ,.!?;:()áàâãéêíóôõúüçÁÀÂÃÉÊÍÓÔÕÚÇ-]"

    fun grammar(actions: List<String>): String {
        require(actions.isNotEmpty()) { "gramática sem ações" }
        val alts = actions.distinct().joinToString(" | ") { "\"\\\"$it\\\"\"" }
        return buildString {
            appendLine("root ::= \"{\" ws \"\\\"acao\\\":\" ws acao \",\" ws \"\\\"pensamento\\\":\" ws texto \",\" ws \"\\\"fala\\\":\" ws texto ws \"}\"")
            appendLine("acao ::= $alts")
            appendLine("texto ::= \"\\\"\" ch{1,$MAX_TEXT} \"\\\"\"")
            appendLine("ch ::= $TEXT_CLASS")
            appendLine("ws ::= \" \"?")
        }
    }

    fun build(
        name: String,
        identity: String,
        needs: Map<String, Double>,
        mood: Double,
        recentMemories: List<String>,
        options: List<Pair<ActionType, Double>>,
        night: Boolean,
        drought: Boolean
    ): NeuralPrompt {
        val system = "Você é $name, cidadão da cidade de Girassol. $identity " +
            "Decida a próxima ação escolhendo UMA das opções. Responda apenas com JSON: " +
            "{\"acao\":\"OPCAO\",\"pensamento\":\"...\",\"fala\":\"...\"} em português, frases curtas."
        val user = buildString {
            append("Agora é ").append(if (night) "noite" else "dia").append(if (drought) ", há seca" else "").append(". ")
            append("Humor ").append("%.2f".fmt(mood)).append(". Necessidades (0=crítico,1=pleno): ")
            append(needs.entries.joinToString(", ") { "${it.key}=${"%.2f".fmt(it.value)}" }).append(". ")
            if (recentMemories.isNotEmpty()) append("Lembranças: ").append(recentMemories.joinToString("; ")).append(". ")
            append("Opções (menor custo é melhor): ")
            append(options.joinToString(", ") { "${it.first.name}(${it.first.label}, custo ${"%.2f".fmt(it.second)})" })
            append(".")
        }
        return NeuralPrompt(system, user, grammar(options.map { it.first.name }))
    }

    data class Parsed(val action: String, val thought: String, val speech: String)

    /** Parser estrito do JSON produzido sob a gramática; qualquer desvio é rejeitado. */
    fun parse(raw: String, allowed: Collection<String>): Parsed? {
        val s = raw.trim()
        if (!s.startsWith("{") || !s.endsWith("}")) return null
        val fields = HashMap<String, String>()
        var i = 1
        fun skipWs() { while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\t' || s[i] == '\r')) i++ }
        fun readString(): String? {
            if (i >= s.length || s[i] != '"') return null
            val start = ++i
            while (i < s.length && s[i] != '"') { if (s[i] == '\\') return null; i++ }
            if (i >= s.length) return null
            return s.substring(start, i++).also { }
        }
        while (true) {
            skipWs()
            val key = readString() ?: return null
            skipWs()
            if (i >= s.length || s[i] != ':') return null
            i++; skipWs()
            val value = readString() ?: return null
            fields[key] = value
            skipWs()
            if (i < s.length && s[i] == ',') { i++; continue }
            break
        }
        skipWs()
        if (i != s.length - 1) return null
        val action = fields["acao"] ?: return null
        if (action !in allowed) return null
        val thought = sanitize(fields["pensamento"] ?: return null)
        val speech = sanitize(fields["fala"] ?: return null)
        if (thought.isEmpty() || speech.isEmpty() || fields.size != 3) return null
        return Parsed(action, thought, speech)
    }

    fun sanitize(t: String): String = t.filter { it.isLetterOrDigit() || it in " ,.!?;:()-" }.trim().take(MAX_TEXT)
}

/** Falas autônomas do modo econômico (sem LLM), escolhidas deterministicamente. */
object SymbolicSpeech {
    private val byNeed = mapOf(
        "FOME" to listOf("Preciso comer alguma coisa.", "A horta deve ter algo hoje.", "Estou com fome."),
        "ENERGIA" to listOf("Estou exausto, vou descansar.", "Meus olhos pesam.", "Preciso dormir um pouco."),
        "HIGIENE" to listOf("Um banho cairia bem.", "Preciso me lavar.", "Vou cuidar de mim."),
        "SOCIAL" to listOf("Quero conversar com alguém.", "A praça deve estar animada.", "Sinto falta dos outros."),
        "DIVERSAO" to listOf("Que dia monótono.", "Preciso de algo novo.", "Vou explorar a cidade."),
        "PROPOSITO" to listOf("Tenho trabalho a fazer.", "A cidade precisa de mim.", "Vou contribuir hoje.")
    )

    fun forDecision(worstNeed: String, action: ActionType, roll: Long): String {
        val lines = byNeed[worstNeed] ?: listOf("Vamos lá.")
        if (action == ActionType.SOCIALIZAR) return byNeed.getValue("SOCIAL")[((roll ushr 1) % 3).toInt()]
        return lines[((roll ushr 1) % lines.size).toInt()]
    }

    fun forGreeting(otherName: String, roll: Long): String {
        val first = otherName.substringBefore(' ')
        val lines = listOf("Oi, $first!", "Bom te ver, $first.", "$first, como vai a horta?", "E aí, $first? Novidades?", "$first, ouviu os boatos?")
        return lines[((roll ushr 1) % lines.size).toInt()]
    }
}
