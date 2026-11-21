package com.enhikyy.girassol.core.world

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.math.hypot

@Serializable
data class LedgerEntry(
    val entryId: Long,
    val tick: Long,
    val fromAccount: String,
    val toAccount: String,
    val resourceType: String,
    val quantity: Double,
    val canonicalEventHash: String
)

/**
 * Livro-razão de partidas dobradas (Stock-Flow Consistent).
 * Os saldos são mantidos internamente em micro-unidades inteiras (Long), de modo que
 * a soma de todas as contas de um recurso é exatamente zero após qualquer sequência
 * de transferências (ΔA + ΔB = 0 sem erro de ponto flutuante).
 */
@Serializable
class StockFlowDoubleEntryLedger(
    private val balancesMicro: MutableMap<String, MutableMap<String, Long>> = mutableMapOf(),
    private val journal: MutableList<LedgerEntry> = mutableListOf(),
    private var nextEntryId: Long = 1L,
    private var journalWindow: Int = 4096
) {
    @Transient
    private val pendingForPersistence = mutableListOf<LedgerEntry>()

    fun getBalance(accountId: String, resourceType: String): Double =
        (balancesMicro[accountId]?.get(resourceType) ?: 0L) / MICRO

    fun mintInitialEndowment(
        accountId: String,
        resourceType: String,
        amount: Double,
        tick: Long,
        eventHash: String
    ) {
        require(amount >= 0.0)
        transfer(NATURE_RESERVOIR, accountId, resourceType, amount, tick, eventHash, allowNegativeSource = true)
    }

    fun contributeToCommunalPool(
        agentAccountId: String,
        resourceType: String,
        quantity: Double,
        tick: Long,
        eventHash: String
    ): Boolean = transfer(agentAccountId, COMMUNAL_COMMONS, resourceType, quantity, tick, eventHash)

    fun withdrawFromCommunalPool(
        agentAccountId: String,
        resourceType: String,
        quantity: Double,
        tick: Long,
        eventHash: String
    ): Boolean = transfer(COMMUNAL_COMMONS, agentAccountId, resourceType, quantity, tick, eventHash)

    fun transfer(
        fromAccount: String,
        toAccount: String,
        resourceType: String,
        quantity: Double,
        tick: Long,
        eventHash: String,
        allowNegativeSource: Boolean = false
    ): Boolean {
        if (!(quantity > 0.0) || quantity.isInfinite() || fromAccount == toAccount) return false
        val q = Math.round(quantity * MICRO)
        if (q <= 0L) return false
        val srcMap = balancesMicro.getOrPut(fromAccount) { mutableMapOf() }
        val srcBal = srcMap[resourceType] ?: 0L
        if (!allowNegativeSource && srcBal < q) return false
        srcMap[resourceType] = srcBal - q
        val dstMap = balancesMicro.getOrPut(toAccount) { mutableMapOf() }
        dstMap[resourceType] = (dstMap[resourceType] ?: 0L) + q
        val entry = LedgerEntry(nextEntryId++, tick, fromAccount, toAccount, resourceType, q / MICRO, eventHash)
        journal.add(entry)
        if (journal.size > journalWindow) journal.subList(0, journal.size - journalWindow).clear()
        pendingForPersistence.add(entry)
        return true
    }

    /** Remove o saldo inteiro de uma conta (ex.: herança) transferindo-o para outra conta. */
    fun transferAll(fromAccount: String, toAccount: String, tick: Long, eventHash: String) {
        val map = balancesMicro[fromAccount] ?: return
        for (res in map.keys.sorted()) {
            val bal = map[res] ?: 0L
            if (bal > 0L) transfer(fromAccount, toAccount, res, bal / MICRO, tick, eventHash)
        }
    }

    fun verifyZeroSumConservation(resourceType: String): Boolean {
        var netSum = 0L
        for ((_, resMap) in balancesMicro) netSum += resMap[resourceType] ?: 0L
        return netSum == 0L
    }

    /** Saldos em micro-unidades achatados (conta × recurso) para a verificação nativa soma-zero. */
    fun packMicroBalances(resourceIndex: Map<String, Int>): Pair<LongArray, IntArray> {
        var n = 0
        for (m in balancesMicro.values) n += m.size
        val b = LongArray(n)
        val r = IntArray(n)
        var i = 0
        for (acc in balancesMicro.keys.sorted()) for ((res, v) in balancesMicro.getValue(acc).toSortedMap()) {
            b[i] = v; r[i] = resourceIndex[res] ?: -1; i++
        }
        return b to r
    }

    fun resourceTypes(): Set<String> = balancesMicro.values.flatMap { it.keys }.toSortedSet()

    fun accounts(): List<String> = balancesMicro.keys.sorted()

    fun recentEntries(limit: Int = 50): List<LedgerEntry> = journal.takeLast(limit)

    fun totalEntries(): Long = nextEntryId - 1

    /** Entradas ainda não persistidas no SQLite (consumidas pelo escritor único). */
    fun drainPendingEntries(): List<LedgerEntry> {
        val out = pendingForPersistence.toList()
        pendingForPersistence.clear()
        return out
    }

    companion object {
        const val MICRO = 1_000_000.0
        const val NATURE_RESERVOIR = "NATURE_RESERVOIR"
        const val COMMUNAL_COMMONS = "COMMUNAL_COMMONS"
        const val METABOLIC_SINK = "METABOLIC_SINK"
        const val CITY_GRID = "CITY_GRID"
        const val CENTRAL_BANK = "CENTRAL_BANK"
    }
}

