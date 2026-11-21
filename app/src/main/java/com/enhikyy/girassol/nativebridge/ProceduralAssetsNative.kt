package com.enhikyy.girassol.nativebridge

import java.nio.ByteBuffer

/**
 * v4.0 — ponte JNI para o gerador C++20 de texturas e IBL da Aba Mundo
 * (`app/src/main/cpp/render/ProceduralWorldAssets.cpp`).
 *
 * Todas as saídas são escritas em [ByteBuffer]s diretos alocados pelo chamador (sem cópias
 * nem referências globais retidas no lado nativo), o que mantém o heap nativo estável entre
 * gerações repetidas — verificado em `WorldRenderStabilityTest`.
 */
object ProceduralAssetsNative {
    /** Superfícies (mesma ordem de `girassol::render::Surface`). */
    const val WET_ASPHALT = 0
    const val FACADE = 1
    const val SIDEWALK = 2
    const val CONCRETE = 3
    const val CARPET = 4
    const val COFFER = 5
    const val PARTITION = 6
    const val BILLBOARD = 7
    const val GRASS = 8
    const val SURFACE_COUNT = 9

    /** Céus (mesma ordem de `girassol::render::SkyPreset`). */
    const val SKY_MORNING = 0
    const val SKY_AFTERNOON = 1
    const val SKY_NIGHT = 2
    const val SKY_INTERIOR = 3

    @Volatile
    var isLoaded: Boolean = false
        private set

    init {
        isLoaded = try {
            System.loadLibrary("girassol_core"); true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /** Bytes da cadeia completa de mips RGBA8 de uma textura quadrada [size] (−1 se inválido). */
    @JvmStatic external fun nativeMipChainBytes(size: Int): Long

    /**
     * Gera uma superfície: [albedo] e [emissive] recebem só o nível 0 (sRGB, mipmaps pelo
     * Filament via generateMipmaps); [orm] e [normal] recebem a cadeia completa (normais
     * renormalizadas por nível + rugosidade de Toksvig).
     */
    @JvmStatic external fun nativeGenerateSurface(
        kind: Int, size: Int, seed: Long,
        albedo: ByteBuffer, orm: ByteBuffer, normal: ByteBuffer, emissive: ByteBuffer
    ): Boolean

    /** Bytes do cubemap RGBA16F com todos os mips (nível-maior, face-menor). */
    @JvmStatic external fun nativeCubemapBytes(faceSize: Int): Long

    /** Cubemap pré-filtrado GGX (rugosidade por LOD na convenção do Filament) + SH de 3 bandas (27 floats). */
    @JvmStatic external fun nativeGenerateIbl(
        preset: Int, sunX: Float, sunY: Float, sunZ: Float, overcast: Float,
        faceSize: Int, samples: Int, out: ByteBuffer, sh: FloatArray
    ): Boolean

    /** Número de níveis de mip de uma textura quadrada [size] (potência de 2). */
    fun mipLevels(size: Int): Int = 32 - Integer.numberOfLeadingZeros(size)

    /** Deslocamento (bytes) do nível [level] numa cadeia RGBA8 de [size]. */
    fun mipOffset(size: Int, level: Int, bytesPerPixel: Int = 4): Long {
        var off = 0L; var s = size
        for (l in 0 until level) { off += s.toLong() * s * bytesPerPixel; s = maxOf(1, s / 2) }
        return off
    }
}
