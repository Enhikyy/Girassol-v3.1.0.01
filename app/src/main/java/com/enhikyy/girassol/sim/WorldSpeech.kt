package com.enhikyy.girassol.sim

/** Fala autônoma de um cidadão na cena (balão + bolha no Chat). */
data class WorldSpeech(
    val key: Long,
    val tick: Long,
    val wallClockMs: Long,
    val citizenId: String,
    val name: String,
    val text: String,
    val neural: Boolean,
    val thought: String,
    val action: String?
)

/** Amostra viva para sparklines (desde o tick 1). Valores 0..1. */
data class LiveSample(
    val tick: Long,
    val population: Int,
    val meanMood: Double,
    val meanNeed: Double,
    val meanEnergy: Double,
    val meanSocial: Double
)

/**
 * Relógio simulado legível: "Dia 3 · 14:30". 480 ticks = 24 h; o tick 0 de cada dia é 06:00
 * (o núcleo marca noite em tick%480 ≥ 360 ⇒ 00:00–06:00, sempre dentro da noite visual).
 */
object SimClock {
    const val TICKS_PER_DAY = 480L
    fun day(tick: Long): Long = tick / TICKS_PER_DAY + 1
    fun minutesOfDay(tick: Long): Int = ((((tick % TICKS_PER_DAY) * 1440L) / TICKS_PER_DAY + DAWN_OFFSET_MIN) % 1440L).toInt()
    const val DAWN_OFFSET_MIN = 360L
    fun hhmm(tick: Long): String { val m = minutesOfDay(tick); return "%02d:%02d".format(m / 60, m % 60) }
    fun label(tick: Long): String = "Dia ${day(tick)} · ${hhmm(tick)}"
    /** Período do dia (para o céu e a luz do sol). */
    fun period(tick: Long): String = when (minutesOfDay(tick) / 60) {
        in 5..6 -> "Amanhecer"; in 7..11 -> "Manhã"; in 12..17 -> "Tarde"; in 18..19 -> "Entardecer"; else -> "Noite"
    }
}
