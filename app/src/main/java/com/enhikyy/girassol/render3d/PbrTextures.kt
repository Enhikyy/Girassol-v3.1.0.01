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

    /**
     * Sobe o nível 0 do KTX2 e gera a cadeia de mipmaps na GPU. O upload direto dos níveis > 0
     * pelo `Texture.setImage` Java do Filament 1.75.1 calcula a altura do nível duas vezes
     * (Precondition "buffer overflow" visto no emulador), então os mips do arquivo servem só de
     * referência/validação. Se algo falhar, devolve uma textura neutra 1×1 (detalhe desligado).
     */
    fun load(context: Context, engine: Engine, asset: String): Texture = runCatching {
        val img = Ktx2.parse(context.assets.open(asset).use { it.readBytes() })
        val levels = img.levels.size
        val tex = Texture.Builder()
            .width(img.width).height(img.height).levels(levels)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(if (img.srgb) Texture.InternalFormat.SRGB8_A8 else Texture.InternalFormat.RGBA8)
            .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
            .build(engine)
        try {
            upload(engine, tex, img.levels[0].rgba)
            if (levels > 1) tex.generateMipmaps(engine)
        } catch (t: Throwable) {
            engine.destroyTexture(tex); throw t
        }
        tex
    }.getOrElse {
        android.util.Log.w("GirassolPbr", "textura $asset indisponível; usando neutra", it)
        neutral(engine)
    }

    /** Textura 1×1 cinza 50 % (albedo de detalhe neutro, rugosidade 1). */
    fun neutral(engine: Engine): Texture {
        val tex = Texture.Builder().width(1).height(1).levels(1).sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.RGBA8).usage(Texture.Usage.DEFAULT).build(engine)
        upload(engine, tex, byteArrayOf(127, 127, 127, -1))
        return tex
    }

    private fun upload(engine: Engine, tex: Texture, rgba: ByteArray) {
        val buf = ByteBuffer.allocateDirect(rgba.size).order(ByteOrder.nativeOrder()).put(rgba)
        buf.flip()
        tex.setImage(engine, 0, Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE))
    }
}
