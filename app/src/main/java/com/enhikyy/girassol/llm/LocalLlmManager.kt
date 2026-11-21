package com.enhikyy.girassol.llm

import android.app.ActivityManager
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
import com.enhikyy.girassol.data.ModelRegistryStore
import com.enhikyy.girassol.data.RegisteredModel
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
    val grammarBackend: String = "",
    /** v3.6: modelos importados, modelo residente, pegada ativa estimada e trocas de contexto. */
    val modelCount: Int = 0,
    val activeModel: String = "",
    val activeEstimateMb: Long = 0,
    val modelSwitches: Int = 0
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

    /** Perfis automáticos por RAM física: 4 GB (≤ 5,5), 8 GB (≤ 10) e 12 GB+. */
    data class DeviceProfile(val label: String, val nCtx: Int, val kvType: Int, val description: String)

    fun profileFor(totalMemBytes: Long): DeviceProfile {
        val gb = 1024L * 1024 * 1024
        return when {
            totalMemBytes <= 5L * gb + gb / 2 -> DeviceProfile("4 GB", 1024, KV_Q4_0, "mmap com troca por turno, KV q4_0, filhos compartilham o .gguf base")
            totalMemBytes <= 10L * gb -> DeviceProfile("8 GB", 2048, KV_Q8_0, "n_ctx 2048, KV q8_0, troca de contexto mais rápida (page cache maior)")
            else -> DeviceProfile("12 GB", 4096, KV_Q8_0, "n_ctx 4096, KV q8_0, modelos até 3B+")
        }
    }

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
     * Teto de RAM ATIVA (anônima: KV-cache + buffers de computação + parcela residente dos
     * pesos) por RAM física: 4 GB → 1,45 GB; ≥ 6 GB → 2,6 GB; ≥ 8 GB → 3,2 GB.
     * NÃO é comparado ao tamanho do arquivo: com mmap os pesos são páginas de arquivo limpas.
     */
    fun budgetFor(totalMemBytes: Long): Long {
        val gb = 1024L * 1024 * 1024
        return when {
            totalMemBytes >= 7L * gb + gb / 2 -> minOf(3_200L * 1024 * 1024, (totalMemBytes * 0.45).toLong())
            totalMemBytes >= 5L * gb + gb / 2 -> minOf(2_600L * 1024 * 1024, (totalMemBytes * 0.45).toLong())
            else -> minOf(1_600L * 1024 * 1024, (totalMemBytes * 0.40).toLong())
        }
    }

    /** Maior arquivo .gguf mapeável (espaço de endereço + page cache): 4 GB → ~1,6 GB. */
    fun maxModelFileFor(totalMemBytes: Long): Long = (totalMemBytes * 0.45).toLong()

    /**
     * Pegada real de RAM ativa de um .gguf em mmap: runtime + buffers de computação (~96 MB),
     * ~35 % dos pesos efetivamente residentes/copiados (embeddings, tensores repacotados) e o
     * KV-cache de [nCtx] tokens no tipo [kvType]. Ex.: LFM2.5-230M-F16 (~460 MB) com n_ctx 1024
     * e KV q4_0 → ~305 MB (e não os 1452 MB do orçamento inteiro).
     */
    fun activeRamEstimate(fileBytes: Long, nCtx: Int, kvType: Int): Long {
        val mb = 1024L * 1024
        val kvPerToken = when (kvType) { KV_Q4_0 -> 48L * 1024; KV_Q8_0 -> 90L * 1024; else -> 168L * 1024 }
        return 96L * mb + (fileBytes * 0.35).toLong() + kvPerToken * nCtx
    }

    /** null = aceitável; caso contrário a mensagem de recusa (português). */
    fun admission(fileBytes: Long, totalMemBytes: Long, nCtx: Int, kvType: Int): String? {
        val mb = 1024L * 1024
        val cap = maxModelFileFor(totalMemBytes)
        if (fileBytes > cap) return "Modelo de ${fileBytes / mb} MB excede o máximo mapeável de ${cap / mb} MB neste aparelho"
        val active = activeRamEstimate(fileBytes, nCtx, kvType)
        val budget = budgetFor(totalMemBytes)
        if (active > budget) return "Modelo precisa de ~${active / mb} MB de RAM ativa (limite ${budget / mb} MB)"
        return null
    }
}

