package com.enhikyy.girassol.render3d

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.enhikyy.girassol.audio.VoiceAmplitude
import com.enhikyy.girassol.core.render.CitizenRig
import com.enhikyy.girassol.core.render.Mat4
import com.enhikyy.girassol.core.world.AgentView
import com.enhikyy.girassol.core.world.WorldRenderSnapshot
import com.enhikyy.girassol.nativebridge.ProceduralAssetsNative
import com.enhikyy.girassol.sim.SimulationHub
import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndexBuffer
import com.google.android.filament.LightManager
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.RenderTarget
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.SwapChainFlags
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.ToneMapper
import com.google.android.filament.VertexBuffer
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.UiHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Telemetria do viewport 3D, lida pela faixa de status e pela aba Sistema. */
data class Render3dStats(val active: Boolean = false, val fps: Double = 0.0, val vertices: Int = 0, val citizens: Int = 0, val error: String = "",
    val effectLevel: String = "", val preset: String = "", val camera: String = "", val phase: String = "", val instances: Int = 0)

object Render3dTelemetry {
    private val _stats = MutableStateFlow(Render3dStats())
    val stats: StateFlow<Render3dStats> = _stats.asStateFlow()
    internal fun set(s: Render3dStats) { _stats.value = s }
    internal fun update(f: (Render3dStats) -> Render3dStats) { _stats.value = f(_stats.value) }
}

/**
 * v4.0 — viewport Filament de alta fidelidade da Aba Mundo: presets URBAN_OVERCAST e
 * LUMON_INTERIOR com PBR (texturas C++20 1024² + mips), IBL procedural por fase do dia,
 * sombras em cascata + contato em espaço de tela, SSAO, SSR, TAA + FXAA, ACES, bloom e a
 * passada analógica girassol_dehancer_post. Cidadãos articulados por ossos (entidades filhas),
 * câmeras Órbita / Inclinação / POV em primeira pessoa. Toda chamada ao Engine ocorre na
 * thread principal (Choreographer); a geração de texturas/IBL roda numa thread de fundo.
 */
