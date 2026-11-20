package com.enhikyy.girassol.core.world

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Navegação contínua de multidão.
 *
 * - [DistrictNavMesh]: campo de folga (clearance, distância de Chebyshev até o tile bloqueado
 *   mais próximo, limitada a 3) usado pelo A* para preferir corredores largos, mais a contagem
 *   de tiles caminháveis por distrito. Recalculado apenas quando a grade muda.
 * - [stepRvo2OrcaVelocities]: porte direto do RVO2 (van den Berg et al.) — linhas ORCA por
 *   vizinho + programas lineares 1/2/3 — para velocidades livres de colisão entre agentes.
 * - [resolveCircleAABB]: resolução círculo × caixa para que nenhum cidadão atravesse
 *   edifícios ou água no 3D (o tile de destino é o único bloqueado que pode ser "entrado").
 */
object DistrictNavMesh {
    const val MAX_CLEARANCE = 3

    private var cachedW = -1
    private var cachedTiles: IntArray? = null
    private var cachedClearance: ByteArray = ByteArray(0)

    /** Campo de folga com cache por conteúdo (11 664 inteiros comparados — barato frente ao A*). */
    @Synchronized
    fun clearance(w: Int, tiles: IntArray): ByteArray {
        val prev = cachedTiles
        if (prev != null && cachedW == w && prev.contentEquals(tiles)) return cachedClearance
        val out = computeClearance(w, tiles)
        cachedW = w
        cachedTiles = tiles.copyOf()
        cachedClearance = out
        return out
    }

    /** Idêntico a computeClearance() em cpp/navigation/grid_astar.hpp. */
    fun computeClearance(w: Int, tiles: IntArray): ByteArray {
        val out = ByteArray(w * w)
        for (z in 0 until w) for (x in 0 until w) {
            val i = z * w + x
            if (!Pathfinding.walkable(tiles[i])) { out[i] = 0; continue }
            var best = MAX_CLEARANCE
            for (r in 1..MAX_CLEARANCE) {
                var hit = false
                for (dz in -r..r) {
                    for (dx in -r..r) {
                        if (max(abs(dx), abs(dz)) != r) continue
                        val nx = x + dx
                        val nz = z + dz
                        if (nx !in 0 until w || nz !in 0 until w || !Pathfinding.walkable(tiles[nz * w + nx])) { hit = true; break }
                    }
                    if (hit) break
                }
                if (hit) { best = r; break }
            }
            out[i] = best.toByte()
        }
        return out
    }

    /** Multiplicador de custo do A* pela folga: encostar em paredes custa mais. */
    fun clearanceCostFactor(clearance: Int): Float = when (clearance) {
        1 -> 1.25f
        2 -> 1.08f
        else -> 1.0f
    }

    data class DistrictStats(val districtId: String, val walkableTiles: Int, val meanClearance: Double)

    fun districtStats(state: WorldState): List<DistrictStats> {
        val cl = clearance(state.widthTiles, state.tiles)
        return state.districts.map { d ->
            var n = 0
            var sum = 0.0
            val ox = d.dx * DISTRICT_SIZE
            val oz = d.dz * DISTRICT_SIZE
            for (z in oz until oz + DISTRICT_SIZE) for (x in ox until ox + DISTRICT_SIZE) {
                if (x !in 0 until state.widthTiles || z !in 0 until state.widthTiles) continue
                val i = z * state.widthTiles + x
                if (cl[i] > 0) { n++; sum += cl[i] }
            }
            DistrictStats(d.id, n, if (n == 0) 0.0 else sum / n)
        }
    }
}

data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Double) = Vec2(x * s, y * s)
    operator fun unaryMinus() = Vec2(-x, -y)
    infix fun dot(o: Vec2) = x * o.x + y * o.y
    fun absSq() = x * x + y * y
    fun length() = sqrt(absSq())
    fun normalized(): Vec2 { val l = length(); return if (l <= 1e-12) Vec2(0.0, 0.0) else Vec2(x / l, y / l) }

    companion object {
        val ZERO = Vec2(0.0, 0.0)
        fun det(a: Vec2, b: Vec2) = a.x * b.y - a.y * b.x
    }
}

data class OrcaAgent(val position: Vec2, val velocity: Vec2, val preferred: Vec2, val radius: Double, val maxSpeed: Double)

private data class OrcaLine(val point: Vec2, val direction: Vec2)

object CrowdNavigation {
    const val AGENT_RADIUS = 0.2  // dois agentes (0,8) cabem lado a lado numa rua de 1 tile
    const val TIME_HORIZON = 2.0
    const val NEIGHBOR_DIST = 3.0
    const val MAX_NEIGHBORS = 6
    private const val EPS = 1e-5