/** Um modelo .gguf importado; cada um é a mente exclusiva de 1 cidadão fundador. */
data class ImportedModel(val key: String, val path: String, val bytes: Long, val active: Boolean = true, val sha256Head: String = "") {
    val displayName: String get() = key.removeSuffix(".gguf")
}

/**
 * v3.6 "Adão e Eva" — gerencia VÁRIOS modelos .gguf importados (1 modelo = 1 cidadão
 * fundador) e implementa a porta neural do WorldCore.
 *
 * O motor nativo mantém um contexto llama.cpp por vez; os modelos são mapeados por mmap e
 * escalonados por turno: cada pedido traz o modelo do cidadão ([NeuralRequest.modelKey]); se
 * outro modelo estiver residente, o escalonador drena a fila atual e troca o contexto ativo
 * (recarregar um .gguf já no page cache leva poucos segundos e não duplica RAM). Um modelo que
 * espera mais de [TURN_MS] ganha a vez, então cada fundador pensa com o SEU próprio arquivo.
 * Os slots de KV por agente ficam num diretório por modelo; o adaptador LoRA também.
 * Sob aquecimento (tier ≥ 2), pressão de memória real ou sem modelos, o mundo segue no
 * SymbolicLookaheadDeliberator.
 */
object LocalLlmManager : NeuralDeliberationPort {
    private const val TAG = "GirassolLLM"
    private const val PREFS = "girassol_llm"
    private const val KEY_MODELS = "models_v36"
    /** n_ctx padrão (perfil 4 GB); o efetivo vem de [LlmQuantPolicy.profileFor]. */
    const val N_CTX = 1024
    private const val MAX_TOKENS = 96
    private const val CHAT_MAX_TOKENS = 160
    private const val MAX_INFLIGHT = 2
    /** RAM livre mínima que precisa sobrar DEPOIS da pegada ativa estimada do modelo. */
    private const val MIN_FREE_AFTER_LOAD = 300L * 1024 * 1024
    private const val MAX_SLOTS = 24
    /** Tempo máximo que um modelo espera a vez antes de forçar a troca de contexto. */
    private const val TURN_MS = 12_000L
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
    /** Desde quando cada modelo (não residente) espera a vez. */
    private val waitingSince = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val _stats = MutableStateFlow(LlmStats())
    val stats: StateFlow<LlmStats> = _stats.asStateFlow()
    private val _models = MutableStateFlow<List<ImportedModel>>(emptyList())
    /** Modelos importados (cada um vinculado a um cidadão fundador). */
    val models: StateFlow<List<ImportedModel>> = _models.asStateFlow()