@Serializable
data class EcologicalNicheStructure(
    val structureId: String,
    val structureType: String,
    val gridX: Int,
    val gridZ: Int,
    val level: Int,
    val structuralIntegrity: Double,
    val builderAgentId: String,
    val builtAtTick: Long
) {
    fun ecologicalEfficiencyBonus(): Double = (0.08 * level * structuralIntegrity).coerceIn(0.0, 0.45)
}

@Serializable
data class AffordanceAdvertisement(
    val sourceEntityId: String,
    val actionType: String,
    val x: Float,
    val z: Float,
    val broadcastRadius: Float,
    val maxConcurrentUsers: Int = 1
)

enum class ToilStatus { RUNNING, SUCCEEDED, FAILED_INTERRUPTED }

interface AtomicToil {
    val name: String
    fun tick(agentId: String, currentTick: Long): ToilStatus
}

class PhysicalJobPipeline(
    val jobId: String,
    val targetEntityId: String,
    private val toils: List<AtomicToil>,
    private val board: SpatialStigmergyAndReservationBoard
) {
    private var currentIndex = 0

    fun step(agentId: String, currentTick: Long): ToilStatus {
        if (currentIndex >= toils.size) return ToilStatus.SUCCEEDED
        val status = toils[currentIndex].tick(agentId, currentTick)
        return when (status) {
            ToilStatus.RUNNING -> ToilStatus.RUNNING
            ToilStatus.SUCCEEDED -> {
                currentIndex++
                if (currentIndex >= toils.size) {
                    board.releaseReservation(targetEntityId, agentId)
                    ToilStatus.SUCCEEDED
                } else {
                    ToilStatus.RUNNING
                }
            }
            ToilStatus.FAILED_INTERRUPTED -> {
                board.releaseReservation(targetEntityId, agentId)
                ToilStatus.FAILED_INTERRUPTED
            }
        }
    }
}

