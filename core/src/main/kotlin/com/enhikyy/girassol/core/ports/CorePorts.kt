package com.enhikyy.girassol.core.ports

import com.enhikyy.girassol.core.cognition.Deliberator
import com.enhikyy.girassol.core.cognition.SymbolicLookaheadDeliberator
import com.enhikyy.girassol.core.forge.KotlinLoraTrainer
import com.enhikyy.girassol.core.forge.LoraTrainerPort
import com.enhikyy.girassol.core.world.Pathfinding
import com.enhikyy.girassol.core.world.WorldState

/**
 * Portas do WorldCore. O núcleo é JVM puro; o app Android injeta implementações
 * nativas (C++ via JNI) que precisam ser bit a bit equivalentes às de referência em
 * Kotlin — a paridade é verificada por testes nos dois lados e na inicialização.
 */
interface PathfinderPort {
    val backend: String
    fun findPath(state: WorldState, sx: Int, sz: Int, gx: Int, gz: Int, trail: FloatArray?, danger: FloatArray?): MutableList<Int>
}

object KotlinPathfinder : PathfinderPort {
    override val backend = "kotlin-astar"
    override fun findPath(state: WorldState, sx: Int, sz: Int, gx: Int, gz: Int, trail: FloatArray?, danger: FloatArray?) =
        Pathfinding.findPath(state, sx, sz, gx, gz, trail, danger)
}

/** Pedido de deliberação neural assíncrona (motor GGUF local). */
data class NeuralRequest(
    val agentId: String,
    val tick: Long,
    val prompt: NeuralPrompt,
    val allowedActions: List<String>,
    /** Modelo .gguf exclusivo do cidadão (fundador ou linhagem); null = qualquer modelo carregado. */
    val modelKey: String? = null
)

/**
 * Prompt neural. [worldPrefix] é o IMMUTABLE_WORLD_PREFIX (idêntico para todos os cidadãos,
 * reaproveitado no KV-cache); [system] carrega o P_SELF do cidadão (slot persistido por
 * cidadão); [jsonKeys] habilita o fast-forward de tokens estruturais; [recentSpeeches] alimenta
 * o SemanticEntropyController; [seed] torna a amostragem reprodutível por semente.
 */
data class NeuralPrompt(
    val system: String,
    val user: String,
    val grammar: String,
    val worldPrefix: String = "",
    val jsonKeys: List<String> = emptyList(),
    val recentSpeeches: List<String> = emptyList(),
    val seed: Long = 0L,
    /** v3.5: mesma gramática na sintaxe Lark do llguidance (usada só se o build incluir o crate). */
    val larkGrammar: String = ""
)

/** Resultado validado pela gramática GBNF e pelo parser; vira evento canônico ao ser aplicado. */
data class NeuralDecision(
    val agentId: String,
    val requestTick: Long,
    val action: String,
    val thought: String,
    val speech: String,
    val tokens: Int,
    val millis: Long,
    val backend: String
)

/**
 * Porta assíncrona do LLM. Disponibilidade e latência não afetam o estado do mundo:
 * apenas os resultados drenados no estágio 1 (registrados como eventos) entram na
 * simulação — o determinismo é condicionado ao log de eventos.
 */
interface NeuralDeliberationPort {
    fun isAvailable(): Boolean
    fun trySubmit(request: NeuralRequest): Boolean
    fun drainResults(): List<NeuralDecision>
}

/** Aceitador de Resultados da Ação (Anokhin); saída = [mismatch, valence, plasticity, confirmed]. */
interface ActionAcceptorPort {
    val backend: String
    fun compare(projected: DoubleArray, forecast: Double, memUtility: Double, atDecision: DoubleArray, obtained: DoubleArray, success: Boolean): DoubleArray
}

object KotlinActionAcceptor : ActionAcceptorPort {
    override val backend = "kotlin-anokhin"
    override fun compare(projected: DoubleArray, forecast: Double, memUtility: Double, atDecision: DoubleArray, obtained: DoubleArray, success: Boolean) =
        com.enhikyy.girassol.core.cognition.AnokhinFunctionalSystem.kotlinCase(projected, forecast, memUtility, atDecision, obtained, success)
}

data class CorePorts(
    val deliberator: Deliberator = SymbolicLookaheadDeliberator(),
    val pathfinder: PathfinderPort = KotlinPathfinder,
    val lora: LoraTrainerPort = KotlinLoraTrainer,
    val neural: NeuralDeliberationPort? = null,
    val acceptor: ActionAcceptorPort = KotlinActionAcceptor
) {
    /** Portas usadas em Shadow-Replay/sandbox: sem LLM (contrafactual puramente simbólico). */
    fun forShadow(): CorePorts = copy(neural = null)
}
