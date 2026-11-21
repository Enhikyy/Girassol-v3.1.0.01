package com.enhikyy.girassol.core.world

import com.enhikyy.girassol.core.biology.DiploidGenome
import com.enhikyy.girassol.core.biology.SkillMasteryState
import com.enhikyy.girassol.core.biology.VoyagerSkillLibrary
import com.enhikyy.girassol.core.memory.AssociativeMemoryNode
import com.enhikyy.girassol.core.memory.SubjectiveOpinion
import com.enhikyy.girassol.core.qd.AuroraBehavioralEncoder
import com.enhikyy.girassol.core.qd.MapElitesArchive
import com.enhikyy.girassol.core.reflex.KotlinTsetlinMachine
import com.enhikyy.girassol.core.rsi.CognitivePolicyGenome
import com.enhikyy.girassol.core.rsi.RsiPromotionDecision
import com.enhikyy.girassol.core.social.AdicoInstitutionRule
import com.enhikyy.girassol.core.social.CliodynamicsSnapshot
import com.enhikyy.girassol.core.social.DunbarSocialNetwork
import com.enhikyy.girassol.core.forge.LoraAdapterState
import com.enhikyy.girassol.core.forge.LoraCycleRecord
import com.enhikyy.girassol.core.forge.LoraSample
import kotlinx.serialization.Serializable

/** Versão das regras do mundo. Eventos de versões anteriores mantêm suas regras históricas. */
const val CURRENT_RULES_VERSION = 12

@Serializable
enum class DistrictType(val label: String) {
    MORADIA("Moradia"),
    AGRICULTURA("Agricultura"),
    RECICLAGEM("Reciclagem"),
    ENERGIA("Energia"),
    PESQUISA("Pesquisa"),
    AGORA("Ágora")
}

@Serializable
enum class TileType(val walkable: Boolean) {
    GRAMA(true), RUA(true), CALCADA(true), EDIFICIO(false), CAMPO(true), PRACA(true), AGUA(false)
}

@Serializable
enum class SmartObjectType(val label: String, val capacity: Int) {
    CASA("Casa", 4),
    HORTA("Horta", 3),
    COMPOSTEIRA("Composteira", 2),
    USINA_SOLAR("Usina solar", 3),
    LABORATORIO("Laboratório", 3),
    ESCOLA("Escola", 6),
    PRACA("Praça", 10),
    OFICINA("Oficina", 2)
}

/** Seis motivos homeostáticos (1.0 = pleno, 0.0 = crítico). */
@Serializable
enum class Need(val label: String) {
    FOME("Saciedade"), ENERGIA("Energia"), SOCIAL("Social"), DIVERSAO("Diversão"), HIGIENE("Higiene"), PROPOSITO("Propósito")
}

@Serializable
enum class ActionType(val label: String, val skill: String?) {
    COMER("Comer", null),
    DESCANSAR("Descansar", null),
    BANHO("Banho", null),
    COLHER("Colher", "cultivo"),
    RECICLAR("Reciclar", "reciclagem"),
    MANTER_USINA("Manter usina", "manutencao"),
    PESQUISAR("Pesquisar", "pesquisa"),
    ESTUDAR("Estudar", null),
    SOCIALIZAR("Socializar", null),
    CONTRIBUIR("Contribuir aos comuns", null),
    CONSTRUIR("Construir", "construcao"),
    EXPLORAR("Explorar", null),
    OCIOSO("Ocioso", null)
}

val SKILLS = listOf("cultivo", "reciclagem", "manutencao", "pesquisa", "construcao")

@Serializable
data class District(
    val id: String,
    val dx: Int,
    val dz: Int,
    val type: DistrictType,
    val createdTick: Long
)

@Serializable
data class SmartObject(
    val id: String,
    val type: SmartObjectType,
    val districtId: String,
    val tileX: Int,
    val tileZ: Int,
    var condition: Double = 1.0,
    var uses: Long = 0L,
    /** Envenenado/defeituoso: usado por eventos de estresse epistêmico. */
    var contaminatedUntilTick: Long = -1L
)

