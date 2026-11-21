package com.enhikyy.girassol.core.world

import com.enhikyy.girassol.core.biology.VoyagerProceduralSkill
import com.enhikyy.girassol.core.rsi.CognitivePolicyGenome
import com.enhikyy.girassol.core.social.AdicoInstitutionRule
import com.enhikyy.girassol.core.social.CliodynamicsSnapshot
import com.enhikyy.girassol.core.world.StockFlowDoubleEntryLedger.Companion.COMMUNAL_COMMONS

/**
 * Modelos imutáveis para a UI, construídos na thread da simulação. A UI nunca lê o
 * [WorldState] mutável diretamente.
 */
data class CitizenSummary(
    val id: String, val name: String, val generation: Int, val ageDays: Double, val alive: Boolean,
    val action: String, val mood: Double, val meanNeed: Double, val lastThought: String, val deathCause: String?,
    /** Barras compactas da lista de cidadãos (0..1). */
    val energy: Double = meanNeed, val social: Double = meanNeed,
    /** Modelo .gguf exclusivo (fundador) ou null; linhagem = modelo herdado e nomes dos pais. */
    val modelKey: String? = null, val lineageModelKey: String? = null,
    val parentNames: List<String> = emptyList(), val sex: String = ""
)

data class NamedValue(val label: String, val value: Double)

/** Nó da árvore geracional g0 → g1 → g2… */
data class LineageNode(val id: String, val name: String, val generation: Int, val parents: List<String>, val alive: Boolean, val modelKey: String?)

/** Estado do mérito individual (gatilho de reprodução) para a aba Evolução. */
data class MeritView(
    val id: String, val name: String, val generation: Int,
    val acceptorBaseline: Double, val acceptorRecent: Double,
    val brierBaseline: Double, val brierRecent: Double,
    val promotions: Int, val wellbeing: Double, val ready: Boolean, val blockers: List<String>
)

data class GenerationView(
    val status: ReproductionMerit.Status = ReproductionMerit.Status.SEM_POPULACAO,
    val etaTicks: Long = -1,
    val parentA: String? = null,
    val parentB: String? = null,
    val nextGeneration: Int = 0,
    val blockers: List<String> = emptyList(),
    val lineage: List<LineageNode> = emptyList(),
    val merit: List<MeritView> = emptyList(),
    val history: List<EvolutionSample> = emptyList()
) {
    val etaDays: Double get() = if (etaTicks < 0) -1.0 else etaTicks.toDouble() / TICKS_PER_DAY
}
data class SkillView(val skill: String, val mastery: Double, val verifiedExecutions: Int)
data class BeliefView(val claim: String, val probability: Double, val uncertainty: Double, val verified: Boolean, val quarantined: Boolean, val source: String?)
data class MemoryView(val layer: String, val content: String, val salience: Double, val tick: Long, val verified: Boolean)
data class TieView(val otherId: String, val otherName: String, val affinity: Double, val familiarity: Double, val trustInfo: Double, val ring: String)

data class CitizenDetail(
    val summary: CitizenSummary,
    val parents: List<String>,
    val children: Int,
    val homeObjectId: String,
    val position: Pair<Int, Int>,
    val needs: List<NamedValue>,
    val emotion: EmotionState,
    val traits: List<NamedValue>,
    val inventory: List<NamedValue>,
    val skills: List<SkillView>,
    val beliefs: List<BeliefView>,
    val memories: List<MemoryView>,
    val ties: List<TieView>,
    val successes: Int,
    val failures: Int,
    val sanctions: Int,
    val opinionCommons: Double,
    val reflexIncludedLiterals: Int
)

data class SocietyView(
    val cliodynamics: CliodynamicsSnapshot?,
    val institutions: List<AdicoInstitutionRule>,
    val recentLedger: List<LedgerEntry>,
    val totalLedgerEntries: Long,
    val commons: List<NamedValue>,
    val meanOpinionCommons: Double,
    val opinionSpread: Double,
    val totalTies: Int
)

