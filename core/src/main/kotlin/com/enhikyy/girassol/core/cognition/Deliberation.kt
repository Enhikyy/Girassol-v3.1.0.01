package com.enhikyy.girassol.core.cognition

import com.enhikyy.girassol.core.util.fmt

data class DeliberationOption(
    val key: String,
    val expectedFreeEnergy: Double,
    /** Necessidades projetadas após executar a opção (6 motivos, 0..1). */
    val projectedNeeds: DoubleArray,
    val travelTicks: Int
)

data class DeliberationContext(
    val bus: PianoCognitiveBusSnapshot,
    val options: List<DeliberationOption>,
    val needDecayPerTick: DoubleArray
)

data class DeliberationResult(val chosenIndex: Int, val rationale: String, val sessionRole: String, val tokensUsed: Int)

/**
 * Sessões especializadas PIANO (PROPOSER / CRITIC / REFLECTOR). A implementação padrão
 * é simbólica e determinística; um motor neural local (GGUF via llama.cpp) pode ser
 * plugado implementando esta interface e respeitando a gramática GBNF de saída.
 */
interface Deliberator {
    val name: String
    fun deliberate(ctx: DeliberationContext): DeliberationResult
}

/**
 * Deliberador simbólico: busca em profundidade 2 sobre as opções (PROPOSER), critica
 * por risco homeostático do pior motivo (CRITIC) e escolhe o menor custo total.
 */
class SymbolicLookaheadDeliberator : Deliberator {
    override val name: String = "simbolico-lookahead-2"

    override fun deliberate(ctx: DeliberationContext): DeliberationResult {
        if (ctx.options.isEmpty()) return DeliberationResult(-1, "sem opções", "PROPOSER", 0)
        var best = 0
        var bestScore = Double.MAX_VALUE
        for ((i, first) in ctx.options.withIndex()) {
            val afterFirst = first.projectedNeeds
            // segundo passo: melhor continuação partindo do estado projetado
            var bestSecond = Double.MAX_VALUE
            for (second in ctx.options) {
                val gain = DoubleArray(afterFirst.size) { k ->
                    (afterFirst[k] + (second.projectedNeeds[k] - ctx.bus.needsArray[k]) -
                        ctx.needDecayPerTick[k] * second.travelTicks).coerceIn(0.0, 1.0)
                }
                bestSecond = minOf(bestSecond, homeostaticRisk(gain))
            }
            val critic = 1.5 * worstDeficit(afterFirst)
            val score = first.expectedFreeEnergy + 0.6 * bestSecond + critic
            if (score < bestScore) { bestScore = score; best = i }
        }
        val o = ctx.options[best]
        return DeliberationResult(
            best,
            "${ctx.bus.coreSelf.renderIdentityHeader()} escolheu ${o.key} (custo=${"%.3f".fmt(bestScore)}, G=${"%.3f".fmt(o.expectedFreeEnergy)})",
            "CRITIC",
            0
        )
    }

    private fun homeostaticRisk(n: DoubleArray): Double = n.sumOf { (1.0 - it) * (1.0 - it) }
    private fun worstDeficit(n: DoubleArray): Double = n.maxOf { 1.0 - it }
}
