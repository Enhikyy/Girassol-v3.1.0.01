package com.enhikyy.girassol.core.world

import com.enhikyy.girassol.core.world.StockFlowDoubleEntryLedger.Companion.COMMUNAL_COMMONS

/**
 * v3.6 — Reprodução por mérito de desempenho.
 *
 * Dois adultos só geram um filho quando AMBOS comprovam evolução real sobre o próprio estado
 * inicial t0 (linha de base formada pelas primeiras [MERIT_BASELINE_SAMPLES] amostras):
 *  1. descompasso do Aceitador de Resultados (Anokhin) ≤ [ANOKHIN_RATIO] × linha de base
 *     e Brier individual abaixo da linha de base;
 *  2. ≥ 1 promoção aprovada no BoundedRsiPromotionGate (CMA-ES/RSI ou ciclo LoRA do sono)
 *     desde a última reprodução;
 *  3. bem-estar ≥ [WELLBEING_MIN] e superávit nos comuns.
 *
 * O ETA da próxima geração é calculado com as taxas observadas (velocidade de queda do erro,
 * próximo ciclo de promoção e maturidade). Funções puras sobre o [WorldState]: não alteram o mundo.
 */
object ReproductionMerit {
    const val ANOKHIN_RATIO = 0.90
    const val BRIER_MARGIN = 0.005
    const val WELLBEING_MIN = 0.70
    const val COMMONS_FOOD_PER_CAPITA = 2.0
    const val EMA_ALPHA = 0.05

    const val IRT_MIN_RESPONSES = 12.0
    const val IRT_MARGIN = 0.10

    data class Check(
        val adult: Boolean,
        val anokhin: Boolean,
        val brier: Boolean,
        val promotion: Boolean,
        val wellbeing: Boolean,
        /** v3.7: habilidade latente IRT 2PL θ ≥ θ0 + [IRT_MARGIN]. */
        val irt: Boolean = true
    ) {
        val all: Boolean get() = adult && anokhin && brier && promotion && wellbeing && irt
        fun blockers(): List<String> = blockerLabels(bits())
        fun bits(): Long = (if (adult) NativeWorldTick.BIT_ADULT else 0) or (if (anokhin) NativeWorldTick.BIT_ANOKHIN else 0) or
            (if (brier) NativeWorldTick.BIT_BRIER else 0) or (if (promotion) NativeWorldTick.BIT_PROMOTION else 0) or
            (if (wellbeing) NativeWorldTick.BIT_WELLBEING else 0) or (if (irt) NativeWorldTick.BIT_IRT else 0)

        companion object {
            fun of(bits: Long) = Check(bits and NativeWorldTick.BIT_ADULT != 0L, bits and NativeWorldTick.BIT_ANOKHIN != 0L,
                bits and NativeWorldTick.BIT_BRIER != 0L, bits and NativeWorldTick.BIT_PROMOTION != 0L,
                bits and NativeWorldTick.BIT_WELLBEING != 0L, bits and NativeWorldTick.BIT_IRT != 0L)
        }
    }

    /** Rótulos dos critérios NÃO satisfeitos (bits ausentes) + bloqueio dos comuns. */
    fun blockerLabels(okBits: Long, commonsBlocked: Boolean = false): List<String> = buildList {
        if (okBits and NativeWorldTick.BIT_ADULT == 0L) add("maturidade")
        if (okBits and NativeWorldTick.BIT_ANOKHIN == 0L) add("erro Anokhin")
        if (okBits and NativeWorldTick.BIT_BRIER == 0L) add("Brier")
        if (okBits and NativeWorldTick.BIT_PROMOTION == 0L) add("promoção RSI/LoRA")
        if (okBits and NativeWorldTick.BIT_WELLBEING == 0L) add("bem-estar")
        if (okBits and NativeWorldTick.BIT_IRT == 0L) add("IRT θ")
        if (commonsBlocked) add("comuns")
    }

