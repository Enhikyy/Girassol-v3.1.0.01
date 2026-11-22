package com.enhikyy.girassol.synaptic

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import com.enhikyy.girassol.data.GirassolRecordCodec
import com.enhikyy.girassol.data.SynapticBudgetStore
import com.enhikyy.girassol.hardware.HardwareAutoTuner
import com.enhikyy.girassol.nativebridge.LlmNativeBridge
import com.enhikyy.girassol.nativebridge.SynapticBudgetNative
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/** Regime espectral do acumulador A (ajustes log-lineares do núcleo C++). */
enum class SpectrumRegime(val label: String) { DESCONHECIDO("indefinido"), EXPONENCIAL("exponencial"), POTENCIA("lei de potência") }

/**
 * Telemetria IMUTÁVEL publicada para a aba Sistema. Somente vals de tipos imutáveis
 * (listas copiadas com toList) — estável para o Compose (sem recomposições espúrias).
 */
@Immutable
data class SynapticTelemetrySnapshot(
    val loaded: Boolean = false,
    val abi: String = "",
    val dim: Int = 0,
    val tasks: Int = 0,
    val protectedRank: Int = 0,
    val freeFraction: Double = 1.0,
    val totalEnergy: Double = 0.0,
    val epsilon: Double = 0.0,
    val epsilonHistory: List<Double> = emptyList(),
    val forgettingBound: Double = 0.0,
    val tau: Double = 0.0,
    val alpha: Double = 0.0,
    val r2Exp: Double = 0.0,
    val r2Pow: Double = 0.0,
    val regime: SpectrumRegime = SpectrumRegime.DESCONHECIDO,
    val loraProjectedBlocks: Long = 0,
    val stagedRows: Int = 0,
    val bridgeActive: Boolean = false,
    val worldObservations: Long = 0,
    val serializedBytes: Int = 0,
    val checksumHex: String = "",
    val persisted: Boolean = false,
    val restoredFromDisk: Boolean = false,
    val selfTest: String = "",
    val message: String = "",
) {
    val ready: Boolean get() = loaded && dim > 0
}

data class SynapticSelfTestResult(
    val abi: String,
    val t13Hash: String,
    val checks: Int,
    val fails: Int,
    val perTestFails: List<Int>,
    val log: String,
) {
    val canonical: Boolean get() = t13Hash == SynapticBudgetNative.CANONICAL_T13_HASH
}

/**
 * Motor do Orçamento Sináptico. Dono único do estado nativo (ByteBuffer direto alinhado a 64 B),
 * executado numa thread dedicada com pilha de 64 MB (observe/commit usam O(D²) doubles de pilha;
 * D=256 ≈ 1,5 MB). D escolhido pela RAM: 4 GB → 64, 8 GB → 128, 12 GB → 256.
 *
 * Fontes de observação:
 *  • LoRA on-device (llama.cpp): a ponte nativa projeta G_A em (I − P) antes do Adam e prepara as
 *    ativações do ciclo aceito; [onLoraCycleFinished] consolida (observe + commit pela cauda).
 *  • LoRA do mundo (forge C++): as features de cada lote de treino são observadas (sem alterar o
 *    treino, preservando a paridade dourada Kotlin↔C++).
 */
object SynapticBudgetEngine {
    private const val TAG = "SynapticBudgetEngine"
    /** Parâmetros da cota P5 (mesmos do planejador de referência T11/T13). */
    const val F_INF = 0.59
    const val C_RATE = 1.0
    /** Cauda tolerada por commit (fração da energia). */
    const val TAIL_FRACTION = 0.05

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(null, r, "girassol-sb", 64L shl 20).apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _telemetry = MutableStateFlow(SynapticTelemetrySnapshot())
    val telemetry: StateFlow<SynapticTelemetrySnapshot> = _telemetry.asStateFlow()

    // Estado nativo — acessado SOMENTE na thread girassol-sb.
    private var buffer: ByteBuffer? = null
    private var offset = 0
    private var dim = 0
    private var store: SynapticBudgetStore? = null
    private var worldObservations = 0L
    private var restored = false
    private var lastSerialized = 0
    private var lastChecksum = ""
    private var persisted = false
    private var selfTestSummary = ""
    @Volatile private var started = false

