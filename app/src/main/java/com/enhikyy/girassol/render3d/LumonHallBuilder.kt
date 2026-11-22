package com.enhikyy.girassol.render3d

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * v4.0 — LUMON_INTERIOR: grande salão brutalista-corporativo.
 *  - teto em caixotões profundos (módulo 2,4 m, vigas 0,30 × 0,55 m) com painéis emissivos
 *    embutidos (textura procedural COFFER com chanfro e difusor);
 *  - carpete esmeralda #133024–#1B3F30 em placas 4×4 com micro-trama e oclusão nas juntas
 *    (rugosidade 0,90);
 *  - ilhas de 4 estações em cruz: divisórias off-white #D6DBD7 (rugosidade 0,68), mesas,
 *    monitores CRT com tela emissiva ciano/azul e cadeiras ergonômicas detalhadas
 *    (base estrela de 5 pés, coluna a gás, assento, encosto com apoio lombar, braços).
 * v4.1: pilares brutalistas com capitel, corredor dos fundos com portas, passadeiras nos
 * corredores e luzes pontuais de teto (350 lux nas mesas / 250 lux nos corredores, níveis 1–3).
 * A posição da simulação (cidade S × S m) é remapeada linearmente para dentro do salão.
 */
object LumonHallBuilder {
    const val COFFER = 2.4f
    const val CELLS_X = 26
    const val CELLS_Z = 20
    const val HALL_W = COFFER * CELLS_X   // 62,4 m
    const val HALL_D = COFFER * CELLS_Z   // 48 m
    const val HEIGHT = 3.4f
    const val BEAM_W = 0.30f
    const val BEAM_D = 0.55f
    const val PARTITION_H = 1.25f
    const val DESK_H = 0.76f
    const val MARGIN = 2.5f
    const val PODS_X = 5
    const val PODS_Z = 3
    const val PILLAR = 0.62f
    /** Corredor dos fundos (além da área mapeada da simulação) com parede e portas. */
    const val CORRIDOR_W = 2.2f
    const val CORRIDOR_WALL_H = 2.7f
    /** Iluminâncias alvo das luzes pontuais de teto (lux): plano das mesas e piso dos corredores. */
    const val DESK_LUX = 350f
    const val CORRIDOR_LUX = 250f

    /** Pilares brutalistas nos cruzamentos entre ilhas (fora das mesas e dos caminhos dos cidadãos). */
    fun pillars(): List<V3> {
        val out = ArrayList<V3>()
        for (j in 1 until PODS_Z) for (i in 1 until PODS_X) out += V3(HALL_W * i / PODS_X, 0f, HALL_D * j / PODS_Z)
        return out
    }

    /**
     * Luzes pontuais do teto: 2 por ilha (350 lux no plano das mesas, d = pé-direito − mesa) e uma a
     * cada 6 m no corredor dos fundos + nos corredores entre fileiras (250 lux no piso). As de mesa
     * vêm primeiro (o nível Lite usa só elas).
     */
    fun ceilingLights(): List<PointLightSpec> {
        val out = ArrayList<PointLightSpec>()
        val y = HEIGHT - 0.05f
        for (j in 0 until PODS_Z) for (i in 0 until PODS_X) {
            val cx = HALL_W * (i + 0.5f) / PODS_X; val cz = HALL_D * (j + 0.5f) / PODS_Z
            for (sx in intArrayOf(-1, 1)) out += PointLightSpec(V3(cx + sx * 1.0f, y, cz), DESK_LUX, HEIGHT - DESK_H, 6.5f)
        }
        var x = 3f
        while (x < HALL_W - 2f) { out += PointLightSpec(V3(x, y, HALL_D - CORRIDOR_W / 2), CORRIDOR_LUX, HEIGHT, 5.0f); x += 6f }
        for (j in 1 until PODS_Z) {
            var xx = HALL_W / PODS_X
            while (xx < HALL_W - 1f) { out += PointLightSpec(V3(xx - HALL_W / PODS_X / 2, y, HALL_D * j / PODS_Z), CORRIDOR_LUX, HEIGHT, 5.0f); xx += HALL_W / PODS_X }
        }
        return out
    }

    data class Station(val x: Float, val z: Float, val facing: Int)