data class MindView(
    val policy: CognitivePolicyGenome,
    val rsiLedger: List<RsiLedgerRecord>,
    val stressEvents: List<StressEventRecord>,
    val skillLibrary: List<VoyagerProceduralSkill>,
    val metrics: WorldMetrics,
    val recentEvents: List<CanonicalEvent>,
    val quarantinedBeliefs: Int,
    val unverifiedBeliefs: Int,
    val loraLedger: List<com.enhikyy.girassol.core.forge.LoraCycleRecord> = emptyList(),
    val loraAcceptedCycles: Int = 0,
    val loraVersion: Int = 0,
    val loraDeltaNorm: Float = 0f,
    val loraPendingSamples: Int = 0,
    val neuralDecisionsApplied: Long = 0,
    val speechesEmitted: Long = 0,
    val meanMood: Double = 0.0,
    val v34: V34View = V34View(),
    val v35: V35View = V35View()
)

/** Telemetria v3.5: Anokhin, CMA-ES, Biblioteca/Léxico, MeshSync. */
data class V35View(
    val acceptorMeanMismatch: Double = 0.0,
    val acceptorConfirmations: Int = 0,
    val acceptorViolations: Int = 0,
    val cmaGeneration: Int = 0,
    val cmaSigma: Double = 0.0,
    val cmaBestCost: Double = 0.0,
    val locomotionSpeedScale: Double = 1.0,
    val documents: List<KnowledgeDocumentView> = emptyList(),
    val knowledgeChunks: Int = 0,
    val lexicon: List<LexiconView> = emptyList(),
    val lexiconCompression: Double = 0.0,
    val compressedSpeechSample: List<String> = emptyList(),
    val migrantsIn: Int = 0,
    val migrantsOut: Int = 0,
    val eventSeq: Long = 0,
    val lastEventHash: String = GENESIS_HASH
)

data class KnowledgeDocumentView(val id: String, val title: String, val format: String, val chunks: Int, val reads: Int, val sha256: String)
data class LexiconView(val glyph: String, val phrase: String, val uses: Int, val coiner: String?)

/** Telemetria dos motores v3.4 (memória 2 estágios, ORCA, CfC hormonal, genética). */
data class V34View(
    val retrievalQueries: Long = 0,
    val meanStage1: Double = 0.0,
    val meanCorpus: Double = 0.0,
    val swrReplayUpdates: Long = 0,
    val aabbCorrections: Long = 0,
    val orcaAdjustedSteps: Long = 0,
    val superacoes: Int = 0,
    val tiesMerges: Int = 0,
    val meanCortisol: Double = 0.0,
    val meanOxytocin: Double = 0.0,
    val meanDopamine: Double = 0.0,
    val meanLocomotion: Double = 1.0,
    val binaryIndexBytes: Long = 0
)

