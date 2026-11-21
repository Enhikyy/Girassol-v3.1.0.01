package com.enhikyy.girassol.render3d

import java.util.concurrent.atomic.AtomicLong

/**
 * v3.8 — memória nativa do Filament realmente em uso (soma dos renderizadores vivos).
 * Cada FilamentCityRenderer mede o heap nativo (Debug.getNativeHeapAllocatedSize) antes e
 * depois de criar Engine, materiais, texturas KTX2 e malhas, e devolve o valor ao destruir.
 * O orçamento do LLM (llm/RamBudget) desconta esse valor e a memória gráfica do processo.
 */
object FilamentMemory {
    /** Piso conservador por Engine (command stream de 3 MB + buffers do driver GL), usado quando a medição dá menos. */
    const val MIN_ENGINE_BYTES = 24L * 1024 * 1024
    private val bytes = AtomicLong(0)

    fun add(delta: Long) { bytes.updateAndGet { (it + delta).coerceAtLeast(0L) } }
    fun nativeBytes(): Long = bytes.get()
}
