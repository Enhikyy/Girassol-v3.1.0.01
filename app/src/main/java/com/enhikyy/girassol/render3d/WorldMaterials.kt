package com.enhikyy.girassol.render3d

import com.enhikyy.girassol.nativebridge.ProceduralAssetsNative as PA

/** Catálogo de materiais da Aba Mundo (Kotlin puro). Cores informadas em sRGB e convertidas para linear. */
object WorldMaterials {
    private fun lin(r: Float, g: Float, b: Float) = floatArrayOf(CityMeshBuilder.srgbToLinear(r), CityMeshBuilder.srgbToLinear(g), CityMeshBuilder.srgbToLinear(b))
    private fun hex(c: Int) = lin(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)

    // cores de referência da especificação
    val FACADE_CONCRETE = 0x6E767A
    val FACADE_TEAL = 0x1D5961
    val CARPET_DARK = 0x133024
    val CARPET_LIGHT = 0x1B3F30
    val PARTITION_OFFWHITE = 0xD6DBD7

    val all: List<MatSpec> = listOf(
        // ---- urbano
        MatSpec("asphalt", PA.WET_ASPHALT, metersU = 4f, normalStrength = 1f),
        MatSpec("sidewalk", PA.SIDEWALK, metersU = 4f),
        MatSpec("curb", PA.CONCRETE, metersU = 1.2f, tint = lin(0.96f, 0.95f, 0.93f)),
        MatSpec("gutter", PA.CONCRETE, metersU = 2f, tint = lin(0.42f, 0.43f, 0.44f)),
        MatSpec("paint_worn", PA.CONCRETE, metersU = 1.5f, tint = lin(1.0f, 1.0f, 0.97f)),
        // v4.1: fachada de concreto #6E767A (sem janelas pintadas — as janelas são geometria real)
        MatSpec("facade", PA.CONCRETE, metersU = 3f, tint = lin(0.66f, 0.71f, 0.73f)),
        MatSpec("spandrel", PA.CONCRETE, metersU = 3f, tint = lin(0.30f, 0.62f, 0.66f)),
        MatSpec("reveal", PA.CONCRETE, metersU = 1f, tint = lin(0.78f, 0.80f, 0.80f)),
        MatSpec("sill", PA.CONCRETE, metersU = 1f, tint = lin(0.95f, 0.94f, 0.91f)),
        MatSpec("frame", -1, tint = lin(0.20f, 0.21f, 0.22f), rough = 0.35f, metal = 1f),
        // vidro: rugosidade 0,08; interior aceso 2700–4500 K decidido por janela no shader
        MatSpec("window_glass", -1, tint = lin(0.035f, 0.045f, 0.05f), rough = 0.08f,
            emissive = floatArrayOf(1f, 1f, 1f), emissiveDriver = EmissiveDriver.WINDOWS, window = true),
        MatSpec("marquee", -1, tint = lin(0.18f, 0.19f, 0.20f), rough = 0.4f, metal = 0.8f),
        MatSpec("wood", -1, tint = lin(0.46f, 0.30f, 0.18f), rough = 0.62f),
        MatSpec("planter", PA.CONCRETE, metersU = 1.5f, tint = lin(0.85f, 0.85f, 0.83f)),
        MatSpec("soil", -1, tint = lin(0.12f, 0.08f, 0.05f), rough = 0.95f),
        MatSpec("leaf_light", -1, tint = lin(0.24f, 0.36f, 0.16f), rough = 0.82f),
        MatSpec("concrete", PA.CONCRETE, metersU = 3f),
        MatSpec("grass", PA.GRASS, metersU = 4f),
        MatSpec("field", PA.GRASS, metersU = 4f, tint = lin(0.92f, 0.86f, 0.62f)),
        MatSpec("billboard", PA.BILLBOARD, metersU = 6f, metersV = 3f, emissive = floatArrayOf(1f, 1f, 1f), emissiveDriver = EmissiveDriver.BILLBOARD),
        MatSpec("ground_far", PA.CONCRETE, metersU = 6f, tint = lin(0.55f, 0.56f, 0.56f)),
        MatSpec("metal_dark", -1, tint = lin(0.16f, 0.17f, 0.18f), rough = 0.42f, metal = 1f),
        MatSpec("metal_rail", -1, tint = lin(0.56f, 0.57f, 0.58f), rough = 0.32f, metal = 1f),
        MatSpec("wire", -1, tint = lin(0.04f, 0.04f, 0.04f), rough = 0.6f),
        MatSpec("lamp_head", -1, tint = lin(0.22f, 0.22f, 0.22f), rough = 0.4f, metal = 0.6f,
            emissive = lin(1.0f, 0.70f, 0.38f), emissiveDriver = EmissiveDriver.STREET_LAMP),
        MatSpec("water", -1, tint = lin(0.035f, 0.05f, 0.055f), rough = 0.05f),
        MatSpec("leaf", -1, tint = lin(0.14f, 0.24f, 0.12f), rough = 0.85f),
        MatSpec("trunk", -1, tint = lin(0.20f, 0.15f, 0.11f), rough = 0.9f),
        MatSpec("barrier_orange", -1, tint = lin(0.86f, 0.34f, 0.08f), rough = 0.5f),
        MatSpec("barrier_white", -1, tint = lin(0.82f, 0.82f, 0.80f), rough = 0.5f),
        MatSpec("hvac", -1, tint = lin(0.50f, 0.52f, 0.53f), rough = 0.45f, metal = 0.7f),
        MatSpec("solar", -1, tint = lin(0.03f, 0.06f, 0.14f), rough = 0.15f, metal = 0.3f),
        MatSpec("sign_cyan", -1, tint = lin(0.05f, 0.08f, 0.09f), rough = 0.3f, emissive = lin(0.35f, 0.90f, 1.0f), emissiveDriver = EmissiveDriver.BILLBOARD),
        // ---- interior Lumon
        MatSpec("carpet", PA.CARPET, metersU = 2f, normalStrength = 1f),
        MatSpec("coffer", PA.COFFER, metersU = 2.4f, emissive = lin(1.0f, 0.98f, 0.93f), emissiveDriver = EmissiveDriver.CEILING),
        MatSpec("beam", -1, tint = lin(0.80f, 0.82f, 0.80f), rough = 0.82f),
        MatSpec("partition", PA.PARTITION, metersU = 1.6f),
        MatSpec("wall", PA.PARTITION, metersU = 3f, tint = lin(0.97f, 0.98f, 0.97f)),
        MatSpec("baseboard", -1, tint = lin(0.05f, 0.12f, 0.09f), rough = 0.6f),
        MatSpec("desk", -1, tint = lin(0.74f, 0.76f, 0.74f), rough = 0.55f),
        MatSpec("monitor_body", -1, tint = lin(0.66f, 0.66f, 0.62f), rough = 0.48f),
        MatSpec("monitor_screen", -1, tint = lin(0.02f, 0.03f, 0.04f), rough = 0.12f,
            emissive = lin(0.30f, 0.86f, 1.0f), emissiveDriver = EmissiveDriver.MONITOR),
        MatSpec("pillar", PA.PARTITION, metersU = 2f, tint = lin(0.90f, 0.91f, 0.89f)),
        MatSpec("carpet_runner", PA.CARPET, metersU = 2f, tint = lin(1.35f, 1.30f, 1.25f), normalStrength = 1.2f),
        MatSpec("corridor_wall", PA.PARTITION, metersU = 3f, tint = lin(0.93f, 0.95f, 0.93f)),
        MatSpec("chair_fabric", -1, tint = lin(0.07f, 0.08f, 0.09f), rough = 0.92f),
        MatSpec("chair_metal", -1, tint = lin(0.20f, 0.21f, 0.22f), rough = 0.35f, metal = 1f)
    )

