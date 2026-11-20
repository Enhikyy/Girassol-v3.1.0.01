package com.enhikyy.girassol.system

/**
 * Disjuntor de memória (lógica pura, testada em JVM).
 *
 * Abre quando o VmRSS do processo passa do limite seguro do aparelho ou quando o Android
 * sinaliza TRIM_MEMORY_RUNNING_CRITICAL/onLowMemory. Rearma só depois que o RSS fica abaixo
 * de [rearmFraction]·limite por [rearmHoldMs] sem novos sinais críticos (histerese), para
 * não ficar carregando/descarregando o modelo em ciclo.
 */
class MemoryBreaker(
    val limitBytes: Long,
    private val rearmFraction: Double = 0.80,
    private val rearmHoldMs: Long = 60_000L
) {
    enum class Action { NONE, TRIP, REARM }

    var open: Boolean = false
        private set
    var trips: Int = 0
        private set
    var lastReason: String = ""
        private set
    private var belowSinceMs: Long = -1L
    private var lastCriticalMs: Long = Long.MIN_VALUE / 2

    val rearmBytes: Long get() = (limitBytes * rearmFraction).toLong()

    /** Avalia uma amostra de RSS (bytes; < 0 = desconhecido). */
    fun onSample(rssBytes: Long, nowMs: Long): Action {
        if (!open) {
            if (rssBytes > limitBytes) return trip("VmRSS ${rssBytes / MB} MB > limite ${limitBytes / MB} MB", nowMs)
            return Action.NONE
        }
        if (rssBytes in 0 until rearmBytes && nowMs - lastCriticalMs >= rearmHoldMs) {
            if (belowSinceMs < 0) belowSinceMs = nowMs
            if (nowMs - belowSinceMs >= rearmHoldMs) {
                open = false
                belowSinceMs = -1L
                return Action.REARM
            }
        } else {
            belowSinceMs = -1L
        }
        return Action.NONE
    }

    /** Rearme manual (botão da aba Sistema / testes). */
    fun forceRearm(): Action {
        if (!open) return Action.NONE
        open = false
        belowSinceMs = -1L
        return Action.REARM
    }

    /** Sinal crítico do sistema (TRIM_MEMORY_RUNNING_CRITICAL, onLowMemory). */
    fun onCriticalSignal(reason: String, nowMs: Long): Action {
        lastCriticalMs = nowMs
        belowSinceMs = -1L
        return if (open) Action.NONE else trip(reason, nowMs)
    }

    private fun trip(reason: String, nowMs: Long): Action {
        open = true
        trips++
        lastReason = reason
        belowSinceMs = -1L
        lastCriticalMs = maxOf(lastCriticalMs, nowMs - rearmHoldMs) // conta a histerese a partir de agora
        return Action.TRIP
    }

    companion object {
        const val MB = 1024L * 1024L

        /**
         * Limite seguro de VmRSS por RAM física: 4 GB → 1,8 GB (deixa ~2 GB para o sistema,
         * GPU e apps em segundo plano). Android reporta totalMem um pouco abaixo do nominal.
         */
        fun limitForTotalRam(totalBytes: Long): Long {
            val gb = totalBytes.toDouble() / (1024.0 * MB)
            val mb = when {
                gb <= 3.2 -> 1_200L
                gb <= 4.6 -> 1_800L
                gb <= 6.6 -> 2_600L
                else -> 3_200L
            }
            return mb * MB
        }
    }
}
