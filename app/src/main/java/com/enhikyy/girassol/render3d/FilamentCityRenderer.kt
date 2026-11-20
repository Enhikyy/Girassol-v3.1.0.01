package com.enhikyy.girassol.render3d

import android.content.Context
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.enhikyy.girassol.core.world.ActionType
import com.enhikyy.girassol.core.world.WorldRenderSnapshot
import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.SwapChainFlags
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

/** Telemetria do viewport 3D, lida pela faixa de status e pela aba Sistema. */
data class Render3dStats(val active: Boolean = false, val fps: Double = 0.0, val vertices: Int = 0, val citizens: Int = 0, val error: String = "",
    val effectLevel: String = "")

object Render3dTelemetry {
    private val _stats = MutableStateFlow(Render3dStats())
    val stats: StateFlow<Render3dStats> = _stats.asStateFlow()
    internal fun set(s: Render3dStats) { _stats.value = s }
    internal fun update(f: (Render3dStats) -> Render3dStats) { _stats.value = f(_stats.value) }
}

/**
 * Viewport 3D Google Filament da aba Mundo. Toda chamada ao Engine ocorre na thread
 * principal (Choreographer). Limitado a ~30 FPS para operação contínua sem estrangulamento
 * térmico; resolução dinâmica reduz a escala de renderização se a GPU não acompanhar.
 */