    private val byId = all.associateBy { it.id }
    fun spec(id: String): MatSpec = byId[id] ?: error("material desconhecido: $id")
    val surfacesUsed: Set<Int> get() = all.filter { it.surface >= 0 }.map { it.surface }.toSet()
    fun surfacesFor(p: WorldPreset): List<Int> = if (p == WorldPreset.URBAN_OVERCAST)
        listOf(PA.WET_ASPHALT, PA.CONCRETE, PA.SIDEWALK, PA.GRASS, PA.BILLBOARD)
    else listOf(PA.CARPET, PA.COFFER, PA.PARTITION)

    /** Roupas sóbrias (sRGB): carvão, marinho, ardósia, aveia, verde-garrafa, vinho fosco, cinza-grafite. */
    val SHIRTS = listOf(0x2E3236, 0x23304A, 0x4A5560, 0xB8AE98, 0x22382E, 0x5A2E33, 0x3C3F44, 0x6B6F73).map(::hex)
    val TROUSERS = listOf(0x1E2024, 0x242B38, 0x3A3A36, 0x5A5446, 0x16181B).map(::hex)
    val SKIN = listOf(0xF1C9A5, 0xE0AC82, 0xC68A5E, 0x9C6644, 0x6E4A33).map(::hex)
    val HAIR = listOf(0x1B1512, 0x3A2A1E, 0x5C4632, 0x8C8A86, 0x241C17).map(::hex)
    val SHOES = listOf(0x111111, 0x2B1D15, 0x1C1C20).map(::hex)
}