    fun dimensionForRam(totalRamMb: Long): Int = HardwareAutoTuner.synapticDimForRam(totalRamMb)

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            runCatching { startOnEngineThread(app) }.onFailure {
                Log.e(TAG, "falha ao iniciar o Orçamento Sináptico", it)
                _telemetry.value = _telemetry.value.copy(message = "erro: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    private fun startOnEngineThread(app: Context) {
        if (!SynapticBudgetNative.loaded) {
            _telemetry.value = SynapticTelemetrySnapshot(message = "libgirassol_sb indisponível: ${SynapticBudgetNative.loadError}")
            return
        }
        val am = app.getSystemService(android.app.ActivityManager::class.java)
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val d = dimensionForRam(mi.totalMem / (1024 * 1024))
        allocate(d)
        store = SynapticBudgetStore(app)
        store?.load(d)?.let { blob ->
            val rc = SynapticBudgetNative.nativeDeserialize(buffer!!, offset, dim, blob)
            restored = rc == 0
            if (!restored) Log.w(TAG, "estado persistido rejeitado (status $rc); começando vazio")
        }
        SynapticBudgetNative.nativeEnableEpsHistory(buffer!!, offset, dim, true)
        SynapticBudgetNative.nativeSetActive(buffer!!, offset, dim)
        val bridgeOk = runCatching {
            LlmNativeBridge.libraryLoaded && LlmNativeBridge.nativeLlmSetGradientProjector(SynapticBudgetNative.nativeBridgeHandle())
        }.getOrDefault(false)
        publish(if (restored) "estado restaurado do SQLite WAL" else "estado novo", bridgeOk)
    }

    private fun allocate(d: Int) {
        val bytes = SynapticBudgetNative.nativeStateBytes(d)
        require(bytes > 0) { "dimensão D=$d sem suporte nativo" }
        val buf = ByteBuffer.allocateDirect((bytes + 64).toInt()).order(ByteOrder.nativeOrder())
        val off = SynapticBudgetNative.nativeAlignOffset(buf)
        require(off in 0..63) { "alinhamento do buffer direto falhou" }
        val rc = SynapticBudgetNative.nativeInit(buf, off, d)
        require(rc == 0) { "sbN_init falhou (status $rc)" }
        buffer = buf; offset = off; dim = d
    }

    /** Observa um lote de features (n × inDim) — completado com zeros/truncado para D. */
    fun observeWorldBatch(xs: FloatArray, n: Int, inDim: Int) {
        if (!started || n <= 0 || inDim <= 0 || xs.size < n * inDim) return
        val copy = xs.copyOf(n * inDim)
        scope.launch {
            val buf = buffer ?: return@launch
            val x = DoubleArray(n * dim)
            val k = minOf(inDim, dim)
            for (r in 0 until n) for (i in 0 until k) x[r * dim + i] = copy[r * inDim + i].toDouble()
            val rc = SynapticBudgetNative.nativeObserve(buf, offset, dim, x, n, 1.0)
            if (rc != 0) return@launch   // lote nulo/degenerado: estado intacto
            worldObservations += n
            SynapticBudgetNative.nativeCommitForTail(buf, offset, dim, TAIL_FRACTION, dim / 2)
            persist()
            publish("lote do mundo observado ($n amostras)", null)
        }
    }

    /** Consolida as ativações preparadas pelo treino LoRA on-device (ciclo aceito). */
    fun onLoraCycleFinished() {
        if (!started) return
        scope.launch {
            if (buffer == null) return@launch
            val out = IntArray(3)
            SynapticBudgetNative.nativeConsolidateStaged(1.0, TAIL_FRACTION, dim / 2, out)
            if (out[0] > 0) persist()
            publish(if (out[0] > 0) "ciclo LoRA consolidado: ${out[0]} ativações, posto ${out[1]}" else "ciclo LoRA sem ativações novas", null)
        }
    }

    /** Projeta linhas (m × D) no complemento do subespaço protegido. */
    suspend fun project(g: DoubleArray, m: Int): DoubleArray = withContext(dispatcher) {
        val buf = buffer ?: return@withContext g
        val out = g.copyOf()
        SynapticBudgetNative.nativeProject(buf, offset, dim, out, m)
        out
    }

    /** Bloco GRSB do estado atual (para o contêiner .grs). Bloqueia até a thread do motor responder. */
    fun serializeBlocking(): ByteArray? = runBlocking { serialize() }

    suspend fun serialize(): ByteArray? = withContext(dispatcher) {
        val buf = buffer ?: return@withContext null
        val n = SynapticBudgetNative.nativeSerializedBytes(dim).toInt()
        val out = ByteArray(n)
        val w = SynapticBudgetNative.nativeSerialize(buf, offset, dim, out)
        if (w != n) null else out
    }

    /** Restaura um bloco GRSB (validado no Kotlin e no C++). Retorna mensagem legível. */
    suspend fun restore(blob: ByteArray): String = withContext(dispatcher) {
        val buf = buffer ?: return@withContext "motor sináptico inativo"
        val info = runCatching { GirassolRecordCodec.inspectGrsb(blob) }.getOrElse { return@withContext "bloco inválido: ${it.message}" }
        if (info.dim != dim) return@withContext "bloco D=${info.dim} incompatível com este aparelho (D=$dim)"
        val rc = SynapticBudgetNative.nativeDeserialize(buf, offset, dim, blob)
        if (rc != 0) return@withContext "rejeitado pelo núcleo (status $rc)"
        persist()
        publish("estado importado do .grs", null)
        "orçamento sináptico restaurado (D=$dim)"
    }

    /** Autoteste T1–T18 no aparelho (bloqueante, thread de pilha grande). */
    suspend fun runSelfTests(): SynapticSelfTestResult = withContext(dispatcher) {
        check(SynapticBudgetNative.loaded) { "libgirassol_sb indisponível" }
        val ints = IntArray(21)
        val hash = LongArray(1)
        val log = SynapticBudgetNative.nativeRunSelfTests(ints, hash)
        val res = SynapticSelfTestResult(
            abi = SynapticBudgetNative.nativeAbi(),
            t13Hash = java.lang.Long.toUnsignedString(hash[0], 16).padStart(16, '0'),
            checks = ints[0], fails = ints[1], perTestFails = ints.copyOfRange(2, 21).toList(), log = log)
        selfTestSummary = "${res.abi}: ${res.checks} verificações, ${res.fails} falhas · T13 ${res.t13Hash}" +
            if (res.canonical) " (canônico)" else " (DIVERGENTE)"
        publish("autoteste concluído", null)
        res
    }

    private fun persist() {
        val buf = buffer ?: return
        val n = SynapticBudgetNative.nativeSerializedBytes(dim).toInt()
        val blob = ByteArray(n)
        if (SynapticBudgetNative.nativeSerialize(buf, offset, dim, blob) != n) return
        val t = DoubleArray(10)
        SynapticBudgetNative.nativeTelemetry(buf, offset, dim, t)
        lastSerialized = n
        lastChecksum = runCatching { GirassolRecordCodec.inspectGrsb(blob).checksumHex }.getOrDefault("")
        persisted = store?.save(dim, blob, t[1].toInt(), t[0].toInt()) ?: false
    }

    private fun publish(message: String, bridge: Boolean?) {
        val buf = buffer ?: return
        val t = DoubleArray(10)
        SynapticBudgetNative.nativeTelemetry(buf, offset, dim, t)
        val epsN = SynapticBudgetNative.nativeEpsHistory(buf, offset, dim, null)
        val eps = DoubleArray(maxOf(epsN, 0))
        if (epsN > 0) SynapticBudgetNative.nativeEpsHistory(buf, offset, dim, eps)
        val bound = SynapticBudgetNative.nativeForgettingBound(buf, offset, dim, F_INF, C_RATE)
        val lam = DoubleArray(dim)
        val k = SynapticBudgetNative.nativeEigenvalues(buf, offset, dim, lam)
        var tau = 0.0; var alpha = 0.0; var r2e = 0.0; var r2p = 0.0
        var regime = SpectrumRegime.DESCONHECIDO
        if (k >= 3 && lam[0] > 0.0) {
            var pos = 0
            while (pos < k && lam[pos] > lam[0] * 1e-12) pos++
            if (pos >= 3) {
                val o = DoubleArray(5)
                if (SynapticBudgetNative.nativeAnalyzeSpectrum(lam, 0, pos, o) == 0) {
                    tau = o[0]; alpha = o[1]; r2e = o[2]; r2p = o[3]
                    regime = when (o[4].toInt()) { 1 -> SpectrumRegime.EXPONENCIAL; 2 -> SpectrumRegime.POTENCIA; else -> SpectrumRegime.DESCONHECIDO }
                }
            }
        }
        val prev = _telemetry.value
        _telemetry.value = SynapticTelemetrySnapshot(
            loaded = true, abi = SynapticBudgetNative.nativeAbi(), dim = dim,
            protectedRank = t[0].toInt(), tasks = t[1].toInt(), freeFraction = t[2], totalEnergy = t[3], epsilon = t[4],
            epsilonHistory = eps.toList(), forgettingBound = bound,
            tau = tau, alpha = alpha, r2Exp = r2e, r2Pow = r2p, regime = regime,
            loraProjectedBlocks = t[7].toLong(), stagedRows = t[8].toInt(),
            bridgeActive = bridge ?: prev.bridgeActive, worldObservations = worldObservations,
            serializedBytes = lastSerialized, checksumHex = lastChecksum, persisted = persisted, restoredFromDisk = restored,
            selfTest = selfTestSummary, message = message)
    }
}