class FilamentCityRenderer(
    private val context: Context,
    private val surfaceView: SurfaceView?,
    initialLevel: EffectLevel = EffectLevel.FULL
) {
    companion object {
        private const val TAG = "GirassolFilament"
        private const val TARGET_FRAME_NS = 1_000_000_000L / 30
        @Volatile private var filamentReady = false
        fun ensureFilament(): Boolean {
            if (!filamentReady) filamentReady = runCatching { Filament.init(); true }.getOrElse { Log.w(TAG, "Filament indisponível", it); false }
            return filamentReady
        }
    }

    /** Nível de efeito ativo (ver [EffectLevel]); pode descer em runtime via [setEffectLevel]. */
    var level: EffectLevel = initialLevel
        private set
    private var headless = false
    private var lastSnapshot: WorldRenderSnapshot? = null
    private val fpsGovernor = FpsGovernor()
    /** Chamado quando o FPS fica abaixo do piso por tempo prolongado (thread principal). */
    var onFpsLow: ((EffectLevel) -> Unit)? = null

    val camera = OrbitCamera()
    private lateinit var engine: Engine
    private lateinit var renderer: Renderer
    private lateinit var scene: Scene
    private lateinit var view: View
    private lateinit var fCamera: Camera
    private lateinit var colorGrading: ColorGrading
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private var swapChain: SwapChain? = null
    private lateinit var cityMat: Material
    private lateinit var glowMat: Material
    private lateinit var cityMi: MaterialInstance
    private lateinit var glowMi: MaterialInstance

    private var cityEntity = 0; private var cityVb: VertexBuffer? = null; private var cityIb: IndexBuffer? = null
    private var glowEntity = 0; private var glowVb: VertexBuffer? = null; private var glowIb: IndexBuffer? = null
    private lateinit var agentVb: VertexBuffer
    private lateinit var agentIb: IndexBuffer
    private var agentIndexCount = 0
    private val agentEntities = HashMap<String, Pair<Int, MaterialInstance>>()
    private val visual = HashMap<String, FloatArray>()
    private var targets: Map<String, FloatArray> = emptyMap()
    private var meshKey = Long.MIN_VALUE
    private var vertexCount = 0
    private var widthPx = 1; private var heightPx = 1
    private var night = false
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
        val sv = checkNotNull(surfaceView) { "init() exige SurfaceView; use initHeadless()" }
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
                updateProjection()
            }
        })
        uiHelper.attachTo(sv)
        initialized = true
        Render3dTelemetry.set(Render3dStats(active = true, effectLevel = level.label))
    }

    /** Modo fora da tela (sonda de GPU): swap chain headless com os mesmos materiais e pós. */
    fun initHeadless(width: Int, height: Int) {
        headless = true
        initCore()
        swapChain = engine.createSwapChain(width, height, SwapChainFlags.CONFIG_DEFAULT)
        widthPx = width; heightPx = height
        view.setViewport(Viewport(0, 0, width, height))
        updateProjection()
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
            night = done % 2 == 1
            applyDayNight()
            val eye = camera.eye(); val tgt = camera.target()
            fCamera.lookAt(eye.x.toDouble(), eye.y.toDouble(), eye.z.toDouble(), tgt.x.toDouble(), tgt.y.toDouble(), tgt.z.toDouble(), 0.0, 1.0, 0.0)
            if (renderer.beginFrame(sc, System.nanoTime())) {
                renderer.render(view)
                renderer.endFrame()
                done++
            }
            Thread.sleep(8)
        }
        engine.flushAndWait()
        return done
    }

    private fun initCore() {
        check(ensureFilament()) { "biblioteca Filament não carregou" }
        engine = Engine.create()
        renderer = engine.createRenderer()
        scene = engine.createScene()
        view = engine.createView()
        fCamera = engine.createCamera(EntityManager.get().create())
        view.setScene(scene)
        view.setCamera(fCamera)
        renderer.setClearOptions(Renderer.ClearOptions().apply { clear = true; clearColor = doubleArrayOf(0.0, 0.0, 0.0, 1.0) })
        fCamera.setExposure(1.0f)
        applyEffectLevel(level)
        cityMat = loadMaterial("materials/city.filamat")
        glowMat = loadMaterial("materials/glow.filamat")
        cityMi = cityMat.createInstance().apply { setParameter("tint", 1f, 1f, 1f, 1f); setParameter("emissiveBoost", 1.2f) }
        glowMi = glowMat.createInstance().apply { setParameter("size", 0.55f); setParameter("intensity", 1.0f) }
        val agentMesh = CityMeshBuilder.citizenMesh()
        agentVb = colorVertexBuffer(agentMesh.positions, agentMesh.colors)
        agentIb = indexBuffer(agentMesh.indices)
        agentIndexCount = agentMesh.indices.size
    }

    /**
     * Pós-processamento "Kodak 500T / 70 mm" graduado por [EffectLevel]: bloom suave, sombras
     * profundas, balanço tungstênio, vinheta, névoa liminar e FXAA só nos níveis que a GPU
     * comprovadamente compila (sonda isolada).
     */
    private fun applyEffectLevel(l: EffectLevel) {
        view.setShadowingEnabled(false)
        if (!l.postProcessing) {
            view.setPostProcessingEnabled(false)
            return
        }
        view.setPostProcessingEnabled(true)
        view.setAntiAliasing(if (l.fxaa) View.AntiAliasing.FXAA else View.AntiAliasing.NONE)
        view.setDithering(if (l.temporalDithering) View.Dithering.TEMPORAL else View.Dithering.NONE)
        view.setBloomOptions(View.BloomOptions().apply {
            enabled = l.bloomLevels > 0; strength = 0.22f; levels = l.bloomLevels.coerceAtLeast(1); resolution = if (l.bloomLevels >= 6) 256 else 192
            threshold = true; highlight = 1000f; lensFlare = false
        })
        view.setVignetteOptions(View.VignetteOptions().apply {
            enabled = l.vignette; midPoint = 0.42f; roundness = 0.55f; feather = 0.62f; color = floatArrayOf(0f, 0f, 0f, 1f)
        })
        view.setFogOptions(View.FogOptions().apply {
            enabled = l.fog; distance = 14f; density = 0.028f; maximumOpacity = 0.75f; height = 0f; heightFalloff = 0.35f
            color = floatArrayOf(0.10f, 0.085f, 0.06f)
        })
        view.setDynamicResolutionOptions(View.DynamicResolutionOptions().apply {
            enabled = l.dynamicResolution; minScale = 0.55f; maxScale = 1.0f; quality = View.QualityLevel.LOW
        })
        if (l.colorGrading) {
            if (!::colorGrading.isInitialized) colorGrading = ColorGrading.Builder()
                .toneMapper(ToneMapper.Filmic())
                .whiteBalance(-0.10f, 0.03f)
                .shadowsMidtonesHighlights(
                    floatArrayOf(0.94f, 1.0f, 1.08f, 0f),
                    floatArrayOf(1.03f, 1.0f, 0.97f, 0f),
                    floatArrayOf(1.08f, 1.0f, 0.90f, 0f),
                    floatArrayOf(0f, 0.30f, 0.55f, 1f))
                .curves(floatArrayOf(1.18f, 1.18f, 1.12f), floatArrayOf(1f, 1f, 1f), floatArrayOf(1f, 1f, 1f))
                .contrast(1.12f)
                .saturation(0.88f)
                .vibrance(1.05f)
                .build(engine)
            view.setColorGrading(colorGrading)
        } else {
            view.setColorGrading(null)
        }
    }

    /** Troca o nível em runtime (fallback por FPS): reaplica o pós e os sprites de halação. */
    fun setEffectLevel(l: EffectLevel) {
        if (!initialized || l == level) return
        level = l
        applyEffectLevel(l)
        lastSnapshot?.let { rebuildCity(it) }
        fpsGovernor.reset()
        Render3dTelemetry.update { it.copy(effectLevel = l.label) }
    }

    private fun loadMaterial(asset: String): Material {
        val bytes = context.assets.open(asset).use { it.readBytes() }
        val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
        buf.flip()
        return Material.Builder().payload(buf, bytes.size).build(engine)
    }

    private fun floatBuffer(a: FloatArray): ByteBuffer {
        val b = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder())
        b.asFloatBuffer().put(a)
        return b
    }

    private fun colorVertexBuffer(pos: FloatArray, col: FloatArray): VertexBuffer {
        val n = pos.size / 3
        val vb = VertexBuffer.Builder().bufferCount(2).vertexCount(n)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 1, VertexBuffer.AttributeType.FLOAT4, 0, 16)
            .build(engine)
        vb.setBufferAt(engine, 0, floatBuffer(pos))
        vb.setBufferAt(engine, 1, floatBuffer(col))
        return vb
    }

    private fun indexBuffer(idx: IntArray): IndexBuffer {
        val ib = IndexBuffer.Builder().indexCount(idx.size).bufferType(IndexBuffer.Builder.IndexType.UINT).build(engine)
        val b = ByteBuffer.allocateDirect(idx.size * 4).order(ByteOrder.nativeOrder())
        b.asIntBuffer().put(idx)
        ib.setBuffer(engine, b)
        return ib
    }

    private fun destroyRenderable(entity: Int) {
        if (entity == 0) return
        scene.removeEntity(entity)
        engine.destroyEntity(entity)
        engine.renderableManager.destroy(entity)
        engine.transformManager.destroy(entity)
        EntityManager.get().destroy(entity)
    }

    private fun rebuildCity(s: WorldRenderSnapshot) {
        destroyRenderable(cityEntity); cityEntity = 0
        cityVb?.let { engine.destroyVertexBuffer(it) }; cityIb?.let { engine.destroyIndexBuffer(it) }
        destroyRenderable(glowEntity); glowEntity = 0
        glowVb?.let { engine.destroyVertexBuffer(it) }; glowIb?.let { engine.destroyIndexBuffer(it) }
        val mesh = CityMeshBuilder.build(s.widthTiles, s.tiles, s.objects)
        vertexCount = mesh.vertexCount
        val vb = colorVertexBuffer(mesh.positions, mesh.colors); val ib = indexBuffer(mesh.indices)
        cityVb = vb; cityIb = ib
        val (mn, mx) = mesh.bounds()
        cityEntity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(Box((mn.x + mx.x) / 2, (mn.y + mx.y) / 2, (mn.z + mx.z) / 2, (mx.x - mn.x) / 2 + 0.1f, (mx.y - mn.y) / 2 + 0.1f, (mx.z - mn.z) / 2 + 0.1f))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib, 0, mesh.indices.size)
            .material(0, cityMi).castShadows(false).receiveShadows(false)
            .build(engine, cityEntity)
        scene.addEntity(cityEntity)

        val glow = CityMeshBuilder.glowSprites(s.widthTiles, s.tiles, s.objects)
        if (glow.count > 0 && level.halationSprites) {
            val gvb = VertexBuffer.Builder().bufferCount(3).vertexCount(glow.count * 4)
                .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
                .attribute(VertexBuffer.VertexAttribute.UV0, 1, VertexBuffer.AttributeType.FLOAT2, 0, 8)
                .attribute(VertexBuffer.VertexAttribute.COLOR, 2, VertexBuffer.AttributeType.FLOAT4, 0, 16)
                .build(engine)
            gvb.setBufferAt(engine, 0, floatBuffer(glow.positions))
            gvb.setBufferAt(engine, 1, floatBuffer(glow.uvs))
            gvb.setBufferAt(engine, 2, floatBuffer(glow.colors))
            val gib = indexBuffer(glow.indices)
            glowVb = gvb; glowIb = gib
            glowEntity = EntityManager.get().create()
            val w = s.widthTiles.toFloat()
            RenderableManager.Builder(1)
                .boundingBox(Box(w / 2, 4f, w / 2, w / 2 + 3f, 8f, w / 2 + 3f))
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, gvb, gib, 0, glow.indices.size)
                .material(0, glowMi).castShadows(false).receiveShadows(false).priority(7)
                .build(engine, glowEntity)
            scene.addEntity(glowEntity)
        }
    }

    /** Recebe o snapshot imutável publicado pelo SimulationHub (thread principal). */
    fun update(s: WorldRenderSnapshot) {
        if (!initialized) return
        lastSnapshot = s
        camera.boundsW = s.widthTiles.toFloat()
        val key = s.widthTiles.toLong() * 1_000_003L + s.tiles.contentHashCode() * 31L + s.objects.size
        if (key != meshKey) {
            if (meshKey == Long.MIN_VALUE) { camera.targetX = s.widthTiles / 2f; camera.targetZ = s.widthTiles / 2f; camera.distance = (s.widthTiles * 0.9f).coerceIn(18f, 110f) }
            meshKey = key
            rebuildCity(s)
        }
        night = s.night
        applyDayNight()
        val newTargets = HashMap<String, FloatArray>(s.agents.size * 2)
        val rm = engine.renderableManager
        for (a in s.agents) {
            newTargets[a.id] = floatArrayOf(a.fx, 0f, a.fz)
            val e = agentEntities[a.id] ?: run {
                val ent = EntityManager.get().create()
                val mi = cityMat.createInstance()
                mi.setParameter("emissiveBoost", 0f)
                engine.transformManager.create(ent)
                RenderableManager.Builder(1)
                    .boundingBox(Box(0f, 0.75f, 0f, 0.3f, 0.8f, 0.3f))
                    .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, agentVb, agentIb, 0, agentIndexCount)
                    .material(0, mi).castShadows(false).receiveShadows(false)
                    .build(engine, ent)
                scene.addEntity(ent)
                visual[a.id] = floatArrayOf(a.fx, 0f, a.fz)
                Pair(ent, mi).also { agentEntities[a.id] = it }
            }
            val c = actionRgb(a.action)
            val glow = if (night) 1.6f else 1f
            val moodLift = (0.85f + 0.15f * a.mood.toFloat())
            e.second.setParameter("tint", c[0] * glow * moodLift, c[1] * glow * moodLift, c[2] * glow * moodLift, 1f)
        }
        val gone = agentEntities.keys.filter { it !in newTargets }
        for (id in gone) {
            val (ent, mi) = agentEntities.remove(id) ?: continue
            destroyRenderable(ent)
            engine.destroyMaterialInstance(mi)
            visual.remove(id)
        }
        targets = newTargets
        Render3dTelemetry.update { it.copy(vertices = vertexCount, citizens = agentEntities.size) }
    }

    private fun applyDayNight() {
        if (night) {
            cityMi.setParameter("tint", 0.42f, 0.46f, 0.62f, 1f); cityMi.setParameter("emissiveBoost", 6.0f); glowMi.setParameter("intensity", 2.4f)
        } else {
            cityMi.setParameter("tint", 1.0f, 0.97f, 0.9f, 1f); cityMi.setParameter("emissiveBoost", 1.2f); glowMi.setParameter("intensity", 0.6f)
        }
    }

    private fun actionRgb(a: ActionType?): FloatArray {
        fun l(r: Float, g: Float, b: Float) = floatArrayOf(CityMeshBuilder.srgbToLinear(r), CityMeshBuilder.srgbToLinear(g), CityMeshBuilder.srgbToLinear(b))
        return when (a) {
            ActionType.COMER, ActionType.COLHER -> l(0.61f, 0.83f, 0.42f)
            ActionType.DESCANSAR -> l(0.43f, 0.55f, 0.79f)
            ActionType.BANHO -> l(0.50f, 0.84f, 0.94f)
            ActionType.RECICLAR, ActionType.CONSTRUIR -> l(0.88f, 0.57f, 0.37f)
            ActionType.MANTER_USINA -> l(1.0f, 0.83f, 0.36f)
            ActionType.PESQUISAR, ActionType.ESTUDAR -> l(0.69f, 0.65f, 1.0f)
            ActionType.SOCIALIZAR, ActionType.CONTRIBUIR -> l(0.95f, 0.63f, 0.72f)
            ActionType.EXPLORAR -> l(0.91f, 0.87f, 0.78f)
            ActionType.OCIOSO, null -> l(0.66f, 0.64f, 0.56f)
        }
    }

    /** Posições visuais atuais (interpoladas) para o raycast de seleção e os balões de fala. */
    fun agentPositions(): Map<String, V3> = visual.mapValues { (_, p) -> V3(p[0], p[1], p[2]) }
    fun viewSize(): Pair<Float, Float> = widthPx.toFloat() to heightPx.toFloat()

    /** Raycast 3D (AABB) com tolerância de ~24 dp em espaço de tela. */
    fun pick(sx: Float, sy: Float): String? {
        val agents = agentPositions()
        val id = Raycast.pickWithSlop(camera, sx, sy, widthPx.toFloat(), heightPx.toFloat(), agents, 24f * context.resources.displayMetrics.density)
        if (id == null) Log.d(TAG, "pick vazio: viewport ${widthPx}x$heightPx, ${agents.size} cidadãos")
        return id
    }

    private fun updateProjection() {
        fCamera.setProjection(camera.fovDeg.toDouble(), widthPx.toDouble() / heightPx, 0.3, 600.0, Camera.Fov.VERTICAL)
    }

    private fun renderFrame(frameTimeNanos: Long, dt: Float) {
        val tm = engine.transformManager
        val k = (dt * 6f).coerceAtMost(1f)
        val m = FloatArray(16)
        for ((id, pair) in agentEntities) {
            val v = visual[id] ?: continue
            val t = targets[id] ?: continue
            v[0] += (t[0] - v[0]) * k; v[2] += (t[2] - v[2]) * k
            m.fill(0f); m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
            m[12] = v[0]; m[13] = v[1]; m[14] = v[2]
            tm.setTransform(tm.getInstance(pair.first), m)
        }
        val eye = camera.eye(); val tgt = camera.target()
        fCamera.lookAt(eye.x.toDouble(), eye.y.toDouble(), eye.z.toDouble(), tgt.x.toDouble(), tgt.y.toDouble(), tgt.z.toDouble(), 0.0, 1.0, 0.0)
        updateProjection()
        val sc = swapChain ?: return
        if (!uiHelper.isReadyToRender) return
        if (renderer.beginFrame(sc, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
            fpsFrames++
        }
        if (fpsWindowStart == 0L) fpsWindowStart = frameTimeNanos
        val span = frameTimeNanos - fpsWindowStart
        if (span >= 1_000_000_000L) {
            val fps = fpsFrames / (span / 1e9)
            fpsFrames = 0; fpsWindowStart = frameTimeNanos
            Render3dTelemetry.update { it.copy(active = true, fps = fps, effectLevel = level.label) }
            if (fpsGovernor.onSecond(fps)) onFpsLow?.invoke(level)
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
        // Modo headless (sonda de GPU) roda numa thread sem Looper: não há Choreographer nem telemetria.
        if (headless) return
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        Render3dTelemetry.update { it.copy(fps = 0.0) }
    }

    fun destroy() {
        pause()
        if (!initialized) return
        initialized = false
        if (!headless) uiHelper.detach()
        for ((_, pair) in agentEntities) { destroyRenderable(pair.first); engine.destroyMaterialInstance(pair.second) }
        agentEntities.clear(); visual.clear()
        destroyRenderable(cityEntity); destroyRenderable(glowEntity)
        cityVb?.let { engine.destroyVertexBuffer(it) }; cityIb?.let { engine.destroyIndexBuffer(it) }
        glowVb?.let { engine.destroyVertexBuffer(it) }; glowIb?.let { engine.destroyIndexBuffer(it) }
        engine.destroyVertexBuffer(agentVb); engine.destroyIndexBuffer(agentIb)
        engine.destroyMaterialInstance(cityMi); engine.destroyMaterialInstance(glowMi)
        engine.destroyMaterial(cityMat); engine.destroyMaterial(glowMat)
        if (::colorGrading.isInitialized) engine.destroyColorGrading(colorGrading)
        swapChain?.let { engine.destroySwapChain(it) }; swapChain = null
        engine.destroyView(view); engine.destroyScene(scene); engine.destroyRenderer(renderer)
        val camEntity = fCamera.entity
        engine.destroyCameraComponent(camEntity); EntityManager.get().destroy(camEntity)
        engine.destroy()
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
