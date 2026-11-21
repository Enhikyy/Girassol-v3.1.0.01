package com.enhikyy.girassol.llm

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.enhikyy.girassol.core.cognition.NeuralProtocol
import com.enhikyy.girassol.core.ports.NeuralDecision
import com.enhikyy.girassol.core.ports.NeuralDeliberationPort
import com.enhikyy.girassol.core.ports.NeuralRequest
import com.enhikyy.girassol.nativebridge.LlmNativeBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

enum class LlmStatus(val label: String) {
    UNAVAILABLE("motor não compilado"),
    NO_MODEL("sem modelo .gguf"),
    DISABLED("desativado"),
    LOADING("carregando"),
    READY("pronto"),
    BUSY("gerando"),
    PAUSED_THERMAL("pausado (térmico)"),
    PAUSED_MEMORY("descarregado (memória)"),
    ERROR("erro")
}

data class LlmStats(
    val status: LlmStatus = LlmStatus.NO_MODEL,
    val modelName: String = "",
    val modelBytes: Long = 0,
    val description: String = "",
    val tokensPerSecond: Double = 0.0,
    val lastLatencyMs: Double = 0.0,
    val decisions: Int = 0,
    val rejected: Int = 0,
    val lastOutput: String = "",
    val lastError: String = "",
    val nativeHeapMb: Double = 0.0,
    val budgetMb: Long = 0,
    val genTokensPerSecond: Double = 0.0,
    val benchPromptTps: Double = 0.0,
    val benchGenTps: Double = 0.0,
    val memoryUnloads: Int = 0,
    val kvCache: String = "",
    val lazyMode: Boolean = false,
    val reusedTokens: Long = 0,
    val forcedTokens: Long = 0,
    val slotRestores: Int = 0,
    val lastTemperature: Float = 0f,
    val quantAdvice: String = "",
    val idleUnloads: Int = 0,
    /** v3.5: tipo real dos pesos (llama_model_ftype) e head_dim do modelo carregado. */
    val weightType: String = "",
    val headDim: Int = 0,
    /** v3.5: JSON de status do adaptador LoRA on-device (ciclos, NLL held-out, aceito). */
    val loraStatus: String = "",
    val loraCycles: Int = 0,
    val loraActive: Boolean = false,
    /** "llguidance" ou "gbnf" (fallback estrito). */
    val grammarBackend: String = ""
)

/**
 * Política de quantização para aparelhos ARM64 (Regra de Ouro KleidiAI): pesos Q4_0/Q8_0
 * usam os micro-kernels KleidiAI/i8mm do ggml-cpu; KV-cache Q4_0 em ≤ 4 GB e Q8_0 acima.
 * O "lazy mode" é aplicado no app: em ≤ 4 GB o modelo só carrega na primeira requisição
 * e é descarregado após [LAZY_IDLE_MS] ociosos (llama.cpp não possui flag --lazy-mode).
 */
object LlmQuantPolicy {
    const val KV_F16 = 0
    const val KV_Q8_0 = 1
    const val KV_Q4_0 = 2
    const val LOW_RAM_BYTES = 4L * 1024 * 1024 * 1024 + 512L * 1024 * 1024
    const val LAZY_IDLE_MS = 90_000L

    fun kvTypeFor(totalMemBytes: Long): Int = if (totalMemBytes <= LOW_RAM_BYTES) KV_Q4_0 else KV_Q8_0
    fun lazyFor(totalMemBytes: Long): Boolean = totalMemBytes <= LOW_RAM_BYTES
    fun kvName(t: Int): String = when (t) { KV_Q8_0 -> "q8_0"; KV_Q4_0 -> "q4_0"; else -> "f16" }