    enum class Status { SEM_POPULACAO, PRECISA_PAR, PRONTO, AGUARDANDO }

    data class Eta(
        val status: Status,
        /** Ticks estimados até a próxima geração; -1 quando ainda não há taxa de evolução mensurável. */
        val ticks: Long,
        val parentA: String? = null,
        val parentB: String? = null,
        val nextGeneration: Int = 0,
        val blockers: List<String> = emptyList()
    ) {
        val days: Double get() = if (ticks < 0) -1.0 else ticks.toDouble() / TICKS_PER_DAY
    }

    /** Atualiza a linha de base t0 e a média recente de uma série (Anokhin ou Brier). */
    fun updateAcceptor(c: Citizen, mismatch: Double, tick: Long) {
        if (c.acceptorBaseN < MERIT_BASELINE_SAMPLES) {
            c.acceptorBaseSum += mismatch; c.acceptorBaseN++
            if (c.acceptorBaseN == MERIT_BASELINE_SAMPLES) { c.acceptorEma = c.acceptorBaseline; c.acceptorBaselineTick = tick }
        } else c.acceptorEma = (1 - EMA_ALPHA) * c.acceptorEma + EMA_ALPHA * mismatch
    }

    fun updateBrier(c: Citizen, squaredError: Double, tick: Long) {
        if (c.brierBaseN < MERIT_BASELINE_SAMPLES) {
            c.brierBaseSum += squaredError; c.brierBaseN++
            if (c.brierBaseN == MERIT_BASELINE_SAMPLES) { c.brierEma = c.brierBaseline; c.brierBaselineTick = tick }
        } else c.brierEma = (1 - EMA_ALPHA) * c.brierEma + EMA_ALPHA * squaredError
    }

    /** Gatilho de mérito avaliado pelo kernel nativo (mesma função usada pela reprodução). */
    fun check(c: Citizen, tick: Long): Check = Check.of(citizenRaw(c, tick)[0])

    private fun citizenRaw(c: Citizen, tick: Long): LongArray {
        val (l, d) = NativeWorldTick.packMerit(listOf(c))
        return NativeWorldTick.nativeMeritCitizens(arrayOf(c.id), l, d, tick)
    }

    fun commonsSurplus(state: WorldState, population: Int): Boolean =
        state.ledger.getBalance(COMMUNAL_COMMONS, FOOD) >= population * COMMONS_FOOD_PER_CAPITA

    fun compatible(a: Citizen, b: Citizen): Boolean = a.sex.isEmpty() || b.sex.isEmpty() || a.sex != b.sex

    /** Ticks estimados para o cidadão satisfazer o próprio gatilho (-1 = sem taxa mensurável ainda). */
    fun citizenEtaTicks(c: Citizen, tick: Long): Long = citizenRaw(c, tick)[1]

    /** Melhor par compatível vivo e o ETA da próxima geração (kernel nativo). */
    fun estimate(state: WorldState): Eta {
        val alive = state.aliveCitizens()
        val (l, d) = NativeWorldTick.packMerit(alive)
        val r = NativeWorldTick.nativeMeritEstimate(alive.map { it.id }.toTypedArray(), l, d,
            state.ledger.getBalance(COMMUNAL_COMMONS, FOOD), state.tick)
        val status = Status.entries[r[0].toInt()]
        val blockers = r[5]
        return Eta(
            status = status,
            ticks = r[1],
            parentA = r[2].toInt().takeIf { it >= 0 }?.let { alive[it].id },
            parentB = r[3].toInt().takeIf { it >= 0 }?.let { alive[it].id },
            nextGeneration = r[4].toInt(),
            blockers = if (status == Status.AGUARDANDO || status == Status.PRONTO)
                blockerLabels(NativeWorldTick.ALL_CITIZEN_BITS and blockers.inv(), blockers and NativeWorldTick.BIT_COMMONS != 0L)
            else emptyList()
        )
    }
}