@Serializable
data class EmotionState(
    var valence: Double = 0.2,
    var arousal: Double = 0.3,
    var mood: Double = 0.1,
    var lastAppraisal: String = "neutro",
    var rumination: Double = 0.0
)

@Serializable
data class Intention(
    val action: ActionType,
    val targetObjectId: String?,
    val targetX: Int,
    val targetZ: Int,
    val startedTick: Long,
    val expectedUtility: Double,
    val forecastSuccess: Double,
    val viaDeliberation: Boolean,
    val viaReflex: Boolean,
    val claimUsed: String? = null,
    val claimVerified: Boolean = true,
    val viaNeural: Boolean = false,
    /** Síntese aferente (Anokhin): necessidades projetadas e motivação no momento da decisão. */
    val expectedNeeds: List<Double> = emptyList(),
    val needsAtDecision: List<Double> = emptyList(),
    val memoryUtilityAtDecision: Double = 0.0
)

@Serializable
data class HeldBelief(
    val claimKey: String,
    val domain: String,
    var opinion: SubjectiveOpinion,
    var alphaEvidence: Double = 1.0,
    var betaEvidence: Double = 1.0,
    var verifiedByExperience: Boolean = false,
    var quarantined: Boolean = false,
    var sourceAgentId: String? = null,
    var provenanceEventId: Long? = null,
    var updatedTick: Long = 0L
)