    /**
     * Classifica o tipo de pesos. [ftype] vem do próprio GGUF (llama_model_ftype) e tem
     * prioridade; o nome do arquivo é só um palpite secundário — qualquer .gguf válido é aceito.
     */
    fun weightAdvice(description: String, ftype: String = "", fp16Cpu: Boolean = false): String {
        val d = (ftype.ifBlank { description }).uppercase()
        return when {
            Regex("Q4_0|Q8_0").containsMatchIn(d) -> "pesos ${ftype.ifBlank { "Q4_0/Q8_0" }}: compatível KleidiAI"
            Regex("Q4_K|Q5_K|Q6_K|Q3_K|Q2_K|IQ").containsMatchIn(d) -> "pesos K-quant: funciona; Q4_0/Q8_0 é mais rápido com KleidiAI"
            Regex("BF16").containsMatchIn(d) -> "pesos BF16: aceito (convertido em tempo de execução); F16 é mais rápido em ARM"
            Regex("F16|FP16").containsMatchIn(d) ->
                if (fp16Cpu) "pesos F16 (FP16): aceleração nativa ARMv8.2 fp16 ativa" else "pesos F16 (FP16): CPU sem fp16 nativo, usa conversão"
            Regex("F32").containsMatchIn(d) -> "pesos F32: aceito; prefira F16 ou Q8_0 para economizar RAM"
            else -> "tipo de pesos não identificado"
        }
    }

    /**
     * Orçamento de RAM do modelo escalado pela RAM física: 4 GB → 1,6 GB (modelo rápido
     * Q4_0 ou F16 de ~350M); ≥ 6 GB → até 2,6 GB (modelo profundo de ~2,2 GB);
     * ≥ 8 GB → até 3,2 GB.
     */
    fun budgetFor(totalMemBytes: Long): Long {
        val gb = 1024L * 1024 * 1024
        return when {
            totalMemBytes >= 7L * gb + gb / 2 -> minOf(3_200L * 1024 * 1024, (totalMemBytes * 0.45).toLong())
            totalMemBytes >= 5L * gb + gb / 2 -> minOf(2_600L * 1024 * 1024, (totalMemBytes * 0.45).toLong())
            else -> minOf(1_600L * 1024 * 1024, (totalMemBytes * 0.40).toLong())
        }
    }
}

/**
 * Gerencia o modelo GGUF local (importado pelo Storage Access Framework) e implementa a
 * porta neural do WorldCore. A geração ocorre numa thread de baixa prioridade; os
 * resultados validados (gramática GBNF + parser estrito) são drenados pelo estágio 1 do
 * próximo tick e viram eventos canônicos. Sob aquecimento (tier ≥ 2), pressão de memória
 * ou ausência de modelo, o mundo segue no SymbolicLookaheadDeliberator (modo econômico).
 */
