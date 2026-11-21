package com.enhikyy.girassol.system

/**
 * Disjuntor de memória (lógica pura, testada em JVM).
 *
 * Abre quando a RAM ANÔNIMA do processo (RssAnon + swap — heap Java/nativo, KV-cache, buffers
 * de computação) passa do limite seguro do aparelho, quando o sistema reporta
 * ActivityManager.MemoryInfo.lowMemory real, ou quando o Android sinaliza
 * TRIM_MEMORY_RUNNING_CRITICAL/onLowMemory. Páginas de arquivo mapeadas por mmap (os pesos
 * .gguf) NÃO contam: são limpas e recuperáveis pelo kernel sem matar o processo. Rearma só depois que o RSS fica abaixo
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

    /**
     * Avalia uma amostra da pressão de memória do processo (bytes; < 0 = desconhecido) — use
     * [pressureBytes] (RAM anônima) — e o lowMemory real do sistema.
     */
    fun onSample(rssBytes: Long, nowMs: Long, systemLowMemory: Boolean = false): Action {
        if (!open) {
            if (rssBytes > limitBytes) return trip("RAM anônima ${rssBytes / MB} MB > limite ${limitBytes / MB} MB", nowMs)
            if (systemLowMemory) return trip("sistema em lowMemory", nowMs)
            return Action.NONE
        }
        if (systemLowMemory) lastCriticalMs = nowMs
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
         * Pressão de memória do processo a partir de /proc/self/status (kB):
         * [0]=VmRSS [3]=RssAnon [5]=VmSwap. Usa RssAnon + swap quando disponível; VmRSS
         * (que inclui o .gguf mapeado) só como último recurso.
         */
        fun pressureBytes(pm: LongArray): Long = when {
            pm.size > 3 && pm[3] > 0 -> (pm[3] + (pm.getOrNull(5) ?: 0L).coerceAtLeast(0L)) * 1024
            pm.isNotEmpty() && pm[0] > 0 -> pm[0] * 1024
            else -> -1L
        }

        /**
         * Sinal do sistema que deve abrir o disjuntor: RUNNING_CRITICAL (app em primeiro plano
         * sob pressão real) sempre; TRIM_MEMORY_COMPLETE só se o sistema também estiver em
         * lowMemory. UI_HIDDEN/BACKGROUND/MODERATE (ex.: abrir o seletor de arquivos para
         * importar um .gguf) nunca descarregam o modelo.
         */
        fun isCriticalTrim(level: Int, systemLowMemory: Boolean): Boolean =
            level == 15 || (level >= 80 && systemLowMemory)

        /**
         * Limite seguro de RAM anônima por RAM física: 4 GB → 1,8 GB (deixa ~2 GB para o sistema,
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
