package com.enhikyy.girassol.llm

import android.app.ActivityManager
import android.content.Context
import com.enhikyy.girassol.data.GirassolDatabase
import com.enhikyy.girassol.render3d.FilamentMemory
import java.io.File

/**
 * v3.8 — orçamento dinâmico de RAM para o pool do llama.cpp.
 *
 * Antes o teto era só função da RAM física ([LlmQuantPolicy.budgetFor]). Agora, a cada carga:
 *   livre    = min(/proc/meminfo MemAvailable, ActivityManager.MemoryInfo.availMem)
 *   reserva  = MemoryInfo.threshold + Filament nativo (FilamentMemory) + gráfico do processo
 *              + WAL do SQLite (×2: o checkpoint copia as páginas) + folga mínima
 *   dinâmico = clamp(livre − reserva, 0, teto do perfil)
 * O pool só admite um modelo se a pegada ativa estimada couber no dinâmico; o perfil 4 GB
 * continua com 1 modelo e o teto físico; 8 GB/12 GB reduzem o pool quando o dinâmico encolhe.
 */
object RamBudget {
    private const val MB = 1024L * 1024
    /** RAM livre que precisa sobrar depois do modelo (mesma folga do disjuntor). */
    const val MIN_FREE_BYTES = 300L * MB

    data class Inputs(
        val totalMem: Long,
        /** MemAvailable do kernel em kB; −1 = indisponível (fica só o ActivityManager). */
        val memAvailableKb: Long,
        val amAvailMem: Long,
        val amThreshold: Long,
        val lowMemory: Boolean,
        val filamentNativeBytes: Long,
        val graphicsBytes: Long,
        val walBytes: Long
    )

    data class Budget(
        val availableBytes: Long,
        val reserveBytes: Long,
        val dynamicBytes: Long,
        val staticBytes: Long,
        val lowMemory: Boolean,
        val source: String
    ) {
        val dynamicMb: Long get() = dynamicBytes / MB
        val availableMb: Long get() = availableBytes / MB
        val reserveMb: Long get() = reserveBytes / MB
        fun fits(activeBytes: Long): Boolean = !lowMemory && activeBytes <= dynamicBytes
    }

    /** Extrai MemAvailable (kB) de um texto no formato de /proc/meminfo; −1 se ausente. */
    fun parseMemAvailableKb(meminfo: String): Long {
        for (line in meminfo.lineSequence()) {
            if (!line.startsWith("MemAvailable:")) continue
            return line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: -1L
        }
        return -1L
    }

    fun compute(i: Inputs): Budget {
        val kernel = if (i.memAvailableKb > 0) i.memAvailableKb * 1024 else -1L
        val avail = when {
            kernel > 0 && i.amAvailMem > 0 -> minOf(kernel, i.amAvailMem)
            kernel > 0 -> kernel
            else -> i.amAvailMem.coerceAtLeast(0L)
        }
        val source = when {
            kernel > 0 && i.amAvailMem > 0 -> if (kernel <= i.amAvailMem) "/proc/meminfo" else "ActivityManager"
            kernel > 0 -> "/proc/meminfo"
            else -> "ActivityManager"
        }
        val reserve = i.amThreshold.coerceAtLeast(0L) + i.filamentNativeBytes.coerceAtLeast(0L) +
            i.graphicsBytes.coerceAtLeast(0L) + 2 * i.walBytes.coerceAtLeast(0L) + MIN_FREE_BYTES
        val static = LlmQuantPolicy.budgetFor(i.totalMem)
        val dynamic = (avail - reserve).coerceIn(0L, static)
        return Budget(avail, reserve, dynamic, static, i.lowMemory, source)
    }

    /** Lê /proc/meminfo, ActivityManager, Filament, memória gráfica e o WAL reais do aparelho. */
    fun read(context: Context): Budget {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val memAvail = runCatching { parseMemAvailableKb(File("/proc/meminfo").readText()) }.getOrDefault(-1L)
        val graphicsKb = runCatching {
            val dmi = android.os.Debug.MemoryInfo()
            android.os.Debug.getMemoryInfo(dmi)
            dmi.getMemoryStat("summary.graphics")?.toLongOrNull() ?: 0L
        }.getOrDefault(0L)
        val wal = runCatching { File(context.getDatabasePath(GirassolDatabase.DB_NAME).path + "-wal").length() }.getOrDefault(0L)
        return compute(Inputs(mi.totalMem, memAvail, mi.availMem, mi.threshold, mi.lowMemory,
            FilamentMemory.nativeBytes(), graphicsKb * 1024, wal))
    }
}
