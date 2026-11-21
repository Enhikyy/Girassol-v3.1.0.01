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

    /**
     * Liquida uma troca bilateral em duas pernas de recursos distintas.
     * Ambas são pré-validadas antes de qualquer mutação; assim não há alimento
     * transferido sem a contrapartida em crédito (ou vice-versa).
     */
    fun exchange(
        firstFrom: String,
        firstTo: String,
        firstResource: String,
        firstQuantity: Double,
        secondFrom: String,
        secondTo: String,
        secondResource: String,
        secondQuantity: Double,
        tick: Long,
        eventHash: String
    ): Boolean {
        if (firstResource == secondResource || firstFrom == firstTo || secondFrom == secondTo) return false
        fun toMicro(quantity: Double): Long? {
            if (!quantity.isFinite() || quantity <= 0.0 || quantity * MICRO >= Long.MAX_VALUE.toDouble()) return null
            return Math.round(quantity * MICRO).takeIf { it > 0L }
        }
        fun canTransfer(from: String, to: String, resource: String, amount: Long): Boolean {
            val source = balancesMicro[from]?.get(resource) ?: 0L
            val destination = balancesMicro[to]?.get(resource) ?: 0L
            return source >= amount && destination <= Long.MAX_VALUE - amount
        }
        val first = toMicro(firstQuantity) ?: return false
        val second = toMicro(secondQuantity) ?: return false
        if (!canTransfer(firstFrom, firstTo, firstResource, first) ||
            !canTransfer(secondFrom, secondTo, secondResource, second)) return false

        // Como os recursos são diferentes, a primeira perna não altera o saldo
        // usado pela segunda. As duas chamadas só podem falhar se o ledger estiver corrompido.
        check(transfer(firstFrom, firstTo, firstResource, first / MICRO, tick, eventHash))
        check(transfer(secondFrom, secondTo, secondResource, second / MICRO, tick, eventHash))
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

/** Cotação subjetiva usada no escambo: valor marginal, escassez e preços de trocas anteriores. */
data class SubjectiveMarketQuote(
    val resourceType: String,
    val currencyType: String,
    val quantity: Double,
    val referenceUnitPrice: Double,
    val unitPrice: Double,
    val totalPrice: Double,
    val buyerUrgency: Double,
    val sellerSurplus: Double,
    val communalScarcity: Double
)

/**
 * Livro de preços leve derivado do próprio diário de partidas dobradas.
 * Não mantém uma segunda fonte de verdade: o histórico de preços é reconstituído
 * de duas pernas do mesmo evento canônico (recurso vendido + moeda paga).
 */
object SubjectiveMarket {
    private data class ObservedTrade(val tick: Long, val entryId: Long, val unitPrice: Double)

    fun quote(
        ledger: StockFlowDoubleEntryLedger,
        resourceType: String,
        currencyType: String,
        quantity: Double,
        buyerNeed: Double,
        buyerInventory: Double,
        sellerInventory: Double,
        communalInventory: Double,
        initialUnitPrice: Double = 1.5,
        buyerTargetInventory: Double = 2.0,
        sellerReserve: Double = 2.0,
        communalTargetInventory: Double = 8.0
    ): SubjectiveMarketQuote? {
        if (!quantity.isFinite() || quantity <= 0.0 ||
            !initialUnitPrice.isFinite() || initialUnitPrice <= 0.0 ||
            buyerTargetInventory <= 0.0 || sellerReserve <= 0.0 || communalTargetInventory <= 0.0) return null

        val observations = observedPrices(ledger, resourceType, currencyType).takeLast(12)
        val reference = observations.fold(initialUnitPrice) { price, observed ->
            price * 0.75 + observed.unitPrice * 0.25
        }.coerceIn(initialUnitPrice * 0.25, initialUnitPrice * 4.0)

        val hungerUrgency = (1.0 - buyerNeed.coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
        val inventoryShortage = ((buyerTargetInventory - buyerInventory) / buyerTargetInventory).coerceIn(0.0, 1.0)
        val buyerUrgency = (0.7 * hungerUrgency + 0.3 * inventoryShortage).coerceIn(0.0, 1.0)
        val sellerSurplus = ((sellerInventory - sellerReserve) / (sellerReserve * 2.0)).coerceIn(0.0, 1.0)
        val communalScarcity = ((communalTargetInventory - communalInventory) / communalTargetInventory).coerceIn(0.0, 1.0)

        val buyerBid = reference * (0.75 + 0.55 * buyerUrgency + 0.25 * inventoryShortage + 0.20 * communalScarcity)
        val sellerAsk = reference * (1.25 - 0.45 * sellerSurplus)
        val unit = (((buyerBid + sellerAsk) * 0.5).coerceIn(reference * 0.55, reference * 2.25) * 100.0)
            .let { kotlin.math.round(it) / 100.0 }
        val total = kotlin.math.round(unit * quantity * 100.0) / 100.0

        return SubjectiveMarketQuote(
            resourceType = resourceType,
            currencyType = currencyType,
            quantity = quantity,
            referenceUnitPrice = reference,
            unitPrice = unit,
            totalPrice = total,
            buyerUrgency = buyerUrgency,
            sellerSurplus = sellerSurplus,
            communalScarcity = communalScarcity
        )
    }

    private fun observedPrices(
        ledger: StockFlowDoubleEntryLedger,
        resourceType: String,
        currencyType: String
    ): List<ObservedTrade> = ledger.recentEntries(1024)
        .groupBy { it.canonicalEventHash }
        .values
        .mapNotNull { entries ->
            val resources = entries.filter { it.resourceType == resourceType && it.quantity > 0.0 }
            val payments = entries.filter { it.resourceType == currencyType && it.quantity > 0.0 }
            resources.firstNotNullOfOrNull { resource ->
                payments.firstOrNull { payment ->
                    payment.fromAccount == resource.toAccount && payment.toAccount == resource.fromAccount
                }?.let { payment ->
                    if (resource.quantity <= 0.0) null
                    else ObservedTrade(resource.tick, resource.entryId, payment.quantity / resource.quantity)
                }
            }
        }
        .filter { it.unitPrice.isFinite() && it.unitPrice > 0.0 }
        .sortedWith(compareBy<ObservedTrade> { it.tick }.thenBy { it.entryId })
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
