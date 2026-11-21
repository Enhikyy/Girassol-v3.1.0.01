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
 * Escolha do backend de CPU dinâmico do ggml (GGML_BACKEND_DL). i8mm só é usado quando o
 * HWCAP2 o confirma (Cortex-A510/A710/X2+); o Snapdragon 695 (Cortex-A78/A55) e o Helio G99
 * (A76/A55) têm dotprod+fp16 mas NÃO i8mm e recebem armv8.2_2 — sem risco de SIGILL (e o
 * próprio ggml recusa um backend cujo ggml_backend_score() seja 0).
 * A lista vai do mais rápido ao mais seguro; armv8.0_1 sempre fecha a lista no arm64.
 */
object LlmVariantSelector {
    const val BASE = "base"
    const val DOTPROD = "dotprod"
    const val I8MM = "i8mm"
    const val X86 = "x86_64"

    fun candidates(f: CpuSimdFlags): List<String> = when {
        !f.aarch64 -> listOf(X86)
        !f.neon -> listOf(BASE)
        f.dotprod && f.fp16 && f.i8mm -> listOf(I8MM, DOTPROD, BASE)
        f.dotprod && f.fp16 -> listOf(DOTPROD, BASE)
        else -> listOf(BASE)
    }

    /** Biblioteca do backend de CPU (mesma tabela de cpuBackendLibrary() no C++). */
    fun backendLibrary(variant: String): String = when (variant) {
        I8MM -> "libggml-cpu-android_armv8.6_1.so"
        DOTPROD -> "libggml-cpu-android_armv8.2_2.so"
        BASE -> "libggml-cpu-android_armv8.0_1.so"
        else -> "libggml-cpu.so"
    }
}

/**
 * Ponte JNI do motor LLM. Carrega a libgirassol_llm.so (que puxa a libllama.so e a
 * libggml.so compartilhadas) e em seguida UM backend de CPU conforme o HWCAP; se o
 * carregamento falhar, desce para o próximo candidato.
 */
object LlmNativeBridge {
    private const val TAG = "GirassolLLM"

    val cpuFlags: CpuSimdFlags =
        if (GeaNativeBridge.isNativeLoaded) runCatching { CpuSimdFlags.fromNative(GeaNativeBridge.nativeCpuFeatures()) }.getOrDefault(CpuSimdFlags())
        else CpuSimdFlags()

    /** Variante (backend de CPU) efetivamente carregada (null = nenhuma). */
    @Volatile var loadedVariant: String? = null
        private set
    @Volatile var loadLog: String = ""
        private set
    @Volatile var libraryLoaded: Boolean = false
        private set

    init {
        val log = StringBuilder()
        libraryLoaded = try {
            System.loadLibrary("girassol_llm"); true
        } catch (t: Throwable) {
            log.append("libgirassol_llm: ${t.javaClass.simpleName}; "); Log.w(TAG, "motor LLM indisponível", t); false
        }
        if (libraryLoaded) {
            val compiled = runCatching { nativeLlmAvailable() }.getOrDefault(false)
            if (!compiled) {
                log.append("sem llama.cpp")
            } else {
                for (v in LlmVariantSelector.candidates(cpuFlags)) {
                    val lib = LlmVariantSelector.backendLibrary(v)
                    val err = runCatching { nativeLlmLoadCpuBackend(lib) }.getOrElse { it.javaClass.simpleName }
                    if (err.isEmpty()) { loadedVariant = v; log.append("$v ($lib): ok"); break }
                    log.append("$v: $err; ")
                }
            }
        }
        loadLog = log.toString()
        Log.i(TAG, "SIMD ${cpuFlags.label()} → backend ${loadedVariant ?: "nenhum"} ($loadLog)")
    }

    val isLoaded: Boolean get() = libraryLoaded && loadedVariant != null