object LocalLlmManager : NeuralDeliberationPort {
    private const val TAG = "GirassolLLM"
    private const val PREFS = "girassol_llm"
    private const val N_CTX = 1024
    private const val MAX_TOKENS = 96
    private const val MAX_INFLIGHT = 2
    private const val MIN_FREE_AFTER_LOAD = 600L * 1024 * 1024
    private const val MAX_SLOTS = 24
    private const val T_BASE = 0.7f
    private const val LORA_RANK = 8
    private const val LORA_ALPHA = 16f
    private const val LORA_EPOCHS = 4
    private const val LORA_LR = 2e-3f
    private const val LORA_TOP_K = 8
    private const val LORA_MAX_TOKENS = 768

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "girassol-llm").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val results = ConcurrentLinkedQueue<NeuralDecision>()
    private val inflight = AtomicInteger(0)
    private val pendingAgents = java.util.Collections.synchronizedSet(HashSet<String>())

    private val _stats = MutableStateFlow(LlmStats())
    val stats: StateFlow<LlmStats> = _stats.asStateFlow()

    @Volatile private var enabled = true
    @Volatile private var thermalPaused = false
    @Volatile private var ready = false
    @Volatile private var lazyMode = false
    @Volatile private var lastUse = 0L
    private var kvType = LlmQuantPolicy.KV_Q8_0
    private var appContext: Context? = null
    private val idleChecker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "girassol-llm-idle").apply { isDaemon = true }
    }

    val engineCompiled: Boolean
        get() = LlmNativeBridge.isLoaded && runCatching { LlmNativeBridge.nativeLlmAvailable() }.getOrDefault(false)

    fun init(context: Context) {
        appContext = context.applicationContext
        val budget = memoryBudget(context)
        val total = context.getSystemService(ActivityManager::class.java).let { am ->
            ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
        }
        kvType = LlmQuantPolicy.kvTypeFor(total)
        lazyMode = LlmQuantPolicy.lazyFor(total)
        _stats.update { it.copy(budgetMb = budget / (1024 * 1024), kvCache = LlmQuantPolicy.kvName(kvType), lazyMode = lazyMode) }
        if (!engineCompiled) { _stats.update { it.copy(status = LlmStatus.UNAVAILABLE) }; return }
        val path = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("model", null)
        val f = path?.let { File(it) }
        when {
            f == null || !f.exists() -> _stats.update { it.copy(status = LlmStatus.NO_MODEL) }
            lazyMode -> _stats.update { it.copy(status = LlmStatus.PAUSED_MEMORY, modelName = f.name, lastError = "modo lazy: carrega na 1ª requisição") }
            else -> loadAsync(f)
        }
        idleChecker.scheduleWithFixedDelay({ unloadIfIdle() }, 30, 30, java.util.concurrent.TimeUnit.SECONDS)
    }

    /** Modo lazy (≤ 4 GB): libera modelo + KV após [LlmQuantPolicy.LAZY_IDLE_MS] sem uso. */
    private fun unloadIfIdle() {
        if (!lazyMode || !ready || inflight.get() > 0) return
        if (System.currentTimeMillis() - lastUse < LlmQuantPolicy.LAZY_IDLE_MS) return
        worker.execute {
            if (!ready || inflight.get() > 0) return@execute
            ready = false
            runCatching { LlmNativeBridge.nativeLlmUnload() }
            _stats.update { it.copy(status = LlmStatus.PAUSED_MEMORY, lastError = "modo lazy: descarregado por ociosidade", idleUnloads = it.idleUnloads + 1) }
        }
    }

    /** Orçamento escalado pela RAM (ver [LlmQuantPolicy.budgetFor]). */
    fun memoryBudget(context: Context): Long {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return LlmQuantPolicy.budgetFor(mi.totalMem)
    }

    /** Arquivo do adaptador LoRA on-device associado a um modelo (um por modelo). */
    fun adapterFileFor(context: Context, model: File): File =
        File(File(context.filesDir, "lora").apply { mkdirs() }, model.name.removeSuffix(".gguf") + ".lora.gguf")

    /** Modelo atualmente escolhido (caminho absoluto) ou null. */
    fun currentModelPath(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("model", null)

    /** Seleciona um GGUF já presente no aparelho (modelo rápido embutido, profundo do asset pack ou importado). */
    fun selectModel(context: Context, file: File): String {
        if (!file.exists()) return "Modelo não encontrado: ${file.name}"
        val budget = memoryBudget(context)
        if (file.length() > budget) return "Modelo de ${file.length() / 1048576} MB excede o orçamento de ${budget / 1048576} MB deste aparelho"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("model", file.absolutePath).apply()
        loadAsync(file)
        return "Selecionado: ${file.name}"
    }

    /**
     * Sharp-Wave Ripple Replay → LoRA real: treina um ciclo sobre os textos das memórias
     * episódicas de maior utilidade. O adaptador só é aplicado (llama_set_adapters_lora) se o
     * NLL real em held-out cair; roda na thread do LLM, entre gerações.
     */
    fun trainLoraAsync(texts: List<String>, onDone: (String) -> Unit = {}) {
        val ctx = appContext ?: return
        if (!ready || thermalPaused || memoryBreakerOpen || texts.size < 2) return
        val modelPath = currentModelPath(ctx) ?: return
        val adapter = adapterFileFor(ctx, File(modelPath))
        worker.execute {
            if (!ready) return@execute
            val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
            val json = runCatching {
                LlmNativeBridge.nativeLlmTrainLora(texts.toTypedArray(), adapter.absolutePath, LORA_RANK, LORA_ALPHA,
                    LORA_EPOCHS, LORA_LR, LORA_TOP_K, LORA_MAX_TOKENS, threads)
            }.getOrElse { "{\"error\":\"${it.javaClass.simpleName}\"}" }
            publishLora(json)
            onDone(json)
        }
    }

    private fun publishLora(json: String) {
        val cycles = Regex("\"cycles\":(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val active = json.contains("\"active\":true")
        _stats.update { it.copy(loraStatus = json, loraCycles = cycles, loraActive = active) }
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        refreshStatus()
    }

    /** Tier térmico (0..3): ≥ 2 suspende o LLM e aborta a geração em curso. */
    fun setThermalTier(tier: Int) {
        val paused = tier >= 2
        if (paused && !thermalPaused && ready) runCatching { LlmNativeBridge.nativeLlmAbort() }
        thermalPaused = paused
        refreshStatus()
    }

    /** Disjuntor de memória aberto: nenhum recarregamento até o MemoryGuard rearmar. */
    @Volatile var memoryBreakerOpen = false
        private set

    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) unloadForMemory("onTrimMemory($level)")
    }

    /**
     * Chamado pelo MemoryGuard (VmRSS acima do limite ou TRIM_MEMORY_RUNNING_CRITICAL).
     * Aborta a geração em curso e libera modelo + KV-cache na thread do LLM; o loop
     * determinístico do mundo não é tocado (cai no deliberador simbólico).
     */
    fun unloadForMemory(reason: String) {
        memoryBreakerOpen = true
        if (!ready && _stats.value.status != LlmStatus.LOADING) {
            if (_stats.value.status == LlmStatus.READY || _stats.value.status == LlmStatus.BUSY) _stats.update { it.copy(status = LlmStatus.PAUSED_MEMORY) }
            return
        }
        Log.w(TAG, "disjuntor de memória: $reason — descarregando modelo")
        ready = false
        runCatching { LlmNativeBridge.nativeLlmAbort() }
        worker.execute {
            runCatching { LlmNativeBridge.nativeLlmClearSlots() }
            runCatching { LlmNativeBridge.nativeLlmUnload() }
            ready = false
            _stats.update { it.copy(status = LlmStatus.PAUSED_MEMORY, lastError = "disjuntor: $reason", memoryUnloads = it.memoryUnloads + 1) }
        }
    }

    /** Rearma o disjuntor (memória voltou abaixo da histerese) e tenta recarregar. */
    fun rearmMemoryBreaker() {
        if (!memoryBreakerOpen) return
        memoryBreakerOpen = false
        maybeReload()
    }

    /** Benchmark pp/tg no modelo carregado (bloqueia até 60 s; chamar fora da main thread). */
    fun benchmark(nPrompt: Int = 64, nGen: Int = 32): String {
        if (!engineCompiled) return "Motor llama.cpp indisponível"
        if (!ready) return "Carregue um modelo .gguf para medir tokens/s"
        val f = java.util.concurrent.FutureTask {
            runCatching { LlmNativeBridge.nativeLlmBench(nPrompt, nGen) }.getOrNull()
        }
        worker.execute(f)
        val r = runCatching { f.get(60, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()
        if (r == null || r.size < 4) return "Benchmark falhou"
        _stats.update { it.copy(benchPromptTps = r[0], benchGenTps = r[1]) }
        return "pp${r[2].toInt()} %.1f tok/s · tg${r[3].toInt()} %.1f tok/s · ${LlmNativeBridge.loadedVariant}".format(r[0], r[1])
    }

    /** Recarrega um modelo descarregado por memória, se houver folga. */
    fun maybeReload() {
        val ctx = appContext ?: return
        if (memoryBreakerOpen || lazyMode) return // modo lazy recarrega sob demanda (lazyWake)
        if (ready || _stats.value.status != LlmStatus.PAUSED_MEMORY) return
        val path = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("model", null) ?: return
        loadAsync(File(path))
    }

    private fun refreshStatus() {
        _stats.update {
            val s = when {
                !engineCompiled -> LlmStatus.UNAVAILABLE
                !ready && it.status == LlmStatus.LOADING -> LlmStatus.LOADING
                !ready && it.status == LlmStatus.PAUSED_MEMORY -> LlmStatus.PAUSED_MEMORY
                !ready && it.status == LlmStatus.ERROR -> LlmStatus.ERROR
                !ready -> LlmStatus.NO_MODEL
                !enabled -> LlmStatus.DISABLED
                thermalPaused -> LlmStatus.PAUSED_THERMAL
                inflight.get() > 0 -> LlmStatus.BUSY
                else -> LlmStatus.READY
            }
            it.copy(status = s)
        }
    }

    /** Copia o .gguf escolhido no SAF para o armazenamento interno e o carrega. */
    fun importModel(context: Context, uri: Uri): String {
        if (!engineCompiled) return "Motor llama.cpp indisponível neste build"
        val cr = context.contentResolver
        var name = "modelo.gguf"
        var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                size = if (c.isNull(1)) -1 else c.getLong(1)
            }
        }
        val budget = memoryBudget(context)
        if (size > budget) return "Modelo de ${size / 1048576} MB excede o orçamento de ${budget / 1048576} MB para este aparelho"
        val dir = File(context.filesDir, "models/importados").apply { mkdirs() }
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").let { if (it.endsWith(".gguf", true)) it else "$it.gguf" }
        val target = File(dir, safeName)
        val tmp = File(dir, "$safeName.part")
        val magic = ByteArray(4)
        cr.openInputStream(uri)?.use { ins ->
            val n = ins.read(magic)
            if (n != 4 || String(magic, Charsets.US_ASCII) != "GGUF") return "Arquivo não é GGUF (cabeçalho inválido)"
            tmp.outputStream().use { out -> out.write(magic); ins.copyTo(out, 1 shl 16) }
        } ?: return "Não foi possível abrir o arquivo"
        if (tmp.length() > budget) { tmp.delete(); return "Modelo excede o orçamento de RAM" }
        dir.listFiles()?.filter { it.name != tmp.name }?.forEach { it.delete() } // um único importado; embutidos ficam
        adapterFileFor(context, target).delete() // adaptador LoRA antigo não vale para o novo modelo
        tmp.renameTo(target)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("model", target.absolutePath).apply()
        loadAsync(target)
        return "Importado: ${target.name} (${target.length() / 1048576} MB)"
    }

    fun removeModel(context: Context) {
        worker.execute {
            runCatching { LlmNativeBridge.nativeLlmUnload() }
            ready = false
            File(context.filesDir, "models/importados").listFiles()?.forEach { it.delete() }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("model").apply()
            _stats.update { LlmStats(status = LlmStatus.NO_MODEL, budgetMb = it.budgetMb) }
        }
    }

    private fun loadAsync(file: File) {
        val ctx = appContext ?: return
        _stats.update { it.copy(status = LlmStatus.LOADING, modelName = file.name, lastError = "") }
        worker.execute {
            ready = false
            if (memoryBreakerOpen) {
                _stats.update { it.copy(status = LlmStatus.PAUSED_MEMORY, lastError = "disjuntor de memória aberto") }
                return@execute
            }
            val am = ctx.getSystemService(ActivityManager::class.java)
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            if (mi.availMem - file.length() < MIN_FREE_AFTER_LOAD || mi.lowMemory) {
                _stats.update { it.copy(status = LlmStatus.PAUSED_MEMORY, lastError = "RAM livre insuficiente (${mi.availMem / 1048576} MB)") }
                return@execute
            }
            val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
            val slotDir = File(ctx.filesDir, "llm_slots").apply { mkdirs() }
            val err = runCatching {
                LlmNativeBridge.nativeLlmLoad(file.absolutePath, threads, N_CTX, true, kvType, slotDir.absolutePath, MAX_SLOTS)
            }.getOrElse { it.message ?: "erro" }
            if (err.isNotEmpty()) {
                _stats.update { it.copy(status = LlmStatus.ERROR, lastError = err) }
                return@execute
            }
            val desc = runCatching { LlmNativeBridge.nativeLlmDescribe() }.getOrDefault("")
            val info = runCatching { LlmNativeBridge.nativeLlmModelInfo() }.getOrDefault("").split('|')
            val ftype = info.getOrElse(0) { "" }
            val headDim = info.getOrNull(1)?.toIntOrNull() ?: 0
            val adapter = adapterFileFor(ctx, file)
            if (adapter.exists()) {
                val e = runCatching { LlmNativeBridge.nativeLlmApplyLora(adapter.absolutePath, 1f) }.getOrElse { it.message ?: "erro" }
                if (e.isNotEmpty()) { Log.w(TAG, "adaptador LoRA descartado: $e"); adapter.delete() }
            }
            publishLora(runCatching { LlmNativeBridge.nativeLlmLoraStatus() }.getOrDefault(""))
            ready = true
            lastUse = System.currentTimeMillis()
            val parts = desc.split('|')
            _stats.update {
                it.copy(modelName = file.name, description = parts.getOrElse(0) { "" },
                    modelBytes = parts.getOrNull(1)?.toLongOrNull() ?: file.length(),
                    kvCache = parts.getOrNull(2)?.takeIf { c -> c.isNotBlank() } ?: LlmQuantPolicy.kvName(kvType),
                    quantAdvice = LlmQuantPolicy.weightAdvice(file.name + " " + parts.getOrElse(0) { "" }, ftype, LlmNativeBridge.cpuFlags.fp16),
                    weightType = ftype, headDim = headDim,
                    nativeHeapMb = android.os.Debug.getNativeHeapAllocatedSize() / 1048576.0)
            }
            refreshStatus()
        }
    }

    // ------------------------------------------------------------ NeuralDeliberationPort

    override fun isAvailable(): Boolean = ready && enabled && !thermalPaused

    /** Modo lazy: dispara o carregamento sob demanda (a requisição atual cai no simbólico). */
    private fun lazyWake() {
        if (!lazyMode || ready || !enabled || thermalPaused || memoryBreakerOpen) return
        if (_stats.value.status != LlmStatus.PAUSED_MEMORY) return
        val ctx = appContext ?: return
        val path = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("model", null) ?: return
        loadAsync(File(path))
    }

    override fun trySubmit(request: NeuralRequest): Boolean {
        if (!ready) lazyWake()
        if (!isAvailable() || inflight.get() >= MAX_INFLIGHT) return false
        if (!pendingAgents.add(request.agentId)) return false
        inflight.incrementAndGet()
        refreshStatus()
        worker.execute {
            try {
                if (!isAvailable()) return@execute
                com.enhikyy.girassol.perf.AdpfHints.registerCurrentThread("girassol-llm")
                val tStart = System.nanoTime()
                lastUse = System.currentTimeMillis()
                val p = request.prompt
                val temperature = runCatching {
                    LlmNativeBridge.nativeSemanticTemperature(p.recentSpeeches.joinToString("\n"), T_BASE)
                }.getOrDefault(T_BASE)
                val r = LlmNativeBridge.nativeLlmGenerate(p.system, p.worldPrefix, p.user, p.grammar,
                    p.jsonKeys.joinToString(","), request.agentId, temperature, (p.seed xor (p.seed ushr 32)).toInt(), MAX_TOKENS, p.larkGrammar)
                lastUse = System.currentTimeMillis()
                com.enhikyy.girassol.perf.AdpfHints.reportWork(System.nanoTime() - tStart)
                val text = r[0]; val err = r[1]
                val genTokens = r[3].toIntOrNull() ?: 0
                val ms = r[4].toDoubleOrNull() ?: 0.0
                val tps = r[5].toDoubleOrNull() ?: 0.0
                val genTps = r.getOrNull(6)?.toDoubleOrNull() ?: 0.0
                val parsed = if (err.isEmpty()) NeuralProtocol.parse(text, request.allowedActions) else null
                if (parsed != null) {
                    results += NeuralDecision(request.agentId, request.tick, parsed.action, parsed.thought, parsed.speech,
                        genTokens, ms.toLong(), "llama.cpp")
                }
                _stats.update {
                    it.copy(tokensPerSecond = if (tps > 0) tps else it.tokensPerSecond, lastLatencyMs = ms,
                        genTokensPerSecond = if (genTps > 0) genTps else it.genTokensPerSecond,
                        decisions = it.decisions + if (parsed != null) 1 else 0,
                        rejected = it.rejected + if (parsed == null) 1 else 0,
                        lastOutput = text.take(220), lastError = err, lastTemperature = temperature,
                        reusedTokens = it.reusedTokens + (r.getOrNull(7)?.toLongOrNull() ?: 0),
                        forcedTokens = it.forcedTokens + (r.getOrNull(8)?.toLongOrNull() ?: 0),
                        slotRestores = it.slotRestores + if (r.getOrNull(9) == "1") 1 else 0,
                        grammarBackend = r.getOrNull(10) ?: it.grammarBackend,
                        nativeHeapMb = android.os.Debug.getNativeHeapAllocatedSize() / 1048576.0)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "geração falhou", t)
                _stats.update { it.copy(rejected = it.rejected + 1, lastError = t.message ?: "erro") }
            } finally {
                pendingAgents.remove(request.agentId)
                inflight.decrementAndGet()
                refreshStatus()
            }
        }
        return true
    }

    override fun drainResults(): List<NeuralDecision> {
        if (results.isEmpty()) return emptyList()
        val out = ArrayList<NeuralDecision>()
        while (true) out += results.poll() ?: break
        return out
    }
}
