package com.enhikyy.girassol.nativebridge

import android.util.Log

/** Recursos SIMD lidos do HWCAP/HWCAP2 pelo núcleo C++ (getauxval). */
data class CpuSimdFlags(
    val neon: Boolean = false,
    val dotprod: Boolean = false,
    val fp16: Boolean = false,
    val i8mm: Boolean = false,
    val sve: Boolean = false,
    val sve2: Boolean = false,
    val aarch64: Boolean = false
) {
    fun label(): String = listOf("NEON" to neon, "DOTPROD" to dotprod, "FP16" to fp16, "I8MM" to i8mm, "SVE" to sve, "SVE2" to sve2)
        .joinToString(" ") { (n, v) -> "$n=${if (v) 1 else 0}" }

    companion object {
        fun fromNative(v: IntArray?): CpuSimdFlags {
            if (v == null || v.size < 7) return CpuSimdFlags()
            return CpuSimdFlags(v[0] != 0, v[1] != 0, v[2] != 0, v[3] != 0, v[4] != 0, v[5] != 0, v[6] != 0)
        }
    }
}

/**
 * Escolha da variante do motor llama.cpp. i8mm só é usado quando o HWCAP2 o confirma
 * (Cortex-A510/A710/X2+); o Snapdragon 695 (Cortex-A78/A55) e o Helio G99 (A76/A55) têm
 * dotprod+fp16 mas NÃO i8mm, e recebem a variante dotprod — sem risco de SIGILL.
 * A lista é ordenada da mais rápida para a mais segura; "base" sempre fecha a lista.
 */
object LlmVariantSelector {
    const val BASE = "base"
    const val DOTPROD = "dotprod"
    const val I8MM = "i8mm"

    fun candidates(f: CpuSimdFlags): List<String> = when {
        !f.aarch64 || !f.neon -> listOf(BASE)
        f.dotprod && f.fp16 && f.i8mm -> listOf(I8MM, DOTPROD, BASE)
        f.dotprod && f.fp16 -> listOf(DOTPROD, BASE)
        else -> listOf(BASE)
    }
}

/**
 * Ponte JNI do motor LLM. Carrega UMA das bibliotecas libgirassol_llm_{i8mm,dotprod,base}.so
 * conforme o HWCAP; se o carregamento falhar, desce para a próxima variante.
 */
object LlmNativeBridge {
    private const val TAG = "GirassolLLM"

    val cpuFlags: CpuSimdFlags =
        if (GeaNativeBridge.isNativeLoaded) runCatching { CpuSimdFlags.fromNative(GeaNativeBridge.nativeCpuFeatures()) }.getOrDefault(CpuSimdFlags())
        else CpuSimdFlags()

    /** Variante efetivamente carregada (null = nenhuma). */
    @Volatile var loadedVariant: String? = null
        private set
    @Volatile var loadLog: String = ""
        private set

    init {
        val log = StringBuilder()
        for (v in LlmVariantSelector.candidates(cpuFlags)) {
            try {
                System.loadLibrary("girassol_llm_$v")
                loadedVariant = v
                log.append("$v: ok")
                break
            } catch (t: Throwable) {
                log.append("$v: ${t.javaClass.simpleName}; ")
                Log.w(TAG, "variante $v indisponível", t)
            }
        }
        loadLog = log.toString()
        Log.i(TAG, "SIMD ${cpuFlags.label()} → variante ${loadedVariant ?: "nenhuma"} ($loadLog)")
    }

    val isLoaded: Boolean get() = loadedVariant != null

    /** "variante|march" compilado na biblioteca carregada (ex.: dotprod|armv8.2-a+dotprod+fp16+kleidiai). */
    val buildVariant: String by lazy { if (isLoaded) runCatching { nativeLlmVariant() }.getOrDefault("?") else "-" }

    /** Recursos que o ggml-cpu realmente usa (llama_print_system_info). */
    val systemInfo: String by lazy { if (isLoaded) runCatching { nativeLlmSystemInfo() }.getOrDefault("") else "" }

    @JvmStatic external fun nativeLlmAvailable(): Boolean
    /** Retorna "" em caso de sucesso ou a mensagem de erro. */
    @JvmStatic external fun nativeLlmLoad(path: String, threads: Int, nCtx: Int): String
    @JvmStatic external fun nativeLlmUnload()
    @JvmStatic external fun nativeLlmAbort()
    /** "descrição|bytes" do modelo carregado, ou "". */
    @JvmStatic external fun nativeLlmDescribe(): String
    /** [texto, erro, promptTokens, genTokens, millis, tokens/s total, tokens/s geração]. */
    @JvmStatic external fun nativeLlmGenerate(system: String, user: String, grammar: String, maxTokens: Int): Array<String>
    /** [pp tokens/s, tg tokens/s, nPrompt, nGen]; vazio se não houver modelo. */
    @JvmStatic external fun nativeLlmBench(nPrompt: Int, nGen: Int): DoubleArray
    @JvmStatic external fun nativeLlmSystemInfo(): String
    @JvmStatic external fun nativeLlmVariant(): String
}