class FilamentCityRenderer(
    private val context: Context,
    private val surfaceView: android.view.View?,
    initialLevel: EffectLevel = EffectLevel.FULL
) {
    companion object {
        private const val TAG = "GirassolFilament"
        /** Emissivo noturno de referência (usado também pelo [SunCycle] do Modo Eco 2D). */
        const val NIGHT_EMISSIVE = 1.4f
        const val BRIGHT_KNEE_LDR = 0.82f
        const val PRIMARY_TEX = 1024
        const val SECONDARY_TEX = 512
        const val CITIZEN_FAR_M = 60f
        private const val TARGET_FRAME_NS = 1_000_000_000L / 30
        @Volatile private var filamentReady = false
        fun ensureFilament(): Boolean {
            if (!filamentReady) filamentReady = runCatching { Filament.init(); true }.getOrElse { Log.w(TAG, "Filament indisponível", it); false }
            return filamentReady
        }

        /** Iluminação física por fase: EV100 da câmera, luz direcional (lux), IBL (lux no plano horizontal). */
        data class PhaseLight(val ev100: Float, val sunLux: Float, val sunColor: FloatArray, val iblLux: Float, val lampLumens: Float,
            val windows: Float, val billboard: Float, val streetLamp: Float, val fogDensity: Float)

        fun phaseLight(p: TimePhase): PhaseLight = when (p) {
            TimePhase.MORNING -> PhaseLight(12.2f, 15000f, floatArrayOf(1.0f, 0.97f, 0.92f), 12000f, 0f, 0.20f, 0.60f, 0.05f, 0.010f)
            TimePhase.AFTERNOON -> PhaseLight(12.0f, 21000f, floatArrayOf(1.0f, 0.80f, 0.58f), 8000f, 0f, 0.60f, 0.90f, 0.30f, 0.012f)
            TimePhase.NIGHT -> PhaseLight(4.6f, 2f, floatArrayOf(0.62f, 0.72f, 1.0f), 6f, 12000f, 1.60f, 1.40f, 3.0f, 0.016f)
            TimePhase.INTERIOR -> PhaseLight(8.2f, 650f, floatArrayOf(1.0f, 0.98f, 0.94f), 300f, 0f, 0f, 0f, 0f, 0f)
        }

        /** Direção representativa do sol (para o céu procedural) por fase. */
        fun phaseSunDir(p: TimePhase): FloatArray = when (p) {
            TimePhase.MORNING -> floatArrayOf(-0.62f, 0.48f, -0.36f)
            TimePhase.AFTERNOON -> floatArrayOf(0.74f, 0.22f, -0.34f)
            TimePhase.NIGHT -> floatArrayOf(0.30f, 0.80f, 0.40f)
            TimePhase.INTERIOR -> floatArrayOf(0f, 1f, 0f)
        }
    }

    var level: EffectLevel = initialLevel
        private set
    private var headless = false
    private var lastSnapshot: WorldRenderSnapshot? = null
    private val fpsGovernor = FpsGovernor()
    /** Chamado quando o FPS fica abaixo do piso por tempo prolongado (thread principal). */
    var onFpsLow: ((EffectLevel) -> Unit)? = null
    /** Desliga o governador de FPS (roteiro de demonstração com nível forçado). */
    var fpsGovernorEnabled = true

    val camera = OrbitCamera()
    private val fpc = FirstPersonCamera()
    private var tiltDist = 26f
    private lateinit var engine: Engine
    private lateinit var renderer: Renderer
    private lateinit var scene: Scene
    private lateinit var view: View
    private lateinit var fCamera: Camera
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private var swapChain: SwapChain? = null
    private lateinit var pbrMat: Material
    private lateinit var glowMat: Material
    private lateinit var shadowMat: Material
    private lateinit var glowMi: MaterialInstance
    private lateinit var shadowMi: MaterialInstance
    private lateinit var texWhite: Texture
    private lateinit var texBlack: Texture
    private lateinit var texOrm: Texture
    private lateinit var texNormal: Texture
    private val surfaces = HashMap<Int, WorldGpu.SurfaceTextures>()
    private val gradings = HashMap<WorldPreset, ColorGrading>()
    private val ibls = HashMap<TimePhase, WorldGpu.Ibl>()
    private val iblPending = HashSet<TimePhase>()
    private var activeIbl: WorldGpu.Ibl? = null

    // passada analógica girassol_dehancer_post (View da cena → RenderTarget → triângulo de tela cheia)
    private lateinit var postMat: Material
    private lateinit var postMi: MaterialInstance
    private lateinit var postScene: Scene
    private lateinit var postView: View
    private lateinit var postCamera: Camera
    private var postEntity = 0
    private var postVb: VertexBuffer? = null; private var postIb: IndexBuffer? = null
    private var rtColor: Texture? = null; private var rtDepth: Texture? = null; private var renderTarget: RenderTarget? = null
    private var postActive = false
    private val startNs = System.nanoTime()

    // ---- cena estática do preset ativo
    private class GpuPart(val entity: Int, val vb: VertexBuffer, val ib: IndexBuffer, val mi: MaterialInstance?, val spec: MatSpec)
    private val worldParts = ArrayList<GpuPart>()
    private val sharedMis = HashMap<String, MaterialInstance>()
    private var world: WorldScene? = null
    private var preset: WorldPreset = WorldViewState.preset.value
    private var sceneKey = Long.MIN_VALUE
    private var glowEntity = 0; private var glowVb: VertexBuffer? = null; private var glowIb: IndexBuffer? = null
    private var sunEntity = 0
    private var sunShadowKey = ""
    private val lampEntities = ArrayList<Int>()

    // ---- cidadãos articulados
    private class BoneGpu(val vb: VertexBuffer, val ib: IndexBuffer, val count: Int, val slot: CitizenModel.Slot, val box: Box)
    private val boneGpu = ArrayList<List<BoneGpu>>()
    private var shadowVb: VertexBuffer? = null; private var shadowIb: IndexBuffer? = null
    private val citizenMis = HashMap<String, MaterialInstance>()
    private class CitizenGpu(val root: Int, val bones: IntArray, val shadow: Int, var hidden: Boolean = false)
    private val citizens = HashMap<String, CitizenGpu>()
    private val rigStates = HashMap<String, CitizenRig.State>()
    private val agentViews = HashMap<String, AgentView>()
    private val boneScratch = FloatArray(CitizenRig.BONES * 16)
    /** Posição visual (metros da simulação, interpolada) e altura do piso suavizada. */
    private val visual = HashMap<String, FloatArray>()
    private var targets: Map<String, FloatArray> = emptyMap()
    private val walking = HashMap<String, Float>()
    private val scenePos = HashMap<String, V3>()

    // ---- tempo/luz
    private var dayPhase = Float.NaN
    private var dayTarget = 0.5f
    private var phase = TimePhase.MORNING
    private var cur = phaseLight(TimePhase.MORNING).let { floatArrayOf(it.ev100, it.sunLux, it.iblLux, it.lampLumens, it.windows, it.billboard, it.streetLamp, it.fogDensity) }
    private var pose = CameraPose(V3(0f, 40f, 40f), V3(0f, 0f, 0f), 42f)
    private var cameraMode = CameraMode.ORBIT
    private var povId: String? = null
    private var povPublishNs = 0L

    // ---- geração de fundo
    private val worker: ExecutorService? by lazy { if (headless) null else Executors.newSingleThreadExecutor { r -> Thread(r, "girassol-world-assets").apply { isDaemon = true } } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0

    private var widthPx = 1; private var heightPx = 1
    private var lastFrameNs = 0L
    private var fpsWindowStart = 0L
    private var fpsFrames = 0
    private var running = false
    var initialized = false; private set
    var onFrame: (() -> Unit)? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            Choreographer.getInstance().postFrameCallback(this)
            if (frameTimeNanos - lastFrameNs < TARGET_FRAME_NS - 2_000_000L) return
            val dt = if (lastFrameNs == 0L) 0.033f else ((frameTimeNanos - lastFrameNs) / 1e9f).coerceAtMost(0.1f)
            lastFrameNs = frameTimeNanos
            renderFrame(frameTimeNanos, dt)
        }
    }

    /** Cria o Engine e os recursos; lança exceção se o dispositivo não suportar (ex.: sem GLES 3). */
    fun init() {
        val sv = checkNotNull(surfaceView) { "init() exige SurfaceView/TextureView; use initHeadless()" }
        initCore()
        uiHelper.setRenderCallback(object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface, uiHelper.swapChainFlags)
            }
            override fun onDetachedFromSurface() {
                swapChain?.let { engine.destroySwapChain(it); engine.flushAndWait() }
                swapChain = null
            }
            override fun onResized(width: Int, height: Int) {
                widthPx = width.coerceAtLeast(1); heightPx = height.coerceAtLeast(1)
                view.setViewport(Viewport(0, 0, widthPx, heightPx))
                configurePost()
            }
        })
        when (sv) {
            is android.view.TextureView -> uiHelper.attachTo(sv)
            is SurfaceView -> uiHelper.attachTo(sv)
            else -> error("superfície não suportada: ${sv.javaClass.simpleName}")
        }
        initialized = true
        Render3dTelemetry.set(Render3dStats(active = true, effectLevel = level.label))
    }

    /** Modo fora da tela (sonda de GPU): mesmos materiais e pós; sem geração assíncrona. */
    fun initHeadless(width: Int, height: Int) {
        headless = true
        initCore()
        swapChain = engine.createSwapChain(width, height, SwapChainFlags.CONFIG_DEFAULT)
        widthPx = width; heightPx = height
        view.setViewport(Viewport(0, 0, width, height))
        configurePost()
        initialized = true
    }

    /** Renderiza [frames] quadros fora da tela e espera a GPU (força a compilação dos shaders). */
    fun renderHeadless(frames: Int): Int {
        check(headless && initialized)
        val sc = swapChain ?: return 0
        var done = 0
        var attempts = 0
        while (done < frames && attempts < frames * 4) {
            attempts++
            if (dayPhase.isNaN()) dayPhase = 0.4f
            dayPhase = if (done % 2 == 0) 0.4f else 0.9f
            dayTarget = dayPhase
            stepLighting(1f)
            updateCamera(0.033f)
            if (renderer.beginFrame(sc, System.nanoTime())) {
                renderViews()
                renderer.endFrame()
                done++
            }
            Thread.sleep(8)
        }
        engine.flushAndWait()
        return done
    }

    private var nativeFootprint = 0L

    private fun initCore() {
        check(ensureFilament()) { "biblioteca Filament não carregou" }
        val heapBefore = android.os.Debug.getNativeHeapAllocatedSize()
        engine = Engine.create()
        renderer = engine.createRenderer()
        scene = engine.createScene()
        view = engine.createView()
        fCamera = engine.createCamera(EntityManager.get().create())
        view.setScene(scene)
        view.setCamera(fCamera)
        renderer.setClearOptions(Renderer.ClearOptions().apply { clear = true; clearColor = doubleArrayOf(0.0, 0.0, 0.0, 1.0) })
        pbrMat = loadMaterial("materials/world_pbr.filamat")
        glowMat = loadMaterial("materials/glow.filamat")
        shadowMat = loadMaterial("materials/contact_shadow.filamat")
        postMat = loadMaterial("materials/girassol_dehancer_post.filamat")
        glowMi = glowMat.createInstance().apply { setParameter("size", 1.1f); setParameter("intensity", 0.4f) }
        shadowMi = shadowMat.createInstance().apply { setParameter("opacity", 0.55f) }
        texWhite = WorldGpu.solid(engine, 255, 255, 255, 255, srgb = true)
        texBlack = WorldGpu.solid(engine, 0, 0, 0, 255, srgb = true)
        texOrm = WorldGpu.solid(engine, 255, 0, 255, 255, srgb = false)
        texNormal = WorldGpu.solid(engine, 128, 128, 255, 255, srgb = false)
        buildCitizenGeometry()
        initPostPass()
        applyEffectLevel(level)
        nativeFootprint = (android.os.Debug.getNativeHeapAllocatedSize() - heapBefore).coerceAtLeast(FilamentMemory.MIN_ENGINE_BYTES)
        FilamentMemory.add(nativeFootprint)
    }

    // ================================================================== materiais

    private fun profile(): CinematicProfile = CinematicProfile.forLevel(preset, level)

    /** Albedo aproximado enquanto a textura procedural da superfície ainda não chegou. */
    private fun fallbackAlbedo(surface: Int): FloatArray = when (surface) {
        ProceduralAssetsNative.WET_ASPHALT -> floatArrayOf(0.035f, 0.037f, 0.040f)
        ProceduralAssetsNative.FACADE -> floatArrayOf(0.14f, 0.16f, 0.17f)
        ProceduralAssetsNative.SIDEWALK -> floatArrayOf(0.22f, 0.22f, 0.21f)
        ProceduralAssetsNative.CONCRETE -> floatArrayOf(0.26f, 0.26f, 0.25f)
        ProceduralAssetsNative.CARPET -> floatArrayOf(0.0065f, 0.030f, 0.018f)
        ProceduralAssetsNative.COFFER -> floatArrayOf(0.62f, 0.64f, 0.62f)
        ProceduralAssetsNative.PARTITION -> floatArrayOf(0.67f, 0.71f, 0.68f)
        ProceduralAssetsNative.BILLBOARD -> floatArrayOf(0.05f, 0.05f, 0.06f)
        ProceduralAssetsNative.GRASS -> floatArrayOf(0.05f, 0.09f, 0.03f)
        else -> floatArrayOf(0.3f, 0.3f, 0.3f)
    }

    private fun configureMi(mi: MaterialInstance, spec: MatSpec) {
        val tex = if (spec.surface >= 0) surfaces[spec.surface] else null
        mi.setParameter("albedoTex", tex?.albedo ?: texWhite, WorldGpu.repeatSampler)
        mi.setParameter("ormTex", tex?.orm ?: texOrm, WorldGpu.repeatSampler)
        mi.setParameter("normalTex", tex?.normal ?: texNormal, WorldGpu.repeatSampler)
        mi.setParameter("emissiveTex", tex?.emissive ?: if (spec.emissive != null && spec.surface < 0) texWhite else texBlack, WorldGpu.repeatSampler)
        val t = spec.tint
        val base = if (spec.surface >= 0 && tex == null) fallbackAlbedo(spec.surface) else floatArrayOf(1f, 1f, 1f)
        mi.setParameter("baseTint", t[0] * base[0], t[1] * base[1], t[2] * base[2], 1f)
        mi.setParameter("uvScale", 1f / spec.metersU, 1f / spec.metersV, 0f, 0f)
        if (tex != null) mi.setParameter("surface", spec.normalStrength, 0f, 1f, 0.05f)
        else if (spec.surface >= 0) mi.setParameter("surface", 0f, fallbackRough(spec.surface), 0f, 0.05f)
        else mi.setParameter("surface", 0f, spec.rough, spec.metal, 0.05f)
        val e = spec.emissive ?: floatArrayOf(0f, 0f, 0f)
        mi.setParameter("emissiveColor", e[0], e[1], e[2], emissiveIntensity(spec.emissiveDriver))
    }

    private fun fallbackRough(surface: Int): Float = when (surface) {
        ProceduralAssetsNative.WET_ASPHALT -> 0.5f
        ProceduralAssetsNative.CARPET -> 0.9f
        ProceduralAssetsNative.PARTITION -> 0.68f
        else -> 0.8f
    }

    private fun emissiveIntensity(d: EmissiveDriver): Float = when (d) {
        EmissiveDriver.NONE -> 0f
        EmissiveDriver.WINDOWS -> cur[4]
        EmissiveDriver.BILLBOARD -> cur[5]
        EmissiveDriver.STREET_LAMP -> cur[6]
        EmissiveDriver.CEILING -> 1.15f
        EmissiveDriver.MONITOR -> 1.0f
        EmissiveDriver.BADGE -> 0.8f
    }

    private fun zeroOffsets(mi: MaterialInstance) = mi.setParameter("instanceOffsets", MaterialInstance.FloatElement.FLOAT4, FloatArray(4), 0, 1)

    private fun sharedMi(spec: MatSpec): MaterialInstance = sharedMis.getOrPut(spec.id) {
        pbrMat.createInstance().also { configureMi(it, spec); zeroOffsets(it) }
    }

    // ================================================================== cena estática

    private fun destroyRenderable(entity: Int) {
        if (entity == 0) return
        scene.removeEntity(entity)
        engine.destroyEntity(entity)
        EntityManager.get().destroy(entity)
    }

    private fun clearWorld() {
        for (p in worldParts) {
            destroyRenderable(p.entity)
            engine.destroyVertexBuffer(p.vb); engine.destroyIndexBuffer(p.ib)
            p.mi?.let { engine.destroyMaterialInstance(it) }
        }
        worldParts.clear()
        for ((_, mi) in sharedMis) engine.destroyMaterialInstance(mi)
        sharedMis.clear()
        destroyRenderable(glowEntity); glowEntity = 0
        glowVb?.let { engine.destroyVertexBuffer(it) }; glowIb?.let { engine.destroyIndexBuffer(it) }; glowVb = null; glowIb = null
        for (e in lampEntities) { scene.removeEntity(e); engine.destroyEntity(e); EntityManager.get().destroy(e) }
        lampEntities.clear()
        if (sunEntity != 0) { scene.removeEntity(sunEntity); engine.destroyEntity(sunEntity); EntityManager.get().destroy(sunEntity); sunEntity = 0 }
        world = null
    }

    private fun box(m: LitMesh): Box {
        val (mn, mx) = m.bounds()
        return Box((mn.x + mx.x) / 2, (mn.y + mx.y) / 2, (mn.z + mx.z) / 2, (mx.x - mn.x) / 2 + 0.05f, (mx.y - mn.y) / 2 + 0.05f, (mx.z - mn.z) / 2 + 0.05f)
    }

    private fun buildWorld(s: WorldRenderSnapshot) {
        clearWorld()
        val citySize = s.widthTiles * TILE_M
        val w = if (preset == WorldPreset.URBAN_OVERCAST) UrbanSceneBuilder.build(s.widthTiles, s.tiles, s.objects)
        else LumonHallBuilder.build(citySize)
        world = w
        val shadows = profile().castShadows
        for (part in w.parts) {
            if (part.mesh.vertexCount == 0) continue
            val spec = WorldMaterials.spec(part.mat)
            val vb = WorldGpu.litVertexBuffer(engine, part.mesh); val ib = WorldGpu.indexBuffer(engine, part.mesh.indices)
            val e = EntityManager.get().create()
            RenderableManager.Builder(1).boundingBox(box(part.mesh))
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib, 0, part.mesh.indices.size)
                .material(0, sharedMi(spec)).castShadows(shadows && part.castShadows).receiveShadows(true)
                .layerMask(0xff, part.layer)
                .build(engine, e)
            scene.addEntity(e)
            worldParts += GpuPart(e, vb, ib, null, spec)
        }
        for (ip in w.instanced) {
            if (ip.offsets.isEmpty() || ip.mesh.vertexCount == 0) continue
            val spec = WorldMaterials.spec(ip.mat)
            for (chunk in ip.offsets.chunked(64)) {
                val mi = pbrMat.createInstance().also { configureMi(it, spec) }
                val arr = FloatArray(chunk.size * 4)
                for ((i, o) in chunk.withIndex()) { arr[i * 4] = o.x; arr[i * 4 + 1] = o.y; arr[i * 4 + 2] = o.z }
                mi.setParameter("instanceOffsets", MaterialInstance.FloatElement.FLOAT4, arr, 0, chunk.size)
                val vb = WorldGpu.litVertexBuffer(engine, ip.mesh); val ib = WorldGpu.indexBuffer(engine, ip.mesh.indices)
                val e = EntityManager.get().create()
                RenderableManager.Builder(1).boundingBox(box(ip.mesh)).culling(false).instances(chunk.size)
                    .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib, 0, ip.mesh.indices.size)
                    .material(0, mi).castShadows(shadows && ip.castShadows).receiveShadows(true)
                    .layerMask(0xff, ip.layer)
                    .build(engine, e)
                engine.transformManager.let { tm -> tm.create(e); tm.setTransform(tm.getInstance(e), Mat4.rotY(ip.yaw)) }
                scene.addEntity(e)
                worldParts += GpuPart(e, vb, ib, mi, spec)
            }
        }
        buildGlows(w)
        buildLights(w)
        Render3dTelemetry.update { it.copy(vertices = w.vertexCount, instances = w.instanceCount, preset = preset.label) }
        Log.i(TAG, "cena ${preset.name}: ${w.parts.size} partes, ${w.instanceCount} instâncias, ${w.vertexCount} vértices")
    }

    private fun buildGlows(w: WorldScene) {
        if (w.glows.isEmpty() || !level.halationSprites) return
        val n = minOf(w.glows.size, 256)
        val pos = FloatArray(n * 12); val uv = FloatArray(n * 8); val col = FloatArray(n * 16); val idx = IntArray(n * 6)
        val corners = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        for (s in 0 until n) {
            val c = w.glows[s]
            for (k in 0 until 4) {
                val v = s * 4 + k
                pos[v * 3] = c.x; pos[v * 3 + 1] = c.y; pos[v * 3 + 2] = c.z
                uv[v * 2] = corners[k * 2]; uv[v * 2 + 1] = corners[k * 2 + 1]
                col[v * 4] = 1f; col[v * 4 + 1] = 0.68f; col[v * 4 + 2] = 0.38f; col[v * 4 + 3] = 1f
            }
            val b = s * 4
            idx[s * 6] = b; idx[s * 6 + 1] = b + 1; idx[s * 6 + 2] = b + 2; idx[s * 6 + 3] = b; idx[s * 6 + 4] = b + 2; idx[s * 6 + 5] = b + 3
        }
        val vb = VertexBuffer.Builder().bufferCount(3).vertexCount(n * 4)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.UV0, 1, VertexBuffer.AttributeType.FLOAT2, 0, 8)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 2, VertexBuffer.AttributeType.FLOAT4, 0, 16)
            .build(engine)
        vb.setBufferAt(engine, 0, WorldGpu.floatBuffer(pos)); vb.setBufferAt(engine, 1, WorldGpu.floatBuffer(uv)); vb.setBufferAt(engine, 2, WorldGpu.floatBuffer(col))
        val ib = WorldGpu.indexBuffer(engine, idx)
        glowVb = vb; glowIb = ib
        glowEntity = EntityManager.get().create()
        RenderableManager.Builder(1).boundingBox(Box(0f, 0f, 0f, 1f, 1f, 1f)).culling(false)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib, 0, idx.size)
            .material(0, glowMi).castShadows(false).receiveShadows(false).priority(7).layerMask(0xff, WorldLayers.WORLD)
            .build(engine, glowEntity)
        scene.addEntity(glowEntity)
    }

    private fun shadowOptions(p: CinematicProfile, interior: Boolean) = LightManager.ShadowOptions().apply {
        mapSize = p.shadowMapSize
        shadowCascades = p.shadowCascades
        screenSpaceContactShadows = p.screenSpaceContactShadows
        stepCount = p.contactShadowSteps
        maxShadowDistance = 0.4f
        shadowFar = if (interior) 60f else 140f
        stable = false
        constantBias = 0.001f
        normalBias = 1.0f
        if (interior) { penumbraScale = 2.2f; penumbraRatioScale = 1.4f }
    }

    private fun buildLights(w: WorldScene) {
        val p = profile()
        val interior = preset == WorldPreset.LUMON_INTERIOR
        sunEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1f, 1f, 1f).intensity(cur[1]).direction(-0.3f, -0.9f, 0.2f)
            .castShadows(p.castShadows).shadowOptions(shadowOptions(p, interior))
            .build(engine, sunEntity)
        scene.addEntity(sunEntity)
        sunShadowKey = "${preset.name}/${level.name}"
        if (!interior) {
            for (lamp in w.lamps.take(64)) {
                val e = EntityManager.get().create()
                LightManager.Builder(LightManager.Type.POINT).color(1.0f, 0.72f, 0.42f).intensity(0f)
                    .position(lamp.x, lamp.y, lamp.z).falloff(16f).castShadows(false).build(engine, e)
                scene.addEntity(e)
                lampEntities += e
            }
        }
    }

    // ================================================================== texturas / IBL

    /** Pede as superfícies do preset (thread de fundo) e o IBL da fase. */
    private fun requestAssets() {
        val ex = worker ?: return
        if (!ProceduralAssetsNative.isLoaded || level == EffectLevel.NONE) return
        val gen = generation
        for (kind in WorldMaterials.surfacesFor(preset)) {
            if (surfaces.containsKey(kind)) continue
            val size = when (kind) {
                ProceduralAssetsNative.WET_ASPHALT, ProceduralAssetsNative.FACADE, ProceduralAssetsNative.CARPET, ProceduralAssetsNative.COFFER ->
                    if (level == EffectLevel.FULL) PRIMARY_TEX else SECONDARY_TEX
                else -> SECONDARY_TEX
            }
            val withEmissive = kind == ProceduralAssetsNative.FACADE || kind == ProceduralAssetsNative.BILLBOARD || kind == ProceduralAssetsNative.COFFER
            ex.execute {
                val d = WorldGpu.generateSurface(kind, size, 0x6172A5L + kind * 7919L) ?: return@execute
                main.post {
                    if (!initialized || gen != generation || surfaces.containsKey(kind)) return@post
                    surfaces[kind] = runCatching { WorldGpu.upload(engine, d, withEmissive) }
                        .getOrElse { Log.w(TAG, "envio da superfície $kind falhou; mantendo cor sólida", it); return@post }
                    refreshMaterials()
                    Log.i(TAG, "superfície $kind ${size}² enviada (${ProceduralAssetsNative.mipLevels(size)} mips)")
                }
            }
        }
    }

    private fun refreshMaterials() {
        for ((id, mi) in sharedMis) configureMi(mi, WorldMaterials.spec(id))
        for (p in worldParts) p.mi?.let { configureMi(it, p.spec) }
    }

    private fun ensureIbl(ph: TimePhase) {
        if (ibls.containsKey(ph)) return
        if (!ProceduralAssetsNative.isLoaded) return
        val overcast = if (ph == TimePhase.INTERIOR) 0f else 0.62f
        // 32² síncrono (≈ poucos ms) para nunca renderizar sem luz ambiente; 256² chega em seguida
        WorldGpu.generateIbl(ph, phaseSunDir(ph), overcast, 32, 16)?.let { d ->
            runCatching { WorldGpu.uploadIbl(engine, d, phaseLight(ph).iblLux) }
                .onSuccess { ibls[ph] = it }.onFailure { Log.w(TAG, "IBL ${ph.label} 32² falhou", it) }
        }
        val ex = worker ?: return
        val face = profile().iblFaceSize
        if (face <= 32 || !iblPending.add(ph)) return
        val gen = generation
        ex.execute {
            val d = WorldGpu.generateIbl(ph, phaseSunDir(ph), overcast, face, 48) ?: return@execute
            main.post {
                iblPending.remove(ph)
                if (!initialized || gen != generation) return@post
                val old = ibls[ph]
                val n = runCatching { WorldGpu.uploadIbl(engine, d, phaseLight(ph).iblLux) }
                    .getOrElse { Log.w(TAG, "IBL ${ph.label} ${face}² falhou", it); return@post }
                ibls[ph] = n
                if (activeIbl === old) applyIbl(n)
                old?.destroy(engine)
                Log.i(TAG, "IBL ${ph.label} ${face}² + SH3 enviado")
            }
        }
    }

    private fun applyIbl(ibl: WorldGpu.Ibl?) {
        activeIbl = ibl
        if (ibl == null) { scene.indirectLight = null; scene.skybox = null; return }
        scene.indirectLight = ibl.light
        scene.skybox = if (preset == WorldPreset.URBAN_OVERCAST) ibl.skybox else null
        updateIblIntensity()
    }

    private fun updateIblIntensity() {
        val ibl = activeIbl ?: return
        // E(+Y) = intensidade · π · SH(+Y) → intensidade que dá a iluminância alvo
        val k = (cur[2] / Math.PI.toFloat()) / ibl.upIrradiance
        ibl.light.intensity = k
    }

    // ================================================================== cidadãos

    private fun buildCitizenGeometry() {
        for (bone in CitizenModel.bones) {
            boneGpu += bone.map { bp ->
                BoneGpu(WorldGpu.litVertexBuffer(engine, bp.mesh), WorldGpu.indexBuffer(engine, bp.mesh.indices), bp.mesh.indices.size, bp.slot, box(bp.mesh))
            }
        }
        val sm = CitizenModel.contactShadowMesh()
        val vb = VertexBuffer.Builder().bufferCount(2).vertexCount(sm.vertexCount)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.UV0, 1, VertexBuffer.AttributeType.FLOAT2, 0, 8)
            .build(engine)
        vb.setBufferAt(engine, 0, WorldGpu.floatBuffer(sm.positions)); vb.setBufferAt(engine, 1, WorldGpu.floatBuffer(sm.uvs))
        shadowVb = vb; shadowIb = WorldGpu.indexBuffer(engine, sm.indices)
    }

    private fun citizenMi(slot: CitizenModel.Slot, rgb: FloatArray): MaterialInstance {
        val key = "${slot.name}:${rgb.contentHashCode()}"
        return citizenMis.getOrPut(key) {
            pbrMat.createInstance().also { mi ->
                mi.setParameter("albedoTex", texWhite, WorldGpu.repeatSampler)
                mi.setParameter("ormTex", texOrm, WorldGpu.repeatSampler)
                mi.setParameter("normalTex", texNormal, WorldGpu.repeatSampler)
                mi.setParameter("emissiveTex", if (slot == CitizenModel.Slot.BADGE) texWhite else texBlack, WorldGpu.repeatSampler)
                mi.setParameter("baseTint", rgb[0], rgb[1], rgb[2], 1f)
                mi.setParameter("uvScale", 1f, 1f, 0f, 0f)
                val rough = when (slot) { CitizenModel.Slot.SKIN -> 0.55f; CitizenModel.Slot.HAIR -> 0.7f; CitizenModel.Slot.SHOES -> 0.45f; else -> 0.85f }
                mi.setParameter("surface", 0f, rough, 0f, 0.1f)
                if (slot == CitizenModel.Slot.BADGE) mi.setParameter("emissiveColor", 0.3f, 0.85f, 1f, 0.8f) else mi.setParameter("emissiveColor", 0f, 0f, 0f, 0f)
                zeroOffsets(mi)
            }
        }
    }

    private fun createCitizen(a: AgentView): CitizenGpu {
        val em = EntityManager.get()
        val tm = engine.transformManager
        val root = em.create()
        tm.create(root)
        val pal = CitizenModel.palette(a.id)
        val badge = floatArrayOf(0.02f, 0.05f, 0.06f)
        val bones = IntArray(CitizenRig.BONES)
        val shadows = profile().castShadows
        for (b in 0 until CitizenRig.BONES) {
            val e = em.create()
            tm.create(e, tm.getInstance(root), Mat4.identity())
            val parts = boneGpu[b]
            val builder = RenderableManager.Builder(parts.size).boundingBox(parts.first().box)
            for ((i, g) in parts.withIndex()) {
                val rgb = when (g.slot) {
                    CitizenModel.Slot.SKIN -> pal.skin; CitizenModel.Slot.SHIRT -> pal.shirt; CitizenModel.Slot.TROUSERS -> pal.trousers
                    CitizenModel.Slot.SHOES -> pal.shoes; CitizenModel.Slot.HAIR -> pal.hair; CitizenModel.Slot.BADGE -> badge
                }
                builder.geometry(i, RenderableManager.PrimitiveType.TRIANGLES, g.vb, g.ib, 0, g.count).material(i, citizenMi(g.slot, rgb))
            }
            builder.culling(false).castShadows(shadows).receiveShadows(true).layerMask(0xff, WorldLayers.CITIZENS).build(engine, e)
            scene.addEntity(e)
            bones[b] = e
        }
        val sh = em.create()
        RenderableManager.Builder(1).boundingBox(Box(0f, 0f, 0f, 0.6f, 0.05f, 0.6f))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, shadowVb!!, shadowIb!!, 0, 6)
            .material(0, shadowMi).castShadows(false).receiveShadows(false).priority(5).layerMask(0xff, WorldLayers.CITIZENS)
            .build(engine, sh)
        tm.create(sh)
        scene.addEntity(sh)
        return CitizenGpu(root, bones, sh)
    }

    private fun destroyCitizen(c: CitizenGpu) {
        for (b in c.bones) destroyRenderable(b)
        destroyRenderable(c.shadow)
        engine.transformManager.destroy(c.root)
        EntityManager.get().destroy(c.root)
    }

    private fun setCitizenHidden(c: CitizenGpu, hidden: Boolean) {
        if (c.hidden == hidden) return
        c.hidden = hidden
        val rm = engine.renderableManager
        val layer = if (hidden) WorldLayers.HIDDEN else WorldLayers.CITIZENS
        for (b in c.bones) rm.setLayerMask(rm.getInstance(b), 0xff, layer)
        rm.setLayerMask(rm.getInstance(c.shadow), 0xff, layer)
    }

    // ================================================================== pós-processamento

    private fun initPostPass() {
        postScene = engine.createScene()
        postView = engine.createView()
        postCamera = engine.createCamera(EntityManager.get().create())
        postView.setScene(postScene)
        postView.setCamera(postCamera)
        postView.setPostProcessingEnabled(false)
        postView.setShadowingEnabled(false)
        postMi = postMat.createInstance()
        val vb = VertexBuffer.Builder().bufferCount(2).vertexCount(3)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.UV0, 1, VertexBuffer.AttributeType.FLOAT2, 0, 8)
            .build(engine)
        vb.setBufferAt(engine, 0, WorldGpu.floatBuffer(floatArrayOf(-1f, -1f, 0f, 3f, -1f, 0f, -1f, 3f, 0f)))
        vb.setBufferAt(engine, 1, WorldGpu.floatBuffer(floatArrayOf(0f, 0f, 2f, 0f, 0f, 2f)))
        val ib = WorldGpu.indexBuffer(engine, intArrayOf(0, 1, 2))
        postVb = vb; postIb = ib
        postEntity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 0f, 0f, 1f, 1f, 1f)).culling(false)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib, 0, 3)
            .material(0, postMi).castShadows(false).receiveShadows(false)
            .build(engine, postEntity)
        postScene.addEntity(postEntity)
    }

    /** (bloom do bright-pass, halação, densidade das sombras, grão Kodak 500T) por nível. */
    private fun postStrength(l: EffectLevel): FloatArray? = when (l) {
        // o bloom principal é o do Filament (perfil); aqui só um véu residual para não dobrar
        EffectLevel.FULL -> floatArrayOf(0.06f, 0.15f, 0.36f, 0.045f)
        EffectLevel.REDUCED -> floatArrayOf(0.05f, 0.12f, 0.32f, 0.04f)
        EffectLevel.MINIMAL -> floatArrayOf(0f, 0.10f, 0.28f, 0.035f)
        EffectLevel.NONE -> null
    }

    private fun configurePost() {
        val strength = postStrength(level)
        renderTarget?.let { view.setRenderTarget(null); engine.destroyRenderTarget(it) }; renderTarget = null
        rtColor?.let { engine.destroyTexture(it) }; rtColor = null
        rtDepth?.let { engine.destroyTexture(it) }; rtDepth = null
        postActive = false
        if (strength == null) return
        val ok = runCatching {
            val color = Texture.Builder().width(widthPx).height(heightPx).levels(1).sampler(Texture.Sampler.SAMPLER_2D)
                .format(Texture.InternalFormat.RGBA8).usage(Texture.Usage.COLOR_ATTACHMENT or Texture.Usage.SAMPLEABLE).build(engine)
            val depth = Texture.Builder().width(widthPx).height(heightPx).levels(1).sampler(Texture.Sampler.SAMPLER_2D)
                .format(Texture.InternalFormat.DEPTH24).usage(Texture.Usage.DEPTH_ATTACHMENT).build(engine)
            rtColor = color; rtDepth = depth
            renderTarget = RenderTarget.Builder()
                .texture(RenderTarget.AttachmentPoint.COLOR, color)
                .texture(RenderTarget.AttachmentPoint.DEPTH, depth)
                .build(engine)
            view.setRenderTarget(renderTarget)
            postView.setViewport(Viewport(0, 0, widthPx, heightPx))
            postMi.setParameter("sceneColor", color, TextureSampler(TextureSampler.MinFilter.LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE))
            postMi.setParameter("texelSize", 1f / widthPx, 1f / heightPx)
            postMi.setParameter("strength", strength[0], strength[1], strength[2], strength[3])
            postMi.setParameter("brightKnee", BRIGHT_KNEE_LDR, 1.0f)
            true
        }.getOrElse { Log.w(TAG, "girassol_dehancer_post indisponível; seguindo sem a passada analógica", it); false }
        postActive = ok
        if (!ok) view.setRenderTarget(null)
    }

    private fun renderViews() {
        if (postActive) {
            postMi.setParameter("time", (System.nanoTime() - startNs) / 1e9f)
            renderer.render(view)
            renderer.render(postView)
        } else {
            renderer.render(view)
        }
    }

    private fun grading(p: WorldPreset): ColorGrading = gradings.getOrPut(p) {
        val prof = CinematicProfile.of(p)
        ColorGrading.Builder()
            .toneMapper(ToneMapper.ACES())
            .contrast(prof.contrast)
            .saturation(prof.saturation)
            .shadowsMidtonesHighlights(prof.shadowsTint, floatArrayOf(1f, 1f, 1f, 0f), floatArrayOf(1f, 1f, 1f, 0f), floatArrayOf(0f, 0.333f, 0.55f, 1f))
            .build(engine)
    }

    /** Aplica o [CinematicProfile] efetivo (preset × nível de efeito) à View. */
    private fun applyEffectLevel(l: EffectLevel) {
        val p = CinematicProfile.forLevel(preset, l)
        view.setShadowingEnabled(p.castShadows)
        if (!l.postProcessing) {
            view.setPostProcessingEnabled(false)
            return
        }
        view.setPostProcessingEnabled(true)
        view.setAntiAliasing(if (p.fxaa) View.AntiAliasing.FXAA else View.AntiAliasing.NONE)
        view.setTemporalAntiAliasingOptions(View.TemporalAntiAliasingOptions().apply { enabled = p.taa; feedback = 0.12f; filterWidth = 1.0f })
        view.setScreenSpaceReflectionsOptions(View.ScreenSpaceReflectionsOptions().apply {
            enabled = p.ssrEnabled; thickness = p.ssrThickness; bias = p.ssrBias; maxDistance = p.ssrMaxDistance; stride = p.ssrStride
        })
        view.setAmbientOcclusionOptions(View.AmbientOcclusionOptions().apply {
            enabled = p.ssaoEnabled; resolution = p.ssaoResolution; bilateralThreshold = p.ssaoBilateralThreshold
            radius = p.ssaoRadius; intensity = p.ssaoIntensity
            quality = if (p.ssaoHighQuality) View.QualityLevel.HIGH else View.QualityLevel.LOW
            lowPassFilter = if (p.ssaoHighQuality) View.QualityLevel.HIGH else View.QualityLevel.MEDIUM
            upsampling = if (p.ssaoHighQuality) View.QualityLevel.HIGH else View.QualityLevel.LOW
        })
        view.setShadowType(if (l == EffectLevel.FULL) View.ShadowType.DPCF else View.ShadowType.PCF)
        view.setDithering(if (l.temporalDithering) View.Dithering.TEMPORAL else View.Dithering.NONE)
        view.setBloomOptions(View.BloomOptions().apply {
            enabled = p.bloomEnabled; strength = p.bloomStrength; levels = p.bloomLevels; resolution = p.bloomResolution
            threshold = true; highlight = p.effectiveBloomHighlight; lensFlare = false; starburst = false
        })
        view.setVignetteOptions(View.VignetteOptions().apply {
            enabled = l.vignette; midPoint = 0.48f; roundness = 0.6f; feather = 0.62f; color = floatArrayOf(0f, 0f, 0f, 1f)
        })
        applyFog(p)
        view.setDynamicResolutionOptions(View.DynamicResolutionOptions().apply { enabled = false; minScale = p.resolutionScale; maxScale = p.resolutionScale })
        view.setColorGrading(if (l.colorGrading) grading(preset) else null)
    }

    private fun applyFog(p: CinematicProfile = profile()) {
        if (!level.postProcessing) return
        view.setFogOptions(View.FogOptions().apply {
            enabled = p.fog
            distance = 18f; cutOffDistance = 1e6f; maximumOpacity = 0.82f
            height = 0f; heightFalloff = 0.06f; density = cur[7]
            inScatteringStart = 0f; inScatteringSize = if (phase == TimePhase.NIGHT) -1f else 40f
            fogColorFromIbl = activeIbl != null
            color = floatArrayOf(0.42f, 0.46f, 0.48f)
        })
    }

    /** Troca o nível em runtime (fallback por FPS ou nível forçado). */
    fun setEffectLevel(l: EffectLevel) {
        if (!initialized || l == level) return
        level = l
        applyEffectLevel(l)
        configurePost()
        lastSnapshot?.let { buildWorld(it); rebuildCitizens() }
        fpsGovernor.reset()
        Render3dTelemetry.update { it.copy(effectLevel = l.label) }
    }

    private fun rebuildCitizens() {
        for ((_, c) in citizens) destroyCitizen(c)
        citizens.clear()
        lastSnapshot?.let { updateCitizens(it) }
    }

    private fun loadMaterial(asset: String): Material {
        val bytes = context.assets.open(asset).use { it.readBytes() }
        val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
        buf.flip()
        return Material.Builder().payload(buf, bytes.size).build(engine)
    }

    // ================================================================== estado do mundo

    /** Recebe o snapshot imutável publicado pelo SimulationHub (thread principal). */
    fun update(s: WorldRenderSnapshot) {
        if (!initialized) return
        lastSnapshot = s
        val wantPreset = WorldViewState.preset.value
        val key = s.widthTiles.toLong() * 1_000_003L + s.tiles.contentHashCode() * 31L + s.objects.size + wantPreset.ordinal * 7L
        if (key != sceneKey || wantPreset != preset) {
            val first = sceneKey == Long.MIN_VALUE
            val presetChanged = wantPreset != preset
            preset = wantPreset
            sceneKey = key
            if (presetChanged) { generation++; applyEffectLevel(level) }
            buildWorld(s)
            if (first || presetChanged) frameCamera(s)
            requestAssets()
        }
        dayTarget = SunCycle.dayFraction(s.tick)
        if (dayPhase.isNaN()) dayPhase = dayTarget
        updateCitizens(s)
    }

    private fun frameCamera(s: WorldRenderSnapshot) {
        val w = world ?: return
        val cx = (w.mapX(s.widthTiles * TILE_M / 2)) / TILE_M
        val cz = (w.mapZ(s.widthTiles * TILE_M / 2)) / TILE_M
        if (preset == WorldPreset.URBAN_OVERCAST) {
            camera.boundsW = s.widthTiles.toFloat()
            camera.frameCity(s.widthTiles)
            tiltDist = 26f
            if (s.agents.isNotEmpty() && s.agents.size <= 6) {
                camera.targetX = s.agents.map { it.fx }.average().toFloat()
                camera.targetZ = s.agents.map { it.fz }.average().toFloat()
                camera.distance = 18f.coerceIn(camera.minDistance, camera.maxDistance)
            }
        } else {
            camera.boundsW = LumonHallBuilder.HALL_W / TILE_M
            camera.targetX = cx; camera.targetZ = cz
            camera.distance = 14f
            tiltDist = 13f
        }
    }

    private fun updateCitizens(s: WorldRenderSnapshot) {
        val newTargets = HashMap<String, FloatArray>(s.agents.size * 2)
        for (a in s.agents) {
            newTargets[a.id] = floatArrayOf(a.fx * TILE_M, 0f, a.fz * TILE_M)
            if (!citizens.containsKey(a.id)) {
                citizens[a.id] = createCitizen(a)
                if (!visual.containsKey(a.id)) visual[a.id] = floatArrayOf(a.fx * TILE_M, Float.NaN, a.fz * TILE_M)
            }
            agentViews[a.id] = a
        }
        val gone = citizens.keys.filter { it !in newTargets }
        for (id in gone) {
            citizens.remove(id)?.let { destroyCitizen(it) }
            visual.remove(id); rigStates.remove(id); agentViews.remove(id); walking.remove(id); scenePos.remove(id)
        }
        targets = newTargets
        Render3dTelemetry.update { it.copy(citizens = citizens.size) }
    }

    /** Foco animado num cidadão (toque no balão, atalho da cápsula); no POV troca o cidadão. */
    fun focusCitizen(id: String): Boolean {
        if (cameraMode == CameraMode.FIRST_PERSON_POV) { WorldViewState.setPovCitizen(id); return citizens.containsKey(id) }
        val p = scenePos[id] ?: return false
        camera.focusOn(p.x / TILE_M, p.z / TILE_M, if (preset == WorldPreset.URBAN_OVERCAST) 9f else 7f)
        return true
    }

    // ================================================================== câmera e gestos

    /** Um dedo: Órbita orbita; Inclinação gira em torno do alvo; POV olha ao redor. */
    fun onDrag(dx: Float, dy: Float, viewH: Float) = when (cameraMode) {
        CameraMode.FIRST_PERSON_POV -> fpc.drag(dx, dy, viewH)
        CameraMode.TILT -> { camera.yaw -= dx * 3.2f / viewH.coerceAtLeast(1f) }
        else -> camera.orbit(dx, dy, viewH)
    }

    /** Dois dedos: pinça, giro e deslocamento. */
    fun onPinch(zoom: Float, rotationDeg: Float, panX: Float, panY: Float, viewH: Float) {
        when (cameraMode) {
            CameraMode.FIRST_PERSON_POV -> fpc.drag(panX, panY, viewH)
            CameraMode.TILT -> {
                if (zoom > 0f) tiltDist = (tiltDist / zoom).coerceIn(4f, 60f)
                camera.rotate(rotationDeg); camera.pan(panX, panY, viewH)
            }
            else -> { camera.zoom(zoom); camera.rotate(rotationDeg); camera.pan(panX, panY, viewH) }
        }
    }

    private fun choosePovCitizen(): String? {
        val want = WorldViewState.povCitizen.value
        if (want != null && citizens.containsKey(want)) return want
        val tx = camera.targetX * TILE_M; val tz = camera.targetZ * TILE_M
        val best = scenePos.minByOrNull { (_, p) -> (p.x - tx) * (p.x - tx) + (p.z - tz) * (p.z - tz) }?.key
        if (best != null) WorldViewState.setPovCitizen(best)
        return best
    }

    private fun updateCamera(dt: Float) {
        val w = world
        val mode = WorldViewState.camera.value.let { if (it == CameraMode.TOP_2D) CameraMode.ORBIT else it }
        if (mode != cameraMode) { if (mode == CameraMode.FIRST_PERSON_POV) fpc.reset(); cameraMode = mode }
        val ceiling = w?.ceilingY
        val newPov = if (mode == CameraMode.FIRST_PERSON_POV) choosePovCitizen() else null
        if (newPov != povId) {
            povId?.let { id -> citizens[id]?.let { setCitizenHidden(it, false) } }
            newPov?.let { id -> citizens[id]?.let { setCitizenHidden(it, true) } }
            if (newPov != null) fpc.reset()
            povId = newPov
        }
        camera.animate(dt)
        pose = when {
            mode == CameraMode.FIRST_PERSON_POV && newPov != null && scenePos[newPov] != null -> {
                val p = scenePos[newPov]!!
                val st = rigStates[newPov]
                val a = agentViews[newPov]
                val h = com.enhikyy.girassol.core.world.CitizenGrowth.proportions(a?.maturity ?: 1f).height
                fpc.pose(p, st?.yaw ?: 0f, h, st?.phase ?: 0f, walking[newPov] ?: 0f, ceiling)
            }
            mode == CameraMode.TILT -> {
                val pitch = Math.toRadians(if (preset == WorldPreset.URBAN_OVERCAST) 9.0 else 6.0).toFloat()
                val tgt = V3(camera.targetX * TILE_M, 1.5f, camera.targetZ * TILE_M)
                var eye = tgt + V3(sin(camera.yaw) * cos(pitch), sin(pitch), cos(camera.yaw) * cos(pitch)) * tiltDist
                eye = V3(eye.x, eye.y.coerceIn(1.7f, (ceiling ?: 1e9f) - 0.35f), eye.z)
                CameraPose(eye, tgt, 50f, near = 0.1f, far = 700f)
            }
            else -> CameraPose(camera.eye() * TILE_M, camera.target() * TILE_M, camera.fovDeg, near = 0.3f, far = 900f)
        }
        val e = pose.eye; val t = pose.target
        fCamera.lookAt(e.x.toDouble(), e.y.toDouble(), e.z.toDouble(), t.x.toDouble(), t.y.toDouble(), t.z.toDouble(), 0.0, 1.0, 0.0)
        fCamera.setProjection(pose.fovDeg.toDouble(), widthPx.toDouble() / heightPx, pose.near.toDouble(), pose.far.toDouble(), Camera.Fov.VERTICAL)
        // teto visível só com o olho abaixo dele (órbita de cima vê o salão como maquete)
        val showCeiling = ceiling == null || e.y < ceiling + 0.3f
        view.setVisibleLayers(0xff, WorldLayers.WORLD or WorldLayers.CITIZENS or (if (showCeiling) WorldLayers.CEILING else 0))
    }

    /** Pose atual da câmera (órbita, inclinação ou POV). */
    fun currentPose(): CameraPose = pose

    /** Raycast em tela com tolerância de ~24 dp (ignora o dono do POV). */
    fun pick(sx: Float, sy: Float): String? {
        val centers = scenePos.filterKeys { it != povId }.mapValues { (_, p) -> V3(p.x, p.y + 1.0f, p.z) }
        val id = ScreenPick.pick(pose, sx, sy, widthPx.toFloat(), heightPx.toFloat(), centers, 24f * context.resources.displayMetrics.density)
        if (id == null) Log.d(TAG, "pick vazio: viewport ${widthPx}x$heightPx, ${centers.size} cidadãos")
        return id
    }

    /** Posições projetadas (px) da cabeça de cada cidadão visível — balões de fala e testes. */
    fun projectAgents(w: Float, h: Float): Map<String, Pair<Float, Float>> {
        val out = HashMap<String, Pair<Float, Float>>()
        for ((id, p) in scenePos) {
            if (id == povId) continue
            if ((p - pose.eye).length() > 260f) continue
            val s = pose.project(V3(p.x, p.y + 1.25f, p.z), w, h) ?: continue
            if (s.first in 0f..w && s.second in 0f..h) out[id] = s
        }
        return out
    }

    /** Posições na cena (metros) dos cidadãos. */
    fun agentPositions(): Map<String, V3> = HashMap(scenePos)
    fun viewSize(): Pair<Float, Float> = widthPx.toFloat() to heightPx.toFloat()

    // ================================================================== quadro

    private fun lerpTo(i: Int, v: Float, k: Float) { cur[i] += (v - cur[i]) * k }

    /** Avança luz/exposição/emissivos rumo à fase atual. [k] = fração de aproximação (1 = imediato). */
    private fun stepLighting(k: Float) {
        if (!dayPhase.isNaN()) {
            var d = dayTarget - dayPhase
            if (d > 0.5f) d -= 1f; if (d < -0.5f) d += 1f
            dayPhase = (dayPhase + d * k.coerceAtMost(1f) + 1f) % 1f
        }
        val ph = TimePhase.of(if (dayPhase.isNaN()) 0.4f else dayPhase, preset)
        if (ph != phase || activeIbl?.phase != ph) {
            phase = ph
            ensureIbl(ph)
            applyIbl(ibls[ph])
            applyFog()
            Render3dTelemetry.update { it.copy(phase = ph.label) }
        }
        val pl = phaseLight(ph)
        val kk = k.coerceIn(0f, 1f)
        lerpTo(0, pl.ev100, kk); lerpTo(1, pl.sunLux, kk); lerpTo(2, pl.iblLux, kk); lerpTo(3, pl.lampLumens, kk)
        lerpTo(4, pl.windows, kk); lerpTo(5, pl.billboard, kk); lerpTo(6, pl.streetLamp, kk); lerpTo(7, pl.fogDensity, kk)
        // exposição física: f/1, ISO 100 → t = 2^−EV100
        fCamera.setExposure(1f, 2.0.pow(-cur[0].toDouble()).toFloat(), 100f)
        val lm = engine.lightManager
        if (sunEntity != 0) {
            val li = lm.getInstance(sunEntity)
            val dir = when (ph) {
                TimePhase.INTERIOR -> floatArrayOf(0.18f, 1f, 0.12f)
                TimePhase.NIGHT -> phaseSunDir(TimePhase.NIGHT)
                else -> SunCycle.at(dayPhase.takeIf { !it.isNaN() } ?: 0.4f).dir
            }
            val n = sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2])
            lm.setDirection(li, -dir[0] / n, -dir[1] / n, -dir[2] / n)
            lm.setColor(li, pl.sunColor[0], pl.sunColor[1], pl.sunColor[2])
            lm.setIntensity(li, cur[1])
        }
        for (e in lampEntities) lm.setIntensity(lm.getInstance(e), cur[3])
        updateIblIntensity()
        for ((id, mi) in sharedMis) {
            val spec = WorldMaterials.spec(id)
            if (spec.emissiveDriver != EmissiveDriver.NONE) { val c = spec.emissive!!; mi.setParameter("emissiveColor", c[0], c[1], c[2], emissiveIntensity(spec.emissiveDriver)) }
        }
        for (p in worldParts) if (p.mi != null && p.spec.emissiveDriver != EmissiveDriver.NONE) {
            val c = p.spec.emissive!!; p.mi.setParameter("emissiveColor", c[0], c[1], c[2], emissiveIntensity(p.spec.emissiveDriver))
        }
        glowMi.setParameter("intensity", 0.10f + 0.30f * (cur[6] / 3f))
        shadowMi.setParameter("opacity", if (ph == TimePhase.NIGHT) 0.35f else 0.55f)
        if (preset == WorldPreset.LUMON_INTERIOR || activeIbl == null)
            renderer.setClearOptions(Renderer.ClearOptions().apply { clear = true; clearColor = doubleArrayOf(0.0, 0.0, 0.0, 1.0) })
    }

    private fun animateCitizens(dt: Float) {
        val w = world ?: return
        val tm = engine.transformManager
        val k = (dt * 6f).coerceAtMost(1f)
        val tps = SimulationHub.status.value.effectiveTps.toFloat()
        val now = System.nanoTime()
        val eye = pose.eye
        for ((id, c) in citizens) {
            val v = visual[id] ?: continue
            val t = targets[id] ?: continue
            v[0] += (t[0] - v[0]) * k; v[2] += (t[2] - v[2]) * k
            val sx = w.mapX(v[0]); val sz = w.mapZ(v[2])
            val floor = w.floorAt(sx, sz)
            v[1] = if (v[1].isNaN()) floor else v[1] + (floor - v[1]) * (dt * 10f).coerceAtMost(1f)
            val p = V3(sx, v[1], sz)
            scenePos[id] = p
            val a = agentViews[id]
            val st = rigStates.getOrPut(id) { CitizenRig.State() }
            val far = (p - eye).length() > CITIZEN_FAR_M * (if (preset == WorldPreset.URBAN_OVERCAST) 2.5f else 1.5f)
            val inp = CitizenRig.Input(maturity = a?.maturity ?: 1f, vx = a?.vx ?: 0f, vz = a?.vz ?: 0f, ticksPerSecond = tps,
                typing = a?.typing == true, amplitude = VoiceAmplitude.level(id, now), dt = dt)
            val speed = sqrt(inp.vx * inp.vx + inp.vz * inp.vz)
            walking[id] = (speed * tps / 0.6f).coerceIn(0f, 1f)
            val root = CitizenRig.pose(st, inp, boneScratch)
            val m = Mat4.mul(Mat4.translate(p.x, p.y, p.z), Mat4.mul(Mat4.scale(CitizenModel.SCALE, CitizenModel.SCALE, CitizenModel.SCALE), root))
            tm.setTransform(tm.getInstance(c.root), m)
            if (!far || id == povId) for (b in 0 until CitizenRig.BONES) {
                System.arraycopy(boneScratch, b * 16, boneM, 0, 16)
                tm.setTransform(tm.getInstance(c.bones[b]), boneM)
            }
            val hs = com.enhikyy.girassol.core.world.CitizenGrowth.proportions(a?.maturity ?: 1f).height
            val r = 0.95f * hs * CitizenModel.SCALE / 1.35f
            tm.setTransform(tm.getInstance(c.shadow), Mat4.mul(Mat4.translate(p.x, p.y + 0.015f, p.z), Mat4.scale(r, 1f, r)))
        }
    }
    private val boneM = FloatArray(16)

    private fun publishPov() {
        val id = povId
        val s = lastSnapshot
        if (id == null || s == null || cameraMode != CameraMode.FIRST_PERSON_POV) { WorldViewState.publishPov(null); return }
        val a = agentViews[id] ?: return
        val v = visual[id] ?: return
        val st = rigStates[id]
        val dIdx = ((a.fx / 12f).toInt()) to ((a.fz / 12f).toInt())
        val district = s.districts.firstOrNull { it.dx == dIdx.first && it.dz == dIdx.second }
        val sector = district?.type?.label ?: "Periferia"
        val neigh = ArrayList<Pair<Float, Float>>()
        for ((oid, ov) in visual) if (oid != id) {
            val dx = ov[0] - v[0]; val dz = ov[2] - v[2]
            if (dx * dx + dz * dz < 48f * 48f) neigh += dx to dz
        }
        val heading = Math.toDegrees(((st?.yaw ?: 0f) + fpc.yawOffset).toDouble()).toFloat()
        WorldViewState.publishPov(PovTelemetry(id, a.name, v[0], v[2], ((heading % 360f) + 360f) % 360f, sector,
            a.action?.name?.lowercase()?.replace('_', ' ') ?: "ocioso", a.thought, neigh, s.widthTiles * TILE_M))
    }

    private fun renderFrame(frameTimeNanos: Long, dt: Float) {
        // troca de ambiente pedida pela UI (sem esperar o próximo snapshot)
        if (WorldViewState.preset.value != preset) lastSnapshot?.let { update(it) }
        updateCamera(dt)
        animateCitizens(dt)
        stepLighting(dt * 1.5f)
        if (frameTimeNanos - povPublishNs > 100_000_000L) { povPublishNs = frameTimeNanos; publishPov() }
        val sc = swapChain ?: return
        if (!uiHelper.isReadyToRender) return
        if (renderer.beginFrame(sc, frameTimeNanos)) {
            renderViews()
            renderer.endFrame()
            fpsFrames++
        }
        if (fpsWindowStart == 0L) fpsWindowStart = frameTimeNanos
        val span = frameTimeNanos - fpsWindowStart
        if (span >= 1_000_000_000L) {
            val fps = fpsFrames / (span / 1e9)
            fpsFrames = 0; fpsWindowStart = frameTimeNanos
            Render3dTelemetry.update { it.copy(active = true, fps = fps, effectLevel = level.label, camera = cameraMode.label, phase = phase.label, preset = preset.label) }
            Log.i(TAG, "fps=%.1f nivel=%s ambiente=%s camera=%s fase=%s cidadaos=%d".format(fps, level.name, preset.name, cameraMode.name, phase.name, citizens.size))
            if (fpsGovernorEnabled && fpsGovernor.onSecond(fps)) onFpsLow?.invoke(level)
        }
        onFrame?.invoke()
    }

    fun resume() {
        if (!initialized || running || headless) return
        running = true; lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun pause() {
        running = false
        if (headless) return
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        Render3dTelemetry.update { it.copy(fps = 0.0) }
    }

    fun destroy() {
        pause()
        if (!initialized) return
        initialized = false
        generation++
        if (!headless) { uiHelper.detach(); WorldViewState.publishPov(null) }
        runCatching { worker?.shutdownNow() }
        for ((_, c) in citizens) destroyCitizen(c)
        citizens.clear(); visual.clear(); rigStates.clear(); agentViews.clear(); scenePos.clear()
        clearWorld()
        for ((_, mi) in citizenMis) engine.destroyMaterialInstance(mi)
        citizenMis.clear()
        for (bone in boneGpu) for (g in bone) { engine.destroyVertexBuffer(g.vb); engine.destroyIndexBuffer(g.ib) }
        boneGpu.clear()
        shadowVb?.let { engine.destroyVertexBuffer(it) }; shadowIb?.let { engine.destroyIndexBuffer(it) }
        scene.indirectLight = null; scene.skybox = null
        for ((_, i) in ibls) i.destroy(engine)
        ibls.clear(); activeIbl = null
        for ((_, s) in surfaces) s.destroy(engine)
        surfaces.clear()
        view.setRenderTarget(null)
        renderTarget?.let { engine.destroyRenderTarget(it) }; rtColor?.let { engine.destroyTexture(it) }; rtDepth?.let { engine.destroyTexture(it) }
        if (postEntity != 0) { postScene.removeEntity(postEntity); engine.destroyEntity(postEntity); EntityManager.get().destroy(postEntity); postEntity = 0 }
        postVb?.let { engine.destroyVertexBuffer(it) }; postIb?.let { engine.destroyIndexBuffer(it) }
        engine.destroyMaterialInstance(postMi); engine.destroyMaterial(postMat)
        engine.destroyView(postView); engine.destroyScene(postScene)
        postCamera.entity.let { engine.destroyCameraComponent(it); EntityManager.get().destroy(it) }
        engine.destroyMaterialInstance(glowMi); engine.destroyMaterialInstance(shadowMi)
        engine.destroyMaterial(pbrMat); engine.destroyMaterial(glowMat); engine.destroyMaterial(shadowMat)
        engine.destroyTexture(texWhite); engine.destroyTexture(texBlack); engine.destroyTexture(texOrm); engine.destroyTexture(texNormal)
        for ((_, g) in gradings) engine.destroyColorGrading(g)
        gradings.clear()
        swapChain?.let { engine.destroySwapChain(it) }; swapChain = null
        engine.destroyView(view); engine.destroyScene(scene); engine.destroyRenderer(renderer)
        val camEntity = fCamera.entity
        engine.destroyCameraComponent(camEntity); EntityManager.get().destroy(camEntity)
        engine.destroy()
        FilamentMemory.add(-nativeFootprint); nativeFootprint = 0L
        if (!headless) Render3dTelemetry.set(Render3dStats(active = false))
    }
}

/** Emulador/GL por software (goldfish/ranchu, SwiftShader): sem pós-processamento do Filament. */
internal fun isSoftwareGl(): Boolean {
    val hw = android.os.Build.HARDWARE.lowercase()
    val fp = android.os.Build.FINGERPRINT.lowercase()
    val product = android.os.Build.PRODUCT.lowercase()
    return hw == "ranchu" || hw == "goldfish" || fp.startsWith("generic") || fp.contains("emulator") ||
        product.contains("sdk_gphone") || product.startsWith("sdk") || product.contains("emulator")
}