    /**
     * Velocidades ORCA para todos os agentes (mesma ordem de entrada). Vizinhos ordenados por
     * distância e índice — determinístico. [timeStep] = 1 tick.
     */
    fun stepRvo2OrcaVelocities(agents: List<OrcaAgent>, timeStep: Double = 1.0): List<Vec2> {
        val out = ArrayList<Vec2>(agents.size)
        for (i in agents.indices) {
            val a = agents[i]
            val neighbors = agents.indices.filter { j -> j != i && (agents[j].position - a.position).absSq() < NEIGHBOR_DIST * NEIGHBOR_DIST }
                .sortedWith(compareBy<Int> { j -> (agents[j].position - a.position).absSq() }.thenBy { it })
                .take(MAX_NEIGHBORS)
            val lines = ArrayList<OrcaLine>()
            val invTimeHorizon = 1.0 / TIME_HORIZON
            for (j in neighbors) {
                val b = agents[j]
                val relativePosition = b.position - a.position
                val relativeVelocity = a.velocity - b.velocity
                val distSq = relativePosition.absSq()
                val combinedRadius = a.radius + b.radius
                val combinedRadiusSq = combinedRadius * combinedRadius
                val direction: Vec2
                val u: Vec2
                if (distSq > combinedRadiusSq) {
                    val w = relativeVelocity - relativePosition * invTimeHorizon
                    val wLengthSq = w.absSq()
                    val dotProduct1 = w dot relativePosition
                    if (dotProduct1 < 0.0 && dotProduct1 * dotProduct1 > combinedRadiusSq * wLengthSq) {
                        val wLength = sqrt(wLengthSq)
                        val unitW = w * (1.0 / wLength)
                        direction = Vec2(unitW.y, -unitW.x)
                        u = unitW * (combinedRadius * invTimeHorizon - wLength)
                    } else {
                        val leg = sqrt(distSq - combinedRadiusSq)
                        direction = if (Vec2.det(relativePosition, w) > 0.0) {
                            Vec2(relativePosition.x * leg - relativePosition.y * combinedRadius,
                                relativePosition.x * combinedRadius + relativePosition.y * leg) * (1.0 / distSq)
                        } else {
                            -Vec2(relativePosition.x * leg + relativePosition.y * combinedRadius,
                                -relativePosition.x * combinedRadius + relativePosition.y * leg) * (1.0 / distSq)
                        }
                        val dotProduct2 = relativeVelocity dot direction
                        u = direction * dotProduct2 - relativeVelocity
                    }
                } else {
                    // já em colisão: resolve no próximo passo de tempo
                    val invTimeStep = 1.0 / timeStep
                    val w = relativeVelocity - relativePosition * invTimeStep
                    val wLength = w.length()
                    val unitW = if (wLength <= 1e-12) Vec2(1.0, 0.0) else w * (1.0 / wLength)
                    direction = Vec2(unitW.y, -unitW.x)
                    u = unitW * (combinedRadius * invTimeStep - wLength)
                }
                lines += OrcaLine(a.velocity + u * 0.5, direction)
            }
            val result = arrayOf(Vec2.ZERO)
            val lineFail = linearProgram2(lines, a.maxSpeed, a.preferred, false, result)
            if (lineFail < lines.size) linearProgram3(lines, 0, lineFail, a.maxSpeed, result)
            out += result[0]
        }
        return out
    }

    private fun linearProgram1(lines: List<OrcaLine>, lineNo: Int, radius: Double, optVelocity: Vec2, directionOpt: Boolean, result: Array<Vec2>): Boolean {
        val line = lines[lineNo]
        val dotProduct = line.point dot line.direction
        val discriminant = dotProduct * dotProduct + radius * radius - line.point.absSq()
        if (discriminant < 0.0) return false
        val sqrtDiscriminant = sqrt(discriminant)
        var tLeft = -dotProduct - sqrtDiscriminant
        var tRight = -dotProduct + sqrtDiscriminant
        for (i in 0 until lineNo) {
            val denominator = Vec2.det(line.direction, lines[i].direction)
            val numerator = Vec2.det(lines[i].direction, line.point - lines[i].point)
            if (abs(denominator) <= EPS) {
                if (numerator < 0.0) return false
                continue
            }
            val t = numerator / denominator
            if (denominator >= 0.0) tRight = min(tRight, t) else tLeft = max(tLeft, t)
            if (tLeft > tRight) return false
        }
        result[0] = if (directionOpt) {
            if ((optVelocity dot line.direction) > 0.0) line.point + line.direction * tRight else line.point + line.direction * tLeft
        } else {
            val t = line.direction dot (optVelocity - line.point)
            when {
                t < tLeft -> line.point + line.direction * tLeft
                t > tRight -> line.point + line.direction * tRight
                else -> line.point + line.direction * t
            }
        }
        return true
    }