@Serializable
class SpatialStigmergyAndReservationBoard(
    private var width: Int,
    private var height: Int,
    private val cellSize: Float = 2.0f,
    private val advertisements: MutableMap<String, AffordanceAdvertisement> = mutableMapOf(),
    private val reservations: MutableMap<String, MutableMap<String, Long>> = mutableMapOf(),
    private val pheromoneChannels: MutableMap<String, FloatArray> = mutableMapOf(),
    private val nicheStructures: MutableMap<String, EcologicalNicheStructure> = mutableMapOf()
) {
    fun cellSizeWorld(): Float = cellSize
    fun gridWidth(): Int = width
    fun gridHeight(): Int = height

    /** Expande a grade preservando os campos já depositados (expansão urbana sem teto fixo). */
    fun resize(newWidth: Int, newHeight: Int) {
        if (newWidth == width && newHeight == height) return
        for (key in pheromoneChannels.keys.toList()) {
            val old = pheromoneChannels.getValue(key)
            val grid = FloatArray(newWidth * newHeight)
            for (z in 0 until minOf(height, newHeight)) {
                for (x in 0 until minOf(width, newWidth)) grid[z * newWidth + x] = old[z * width + x]
            }
            pheromoneChannels[key] = grid
        }
        width = newWidth
        height = newHeight
    }

    private fun cellIndex(worldX: Float, worldZ: Float): Int {
        val gx = (worldX / cellSize).toInt().coerceIn(0, width - 1)
        val gz = (worldZ / cellSize).toInt().coerceIn(0, height - 1)
        return gz * width + gx
    }

    fun depositPheromone(channel: String, worldX: Float, worldZ: Float, amount: Float) {
        val grid = pheromoneChannels.getOrPut(channel) { FloatArray(width * height) }
        val idx = cellIndex(worldX, worldZ)
        grid[idx] = (grid[idx] + amount).coerceIn(0f, 100f)
    }

    fun readPheromone(channel: String, worldX: Float, worldZ: Float): Float {
        val grid = pheromoneChannels[channel] ?: return 0f
        return grid[cellIndex(worldX, worldZ)]
    }

    fun channelSnapshot(channel: String): FloatArray =
        pheromoneChannels[channel]?.copyOf() ?: FloatArray(width * height)

    fun channelNames(): List<String> = pheromoneChannels.keys.sorted()

    fun stepDiffusionAndEvaporation(evap: Float = 0.03f, diff: Float = 0.12f) {
        for (key in pheromoneChannels.keys.sorted()) {
            val grid = pheromoneChannels.getValue(key)
            val next = FloatArray(grid.size)
            for (z in 0 until height) {
                for (x in 0 until width) {
                    val idx = z * width + x
                    val c = grid[idx]
                    val n = if (z > 0) grid[(z - 1) * width + x] else c
                    val s = if (z < height - 1) grid[(z + 1) * width + x] else c
                    val w = if (x > 0) grid[z * width + (x - 1)] else c
                    val e = if (x < width - 1) grid[z * width + (x + 1)] else c
                    val v = (((1f - diff) * c + diff * 0.25f * (n + s + w + e)) * (1f - evap)).coerceAtLeast(0f)
                    next[idx] = if (v < 1e-4f) 0f else v
                }
            }
            next.copyInto(grid)
        }
    }

    fun publishAdvertisement(ad: AffordanceAdvertisement) {
        advertisements[ad.sourceEntityId] = ad
    }

    fun advertisement(entityId: String): AffordanceAdvertisement? = advertisements[entityId]

    fun registerNicheStructure(structure: EcologicalNicheStructure) {
        nicheStructures[structure.structureId] = structure
    }

    fun nicheStructures(): List<EcologicalNicheStructure> = nicheStructures.values.sortedBy { it.structureId }

    fun totalDistrictNicheBonus(): Double =
        nicheStructures.values.sumOf { it.ecologicalEfficiencyBonus() }.coerceIn(0.0, 0.75)

    fun tryReserve(entityId: String, agentId: String, currentTick: Long, ttlTicks: Long = 30L): Boolean {
        val ad = advertisements[entityId] ?: return false
        val holders = reservations.getOrPut(entityId) { mutableMapOf() }
        holders.entries.removeIf { it.value <= currentTick }
        if (agentId in holders) {
            holders[agentId] = currentTick + ttlTicks
            return true
        }
        if (holders.size >= ad.maxConcurrentUsers) return false
        holders[agentId] = currentTick + ttlTicks
        return true
    }

    fun releaseReservation(entityId: String, agentId: String) {
        reservations[entityId]?.remove(agentId)
    }

    fun releaseAllFor(agentId: String) {
        for (holders in reservations.values) holders.remove(agentId)
    }

    fun activeReservations(entityId: String, currentTick: Long): Int =
        reservations[entityId].orEmpty().count { it.value > currentTick }

    /** Invariante: nenhuma affordance com mais reservas ativas que sua capacidade. */
    fun reservationViolations(currentTick: Long): Int =
        advertisements.values.count { ad -> activeReservations(ad.sourceEntityId, currentTick) > ad.maxConcurrentUsers }

    fun queryReachable(
        agentId: String,
        x: Float,
        z: Float,
        radius: Float,
        currentTick: Long
    ): List<Pair<AffordanceAdvertisement, Float>> =
        advertisements.values.mapNotNull { ad ->
            val active = reservations[ad.sourceEntityId].orEmpty().count { it.value > currentTick && it.key != agentId }
            if (active >= ad.maxConcurrentUsers) return@mapNotNull null
            val dist = hypot((ad.x - x).toDouble(), (ad.z - z).toDouble()).toFloat()
            if (dist <= minOf(radius, ad.broadcastRadius)) (ad to dist) else null
        }.sortedWith(compareBy<Pair<AffordanceAdvertisement, Float>> { it.second }.thenBy { it.first.sourceEntityId })
}