@Serializable
data class Citizen(
    val id: String,
    val name: String,
    val generation: Int,
    val birthTick: Long,
    val parents: List<String>,
    val genome: DiploidGenome,
    var x: Int,
    var z: Int,
    val homeObjectId: String,
    val needs: MutableMap<Need, Double>,
    val emotion: EmotionState = EmotionState(),
    val skills: MutableMap<String, SkillMasteryState> = mutableMapOf(),
    val memories: MutableList<AssociativeMemoryNode> = mutableListOf(),
    val beliefs: MutableMap<String, HeldBelief> = mutableMapOf(),
    val social: DunbarSocialNetwork,
    var intention: Intention? = null,
    var path: MutableList<Int> = mutableListOf(),
    var actionTicksRemaining: Int = 0,
    var consecutiveFailures: Int = 0,
    var starvationTicks: Int = 0,
    var alive: Boolean = true,
    var deathTick: Long = -1L,
    var deathCause: String? = null,
    val actionCounts: MutableMap<ActionType, Int> = mutableMapOf(),
    val reflex: KotlinTsetlinMachine,
    var opinionCommons: Double = 0.0,
    val intrinsicOpinionCommons: Double = 0.0,
    var lastReward: Double = 0.0,
    var children: Int = 0,
    var lastReproductionTick: Long = -100000L,
    var sanctionsReceived: Int = 0,
    var windowUsage: MutableMap<String, Int> = mutableMapOf(),
    var nextMemoryId: Long = 1L,
    var actedOnUnverifiedClaims: Int = 0,
    var verifiedBeforeActing: Int = 0,
    var lastThought: String = "",
    var lastSleepConsolidationDay: Long = -1L,
    var successes: Int = 0,
    var failures: Int = 0,
    var lastAction: ActionType? = null,
    var lastSpeech: String = "",
    var lastSpeechTick: Long = -1L,
    var neuralSuggestion: String? = null,
    var neuralSuggestionTick: Long = -1L,
    var neuralDecisions: Int = 0,
    /** Últimas 6 falas (entrada do SemanticEntropyController do motor neural). */
    val recentSpeeches: MutableList<String> = mutableListOf(),
    /** Estado da rede CfC de 48 neurônios e níveis hormonais (ODEs de 1ª ordem). */
    var cfcState: DoubleArray = DoubleArray(48),
    var cortisol: Double = 0.3,
    var oxytocin: Double = 0.4,
    var dopamine: Double = 0.5,
    var locomotion: Double = 1.0,
    /** Posição contínua (centro do agente em tiles) e velocidade ORCA; NaN = sincronizar com (x, z). */
    var px: Double = Double.NaN,
    var pz: Double = Double.NaN,
    var vx: Double = 0.0,
    var vz: Double = 0.0,
    var stuckTicks: Int = 0,
    /** Adaptador pessoal de viés de ação (herdado por TIES-Merging). */
    var personalAdapter: FloatArray = FloatArray(18),
    /** Fitness média dos pais no nascimento (−1 = fundador) e se a superação já foi avaliada. */
    var parentsFitnessAtBirth: Double = -1.0,
    var superacaoEvaluated: Boolean = false,
    var superacao: Boolean = false,
    /** Aceitador de Resultados da Ação (Anokhin): último descompasso, valência e plasticidade. */
    var acceptorMismatch: Double = 0.0,
    var acceptorValence: Double = 0.0,
    var acceptorPlasticity: Double = 1.0,
    var acceptorConfirmations: Int = 0,
    var acceptorViolations: Int = 0,
    /**
     * v3.6 "Adão e Eva": chave do modelo .gguf exclusivo do fundador (1 modelo = 1 pessoa);
     * null para quem nasceu na simulação, que pensa com o modelo da linhagem ([lineageModelKey]).
     */
    var modelKey: String? = null,
    var lineageModelKey: String? = null,
    /** "F", "M" ou "" (desconhecido: compatível com qualquer parceiro, mundos antigos). */
    var sex: String = "",
    /**
     * v3.6 mérito reprodutivo: linha de base t0 (média das primeiras amostras) e média móvel
     * recente do descompasso do Aceitador de Resultados (Anokhin) e do Brier individual.
     */
    var acceptorBaseSum: Double = 0.0,
    var acceptorBaseN: Int = 0,
    var acceptorEma: Double = -1.0,
    var acceptorBaselineTick: Long = -1L,
    var brierBaseSum: Double = 0.0,
    var brierBaseN: Int = 0,
    var brierEma: Double = -1.0,
    var brierBaselineTick: Long = -1L,
    /** Promoções aprovadas creditadas (BoundedRsiPromotionGate: CMA-ES/RSI ou LoRA do sono). */
    var promotions: Int = 0,
    var promotionsAtLastReproduction: Int = 0,
    /** Referências ordenadas do buffer de trabalho deste cidadão (mais antigo → mais recente). */
    val workingMemoryIds: MutableList<Long> = mutableListOf()
) {
    val acceptorBaseline: Double get() = if (acceptorBaseN >= MERIT_BASELINE_SAMPLES) acceptorBaseSum / acceptorBaseN else -1.0
    val brierBaseline: Double get() = if (brierBaseN >= MERIT_BASELINE_SAMPLES) brierBaseSum / brierBaseN else -1.0

    /** Modelo com que este cidadão pensa: o próprio (fundador) ou o herdado da linhagem. */
    val effectiveModelKey: String? get() = modelKey ?: lineageModelKey
    val founder: Boolean get() = modelKey != null

    fun ageDays(tick: Long): Double = (tick - birthTick).toDouble() / TICKS_PER_DAY
    fun trait(name: String): Double = genome.expressTrait(name)
    fun meanNeed(): Double = needs.values.average()

    /** Aptidão de vida: taxa de sucesso (Laplace) ponderada pelo bem-estar médio. */
    fun lifeFitness(): Double = (successes + 1.0) / (successes + failures + 2.0) * (0.5 + 0.5 * meanNeed())
}

/** Amostras iniciais que formam a linha de base t0 do mérito reprodutivo. */
const val MERIT_BASELINE_SAMPLES = 10

/** Amostra diária: desempenho (Anokhin/Brier/promoções/LoRA), população por geração e economia. */
@Serializable
data class EvolutionSample(
    val day: Long,
    val population: Int,
    val maxGeneration: Int,
    val acceptorMismatch: Double,
    val brier: Double,
    val promotions: Int,
    val loraAccepted: Int,
    val cmaGeneration: Int,
    val psi: Double,
    val gini: Double,
    val commonsFood: Double,
    val ledgerEntries: Long
)