    /** Estações (monitor/cadeira) de todas as ilhas: 4 por ilha em cruz. */
    fun stations(): List<Station> {
        val out = ArrayList<Station>()
        for (j in 0 until PODS_Z) for (i in 0 until PODS_X) {
            val cx = HALL_W * (i + 0.5f) / PODS_X; val cz = HALL_D * (j + 0.5f) / PODS_Z
            for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) out += Station(cx + sx * 0.95f, cz + sz * 0.60f, sz)
        }
        return out
    }

    fun build(citySizeM: Float): WorldScene {
        val parts = ArrayList<ScenePart>()
        val ceil = HEIGHT + BEAM_D
        // piso de carpete
        val carpet = LitMeshBuilder().apply { rect(V3(0f, 0f, HALL_D), V3(HALL_W, 0f, 0f), V3(0f, 0f, -HALL_D), worldUv = true) }
        parts += ScenePart("carpet", carpet.build(), castShadows = false)
        // paredes internas (normais para dentro) e rodapé verde-escuro
        val wall = LitMeshBuilder(); val base = LitMeshBuilder()
        wall.rect(V3(0f, 0f, 0f), V3(HALL_W, 0f, 0f), V3(0f, ceil, 0f))                      // z = 0, normal +Z
        wall.rect(V3(HALL_W, 0f, HALL_D), V3(-HALL_W, 0f, 0f), V3(0f, ceil, 0f))             // z = D, normal −Z
        wall.rect(V3(0f, 0f, HALL_D), V3(0f, 0f, -HALL_D), V3(0f, ceil, 0f))                 // x = 0, normal +X
        wall.rect(V3(HALL_W, 0f, 0f), V3(0f, 0f, HALL_D), V3(0f, ceil, 0f))                  // x = W, normal −X
        base.box(V3(0f, 0f, 0f), V3(HALL_W, 0.12f, 0.03f)); base.box(V3(0f, 0f, HALL_D - 0.03f), V3(HALL_W, 0.12f, HALL_D))
        base.box(V3(0f, 0f, 0f), V3(0.03f, 0.12f, HALL_D)); base.box(V3(HALL_W - 0.03f, 0f, 0f), V3(HALL_W, 0.12f, HALL_D))
        parts += ScenePart("wall", wall.build(), castShadows = false)
        parts += ScenePart("baseboard", base.build(), castShadows = false)
        // teto: painel de caixotões (voltado para baixo) + grelha de vigas
        val coffer = LitMeshBuilder().apply { rect(V3(0f, ceil, 0f), V3(HALL_W, 0f, 0f), V3(0f, 0f, HALL_D)) }
        parts += ScenePart("coffer", coffer.build(), castShadows = false, layer = WorldLayers.CEILING)
        val beams = LitMeshBuilder()
        for (k in 0..CELLS_X) {
            val x = k * COFFER
            beams.box(V3(x - BEAM_W / 2, HEIGHT, 0f), V3(x + BEAM_W / 2, ceil, HALL_D), top = false, bottom = true)
        }
        for (k in 0..CELLS_Z) {
            val z = k * COFFER
            beams.box(V3(0f, HEIGHT, z - BEAM_W / 2), V3(HALL_W, ceil, z + BEAM_W / 2), top = false, bottom = true)
        }
        parts += ScenePart("beam", beams.build(), castShadows = false, layer = WorldLayers.CEILING)
        // ilhas de trabalho
        val part = LitMeshBuilder(); val desk = LitMeshBuilder(); val metal = LitMeshBuilder()
        for (j in 0 until PODS_Z) for (i in 0 until PODS_X) {
            val cx = HALL_W * (i + 0.5f) / PODS_X; val cz = HALL_D * (j + 0.5f) / PODS_Z
            part.chamferBox(V3(cx - 2.1f, 0f, cz - 0.035f), V3(cx + 2.1f, PARTITION_H, cz + 0.035f), 0.02f)
            part.chamferBox(V3(cx - 0.035f, 0f, cz - 1.9f), V3(cx + 0.035f, PARTITION_H + 0.12f, cz + 1.9f), 0.02f)
            metal.box(V3(cx - 2.12f, PARTITION_H, cz - 0.045f), V3(cx + 2.12f, PARTITION_H + 0.025f, cz + 0.045f))
            for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
                val xa = cx + sx * 0.12f; val xb = cx + sx * 1.85f
                val za = cz + sz * 0.06f; val zb = cz + sz * 0.95f
                desk.chamferBox(V3(minOf(xa, xb), DESK_H - 0.035f, minOf(za, zb)), V3(maxOf(xa, xb), DESK_H, maxOf(za, zb)), 0.012f)
                // painéis laterais (pés) da mesa
                val xo = cx + sx * 1.80f
                desk.box(V3(xo - 0.025f, 0f, minOf(za, zb) + 0.05f), V3(xo + 0.025f, DESK_H - 0.035f, maxOf(za, zb) - 0.05f))
                desk.box(V3(minOf(xa, xb), 0.25f, cz + sz * 0.08f - 0.01f), V3(maxOf(xa, xb), DESK_H - 0.035f, cz + sz * 0.08f + 0.01f))
            }
        }
        // pilares (fuste chanfrado + capitel alargado sob as vigas + base) e corredores
        val pil = LitMeshBuilder(); val runner = LitMeshBuilder()
        for (p in pillars()) {
            val h = PILLAR / 2
            pil.chamferBox(V3(p.x - h, 0f, p.z - h), V3(p.x + h, HEIGHT - 0.35f, p.z + h), 0.03f)
            pil.box(V3(p.x - h - 0.12f, HEIGHT - 0.35f, p.z - h - 0.12f), V3(p.x + h + 0.12f, HEIGHT, p.z + h + 0.12f), top = false, bottom = true)
            pil.box(V3(p.x - h - 0.02f, 0f, p.z - h - 0.02f), V3(p.x + h + 0.02f, 0.12f, p.z + h + 0.02f))
        }
        // passadeiras de carpete mais claro nos corredores entre fileiras e entre colunas de ilhas
        for (j in 1 until PODS_Z) {
            val z = HALL_D * j / PODS_Z
            runner.rect(V3(0.6f, 0.004f, z + 0.9f), V3(HALL_W - 1.2f, 0f, 0f), V3(0f, 0f, -1.8f), worldUv = true)
        }
        for (i in 1 until PODS_X) {
            val x = HALL_W * i / PODS_X
            runner.rect(V3(x - 0.9f, 0.003f, HALL_D - CORRIDOR_W - 0.3f), V3(1.8f, 0f, 0f), V3(0f, 0f, -(HALL_D - CORRIDOR_W - 0.9f)), worldUv = true)
        }
        runner.rect(V3(0.3f, 0.004f, HALL_D - 0.3f), V3(HALL_W - 0.6f, 0f, 0f), V3(0f, 0f, -(CORRIDOR_W - 0.5f)), worldUv = true)
        // parede do corredor dos fundos (z = D − largura), com portas de 1,2 m a cada 12 m
        val cw = LitMeshBuilder(); val door = LitMeshBuilder()
        val zc = HALL_D - CORRIDOR_W
        var x0 = 0f
        var k = 0
        while (x0 < HALL_W) {
            val doorX = 6f + k * 12f
            val segEnd = minOf(if (doorX < HALL_W - 1f) doorX else HALL_W, HALL_W)
            if (segEnd > x0) cw.box(V3(x0, 0f, zc - 0.06f), V3(segEnd, CORRIDOR_WALL_H, zc + 0.06f))
            if (doorX >= HALL_W - 1f) break
            // verga sobre a porta + batentes metálicos
            cw.box(V3(doorX, 2.1f, zc - 0.06f), V3(doorX + 1.2f, CORRIDOR_WALL_H, zc + 0.06f), bottom = true)
            door.box(V3(doorX - 0.04f, 0f, zc - 0.08f), V3(doorX, 2.14f, zc + 0.08f))
            door.box(V3(doorX + 1.2f, 0f, zc - 0.08f), V3(doorX + 1.24f, 2.14f, zc + 0.08f))
            door.box(V3(doorX - 0.04f, 2.1f, zc - 0.08f), V3(doorX + 1.24f, 2.14f, zc + 0.08f))
            x0 = doorX + 1.2f
            k++
        }
        parts += ScenePart("pillar", pil.build())
        parts += ScenePart("carpet_runner", runner.build(), castShadows = false)
        parts += ScenePart("corridor_wall", cw.build())
        parts += ScenePart("chair_metal", door.build())
        parts += ScenePart("partition", part.build())
        parts += ScenePart("desk", desk.build())
        parts += ScenePart("chair_metal", metal.build())
        // instanciados: monitores e cadeiras (2 guinadas cada)
        val st = stations()
        val inst = ArrayList<InstancedPart>()
        val monBody = monitorBodyMesh(); val monScreen = monitorScreenMesh(); val chairF = chairFabricMesh(); val chairM = chairMetalMesh()
        for (facing in intArrayOf(1, -1)) {
            val group = st.filter { it.facing == facing }
            val monYaw = if (facing == 1) 0f else PI.toFloat()
            val chairYaw = if (facing == 1) PI.toFloat() else 0f
            val mon = group.map { V3(it.x, 0f, it.z) }
            val chairs = group.map { V3(it.x, 0f, it.z + facing * 0.95f) }
            inst += InstancedPart("monitor_body", monBody, monYaw, mon)
            inst += InstancedPart("monitor_screen", monScreen, monYaw, mon, castShadows = false)
            inst += InstancedPart("chair_fabric", chairF, chairYaw, chairs)
            inst += InstancedPart("chair_metal", chairM, chairYaw, chairs)
        }
        val s = citySizeM.coerceAtLeast(1f)
        return WorldScene(WorldPreset.LUMON_INTERIOR, parts, inst, lamps = emptyList(), glows = emptyList(),
            min = V3(0f, 0f, 0f), max = V3(HALL_W, ceil, HALL_D), ceilingY = HEIGHT,
            mapX = { MARGIN + (it / s).coerceIn(0f, 1f) * (HALL_W - 2 * MARGIN) },
            mapZ = { MARGIN + (it / s).coerceIn(0f, 1f) * (HALL_D - 2 * MARGIN) },
            floorAt = { _, _ -> 0f },
            pointLights = ceilingLights())
    }

    /** Monitor CRT bege (frente em +Z) + teclado, sobre a mesa. */
    fun monitorBodyMesh(): LitMesh {
        val b = LitMeshBuilder()
        val y0 = DESK_H
        b.chamferBox(V3(-0.13f, y0, -0.28f), V3(0.13f, y0 + 0.04f, -0.02f), 0.01f)              // pé
        b.chamferBox(V3(-0.22f, y0 + 0.06f, -0.30f), V3(0.22f, y0 + 0.44f, 0.12f), 0.035f)       // gabinete
        b.chamferBox(V3(-0.16f, y0 + 0.12f, -0.52f), V3(0.16f, y0 + 0.38f, -0.30f), 0.05f)       // traseira do tubo
        b.chamferBox(V3(-0.23f, y0, 0.18f), V3(0.23f, y0 + 0.025f, 0.32f), 0.008f)               // teclado
        return b.build()
    }

    fun monitorScreenMesh(): LitMesh {
        val b = LitMeshBuilder()
        val y0 = DESK_H
        b.rect(V3(-0.175f, y0 + 0.11f, 0.122f), V3(0.35f, 0f, 0f), V3(0f, 0.27f, 0f))
        return b.build()
    }

    /** Cadeira ergonômica (frente em +Z): assento, encosto com apoio lombar e braços estofados. */
    fun chairFabricMesh(): LitMesh {
        val b = LitMeshBuilder()
        b.chamferBox(V3(-0.25f, 0.43f, -0.22f), V3(0.25f, 0.51f, 0.26f), 0.035f)
        b.chamferBox(V3(-0.23f, 0.62f, -0.32f), V3(0.23f, 1.08f, -0.25f), 0.03f)
        b.chamferBox(V3(-0.20f, 0.66f, -0.27f), V3(0.20f, 0.80f, -0.22f), 0.02f)   // lombar
        b.chamferBox(V3(-0.30f, 0.66f, -0.12f), V3(-0.25f, 0.69f, 0.16f), 0.012f)
        b.chamferBox(V3(0.25f, 0.66f, -0.12f), V3(0.30f, 0.69f, 0.16f), 0.012f)
        return b.build()
    }

    fun chairMetalMesh(): LitMesh {
        val b = LitMeshBuilder()
        b.cylinder(0f, 0f, 0.09f, 0.43f, 0.028f, segments = 10)
        b.cylinder(0f, 0f, 0.22f, 0.34f, 0.042f, segments = 10, caps = false)
        for (k in 0 until 5) {
            val a = (2.0 * PI * k / 5).toFloat()
            val tip = V3(sin(a) * 0.31f, 0.07f, cos(a) * 0.31f)
            b.tube(V3(0f, 0.10f, 0f), tip, 0.018f)
            b.sphere(V3(tip.x, 0.035f, tip.z), 0.035f, 0.035f, 0.035f, rings = 4, segments = 6)
        }
        b.tube(V3(0f, 0.47f, -0.18f), V3(0f, 0.70f, -0.29f), 0.02f)
        b.tube(V3(-0.275f, 0.47f, 0.02f), V3(-0.275f, 0.66f, 0.02f), 0.012f)
        b.tube(V3(0.275f, 0.47f, 0.02f), V3(0.275f, 0.66f, 0.02f), 0.012f)
        return b.build()
    }
}
