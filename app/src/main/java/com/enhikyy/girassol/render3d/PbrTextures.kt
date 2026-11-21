package com.enhikyy.girassol.render3d

import android.content.Context
import com.enhikyy.girassol.core.render.Ktx2
import com.google.android.filament.Engine
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Carrega as texturas PBR procedurais KTX2 (`assets/textures/<nome>.ktx2`, geradas por
 * `scripts/gen_pbr_ktx2.py`) para o Filament, com a cadeia de mipmaps do próprio arquivo.
 * RGB = albedo de detalhe, A = rugosidade; amostradas em projeção triplanar no city.mat.
 */
object PbrTextures {
    const val CONCRETE = "textures/concreto_brutalista.ktx2"
    const val CARPET = "textures/carpete_backrooms.ktx2"

    val sampler: TextureSampler = TextureSampler(
        TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.REPEAT, TextureSampler.WrapMode.REPEAT, TextureSampler.WrapMode.REPEAT
    ).apply { anisotropy = 4f }

    fun load(context: Context, engine: Engine, asset: String): Texture {
        val img = Ktx2.parse(context.assets.open(asset).use { it.readBytes() })
        val tex = Texture.Builder()
            .width(img.width).height(img.height).levels(img.levels.size)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(if (img.srgb) Texture.InternalFormat.SRGB8_A8 else Texture.InternalFormat.RGBA8)
            .usage(Texture.Usage.DEFAULT)
            .build(engine)
        for ((i, lv) in img.levels.withIndex()) {
            val buf = ByteBuffer.allocateDirect(lv.rgba.size).order(ByteOrder.nativeOrder()).put(lv.rgba)
            buf.flip()
            tex.setImage(engine, i, Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE))
        }
        return tex
    }
}