data class DashboardSnapshot(
    val tick: Long,
    val citizens: List<CitizenSummary>,
    val society: SocietyView,
    val mind: MindView,
    val generation: GenerationView = GenerationView()
) {
    companion object {
        fun summaryOf(state: WorldState, c: Citizen) = CitizenSummary(
            c.id, c.name, c.generation, c.ageDays(state.tick), c.alive,
            c.intention?.action?.label ?: if (c.alive) "Ocioso" else "Falecido(a)",
            c.emotion.mood, c.meanNeed(), c.lastThought, c.deathCause,
            energy = c.needs[Need.ENERGIA] ?: c.meanNeed(), social = c.needs[Need.SOCIAL] ?: c.meanNeed(),
            modelKey = c.modelKey, lineageModelKey = c.lineageModelKey,
            parentNames = c.parents.mapNotNull { pid -> state.citizens.firstOrNull { it.id == pid }?.name },
            sex = c.sex
        )

        fun of(state: WorldState): DashboardSnapshot {
            val alive = state.aliveCitizens()
            val citizens = (alive.sortedBy { it.name } + state.citizens.filter { !it.alive }.sortedByDescending { it.deathTick }.take(30))
                .map { summaryOf(state, it) }
            val opinions = alive.map { it.opinionCommons }
            val mean = if (opinions.isEmpty()) 0.0 else opinions.average()
            val spread = if (opinions.size < 2) 0.0 else kotlin.math.sqrt(opinions.sumOf { (it - mean) * (it - mean) } / opinions.size)
            val society = SocietyView(
                cliodynamics = state.cliodynamics,
                institutions = state.institutions.toList(),
                recentLedger = state.ledger.recentEntries(25).reversed(),
                totalLedgerEntries = state.ledger.totalEntries(),
                commons = listOf(FOOD, MATERIAL, CREDIT).map { NamedValue(it, state.ledger.getBalance(COMMUNAL_COMMONS, it)) },
                meanOpinionCommons = mean,
                opinionSpread = spread,
                totalTies = alive.sumOf { it.social.allTies().size }
            )
            val beliefs = alive.flatMap { it.beliefs.values }
            val mind = MindView(
                policy = state.policy,
                rsiLedger = state.rsiLedger.takeLast(30).reversed(),
                stressEvents = state.stressEvents.takeLast(20).reversed().map { it.copy() },
                skillLibrary = state.skillLibrary.all(),
                metrics = state.metrics.copy(),
                recentEvents = state.recentEvents.takeLast(40).reversed(),
                quarantinedBeliefs = beliefs.count { it.quarantined },
                unverifiedBeliefs = beliefs.count { !it.verifiedByExperience },
                loraLedger = state.loraLedger.takeLast(20).reversed(),
                loraAcceptedCycles = state.lora.acceptedCycles,
                loraVersion = state.lora.version,
                loraDeltaNorm = state.lora.deltaNorm(),
                loraPendingSamples = state.loraPendingSamples.size,
                neuralDecisionsApplied = state.neuralDecisionsApplied,
                speechesEmitted = state.speechesEmitted,
                meanMood = if (alive.isEmpty()) 0.0 else alive.sumOf { it.emotion.mood } / alive.size,
                v34 = V34View(
                    retrievalQueries = state.retrievalQueries,
                    meanStage1 = if (state.retrievalQueries == 0L) 0.0 else state.retrievalStage1Total.toDouble() / state.retrievalQueries,
                    meanCorpus = if (state.retrievalQueries == 0L) 0.0 else state.retrievalCorpusTotal.toDouble() / state.retrievalQueries,
                    swrReplayUpdates = state.swrReplayUpdates,
                    aabbCorrections = state.aabbCorrections,
                    orcaAdjustedSteps = state.orcaAdjustedSteps,
                    superacoes = state.superacoes,
                    tiesMerges = state.tiesMerges,
                    meanCortisol = if (alive.isEmpty()) 0.0 else alive.sumOf { it.cortisol } / alive.size,
                    meanOxytocin = if (alive.isEmpty()) 0.0 else alive.sumOf { it.oxytocin } / alive.size,
                    meanDopamine = if (alive.isEmpty()) 0.0 else alive.sumOf { it.dopamine } / alive.size,
                    meanLocomotion = if (alive.isEmpty()) 1.0 else alive.sumOf { it.locomotion } / alive.size,
                    binaryIndexBytes = alive.sumOf { c -> c.memories.count { it.bits != null } * 32L }
                ),
                v35 = V35View(
                    acceptorMeanMismatch = state.acceptorMeanMismatch,
                    acceptorConfirmations = alive.sumOf { it.acceptorConfirmations },
                    acceptorViolations = alive.sumOf { it.acceptorViolations },
                    cmaGeneration = state.cmaEs?.generation ?: 0,
                    cmaSigma = state.cmaEs?.sigma ?: 0.0,
                    cmaBestCost = state.cmaEs?.bestCost?.takeIf { it < 1e300 } ?: 0.0,
                    locomotionSpeedScale = state.policy.locomotionSpeedScale,
                    documents = state.knowledge.documents.map { KnowledgeDocumentView(it.id, it.title, it.format.name, it.chunks, it.reads, it.sha256) },
                    knowledgeChunks = state.knowledge.chunks.size,
                    lexicon = state.lexicon.entries.sortedByDescending { it.uses }.take(24).map { e ->
                        LexiconView(e.glyph, e.phrase, e.uses, e.coinerId?.let { id -> state.citizen(id)?.name ?: id })
                    },
                    lexiconCompression = state.lexicon.compressionRatio(),
                    compressedSpeechSample = alive.map { it.lastSpeech }.filter { it.isNotBlank() }.take(4).map { state.lexicon.compress(it) },
                    migrantsIn = state.migrantsIn,
                    migrantsOut = state.migrantsOut,
                    eventSeq = state.eventSeq,
                    lastEventHash = state.lastEventHash
                )
            )
            val eta = ReproductionMerit.estimate(state)
            val name = { id: String? -> id?.let { i -> state.citizen(i)?.name ?: i } }
            val generation = GenerationView(
                status = eta.status, etaTicks = eta.ticks, parentA = name(eta.parentA), parentB = name(eta.parentB),
                nextGeneration = eta.nextGeneration, blockers = eta.blockers,
                lineage = (alive + state.citizens.filter { !it.alive }.sortedByDescending { it.deathTick }.take(20))
                    .sortedWith(compareBy<Citizen> { it.generation }.thenBy { it.birthTick }.thenBy { it.id })
                    .map { LineageNode(it.id, it.name, it.generation, it.parents, it.alive, it.modelKey) },
                merit = alive.sortedWith(compareBy<Citizen> { it.generation }.thenBy { it.name }).map { c ->
                    val ch = ReproductionMerit.check(c, state.tick)
                    MeritView(c.id, c.name, c.generation, c.acceptorBaseline, c.acceptorEma, c.brierBaseline, c.brierEma,
                        c.promotions - c.promotionsAtLastReproduction, c.meanNeed(), ch.all, ch.blockers())
                },
                history = state.evolutionHistory.toList()
            )
            return DashboardSnapshot(state.tick, citizens, society, mind, generation)
        }

        fun detailOf(state: WorldState, id: String): CitizenDetail? {
            val c = state.citizen(id) ?: return null
            val names = state.citizens.associate { it.id to it.name }
            return CitizenDetail(
                summary = summaryOf(state, c),
                parents = c.parents.map { names[it] ?: it },
                children = c.children,
                homeObjectId = c.homeObjectId,
                position = c.x to c.z,
                needs = Need.entries.map { NamedValue(it.label, c.needs.getValue(it)) },
                emotion = c.emotion.copy(),
                traits = TRAITS.map { NamedValue(it, c.trait(it)) },
                inventory = listOf(FOOD, MATERIAL, CREDIT).map { NamedValue(it, state.ledger.getBalance(c.id, it)) },
                skills = c.skills.values.sortedBy { it.skillId }.map { SkillView(it.skillId, it.masteryProb, it.verifiedExecutions) },
                beliefs = c.beliefs.values.sortedByDescending { it.updatedTick }.take(16).map {
                    BeliefView(it.claimKey, it.opinion.projectedProbability(), it.opinion.uncertainty, it.verifiedByExperience, it.quarantined, it.sourceAgentId?.let { s -> names[s] ?: s })
                },
                memories = c.memories.sortedByDescending { it.createdTick }.take(14).map {
                    MemoryView(it.layer.name, it.content, it.salience, it.createdTick, it.verifiedByWorldCore)
                },
                ties = c.social.allTies().sortedByDescending { it.tieStrength() }.take(10).map {
                    TieView(it.toAgentId, names[it.toAgentId] ?: it.toAgentId, it.affinity, it.familiarity, it.domainTrust("informacao"),
                        c.social.classifyDunbarRing(it.toAgentId)?.name ?: "-")
                },
                successes = c.successes, failures = c.failures, sanctions = c.sanctionsReceived,
                opinionCommons = c.opinionCommons,
                reflexIncludedLiterals = c.reflex.includedLiteralCount()
            )
        }
    }
}