    /** "variante|biblioteca" do backend de CPU carregado. */
    val buildVariant: String by lazy {
        if (isLoaded) "${loadedVariant}|${runCatching { nativeLlmCpuBackend() }.getOrDefault("?")}" else "-"
    }

    /** Recursos que o ggml-cpu realmente usa (llama_print_system_info). */
    val systemInfo: String by lazy { if (isLoaded) runCatching { nativeLlmSystemInfo() }.getOrDefault("") else "" }

    @JvmStatic external fun nativeLlmAvailable(): Boolean
    /** "" em caso de sucesso ou a mensagem de erro. */
    @JvmStatic external fun nativeLlmLoadCpuBackend(library: String): String
    @JvmStatic external fun nativeLlmCpuBackend(): String
    /**
     * Carrega o GGUF. kvType: 0=f16, 1=q8_0, 2=q4_0. slotDir = diretório dos slots por
     * cidadão (llama_state_seq_save_file). Retorna "" em caso de sucesso.
     */
    @JvmStatic external fun nativeLlmLoad(path: String, threads: Int, nCtx: Int, flashAttn: Boolean, kvType: Int, slotDir: String, maxSlots: Int, poolCapacity: Int): String
    /** v3.7: pool de llama_model residentes ("cap|residentes|trocas|frias|despejos|ativo|p:b;…"). */
    @JvmStatic external fun nativeLlmPoolStatus(): String
    @JvmStatic external fun nativeLlmSetPoolCapacity(capacity: Int)
    /** v3.8: libera já os modelos estacionados do pool (pressão de memória); devolve quantos. */
    @JvmStatic external fun nativeLlmEvictParked(): Int
    @JvmStatic external fun nativeLlmUnload()
    @JvmStatic external fun nativeLlmAbort()
    @JvmStatic external fun nativeLlmClearSlots()
    /** "descrição|bytes|config" do modelo carregado, ou "". */
    @JvmStatic external fun nativeLlmDescribe(): String
    @JvmStatic external fun nativeSemanticTemperature(recentSpeeches: String, tBase: Float): Float
    /**
     * [texto, erro, promptTokens, genTokens, millis, tok/s total, tok/s geração,
     *  tokens reaproveitados, tokens fast-forward, slot restaurado, backend da gramática].
     */
    @JvmStatic external fun nativeLlmGenerate(
        system: String, worldPrefix: String, user: String, grammar: String, jsonKeysCsv: String,
        slotKey: String, temperature: Float, seed: Int, maxTokens: Int, larkGrammar: String
    ): Array<String>
    /** [pp tokens/s, tg tokens/s, nPrompt, nGen]; vazio se não houver modelo. */
    @JvmStatic external fun nativeLlmBench(nPrompt: Int, nGen: Int): DoubleArray
    @JvmStatic external fun nativeLlmSystemInfo(): String

    /**
     * v3.5 — treina um ciclo LoRA (rank r sobre blk.{L-1}.ffn_down) nos textos episódicos.
     * Retorna o JSON de status (active, cycles, lastAccepted, NLLs, erro).
     */
    @JvmStatic external fun nativeLlmTrainLora(
        texts: Array<String>, adapterPath: String, rank: Int, alpha: Float, epochs: Int,
        lr: Float, topK: Int, maxTokens: Int, threads: Int
    ): String
    /** "" = sucesso; aplica via llama_adapter_lora_init + llama_set_adapters_lora. */
    @JvmStatic external fun nativeLlmApplyLora(path: String, scale: Float): String
    @JvmStatic external fun nativeLlmClearLora()
    @JvmStatic external fun nativeLlmLoraStatus(): String
    /** "ftype|head_dim" do modelo carregado (ex.: "F16|64"). */
    @JvmStatic external fun nativeLlmModelInfo(): String
    /** true se o build incluiu o crate llguidance (Rust); senão a GBNF estrita é usada. */
    @JvmStatic external fun nativeLlmHasLlguidance(): Boolean
}