    private fun linearProgram2(lines: List<OrcaLine>, radius: Double, optVelocity: Vec2, directionOpt: Boolean, result: Array<Vec2>): Int {
        result[0] = when {
            directionOpt -> optVelocity * radius
            optVelocity.absSq() > radius * radius -> optVelocity.normalized() * radius
            else -> optVelocity
        }
        for (i in lines.indices) {
            if (Vec2.det(lines[i].direction, lines[i].point - result[0]) > 0.0) {
                val temp = result[0]
                if (!linearProgram1(lines, i, radius, optVelocity, directionOpt, result)) {
                    result[0] = temp
                    return i
                }
            }
        }
        return lines.size
    }

    private fun linearProgram3(lines: List<OrcaLine>, numObstLines: Int, beginLine: Int, radius: Double, result: Array<Vec2>) {
        var distance = 0.0
        for (i in beginLine until lines.size) {
            if (Vec2.det(lines[i].direction, lines[i].point - result[0]) > distance) {
                val projLines = ArrayList<OrcaLine>()
                for (j in numObstLines until i) {
                    val determinant = Vec2.det(lines[i].direction, lines[j].direction)
                    val point: Vec2
                    if (abs(determinant) <= EPS) {
                        if ((lines[i].direction dot lines[j].direction) > 0.0) continue
                        point = (lines[i].point + lines[j].point) * 0.5
                    } else {
                        point = lines[i].point + lines[i].direction * (Vec2.det(lines[j].direction, lines[i].point - lines[j].point) / determinant)
                    }
                    projLines += OrcaLine(point, (lines[j].direction - lines[i].direction).normalized())
                }
                val temp = result[0]
                if (linearProgram2(projLines, radius, Vec2(-lines[i].direction.y, lines[i].direction.x), true, result) < projLines.size) {
                    result[0] = temp
                }
                distance = Vec2.det(lines[i].direction, lines[i].point - result[0])
            }
        }
    }

    /**
     * Empurra o círculo (cx, cz, r) para fora da caixa [minX,maxX]×[minZ,maxZ]. Se o centro
     * estiver dentro da caixa, sai pela face mais próxima. Devolve a posição corrigida.
     */
    fun resolveCircleAABB(cx: Double, cz: Double, r: Double, minX: Double, minZ: Double, maxX: Double, maxZ: Double): Vec2 {
        val qx = cx.coerceIn(minX, maxX)
        val qz = cz.coerceIn(minZ, maxZ)
        val dx = cx - qx
        val dz = cz - qz
        val d2 = dx * dx + dz * dz
        if (d2 > r * r) return Vec2(cx, cz)
        if (d2 > 1e-18) {
            val d = sqrt(d2)
            val push = (r - d) + 1e-9
            return Vec2(cx + dx / d * push, cz + dz / d * push)
        }
        // centro dentro da caixa: menor penetração
        val left = cx - minX
        val right = maxX - cx
        val down = cz - minZ
        val up = maxZ - cz
        val m = min(min(left, right), min(down, up))
        return when (m) {
            left -> Vec2(minX - r - 1e-9, cz)
            right -> Vec2(maxX + r + 1e-9, cz)
            down -> Vec2(cx, minZ - r - 1e-9)
            else -> Vec2(cx, maxZ + r + 1e-9)
        }
    }

    /**
     * Colisão contra todos os tiles bloqueados em torno de (px, pz), exceto [allowedTiles]
     * (destino e o tile de onde o agente está saindo). Duas passadas cobrem cantos.
     */
    fun resolveAgainstGrid(w: Int, tiles: IntArray, px: Double, pz: Double, r: Double, allowedTiles: Set<Int>): Vec2 {
        var p = Vec2(px.coerceIn(r, w - r), pz.coerceIn(r, w - r))
        repeat(2) {
            val tx = floor(p.x).toInt()
            val tz = floor(p.y).toInt()
            for (dz in -1..1) for (dx in -1..1) {
                val nx = tx + dx
                val nz = tz + dz
                if (nx !in 0 until w || nz !in 0 until w) continue
                val idx = nz * w + nx
                if (idx in allowedTiles || Pathfinding.walkable(tiles[idx])) continue
                p = resolveCircleAABB(p.x, p.y, r, nx.toDouble(), nz.toDouble(), nx + 1.0, nz + 1.0)
            }
        }
        return p
    }
}
