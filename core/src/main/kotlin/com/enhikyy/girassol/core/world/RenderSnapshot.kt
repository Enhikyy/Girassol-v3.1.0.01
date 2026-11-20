package com.enhikyy.girassol.core.world

/** Snapshot imutável publicado ao renderizador/UI (desacoplado do WorldCore). */
data class AgentView(
    val id: String, val name: String, val x: Int, val z: Int, val action: ActionType?,
    val mood: Double, val meanNeed: Double, val generation: Int, val thought: String,
    val speech: String = "", val speechAgeTicks: Long = Long.MAX_VALUE, val neural: Boolean = false
)

data class ObjectView(val id: String, val type: SmartObjectType, val x: Int, val z: Int, val contaminated: Boolean, val occupancy: Int)

data class WorldRenderSnapshot(
    val tick: Long,
    val day: Long,
    val night: Boolean,
    val drought: Boolean,
    val widthTiles: Int,
    val tiles: IntArray,
    val districts: List<District>,
    val objects: List<ObjectView>,
    val agents: List<AgentView>,
    val trail: FloatArray,
    val foodScent: FloatArray,
    val population: Int,
    val totalBorn: Int,
    val totalDied: Int,
    val metrics: WorldMetrics,
    val cityEnergy: Double,
    val commonsFood: Double,
    val lastHash: String,
    val policyVersion: Int
) {
    companion object {
        fun of(state: WorldState): WorldRenderSnapshot {
            val alive = state.aliveCitizens()
            return WorldRenderSnapshot(
                tick = state.tick, day = state.day(), night = state.isNight(), drought = state.inDrought(),
                widthTiles = state.widthTiles, tiles = state.tiles.copyOf(), districts = state.districts.toList(),
                objects = state.objects.map { ObjectView(it.id, it.type, it.tileX, it.tileZ, it.contaminatedUntilTick > state.tick, state.board.activeReservations(it.id, state.tick)) },
                agents = alive.map { AgentView(it.id, it.name, it.x, it.z, it.intention?.action, it.emotion.mood, it.meanNeed(), it.generation, it.lastThought,
                    it.lastSpeech, if (it.lastSpeechTick < 0) Long.MAX_VALUE else state.tick - it.lastSpeechTick, it.intention?.viaNeural == true) },
                trail = state.board.channelSnapshot(CH_TRAIL),
                foodScent = state.board.channelSnapshot(CH_FOOD),
                population = alive.size,
                totalBorn = state.metrics.births,
                totalDied = state.metrics.deaths,
                metrics = state.metrics.copy(),
                cityEnergy = state.cityEnergy,
                commonsFood = state.ledger.getBalance(StockFlowDoubleEntryLedger.COMMUNAL_COMMONS, FOOD),
                lastHash = state.lastEventHash,
                policyVersion = state.policy.version
            )
        }
    }
}
