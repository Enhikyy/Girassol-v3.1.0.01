package com.enhikyy.girassol.nativebridge

import android.content.Context
import com.enhikyy.girassol.core.forge.LoraAdapterState
import com.enhikyy.girassol.core.forge.LoraSample
import com.enhikyy.girassol.core.forge.LoraTrainResult
import com.enhikyy.girassol.core.forge.LoraTrainerPort
import com.enhikyy.girassol.core.ports.ParityVectors
import com.enhikyy.girassol.core.ports.PathfinderPort
import com.enhikyy.girassol.core.world.WorldState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A* em C++ (navigation/grid_astar.hpp), bit a bit equivalente ao Pathfinding Kotlin. */
object NativePathfinder : PathfinderPort {
    override val backend = "cpp-astar"
    override fun findPath(state: WorldState, sx: Int, sz: Int, gx: Int, gz: Int, trail: FloatArray?, danger: FloatArray?): MutableList<Int> =
        GeaNativeBridge.nativeNavFindPathGrid(state.widthTiles, state.tiles, sx, sz, gx, gz, trail, danger).toMutableList()
}

/** Treinador LoRA em C++ (forge/lora_trainer_realtime.hpp), equivalente ao LoraMath Kotlin. */
object NativeLoraTrainer : LoraTrainerPort {
    override val backend = "cpp-lora"

    fun trainPacked(state: LoraAdapterState, xs: FloatArray, ys: FloatArray, n: Int, epochs: Int, lr: Float, ewc: Float): Triple<LoraAdapterState, Float, Float> {
        val out = GeaNativeBridge.nativeLoraTrain(state.inDim, state.outDim, state.rank, state.scaling, state.packed(), state.step,
            xs, ys, n, epochs, lr, ewc) ?: error("nativeLoraTrain rejeitou a entrada")
        val m = out.size
        val step = java.lang.Float.floatToRawIntBits(out[m - 1])
        val next = state.withPacked(out.copyOfRange(0, m - 3), step)
        return Triple(next, out[m - 3], out[m - 2])
    }

    override fun train(state: LoraAdapterState, samples: List<LoraSample>, epochs: Int, lr: Float, ewcLambda: Float): LoraTrainResult {
        val xs = FloatArray(samples.size * state.inDim)
        val ys = FloatArray(samples.size * state.outDim)
        samples.forEachIndexed { i, s -> s.x.copyInto(xs, i * state.inDim); ys[i * state.outDim] = s.y }
        val (next, first, last) = trainPacked(state, xs, ys, samples.size, epochs, lr, ewcLambda)
        return LoraTrainResult(next, first, last, backend)
    }
}

data class ModuleToggles(
    val nativeNavigation: Boolean = true,
    val nativeLora: Boolean = true,
    val binauralAudio: Boolean = false,
    val localLlm: Boolean = true,
    val voiceTts: Boolean = false
)

data class ParityStatus(
    val checked: Boolean = false,
    val navOk: Boolean = false,
    val loraOk: Boolean = false,
    val navSignature: String = "-",
    val loraSignature: String = "-",
    val message: String = "não verificado",
    val memoryOk: Boolean = false,
    val memorySignature: String = "-"
)

/**
 * Seleção dos backends nativos. Um backend C++ só é ativado depois de reproduzir, no
 * próprio aparelho, as assinaturas douradas compartilhadas com a JVM; em caso de
 * divergência a simulação permanece no backend Kotlin (determinismo preservado).
 */
object NativeModules {
    private const val PREFS = "girassol_modules"
    private val _toggles = MutableStateFlow(ModuleToggles())
    val toggles: StateFlow<ModuleToggles> = _toggles.asStateFlow()
    private val _parity = MutableStateFlow(ParityStatus())
    val parity: StateFlow<ParityStatus> = _parity.asStateFlow()

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _toggles.value = ModuleToggles(
            p.getBoolean("nav", true), p.getBoolean("lora", true), p.getBoolean("audio", false), p.getBoolean("llm", true),
            p.getBoolean("tts", false)
        )
    }

    fun update(context: Context, transform: (ModuleToggles) -> ModuleToggles) {
        _toggles.update(transform)
        val t = _toggles.value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("nav", t.nativeNavigation).putBoolean("lora", t.nativeLora)
            .putBoolean("audio", t.binauralAudio).putBoolean("llm", t.localLlm).putBoolean("tts", t.voiceTts).apply()
    }

    /** Executa os vetores de paridade (alguns ms). Seguro chamar mais de uma vez. */
    fun verifyParity(): ParityStatus {
        if (!GeaNativeBridge.isNativeLoaded) {
            return ParityStatus(true, false, false, "-", "-", "biblioteca nativa indisponível").also { _parity.value = it }
        }
        val status = runCatching {
            val nav = ParityVectors.navSignature { g, sx, sz, gx, gz ->
                GeaNativeBridge.nativeNavFindPathGrid(g.w, g.tiles, sx, sz, gx, gz, g.trail, g.danger)
            }
            val samples = ParityVectors.loraSamples()
            val init = ParityVectors.loraInitial()
            val xs = FloatArray(samples.size * init.inDim)
            val ys = FloatArray(samples.size)
            samples.forEachIndexed { i, s -> s.x.copyInto(xs, i * init.inDim); ys[i] = s.y }
            val (trained, _, _) = NativeLoraTrainer.trainPacked(init, xs, ys, samples.size,
                ParityVectors.LORA_EPOCHS, ParityVectors.LORA_LR, ParityVectors.LORA_EWC)
            val lora = ParityVectors.loraSignature(trained.packed(), trained.step)
            val navOk = nav == ParityVectors.NAV_GOLDEN
            val loraOk = lora == ParityVectors.LORA_GOLDEN
            val mem = GeaNativeBridge.nativeBinaryMemorySignature()
            val memOk = mem == com.enhikyy.girassol.core.memory.EpisodicIndex.PARITY_GOLDEN &&
                mem == com.enhikyy.girassol.core.memory.EpisodicIndex.paritySignature()
            ParityStatus(true, navOk, loraOk, "%016x".format(nav), "%016x".format(lora),
                when {
                    navOk && loraOk && memOk -> "OK: navegação, LoRA e memória binária C++ idênticos ao Kotlin"
                    else -> "DIVERGENTE (nav=$navOk, lora=$loraOk, memoria=$memOk): usando Kotlin"
                }, memOk, "%016x".format(mem))
        }.getOrElse { ParityStatus(true, false, false, "-", "-", "erro: ${it.message}") }
        _parity.value = status
        return status
    }

    fun pathfinder(): PathfinderPort =
        if (_toggles.value.nativeNavigation && _parity.value.navOk) NativePathfinder else com.enhikyy.girassol.core.ports.KotlinPathfinder

    fun loraTrainer(): LoraTrainerPort =
        if (_toggles.value.nativeLora && _parity.value.loraOk) NativeLoraTrainer else com.enhikyy.girassol.core.forge.KotlinLoraTrainer
}
