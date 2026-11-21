package com.enhikyy.girassol.render3d

import android.util.Log
import com.enhikyy.girassol.nativebridge.ProceduralAssetsNative
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.IndirectLight
import com.google.android.filament.Skybox
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Utilitários de GPU do Filament para a Aba Mundo (buffers, texturas procedurais, IBL). */
internal object WorldGpu {
    private const val TAG = "GirassolWorldGpu"

    fun direct(bytes: Long): ByteBuffer = ByteBuffer.allocateDirect(bytes.toInt()).order(ByteOrder.nativeOrder())

    fun floatBuffer(a: FloatArray): ByteBuffer {
        val b = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder())
        b.asFloatBuffer().put(a)
        return b
    }

    fun indexBuffer(engine: Engine, idx: IntArray): IndexBuffer {
        val ib = IndexBuffer.Builder().indexCount(idx.size).bufferType(IndexBuffer.Builder.IndexType.UINT).build(engine)
        val b = ByteBuffer.allocateDirect(idx.size * 4).order(ByteOrder.nativeOrder())
        b.asIntBuffer().put(idx)
        ib.setBuffer(engine, b)
        return ib
    }

    /** VB com POSITION (f3), TANGENTS (quaternion f4) e UV0 (f2). */
    fun litVertexBuffer(engine: Engine, m: LitMesh): VertexBuffer {
        val vb = VertexBuffer.Builder().bufferCount(3).vertexCount(m.vertexCount)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1, VertexBuffer.AttributeType.FLOAT4, 0, 16)
            .attribute(VertexBuffer.VertexAttribute.UV0, 2, VertexBuffer.AttributeType.FLOAT2, 0, 8)
            .build(engine)
        vb.setBufferAt(engine, 0, floatBuffer(m.positions))
        vb.setBufferAt(engine, 1, floatBuffer(m.tangents))
        vb.setBufferAt(engine, 2, floatBuffer(m.uvs))
        return vb
    }

    /**
     * GL por software (SwiftShader do emulador): filtragem anisotrópica e milhares de envios de
     * mips linha a linha travam o tradutor GL do emulador. Nesse caso: anisotropia 1 e mips de
     * ORM/normal gerados pela GPU (generateMipmaps). Em GPU real: anisotropia 8 e cadeia do C++.
     */
    @Volatile var softwareGl = false
    private val hwSampler = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.REPEAT).apply { anisotropy = 8f }
    private val swSampler = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.REPEAT)
    val repeatSampler: TextureSampler get() = if (softwareGl) swSampler else hwSampler

    /** Textura 1 × 1 RGBA8 (sRGB ou linear). */
    fun solid(engine: Engine, r: Int, g: Int, b: Int, a: Int, srgb: Boolean): Texture {
        val t = Texture.Builder().width(1).height(1).levels(1).sampler(Texture.Sampler.SAMPLER_2D)
            .format(if (srgb) Texture.InternalFormat.SRGB8_A8 else Texture.InternalFormat.RGBA8).build(engine)
        val buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        buf.put(r.toByte()).put(g.toByte()).put(b.toByte()).put(a.toByte()).flip()
        t.setImage(engine, 0, Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE))
        return t
    }

    /** Dados CPU de uma superfície gerada pelo C++ (thread de fundo). */
    class SurfaceData(val kind: Int, val size: Int, val albedo: ByteBuffer, val orm: ByteBuffer, val normal: ByteBuffer, val emissive: ByteBuffer)

    fun generateSurface(kind: Int, size: Int, seed: Long): SurfaceData? {
        if (!ProceduralAssetsNative.isLoaded) return null
        return runCatching {
            val chain = ProceduralAssetsNative.nativeMipChainBytes(size)
            val level0 = size.toLong() * size * 4
            val d = SurfaceData(kind, size, direct(level0), direct(chain), direct(chain), direct(level0))
            check(ProceduralAssetsNative.nativeGenerateSurface(kind, size, seed, d.albedo, d.orm, d.normal, d.emissive))
            d
        }.getOrElse { Log.w(TAG, "geração da superfície $kind falhou", it); null }
    }

    class SurfaceTextures(val albedo: Texture, val orm: Texture, val normal: Texture, val emissive: Texture?) {
        fun destroy(engine: Engine) { engine.destroyTexture(albedo); engine.destroyTexture(orm); engine.destroyTexture(normal); emissive?.let { engine.destroyTexture(it) } }
    }

    /** Envia a superfície à GPU: albedo/emissivo sRGB com generateMipmaps; ORM/normal com a cadeia completa do C++. */
    fun upload(engine: Engine, d: SurfaceData, withEmissive: Boolean): SurfaceTextures {
        val levels = ProceduralAssetsNative.mipLevels(d.size)
        fun srgbMipmapped(buf: ByteBuffer): Texture {
            val t = Texture.Builder().width(d.size).height(d.size).levels(levels).sampler(Texture.Sampler.SAMPLER_2D)
                .format(Texture.InternalFormat.SRGB8_A8)
                .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE).build(engine)
            buf.rewind()
            t.setImage(engine, 0, Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE))
            t.generateMipmaps(engine)
            return t
        }
        fun chain(buf: ByteBuffer): Texture {
            if (softwareGl) {
                val t = Texture.Builder().width(d.size).height(d.size).levels(levels).sampler(Texture.Sampler.SAMPLER_2D)
                    .format(Texture.InternalFormat.RGBA8).usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE).build(engine)
                setLevel(engine, t, 0, d.size, 0, buf, 0, 4, Texture.Type.UBYTE)
                t.generateMipmaps(engine)
                return t
            }
            val t = Texture.Builder().width(d.size).height(d.size).levels(levels).sampler(Texture.Sampler.SAMPLER_2D)
                .format(Texture.InternalFormat.RGBA8).build(engine)
            var s = d.size
            for (l in 0 until levels) {
                val off = ProceduralAssetsNative.mipOffset(d.size, l).toInt()
                setLevel(engine, t, l, s, 0, buf, off, 4, Texture.Type.UBYTE)
                s = maxOf(1, s / 2)
            }
            return t
        }
        return SurfaceTextures(srgbMipmapped(d.albedo), chain(d.orm), chain(d.normal), if (withEmissive) srgbMipmapped(d.emissive) else null)
    }

    /**
     * Envia o nível [level] (quadrado [size]², camada/face [z]) de [buf] a partir de [offset].
     *
     * O JNI do Filament 1.75.1 (`Texture.cpp`, `getTextureDataSize`) desloca a altura da região
     * por `level` uma segunda vez (`height >> level`), declarando um buffer 2^level vezes menor que
     * a região e disparando o Precondition "buffer overflow" do `setImageCommon` em todo nível > 0.
     * Contorno: o nível 0 vai inteiro; os demais linha a linha (altura 1 ⇒ `max(1, 1 >> level)` = 1,
     * tamanho exato), preservando a cadeia pré-filtrada do C++ (GGX no IBL, Toksvig nas normais).
     */
    fun setLevel(engine: Engine, tex: Texture, level: Int, size: Int, z: Int, buf: ByteBuffer, offset: Int, bpp: Int, type: Texture.Type) {
        val rowBytes = size * bpp
        fun region(off: Int, bytes: Int): ByteBuffer {
            val d = buf.duplicate().order(ByteOrder.nativeOrder())
            d.limit(off + bytes); d.position(off)
            return d.slice().order(ByteOrder.nativeOrder())
        }
        if (level == 0) {
            tex.setImage(engine, 0, 0, 0, z, size, size, 1, Texture.PixelBufferDescriptor(region(offset, rowBytes * size), Texture.Format.RGBA, type))
            return
        }
        for (y in 0 until size) {
            tex.setImage(engine, level, 0, y, z, size, 1, 1, Texture.PixelBufferDescriptor(region(offset + y * rowBytes, rowBytes), Texture.Format.RGBA, type))
        }
    }

    /** Cubemap pré-filtrado + SH de 3 bandas (CPU, thread de fundo). */
    class IblData(val phase: TimePhase, val faceSize: Int, val cube: ByteBuffer, val sh: FloatArray)

    fun generateIbl(phase: TimePhase, sunDir: FloatArray, overcast: Float, faceSize: Int, samples: Int): IblData? {
        if (!ProceduralAssetsNative.isLoaded) return null
        return runCatching {
            val buf = direct(ProceduralAssetsNative.nativeCubemapBytes(faceSize))
            val sh = FloatArray(27)
            check(ProceduralAssetsNative.nativeGenerateIbl(phase.skyPreset, sunDir[0], sunDir[1], sunDir[2], overcast, faceSize, samples, buf, sh))
            IblData(phase, faceSize, buf, sh)
        }.getOrElse { Log.w(TAG, "IBL ${phase.label} falhou", it); null }
    }

    class Ibl(val phase: TimePhase, val cubemap: Texture, val light: IndirectLight, val skybox: Skybox, val sh: FloatArray) {
        /** Irradiância (já dividida por π, convenção do Filament) na direção +Y — calibra a intensidade em lux. */
        val upIrradiance: Float get() = shEval(sh, 0f, 1f, 0f)
        fun destroy(engine: Engine) { engine.destroyIndirectLight(light); engine.destroySkybox(skybox); engine.destroyTexture(cubemap) }
    }

    /** Avalia o SH pré-escalado (luminância Rec.709) na direção (x, y, z). */
    fun shEval(sh: FloatArray, x: Float, y: Float, z: Float): Float {
        val basis = floatArrayOf(1f, y, z, x, y * x, y * z, 3f * z * z - 1f, z * x, x * x - y * y)
        var r = 0f; var g = 0f; var b = 0f
        for (i in 0 until 9) { r += sh[i * 3] * basis[i]; g += sh[i * 3 + 1] * basis[i]; b += sh[i * 3 + 2] * basis[i] }
        return (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceAtLeast(1e-6f)
    }

    fun uploadIbl(engine: Engine, d: IblData, targetLux: Float): Ibl {
        val levels = ProceduralAssetsNative.mipLevels(d.faceSize)
        val tex = Texture.Builder().width(d.faceSize).height(d.faceSize).levels(levels).sampler(Texture.Sampler.SAMPLER_CUBEMAP)
            .format(Texture.InternalFormat.RGBA16F).build(engine)
        var off = 0
        var s = d.faceSize
        for (l in 0 until levels) {
            val faceBytes = s * s * 8
            // z = face (cubemap): as 6 faces do nível são contíguas
            for (face in 0 until 6) setLevel(engine, tex, l, s, face, d.cube, off + face * faceBytes, 8, Texture.Type.HALF)
            off += faceBytes * 6
            s = maxOf(1, s / 2)
        }
        val light = IndirectLight.Builder().reflections(tex).irradiance(3, d.sh).intensity(1f).build(engine)
        // o Skybox do Filament 1.75 não tem setIntensity: a intensidade é fixada para a iluminância alvo da fase
        val sky = Skybox.Builder().environment(tex).intensity((targetLux / Math.PI.toFloat()) / shEval(d.sh, 0f, 1f, 0f)).build(engine)
        return Ibl(d.phase, tex, light, sky, d.sh)
    }
}