/** Mensagem do Chat: usuário → cidadão (ou canal aberto, citizenId = null) e respostas. */
@Serializable
data class ChatMessage(
    val id: Long,
    val tick: Long,
    val wallClockMs: Long,
    /** null = canal aberto. */
    val citizenId: String?,
    /** "usuario" ou id do cidadão que respondeu. */
    val author: String,
    val authorName: String,
    val text: String,
    /** "gguf:<modelo>", "simbolico" ou "usuario". */
    val engine: String
)

@Serializable
data class CanonicalEvent(
    val seq: Long,
    val tick: Long,
    val rulesVersion: Int,
    val type: String,
    val actorId: String?,
    val payload: String,
    val prevHash: String,
    val hash: String
)

@Serializable
data class TelemetryInput(
    val thermalTier: Int = 0,
    val availableMemoryMb: Long = 4096,
    val batteryPct: Int = 100,
    val charging: Boolean = true,
    /** Temperatura do SoC/bateria em °C (entra no custo C_neural(T_SoC) da EFE). */
    val socTempC: Double = 32.0
)

@Serializable
data class RsiLedgerRecord(
    val tick: Long,
    val proposerAgentId: String,
    val rationale: String,
    val decision: RsiPromotionDecision,
    val brierBefore: Double,
    val brierAfter: Double,
    val diversityBefore: Double,
    val diversityAfter: Double
)

@Serializable
data class StressEventRecord(
    val tick: Long,
    val kind: String,
    val description: String,
    val targetId: String?,
    var resolvedTick: Long = -1L,
    var actedBeforeVerification: Int = 0,
    var verifiedBeforeAction: Int = 0,
    var quarantinedBy: Int = 0
)

@Serializable
data class WorldMetrics(
    var semanticEntropy: Double = 1.0,
    var brierScore: Double = 0.25,
    var meanFreeEnergy: Double = 1.0,
    var survivalEfficiency: Double = 1.0,
    var deliberationsPer100Ticks: Double = 0.0,
    var reflexBypassesPer100Ticks: Double = 0.0,
    var invariantsViolated: Int = 0,
    var births: Int = 0,
    var deaths: Int = 0,
    var foodProduced: Double = 0.0,
    var foodConsumed: Double = 0.0,
    var energyProduced: Double = 0.0,
    var materialProduced: Double = 0.0,
    var mapElitesCoverage: Double = 0.0,
    var mapElitesNiches: Int = 0
)