    @Volatile private var enabled = true
    @Volatile private var thermalPaused = false
    @Volatile private var ready = false
    @Volatile private var lazyMode = false
    @Volatile private var lastUse = 0L
    /** Modelo residente no motor nativo (null = nenhum) e o que o escalonador está servindo. */
    @Volatile private var loadedKey: String? = null
    @Volatile private var scheduledKey: String? = null
    @Volatile private var lastSwitchMs = 0L
    private var kvType = LlmQuantPolicy.KV_Q8_0
    /** Perfil do aparelho (4 / 8 / 12 GB): n_ctx e tipo de KV. */
    @Volatile var profile: LlmQuantPolicy.DeviceProfile = LlmQuantPolicy.profileFor(4L * 1024 * 1024 * 1024); private set
    private val nCtx: Int get() = profile.nCtx
    private var totalMem = 4L * 1024 * 1024 * 1024
    private var appContext: Context? = null
    private val idleChecker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "girassol-llm-idle").apply { isDaemon = true }
    }

    val engineCompiled: Boolean
        get() = LlmNativeBridge.isLoaded && runCatching { LlmNativeBridge.nativeLlmAvailable() }.getOrDefault(false)

    fun init(context: Context) {
        appContext = context.applicationContext
        totalMem = context.getSystemService(ActivityManager::class.java).let { am ->
            ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
        }
        profile = LlmQuantPolicy.profileFor(totalMem)
        kvType = profile.kvType
        lazyMode = LlmQuantPolicy.lazyFor(totalMem)
        _models.value = readRegistry(context)
        _stats.update { it.copy(budgetMb = LlmQuantPolicy.budgetFor(totalMem) / (1024 * 1024), kvCache = LlmQuantPolicy.kvName(kvType), lazyMode = lazyMode) }
        if (!engineCompiled) { _stats.update { it.copy(status = LlmStatus.UNAVAILABLE) }; return }
        refreshStatus()
        idleChecker.scheduleWithFixedDelay({ unloadIfIdle() }, 30, 30, java.util.concurrent.TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------ registro de modelos

    /**
     * Registro fixo em SQLite WAL (`registered_models`). Migra a lista antiga das preferências
     * e descarta entradas cujo arquivo sumiu do armazenamento privado.
     */
    private fun readRegistry(context: Context): List<ImportedModel> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val legacy = prefs.getStringSet(KEY_MODELS, emptySet()) ?: emptySet()
        val stored = ModelRegistryStore.list(context)
        for (path in legacy) {
            val f = File(path)
            if (f.exists() && f.length() > 0 && stored.none { it.key == f.name })
                ModelRegistryStore.upsert(context, RegisteredModel(f.name, f.absolutePath, f.length(), headSha(f), null, null, null, true))
        }
        if (legacy.isNotEmpty()) prefs.edit().remove(KEY_MODELS).remove("model").apply()
        return ModelRegistryStore.list(context).mapNotNull { r ->
            val f = File(r.path)
            if (f.exists() && f.length() > 0) ImportedModel(r.key, f.absolutePath, f.length(), r.active, r.sha256Head)
            else { ModelRegistryStore.remove(context, r.key); null }
        }.sortedBy { it.key }
    }

    /** Linhas do registro (com o fundador vinculado) para restaurar cidadãos ao reabrir o app. */
    fun registeredModels(context: Context): List<RegisteredModel> = ModelRegistryStore.list(context)

    fun bindFounder(context: Context, key: String, founderId: String, name: String, sex: String) =
        ModelRegistryStore.bindFounder(context, key, founderId, name, sex)

    /** Pausa/ativa um modelo: pausado, o fundador segue vivo no deliberador simbólico. */
    fun setModelActive(context: Context, key: String, active: Boolean) {
        ModelRegistryStore.setActive(context, key, active)
        _models.update { list -> list.map { if (it.key == key) it.copy(active = active) else it } }
        if (!active && loadedKey == key) worker.execute {
            if (loadedKey == key && inflight.get() == 0) { runCatching { LlmNativeBridge.nativeLlmUnload() }; ready = false; loadedKey = null }
            refreshStatus()
        }
        if (!active && scheduledKey == key) scheduledKey = null
        refreshStatus()
    }

    private fun headSha(f: File): String = runCatching {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins -> val buf = ByteArray(1 shl 20); val n = ins.read(buf); if (n > 0) md.update(buf, 0, n) }
        md.digest().joinToString("") { "%02x".format(it) }.take(32)
    }.getOrDefault("")

    fun modelFor(key: String?): ImportedModel? = key?.let { k -> _models.value.firstOrNull { it.key == k } }

    /** Arquivo do adaptador LoRA on-device associado a um modelo (um por modelo). */
    fun adapterFileFor(context: Context, model: File): File =
        File(File(context.filesDir, "lora").apply { mkdirs() }, model.name.removeSuffix(".gguf") + ".lora.gguf")

    private fun slotDirFor(context: Context, key: String): File =
        File(File(context.filesDir, "llm_slots"), key.removeSuffix(".gguf")).apply { mkdirs() }

    /** Orçamento de RAM ativa (ver [LlmQuantPolicy.budgetFor]). */
    fun memoryBudget(context: Context): Long {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return LlmQuantPolicy.budgetFor(mi.totalMem)
    }

    /** Pegada ativa estimada de um modelo neste aparelho (MB). */
    fun activeEstimateMb(model: ImportedModel): Long = LlmQuantPolicy.activeRamEstimate(model.bytes, nCtx, kvType) / (1024 * 1024)

    /** Resultado de importação: chave do modelo (null = falhou) e mensagem. */
    data class ImportResult(val key: String?, val message: String)

    /**
     * Copia o .gguf escolhido no SAF para filesDir/models/importados (sem apagar os outros:
     * cada arquivo é a mente de um fundador). O cidadão é criado pelo SimulationHub.
     */
    fun importModel(context: Context, uri: Uri): ImportResult {
        if (!engineCompiled) return ImportResult(null, "Motor llama.cpp indisponível neste build")
        val cr = context.contentResolver
        var name = "modelo.gguf"
        var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                size = if (c.isNull(1)) -1 else c.getLong(1)
            }
        }
        if (size > 0) LlmQuantPolicy.admission(size, totalMem, nCtx, kvType)?.let { return ImportResult(null, it) }
        val dir = File(context.filesDir, "models/importados").apply { mkdirs() }
        val base = name.replace(Regex("[^A-Za-z0-9._-]"), "_").let { if (it.endsWith(".gguf", true)) it else "$it.gguf" }
        var safeName = base
        var n = 2
        while (File(dir, safeName).exists()) safeName = base.removeSuffix(".gguf") + "-$n.gguf".also { n++ }
        val target = File(dir, safeName)
        val tmp = File(dir, "$safeName.part")
        val magic = ByteArray(4)
        cr.openInputStream(uri)?.use { ins ->
            val r = ins.read(magic)
            if (r != 4 || String(magic, Charsets.US_ASCII) != "GGUF") return ImportResult(null, "Arquivo não é GGUF (cabeçalho inválido)")
            tmp.outputStream().use { out -> out.write(magic); ins.copyTo(out, 1 shl 16) }
        } ?: return ImportResult(null, "Não foi possível abrir o arquivo")
        LlmQuantPolicy.admission(tmp.length(), totalMem, nCtx, kvType)?.let { tmp.delete(); return ImportResult(null, it) }
        adapterFileFor(context, target).delete()
        tmp.renameTo(target)
        val m = ImportedModel(target.name, target.absolutePath, target.length(), true, headSha(target))
        ModelRegistryStore.upsert(context, RegisteredModel(m.key, m.path, m.bytes, m.sha256Head, null, null, null, true))
        _models.value = (_models.value.filter { it.key != m.key } + m).sortedBy { it.key }
        refreshStatus()
        return ImportResult(m.key, "Importado: ${m.key} (${m.bytes / 1048576} MB, ~${activeEstimateMb(m)} MB de RAM ativa)")
    }

    /** Remove um modelo importado (o cidadão continua vivo, pensando no modo simbólico). */
    fun removeModel(context: Context, key: String) {
        worker.execute {
            if (loadedKey == key) {
                runCatching { LlmNativeBridge.nativeLlmUnload() }
                ready = false; loadedKey = null
            }
            modelFor(key)?.let { m ->
                File(m.path).delete()
                adapterFileFor(context, File(m.path)).delete()
                slotDirFor(context, key).deleteRecursively()
            }
            ModelRegistryStore.remove(context, key)
            _models.value = _models.value.filter { it.key != key }
            if (scheduledKey == key) scheduledKey = null
            waitingSince.remove(key)
            refreshStatus()
        }
    }

    /** Modo lazy (≤ 4 GB): libera modelo + KV após [LlmQuantPolicy.LAZY_IDLE_MS] sem uso. */
    private fun unloadIfIdle() {
        if (!lazyMode || !ready || inflight.get() > 0) return
        if (System.currentTimeMillis() - lastUse < LlmQuantPolicy.LAZY_IDLE_MS) return
        worker.execute {
            if (!ready || inflight.get() > 0) return@execute
            ready = false; loadedKey = null
            runCatching { LlmNativeBridge.nativeLlmUnload() }
            _stats.update { it.copy(lastError = "ocioso: contexto liberado (recarrega sob demanda)", idleUnloads = it.idleUnloads + 1) }
            refreshStatus()
        }
    }

    /**
     * Sharp-Wave Ripple Replay → LoRA real: treina um ciclo sobre os textos das memórias
     * episódicas de maior utilidade no modelo RESIDENTE. O adaptador só é aplicado se o NLL
     * real em held-out cair; roda na thread do LLM, entre gerações.
     */
    fun trainLoraAsync(texts: List<String>, onDone: (String) -> Unit = {}) {
        val ctx = appContext ?: return
        if (!ready || thermalPaused || memoryBreakerOpen || texts.size < 2) return
        val m = modelFor(loadedKey) ?: return
        val adapter = adapterFileFor(ctx, File(m.path))
        worker.execute {
            if (!ready || loadedKey != m.key) return@execute
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

    /**
     * Chamado pelo MemoryGuard (RAM anônima acima do limite, lowMemory real ou
     * TRIM_MEMORY_RUNNING_CRITICAL). Aborta a geração e libera o contexto; o mundo segue.
     */
    fun unloadForMemory(reason: String) {
        memoryBreakerOpen = true
        Log.w(TAG, "disjuntor de memória: $reason — descarregando modelo")
        runCatching { LlmNativeBridge.nativeLlmAbort() }
        worker.execute {
            if (ready) {
                runCatching { LlmNativeBridge.nativeLlmClearSlots() }
                runCatching { LlmNativeBridge.nativeLlmUnload() }
            }
            ready = false; loadedKey = null
            _stats.update { it.copy(status = LlmStatus.PAUSED_MEMORY, lastError = "disjuntor: $reason", memoryUnloads = it.memoryUnloads + 1) }
        }
    }

    /** Rearma o disjuntor (memória voltou abaixo da histerese); o próximo pedido recarrega. */
    fun rearmMemoryBreaker() {
        if (!memoryBreakerOpen) return
        memoryBreakerOpen = false
        refreshStatus()
    }

    /** Compatibilidade: com escalonamento sob demanda não há recarga ansiosa. */
    fun maybeReload() = refreshStatus()

    /** Benchmark pp/tg no modelo residente (ou no primeiro importado). Bloqueia até 90 s. */
    fun benchmark(nPrompt: Int = 64, nGen: Int = 32): String {
        if (!engineCompiled) return "Motor llama.cpp indisponível"
        val target = modelFor(loadedKey) ?: _models.value.firstOrNull() ?: return "Importe um modelo .gguf para medir tokens/s"
        val f = java.util.concurrent.FutureTask {
            if (!ensureLoaded(target)) null else runCatching { LlmNativeBridge.nativeLlmBench(nPrompt, nGen) }.getOrNull()
        }
        worker.execute(f)
        val r = runCatching { f.get(90, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()
        if (r == null || r.size < 4) return "Benchmark falhou (${_stats.value.lastError.ifEmpty { "sem resposta" }})"
        _stats.update { it.copy(benchPromptTps = r[0], benchGenTps = r[1]) }
        return "${target.displayName}: pp${r[2].toInt()} %.1f tok/s · tg${r[3].toInt()} %.1f tok/s · ${LlmNativeBridge.loadedVariant}".format(r[0], r[1])
    }

    private fun refreshStatus() {
        _stats.update {
            val s = when {
                !engineCompiled -> LlmStatus.UNAVAILABLE
                _models.value.isEmpty() -> LlmStatus.NO_MODEL
                !enabled -> LlmStatus.DISABLED
                memoryBreakerOpen -> LlmStatus.PAUSED_MEMORY
                thermalPaused -> LlmStatus.PAUSED_THERMAL
                it.status == LlmStatus.LOADING -> LlmStatus.LOADING
                !ready && it.status == LlmStatus.ERROR -> LlmStatus.ERROR
                inflight.get() > 0 -> LlmStatus.BUSY
                else -> LlmStatus.READY
            }
            it.copy(status = s, modelCount = _models.value.size, activeModel = loadedKey ?: "")
        }
    }

    /**
     * Garante que [m] é o contexto residente (roda NA thread do LLM). Troca de modelo =
     * descarrega o atual e mapeia o novo; a admissão usa a pegada ATIVA estimada contra
     * ActivityManager.MemoryInfo.availMem + lowMemory reais, nunca o tamanho do arquivo.
     */
    private fun ensureLoaded(m: ImportedModel): Boolean {
        if (ready && loadedKey == m.key) return true
        val ctx = appContext ?: return false
        if (memoryBreakerOpen) {
            _stats.update { it.copy(status = LlmStatus.PAUSED_MEMORY, lastError = "disjuntor de memória aberto") }
            return false
        }
        val file = File(m.path)
        if (!file.exists()) { _stats.update { it.copy(status = LlmStatus.ERROR, lastError = "arquivo ausente: ${m.key}") }; return false }
        if (ready) {
            runCatching { LlmNativeBridge.nativeLlmUnload() }
            ready = false; loadedKey = null
        }
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val active = LlmQuantPolicy.activeRamEstimate(file.length(), nCtx, kvType)
        if (mi.lowMemory || mi.availMem - active < MIN_FREE_AFTER_LOAD) {
            _stats.update { it.copy(status = LlmStatus.PAUSED_MEMORY,
                lastError = "RAM livre insuficiente agora (${mi.availMem / 1048576} MB livres, modelo precisa de ~${active / 1048576} MB)") }
            return false
        }
        _stats.update { it.copy(status = LlmStatus.LOADING, modelName = m.key, lastError = "") }
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        fun load(kv: Int, flash: Boolean) = runCatching {
            LlmNativeBridge.nativeLlmLoad(file.absolutePath, threads, nCtx, flash, kv, slotDirFor(ctx, m.key).absolutePath, MAX_SLOTS)
        }.getOrElse { it.message ?: "erro" }
        var err = load(kvType, true)
        // fallback seguro p/ arquiteturas híbridas (ex.: LFM2.5 conv+atenção): KV f16 e, por fim, sem flash-attn
        if (err.isNotEmpty() && kvType != LlmQuantPolicy.KV_F16) {
            Log.w(TAG, "${m.key}: KV ${LlmQuantPolicy.kvName(kvType)} recusado ($err); tentando f16")
            err = load(LlmQuantPolicy.KV_F16, true)
        }
        if (err.isNotEmpty()) err = load(LlmQuantPolicy.KV_F16, false)
        if (err.isNotEmpty()) {
            _stats.update { it.copy(status = LlmStatus.ERROR, lastError = "${m.key}: $err") }
            return false
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
        loadedKey = m.key
        lastUse = System.currentTimeMillis()
        val parts = desc.split('|')
        _stats.update {
            it.copy(status = LlmStatus.READY, modelName = m.key, description = parts.getOrElse(0) { "" },
                modelBytes = parts.getOrNull(1)?.toLongOrNull() ?: file.length(),
                kvCache = parts.getOrNull(2)?.takeIf { c -> c.isNotBlank() } ?: LlmQuantPolicy.kvName(kvType),
                quantAdvice = LlmQuantPolicy.weightAdvice(file.name + " " + parts.getOrElse(0) { "" }, ftype, LlmNativeBridge.cpuFlags.fp16),
                weightType = ftype, headDim = headDim, activeEstimateMb = active / 1048576,
                modelSwitches = it.modelSwitches + 1,
                nativeHeapMb = android.os.Debug.getNativeHeapAllocatedSize() / 1048576.0)
        }
        refreshStatus()
        return true
    }

    // ------------------------------------------------------------ NeuralDeliberationPort

    override fun isAvailable(): Boolean =
        enabled && !thermalPaused && !memoryBreakerOpen && _models.value.any { it.active } && engineCompiled

    /**
     * Escalonamento por turno entre modelos. Aceita o pedido se ele é para o modelo em
     * serviço; troca de modelo só com a fila vazia, e o modelo em serviço deixa de receber
     * pedidos novos quando outro já espera há mais de [TURN_MS] (drena e passa a vez).
     */
    override fun trySubmit(request: NeuralRequest): Boolean {
        if (!isAvailable()) return false
        val target = (if (request.modelKey != null) modelFor(request.modelKey) else modelFor(scheduledKey) ?: _models.value.firstOrNull { it.active })
            ?: return false
        if (!target.active) return false
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (inflight.get() >= MAX_INFLIGHT) return false
            val serving = scheduledKey
            if (serving != target.key) {
                // turno mínimo de TURN_MS por modelo: evita trocar de contexto a cada pedido
                if (serving != null && modelFor(serving) != null && (inflight.get() > 0 || now - lastSwitchMs < TURN_MS)) {
                    waitingSince.putIfAbsent(target.key, now); return false
                }
                scheduledKey = target.key
                lastSwitchMs = now
                waitingSince.remove(target.key)
            } else {
                val starving = waitingSince.entries.any { (k, t) -> k != target.key && now - t > TURN_MS && modelFor(k) != null }
                if (starving && now - lastSwitchMs > TURN_MS) {
                    // passa a vez: não aceita mais nada deste modelo até drenar
                    if (inflight.get() == 0) {
                        val next = waitingSince.entries.filter { modelFor(it.key) != null }.minByOrNull { it.value }?.key
                        scheduledKey = next
                    }
                    return false
                }
            }
            if (!pendingAgents.add(request.agentId)) return false
            waitingSince.remove(target.key)
            inflight.incrementAndGet()
        }
        refreshStatus()
        worker.execute {
            try {
                if (!isAvailable() || !ensureLoaded(target)) return@execute
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
                        genTokens, ms.toLong(), "llama.cpp:${target.displayName}")
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

    /**
     * Conversa (aba Chat): gera a fala do cidadão com o SEU próprio .gguf, restrita pela
     * gramática {"fala":"..."}. [onDone] recebe (texto, motor) ou (null, motivo) se o modelo
     * estiver indisponível — nesse caso o SimulationHub usa a resposta simbólica do WorldCore.
     */
    fun chatAsync(modelKey: String?, agentId: String, prompt: com.enhikyy.girassol.core.ports.NeuralPrompt, onDone: (String?, String) -> Unit) {
        val target = modelFor(modelKey)
        if (target == null || !target.active || !enabled || thermalPaused || memoryBreakerOpen || !engineCompiled) {
            onDone(null, when {
                target == null -> "sem modelo .gguf vinculado"
                !target.active -> "modelo pausado"
                memoryBreakerOpen -> "disjuntor de memória aberto"
                thermalPaused -> "pausa térmica"
                else -> "motor llama.cpp indisponível"
            }); return
        }
        inflight.incrementAndGet()
        worker.execute {
            try {
                if (!ensureLoaded(target)) { onDone(null, _stats.value.lastError.ifEmpty { "modelo não carregou" }); return@execute }
                scheduledKey = target.key
                lastUse = System.currentTimeMillis()
                val r = LlmNativeBridge.nativeLlmGenerate(prompt.system, prompt.worldPrefix, prompt.user, prompt.grammar,
                    prompt.jsonKeys.joinToString(","), "$agentId#chat", T_BASE, (prompt.seed xor (prompt.seed ushr 32)).toInt(),
                    CHAT_MAX_TOKENS, prompt.larkGrammar)
                lastUse = System.currentTimeMillis()
                val text = if (r[1].isEmpty()) com.enhikyy.girassol.core.cognition.NeuralProtocol.parseChat(r[0]) else null
                val tps = r[5].toDoubleOrNull() ?: 0.0
                _stats.update { it.copy(tokensPerSecond = if (tps > 0) tps else it.tokensPerSecond, lastLatencyMs = r[4].toDoubleOrNull() ?: it.lastLatencyMs,
                    lastOutput = r[0].take(220), lastError = r[1]) }
                if (text != null) onDone(text, "gguf:${target.displayName}") else onDone(null, r[1].ifEmpty { "resposta fora da gramática" })
            } catch (t: Throwable) {
                onDone(null, t.message ?: "erro")
            } finally {
                inflight.decrementAndGet()
                refreshStatus()
            }
        }
    }

    override fun drainResults(): List<NeuralDecision> {
        if (results.isEmpty()) return emptyList()
        val out = ArrayList<NeuralDecision>()
        while (true) out += results.poll() ?: break
        return out
    }
}
