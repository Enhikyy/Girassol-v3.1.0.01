package com.enhikyy.girassol.core.world

import com.enhikyy.girassol.core.world.StockFlowDoubleEntryLedger.Companion.COMMUNAL_COMMONS
import kotlin.math.ceil
import kotlin.math.max

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

    data class Check(
        val adult: Boolean,
        val anokhin: Boolean,
        val brier: Boolean,
        val promotion: Boolean,
        val wellbeing: Boolean
    ) {
        val all: Boolean get() = adult && anokhin && brier && promotion && wellbeing
        fun blockers(): List<String> = buildList {
            if (!adult) add("maturidade")
            if (!anokhin) add("erro Anokhin")
            if (!brier) add("Brier")
            if (!promotion) add("promoção RSI/LoRA")
            if (!wellbeing) add("bem-estar")
        }
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

    fun check(c: Citizen, tick: Long): Check {
        val ab = c.acceptorBaseline
        val bb = c.brierBaseline
        return Check(
            adult = c.ageDays(tick) >= ADULT_DAYS,
            // descompasso nulo desde o início também conta como domínio (nada a reduzir)
            anokhin = ab >= 0 && c.acceptorEma >= 0 && (c.acceptorEma <= ANOKHIN_RATIO * ab || (ab < 1e-6 && c.acceptorEma < 1e-6)),
            brier = bb >= 0 && c.brierEma >= 0 && c.brierEma <= bb - BRIER_MARGIN,
            promotion = c.promotions > c.promotionsAtLastReproduction,
            wellbeing = c.meanNeed() >= WELLBEING_MIN
        )
    }

    fun commonsSurplus(state: WorldState, population: Int): Boolean =
        state.ledger.getBalance(COMMUNAL_COMMONS, FOOD) >= population * COMMONS_FOOD_PER_CAPITA

    fun compatible(a: Citizen, b: Citizen): Boolean = a.sex.isEmpty() || b.sex.isEmpty() || a.sex != b.sex

    /** Ticks estimados para o cidadão satisfazer o próprio gatilho (-1 = sem taxa mensurável ainda). */
    fun citizenEtaTicks(c: Citizen, tick: Long, check: Check = check(c, tick)): Long {
        var worst = 0L
        fun take(t: Long) { if (worst < 0) return; worst = if (t < 0) -1 else max(worst, t) }
        if (!check.adult) take(ceil((ADULT_DAYS - c.ageDays(tick)) * TICKS_PER_DAY).toLong())
        if (!check.anokhin) take(seriesEta(c.acceptorBaseN, c.acceptorBaseline, c.acceptorEma, c.acceptorBaselineTick, ANOKHIN_RATIO * c.acceptorBaseline, tick))
        if (!check.brier) take(seriesEta(c.brierBaseN, c.brierBaseline, c.brierEma, c.brierBaselineTick, c.brierBaseline - BRIER_MARGIN, tick))
        if (!check.promotion) {
            // próximo ciclo de promoção: o LoRA do sono roda na virada do dia; o RSI/CMA-ES a cada 3 dias
            val toDay = TICKS_PER_DAY - (tick % TICKS_PER_DAY)
            take(toDay)
        }
        if (!check.wellbeing) take(-1)
        return worst
    }

    /** Tempo até a série cair abaixo do alvo, pela velocidade média de queda desde t0. */
    private fun seriesEta(n: Int, baseline: Double, ema: Double, baselineTick: Long, target: Double, tick: Long): Long {
        if (n < MERIT_BASELINE_SAMPLES || baselineTick < 0) return -1
        val elapsed = (tick - baselineTick).coerceAtLeast(1)
        val rate = (baseline - ema) / elapsed
        if (rate <= 1e-9) return -1
        return ceil((ema - target).coerceAtLeast(0.0) / rate).toLong()
    }

    /** Melhor par compatível vivo e o ETA da próxima geração. */
    fun estimate(state: WorldState): Eta {
        val alive = state.aliveCitizens()
        if (alive.isEmpty()) return Eta(Status.SEM_POPULACAO, -1)
        if (alive.size < 2) return Eta(Status.PRECISA_PAR, -1, alive[0].id, nextGeneration = alive[0].generation + 1)
        val tick = state.tick
        val checks = alive.associate { it.id to check(it, tick) }
        val etas = alive.associate { it.id to citizenEtaTicks(it, tick, checks.getValue(it.id)) }
        val surplus = commonsSurplus(state, alive.size)
        var best: Eta? = null
        val sorted = alive.sortedBy { it.id }
        for (i in sorted.indices) for (j in i + 1 until sorted.size) {
            val a = sorted[i]; val b = sorted[j]
            if (!compatible(a, b) || a.children >= 4 || b.children >= 4) continue
            val ca = checks.getValue(a.id); val cb = checks.getValue(b.id)
            val ea = etas.getValue(a.id); val eb = etas.getValue(b.id)
            val ticks = if (ea < 0 || eb < 0) -1 else max(ea, eb)
            val blockers = (ca.blockers() + cb.blockers()).distinct() + (if (surplus) emptyList() else listOf("comuns"))
            val status = if (ca.all && cb.all && surplus) Status.PRONTO else Status.AGUARDANDO
            val cand = Eta(status, if (status == Status.PRONTO) 0 else ticks, a.id, b.id, max(a.generation, b.generation) + 1, blockers)
            best = when {
                best == null -> cand
                rank(cand) < rank(best) -> cand
                else -> best
            }
        }
        return best ?: Eta(Status.PRECISA_PAR, -1, sorted[0].id, nextGeneration = sorted[0].generation + 1)
    }

    private fun rank(e: Eta): Double = when {
        e.status == Status.PRONTO -> -1.0
        e.ticks >= 0 -> e.ticks.toDouble()
        else -> 1e12 + e.blockers.size
    }
}