@Serializable
data class WorldState(
    val seed: Long,
    val rulesVersion: Int = CURRENT_RULES_VERSION,
    var tick: Long = 0L,
    var wallClockUtcAtLastTick: Long = 0L,
    val districtSize: Int = DISTRICT_SIZE,
    var districtsPerSide: Int = 3,
    var tiles: IntArray = IntArray(0),
    val districts: MutableList<District> = mutableListOf(),
    val objects: MutableList<SmartObject> = mutableListOf(),
    val citizens: MutableList<Citizen> = mutableListOf(),
    val ledger: StockFlowDoubleEntryLedger = StockFlowDoubleEntryLedger(),
    var board: SpatialStigmergyAndReservationBoard,
    var policy: CognitivePolicyGenome = CognitivePolicyGenome(version = 1),
    val institutions: MutableList<AdicoInstitutionRule> = mutableListOf(),
    val skillLibrary: VoyagerSkillLibrary = VoyagerSkillLibrary(),
    val mapElites: MapElitesArchive = MapElitesArchive(8),
    val aurora: AuroraBehavioralEncoder = AuroraBehavioralEncoder(8),
    var cliodynamics: CliodynamicsSnapshot? = null,
    val metrics: WorldMetrics = WorldMetrics(),
    val rsiLedger: MutableList<RsiLedgerRecord> = mutableListOf(),
    val stressEvents: MutableList<StressEventRecord> = mutableListOf(),
    val recentEvents: MutableList<CanonicalEvent> = mutableListOf(),
    var lastEventHash: String = GENESIS_HASH,
    var eventSeq: Long = 0L,
    var nextCitizenSerial: Long = 1L,
    var nextObjectSerial: Long = 1L,
    var telemetry: TelemetryInput = TelemetryInput(),
    val forecastWindow: MutableList<Double> = mutableListOf(),
    val outcomeWindow: MutableList<Boolean> = mutableListOf(),
    val deliberationTicks: MutableList<Long> = mutableListOf(),
    val reflexTicks: MutableList<Long> = mutableListOf(),
    var droughtUntilTick: Long = -1L,
    var cityEnergy: Double = 50.0,
    var admissionCap: Int = 150,
    var dayFoodProduced: Double = 0.0,
    var dayFoodConsumed: Double = 0.0,
    var lastExpansionTick: Long = -100000L,
    var lora: LoraAdapterState = LoraAdapterState.initial(seed xor 0x10BA_0A0AL),
    val loraPendingSamples: MutableList<LoraSample> = mutableListOf(),
    val loraLedger: MutableList<LoraCycleRecord> = mutableListOf(),
    var neuralDecisionsApplied: Long = 0L,
    var speechesEmitted: Long = 0L,
    /** Estatísticas da recuperação episódica em 2 estágios (Hamming → RRF). */
    var retrievalQueries: Long = 0L,
    var retrievalStage1Total: Long = 0L,
    var retrievalCorpusTotal: Long = 0L,
    var swrReplayUpdates: Long = 0L,
    /** Navegação contínua: colisões resolvidas contra edifícios e correções ORCA. */
    var aabbCorrections: Long = 0L,
    var orcaAdjustedSteps: Long = 0L,
    var superacoes: Int = 0,
    var tiesMerges: Int = 0,
    /** v3.5: estado persistente do CMA-ES do núcleo evolutivo (null até o 1º ciclo RSI). */
    var cmaEs: com.enhikyy.girassol.core.rsi.CmaEsState? = null,
    /** v3.5: biblioteca de conhecimento (Processo 19) e léxico comprimido emergente. */
    val knowledge: com.enhikyy.girassol.core.knowledge.KnowledgeShelf = com.enhikyy.girassol.core.knowledge.KnowledgeShelf(),
    val lexicon: com.enhikyy.girassol.core.knowledge.EmergentLexicon = com.enhikyy.girassol.core.knowledge.EmergentLexicon(),
    var acceptorMeanMismatch: Double = 0.0,
    /** Cidadãos recebidos/enviados por MeshSync LAN. */
    var migrantsIn: Int = 0,
    var migrantsOut: Int = 0,
    /** v3.6: contribuintes das amostras LoRA pendentes (creditados se o ciclo do sono for aceito). */
    val loraContributors: MutableSet<String> = mutableSetOf(),
    /** v3.6: conversa persistente (aba Chat), também no .grs e na tabela chat_messages. */
    val chat: MutableList<ChatMessage> = mutableListOf(),
    var nextChatSerial: Long = 1L,
    /** v3.6: série diária de evolução (gráficos unificados da aba Evolução). */
    val evolutionHistory: MutableList<EvolutionSample> = mutableListOf()
) {
    val widthTiles: Int get() = districtsPerSide * districtSize
    fun tileAt(x: Int, z: Int): TileType = TileType.entries[tiles[z * widthTiles + x]]
    fun inBounds(x: Int, z: Int) = x in 0 until widthTiles && z in 0 until widthTiles
    fun aliveCitizens(): List<Citizen> = citizens.filter { it.alive }
    fun citizen(id: String): Citizen? = citizens.firstOrNull { it.id == id }
    fun obj(id: String): SmartObject? = objects.firstOrNull { it.id == id }
    fun isNight(): Boolean = (tick % TICKS_PER_DAY) >= TICKS_PER_DAY * 3 / 4
    fun day(): Long = tick / TICKS_PER_DAY
    fun inDrought(): Boolean = tick < droughtUntilTick
}

const val DISTRICT_SIZE = 12
const val TICKS_PER_DAY = 480L
const val GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"
