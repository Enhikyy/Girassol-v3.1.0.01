package com.enhikyy.girassol.core.world

import com.enhikyy.girassol.core.biology.Allele
import com.enhikyy.girassol.core.biology.CfcNeuromodulator
import com.enhikyy.girassol.core.biology.DiploidGenome
import com.enhikyy.girassol.core.biology.DiploidLocus
import com.enhikyy.girassol.core.biology.SemanticCollapseGuard
import com.enhikyy.girassol.core.biology.SkillMasteryState
import com.enhikyy.girassol.core.biology.VoyagerProceduralSkill
import com.enhikyy.girassol.core.cognition.ActiveInferencePianoOrchestrator
import com.enhikyy.girassol.core.cognition.AnokhinFunctionalSystem
import com.enhikyy.girassol.core.knowledge.KnowledgeDocument
import com.enhikyy.girassol.core.knowledge.ParsedDocument
import com.enhikyy.girassol.core.mesh.MeshSyncProtocol
import com.enhikyy.girassol.core.cognition.AllostaticThermalCoupling
import com.enhikyy.girassol.core.cognition.CoreSelfAnchor
import com.enhikyy.girassol.core.cognition.DeliberationContext
import com.enhikyy.girassol.core.cognition.DeliberationOption
import com.enhikyy.girassol.core.cognition.Deliberator
import com.enhikyy.girassol.core.cognition.GlobalWorkspaceStage
import com.enhikyy.girassol.core.cognition.PerceptualSignal
import com.enhikyy.girassol.core.cognition.PianoCognitiveBusSnapshot
import com.enhikyy.girassol.core.cognition.SymbolicLookaheadDeliberator
import com.enhikyy.girassol.core.cognition.NeuralProtocol
import com.enhikyy.girassol.core.cognition.SymbolicSpeech
import com.enhikyy.girassol.core.forge.LoraAdapterState
import com.enhikyy.girassol.core.forge.LoraCycleRecord
import com.enhikyy.girassol.core.forge.LoraFeatures
import com.enhikyy.girassol.core.forge.LoraMath
import com.enhikyy.girassol.core.forge.LoraSample
import com.enhikyy.girassol.core.forge.PersonalAdapter
import com.enhikyy.girassol.core.forge.TiesMerging
import com.enhikyy.girassol.core.memory.EpisodicIndex
import com.enhikyy.girassol.core.ports.CorePorts
import com.enhikyy.girassol.core.ports.NeuralDecision
import com.enhikyy.girassol.core.ports.NeuralRequest
import com.enhikyy.girassol.core.ports.NeuralPrompt
import com.enhikyy.girassol.core.memory.AssimilationOutcome
import com.enhikyy.girassol.core.memory.AssociativeMemoryNode
import com.enhikyy.girassol.core.memory.EpistemicImmunity
import com.enhikyy.girassol.core.memory.FourLayerMemoryAndSleepEngine
import com.enhikyy.girassol.core.memory.MemoryLayer
import com.enhikyy.girassol.core.memory.SubjectiveOpinion
import com.enhikyy.girassol.core.qd.EliteNicheCell
import com.enhikyy.girassol.core.reflex.KotlinTsetlinMachine
import com.enhikyy.girassol.core.social.AdicoInstitutionRule
import com.enhikyy.girassol.core.social.DeonticOperator
import com.enhikyy.girassol.core.social.DunbarSocialNetwork
import com.enhikyy.girassol.core.social.SocialDynamicsAndCliodynamics
import com.enhikyy.girassol.core.util.SplitMix64
import com.enhikyy.girassol.core.util.StreamSeeds
import com.enhikyy.girassol.core.util.Sha256
import com.enhikyy.girassol.core.util.fmt
import com.enhikyy.girassol.core.world.StockFlowDoubleEntryLedger.Companion.CENTRAL_BANK
import com.enhikyy.girassol.core.world.StockFlowDoubleEntryLedger.Companion.COMMUNAL_COMMONS
import com.enhikyy.girassol.core.world.StockFlowDoubleEntryLedger.Companion.METABOLIC_SINK
import com.enhikyy.girassol.core.world.StockFlowDoubleEntryLedger.Companion.NATURE_RESERVOIR
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

const val FOOD = "ALIMENTO"
/** Conta-fronteira do MeshSync: saldos de migrantes entram/saem por aqui (soma zero preservada). */
const val MESH_BORDER = "MESH_BORDER"
const val MATERIAL = "MATERIAL"
const val CREDIT = "CREDITO"

const val CH_TRAIL = "TRAIL_WEAR"
const val CH_FOOD = "FOOD_SCENT"
const val CH_DANGER = "DANGER_TRACE"
const val CH_SOCIAL = "SOCIAL_GATHERING"

/** Perfil de uma ação: ganho total de cada motivo, duração e tipo de objeto exigido. */
data class ActionProfile(
    val action: ActionType,
    val objectType: SmartObjectType?,
    val durationTicks: Int,
    val needGains: Map<Need, Double>
)

val ACTION_PROFILES: Map<ActionType, ActionProfile> = listOf(
    ActionProfile(ActionType.COMER, SmartObjectType.CASA, 20, mapOf(Need.FOME to 0.60)),
    ActionProfile(ActionType.DESCANSAR, SmartObjectType.CASA, 90, mapOf(Need.ENERGIA to 0.65)),
    ActionProfile(ActionType.BANHO, SmartObjectType.CASA, 15, mapOf(Need.HIGIENE to 0.70)),
    ActionProfile(ActionType.COLHER, SmartObjectType.HORTA, 40, mapOf(Need.PROPOSITO to 0.18, Need.ENERGIA to -0.06)),
    ActionProfile(ActionType.RECICLAR, SmartObjectType.COMPOSTEIRA, 40, mapOf(Need.PROPOSITO to 0.18, Need.HIGIENE to -0.10)),
    ActionProfile(ActionType.MANTER_USINA, SmartObjectType.USINA_SOLAR, 40, mapOf(Need.PROPOSITO to 0.20, Need.ENERGIA to -0.06)),
    ActionProfile(ActionType.PESQUISAR, SmartObjectType.LABORATORIO, 50, mapOf(Need.PROPOSITO to 0.30, Need.DIVERSAO to 0.12)),
    ActionProfile(ActionType.ESTUDAR, SmartObjectType.ESCOLA, 40, mapOf(Need.PROPOSITO to 0.18, Need.SOCIAL to 0.08)),
    ActionProfile(ActionType.SOCIALIZAR, SmartObjectType.PRACA, 45, mapOf(Need.SOCIAL to 0.50, Need.DIVERSAO to 0.30)),
    ActionProfile(ActionType.CONTRIBUIR, SmartObjectType.PRACA, 10, mapOf(Need.PROPOSITO to 0.20, Need.SOCIAL to 0.10)),
    ActionProfile(ActionType.CONSTRUIR, SmartObjectType.OFICINA, 60, mapOf(Need.PROPOSITO to 0.35, Need.ENERGIA to -0.08)),
    ActionProfile(ActionType.EXPLORAR, null, 1, mapOf(Need.DIVERSAO to 0.20))
).associateBy { it.action }

val TRAITS = listOf("metabolismo", "sociabilidade", "curiosidade", "aptidao", "conscienciosidade", "longevidade", "amabilidade")

private val FIRST_NAMES = listOf(
    "Ana", "Bento", "Cecília", "Davi", "Elisa", "Fábio", "Gabriela", "Heitor", "Iara", "João", "Kaique", "Lívia",
    "Miguel", "Nina", "Otávio", "Paula", "Quitéria", "Rafael", "Sofia", "Tiago", "Ursula", "Vicente", "Wanda",
    "Yuri", "Zélia", "Benedita", "Caio", "Dalva", "Enzo", "Flora", "Gael", "Helena", "Ícaro", "Jurema", "Lara",
    "Moacir", "Noemi", "Orlando", "Pietra", "Raul", "Sara", "Teodoro", "Valentina", "Augusto", "Beatriz", "Cauã"
)
private val SURNAMES = listOf(
    "Girassol", "Ipê", "Jatobá", "Aroeira", "Cajueiro", "Buriti", "Araçá", "Pitanga", "Jacarandá", "Umbu",
    "Pequi", "Babaçu", "Carnaúba", "Imbuia", "Peroba", "Mangaba"
)

/** Relatório do tick (consumido pelo escritor único de persistência e pela UI). */
data class TickReport(
    val tick: Long,
    val events: List<CanonicalEvent>,
    val ledgerEntries: List<LedgerEntry>,
    val invariantViolations: List<String>
)

private data class Proposal(val citizen: Citizen, val intention: Intention, val priority: Double)

/**
 * WorldCore — autoridade única da realidade física, econômica e temporal.
 * Cada tick executa 12 estágios com barreiras (ver docs/ARQUITETURA.md). Toda
 * mutação relevante emite um [CanonicalEvent] encadeado por SHA-256.
 */
class WorldCore(
    val state: WorldState,
    val ports: CorePorts = CorePorts(),
    /** Em shadow-replay os eventos não são retidos para persistência. */
    private val shadow: Boolean = false
) {
    private val deliberator: Deliberator = ports.deliberator
    private val memoryEngine = FourLayerMemoryAndSleepEngine(maxNodesPerAgent = MAX_MEMORIES)
    private val workspace = GlobalWorkspaceStage(3)
    private val tickEvents = mutableListOf<CanonicalEvent>()
    /** prev_hash esperado do primeiro evento pendente (cabeça da cadeia já gravada). */
    private var tickEventsAnchor: String? = null
    /** Redes CfC derivadas de (semente, id) — pesos determinísticos, não persistidos. */
    private val cfcNets = HashMap<String, CfcNeuromodulator>()
    private val violations = mutableListOf<String>()

    // ===================================================================== criação

    companion object {
        const val MAX_MEMORIES = 160
        const val MAX_CHAT = 2000
        const val RSI_PERIOD_TICKS = TICKS_PER_DAY * 3
        const val TSETLIN_FEATURES = 16
        const val MAX_DISTRICTS_PER_SIDE = 9
        const val REFLEX_CONFIDENCE = 0.6

        const val LORA_DECISION_WEIGHT = 0.08
        const val LORA_SANDBOX_TICKS = 120
        const val LORA_MIN_SAMPLES = 16
        const val NEURAL_SUGGESTION_TTL = 120L
        const val SPEECH_MIN_GAP = 40L
        const val LLM_MIN_FREE_MB = 512L
        const val SUPERACAO_RATIO = 1.15
        const val MAX_RECENT_SPEECHES = 6

        /**
         * v3.6: o mundo nasce VAZIO (0 cidadãos). A população vem só de fundadores — um por
         * modelo .gguf importado ([spawnFounder]) — e da reprodução natural. [initialPopulation]
         * existe apenas para testes e para a sonda de GPU (processo isolado).
         */
        fun create(seed: Long, initialPopulation: Int = 0, ports: CorePorts = CorePorts()): WorldCore {
            val state = WorldState(seed = seed, board = SpatialStigmergyAndReservationBoard(36, 36, cellSize = 1f))
            state.districts += CityGenerator.initialLayout(seed)
            CityGenerator.rebuildTiles(state)
            state.board.resize(state.widthTiles, state.widthTiles)
            for (d in state.districts) CityGenerator.spawnObjectsFor(state, d)
            state.institutions += defaultInstitutions()
            val core = WorldCore(state, ports)
            core.emit("GENESE", null, "seed=$seed;districts=${state.districts.size};rules=$CURRENT_RULES_VERSION")
            state.ledger.mintInitialEndowment(COMMUNAL_COMMONS, FOOD, 40.0, 0L, state.lastEventHash)
            state.ledger.mintInitialEndowment(COMMUNAL_COMMONS, MATERIAL, 6.0, 0L, state.lastEventHash)
            val rng = StreamSeeds.rng(seed, "WORLD", 0L, "population")
            repeat(initialPopulation) { core.spawnCitizen(randomGenome(rng), emptyList(), 0, rng) }
            return core
        }

        fun defaultInstitutions(): List<AdicoInstitutionRule> = listOf(
            AdicoInstitutionRule(
                ruleId = "cota-comuns", attributes = setOf("MORADOR"), deontic = DeonticOperator.PERMITIDO,
                aimAction = "RETIRAR_COMUNS", conditions = "", orElseSanctionUnits = 1, maxQuotaPerWindow = 3,
                description = "Moradores PODEM retirar alimento dos comuns até a cota diária, SENÃO pagam sanção"
            ),
            AdicoInstitutionRule(
                ruleId = "colheita-seca", attributes = setOf("MORADOR"), deontic = DeonticOperator.PERMITIDO,
                aimAction = "COLHER", conditions = "SECA", orElseSanctionUnits = 2, maxQuotaPerWindow = 2,
                description = "Durante a SECA, moradores PODEM colher no máximo 2 vezes por dia, SENÃO sanção"
            ),
            AdicoInstitutionRule(
                ruleId = "manutencao-obrigatoria", attributes = setOf("ADULTO"), deontic = DeonticOperator.OBRIGADO,
                aimAction = "CONTRIBUIR", conditions = "ESCASSEZ", orElseSanctionUnits = 1, maxQuotaPerWindow = 1,
                description = "Em ESCASSEZ dos comuns, adultos DEVEM contribuir ao menos 1 vez por dia, SENÃO sanção"
            )
        )

        fun randomGenome(rng: SplitMix64): DiploidGenome = DiploidGenome(
            TRAITS.associateWith {
                DiploidLocus(
                    Allele(0.2 + 0.6 * rng.nextDouble(), 0.2 + 0.8 * rng.nextDouble()),
                    Allele(0.2 + 0.6 * rng.nextDouble(), 0.2 + 0.8 * rng.nextDouble())
                )
            }
        )
    }

    fun spawnCitizen(genome: DiploidGenome, parents: List<String>, generation: Int, rng: SplitMix64, nearHome: String? = null): Citizen {
        val serial = state.nextCitizenSerial++
        val id = "C$serial"
        val name = FIRST_NAMES[rng.nextInt(FIRST_NAMES.size)] + " " + SURNAMES[rng.nextInt(SURNAMES.size)]
        val home = nearHome?.let { state.obj(it) } ?: leastOccupiedHome()
            ?: error("mundo sem nenhuma CASA: não há onde alojar o cidadão $id (gere/expanda um distrito MORADIA)")
        val c = Citizen(
            id = id, name = name, generation = generation, birthTick = state.tick, parents = parents, genome = genome,
            x = home.tileX, z = home.tileZ, homeObjectId = home.id,
            needs = Need.entries.associateWith { 0.65 + 0.3 * rng.nextDouble() }.toMutableMap(),
            social = DunbarSocialNetwork(id),
            skills = SKILLS.associateWith { SkillMasteryState(it, masteryProb = 0.05 + 0.15 * rng.nextDouble()) }.toMutableMap(),
            reflex = KotlinTsetlinMachine(TSETLIN_FEATURES, 20, 64, 3.5f, 8, StreamSeeds.derive(state.seed, id, state.tick, "tsetlin")),
            opinionCommons = rng.nextDouble() * 2 - 1,
            intrinsicOpinionCommons = rng.nextDouble() * 2 - 1
        )
        state.citizens += c
        val h = emit("NASCIMENTO", id, "nome=$name;geracao=$generation;pais=${parents.joinToString(",")}")
        state.ledger.mintInitialEndowment(c.id, CREDIT, 10.0, state.tick, h)
        if (parents.isEmpty()) state.ledger.mintInitialEndowment(c.id, FOOD, 6.0, state.tick, h)
        return c
    }

    /**
     * Cria o cidadão fundador de um modelo .gguf (1 modelo = 1 pessoa). Já nasce adulto, com
     * identidade derivada deterministicamente da chave do modelo; [name]/[sex] opcionais.
     * Recusa um segundo fundador para o mesmo modelo enquanto o primeiro estiver vivo.
     */
    fun spawnFounder(modelKey: String, name: String? = null, sex: String? = null): Citizen {
        state.citizens.firstOrNull { it.alive && it.modelKey == modelKey }?.let { return it }
        val rng = StreamSeeds.rng(state.seed, "FUNDADOR:$modelKey", state.tick, "fundador")
        val c = spawnCitizen(randomGenome(rng), emptyList(), 0, rng)
        // spawnCitizen acaba de anexar o novo cidadão ao fim da lista.
        val idx = state.citizens.lastIndex
        val chosenSex = sex?.uppercase()?.takeIf { it == "F" || it == "M" }
            ?: if (state.citizens.count { it.alive && it.founder && it.sex == "F" } >
                state.citizens.count { it.alive && it.founder && it.sex == "M" }) "M" else if (rng.nextDouble() < 0.5) "F" else "M"
        val founder = c.copy(
            name = name?.trim()?.take(40)?.takeIf { it.isNotEmpty() } ?: c.name,
            birthTick = state.tick - (ADULT_DAYS * TICKS_PER_DAY).toLong() - TICKS_PER_DAY
        ).also { it.modelKey = modelKey; it.sex = chosenSex }
        state.citizens[idx] = founder
        // Registra a identidade final do fundador após substituir nome e sexo sorteados.
        emit("FUNDADOR", founder.id, "modelo=$modelKey;nome=${founder.name};sexo=$chosenSex")
        emit("FUNDADOR", founder.id, "modelo=$modelKey;nome=${founder.name};sexo=$chosenSex")
        return founder
    }

    // ===================================================================== Chat (v3.6)

    /**
     * Registra uma mensagem do usuário (Arquiteto) para [citizenId] (null = canal aberto). Entra na
     * cadeia de eventos (CHAT), na memória episódica do destinatário e no léxico emergente.
     */
    fun postUserChat(citizenId: String?, text: String, wallClockMs: Long): ChatMessage {
        val clean = text.trim().take(600)
        val h = emit("CHAT", citizenId, "autor=usuario;sha=${Sha256.hex(clean).take(16)};chars=${clean.length}")
        val msg = ChatMessage(state.nextChatSerial++, state.tick, wallClockMs, citizenId, "usuario", "Você", clean, "usuario")
        appendChat(msg)
        val targets = if (citizenId == null) state.aliveCitizens() else listOfNotNull(state.citizen(citizenId)?.takeIf { it.alive })
        for (c in targets) { rememberChat(c, "O Arquiteto disse: $clean", h); c.lexicon.observe(clean) }
        return msg
    }

    /** Registra a resposta de um cidadão (gerada pelo próprio .gguf ou pelo deliberador simbólico). */
    fun postCitizenReply(citizenId: String, channel: String?, text: String, engine: String, wallClockMs: Long): ChatMessage? {
        val c = state.citizen(citizenId)?.takeIf { it.alive } ?: return null
        val clean = text.trim().take(600).ifEmpty { return null }
        val h = emit("CHAT", c.id, "autor=${c.id};motor=$engine;sha=${Sha256.hex(clean).take(16)};chars=${clean.length}")
        val msg = ChatMessage(state.nextChatSerial++, state.tick, wallClockMs, channel, c.id, c.name, clean, engine)
        appendChat(msg)
        c.lastSpeech = clean.take(120); c.lastSpeechTick = state.tick
        rememberSpeech(c, clean.take(120))
        state.speechesEmitted++
        c.lexicon.observe(clean)
        rememberChat(c, "Respondi ao Arquiteto: ${clean.take(200)}", h)
        return msg
    }

    /**
     * Resposta simbólica ancorada no estado real do cidadão (sem modelo .gguf ativo): ação atual,
     * necessidade mais urgente e a memória mais relacionada à pergunta (sobreposição de termos).
     */
    fun symbolicReply(citizenId: String, question: String): String {
        val c = state.citizen(citizenId) ?: return ""
        val worst = c.needs.minWith(compareBy<Map.Entry<Need, Double>> { it.value }.thenBy { it.key.ordinal })
        val action = c.intention?.action?.label?.lowercase() ?: "parado(a)"
        val terms = question.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 3 }.toSet()
        val mem = c.memories.filter { it.layer != MemoryLayer.PROCEDURAL }
            .maxWithOrNull(compareBy<AssociativeMemoryNode> { m -> terms.count { t -> m.content.lowercase().contains(t) } }.thenBy { it.createdTick })
        val memLine = mem?.takeIf { m -> terms.any { m.content.lowercase().contains(it) } }?.content?.take(140)
        return buildString {
            append("Agora estou $action. ")
            append("O que mais me falta é ${worst.key.name.lowercase()} (${(worst.value * 100).roundToInt()}%). ")
            if (memLine != null) append("Lembro disto: $memLine")
            else if (c.lastThought.isNotBlank()) append(c.lastThought)
        }.trim()
    }

    /** Prompt de conversa do cidadão (estado real + memórias relacionadas + histórico do canal). */
    fun chatPromptFor(citizenId: String, question: String): NeuralPrompt? {
        val c = state.citizen(citizenId)?.takeIf { it.alive } ?: return null
        val terms = question.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 3 }.toSet()
        val mems = c.memories.sortedWith(compareByDescending<AssociativeMemoryNode> { m -> terms.count { t -> m.content.lowercase().contains(t) } }
            .thenByDescending { it.createdTick }).take(4).map { it.content.take(90) }
        val history = state.chat.filter { it.citizenId == citizenId || (it.citizenId == null && it.author == citizenId) }
            .takeLast(6).map { (if (it.author == "usuario") "Arquiteto" else it.authorName) to it.text }
        val lineage = if (c.parents.isEmpty()) "fundador(a)" else "filho(a) de " + c.parents.mapNotNull { state.citizen(it)?.name }.joinToString(" e ")
        return NeuralProtocol.chatPrompt(c.name,
            "Geração ${c.generation}, $lineage, ${"%.0f".fmt(c.ageDays(state.tick))} dias de vida. Agora: ${c.intention?.action?.label ?: "parado(a)"}.",
            Need.entries.associate { it.name.lowercase() to c.needs.getValue(it) }, c.emotion.mood, mems, history, question,
            StreamSeeds.derive(state.seed, c.id, state.tick, "chat"))
    }

    private fun appendChat(msg: ChatMessage) {
        state.chat += msg
        if (state.chat.size > MAX_CHAT) state.chat.subList(0, state.chat.size - MAX_CHAT).clear()
    }

    private fun rememberChat(c: Citizen, content: String, eventHash: String) {
        c.memories += AssociativeMemoryNode(c.nextMemoryId++, c.id, MemoryLayer.EPISODIC, content.take(300),
            linkedSetOf("CHAT"), linkedSetOf(), salience = 0.7, confidence = 0.9, sourceEventId = state.eventSeq,
            sourceAgentId = null, createdTick = state.tick, verifiedByWorldCore = true, qUtility = 0.1)
        while (c.memories.size > MAX_MEMORIES) c.memories.removeAt(c.memories.indices.minBy { c.memories[it].salience })
    }

    /** Parceiros compatíveis para reprodução: sexos diferentes (ou desconhecido em mundos antigos). */

    private fun leastOccupiedHome(): SmartObject? {
        val homes = state.objects.filter { it.type == SmartObjectType.CASA }
        val counts = state.aliveCitizens().groupingBy { it.homeObjectId }.eachCount()
        return homes.minWithOrNull(compareBy<SmartObject> { counts[it.id] ?: 0 }.thenBy { it.id })
    }

    // ===================================================================== Anokhin

    /** Aceitador de Resultados da Ação: compara o modelo da síntese aferente com a aferência de retorno. */
    private fun acceptResult(c: Citizen, it: Intention, success: Boolean): AnokhinFunctionalSystem.Comparison? {
        if (it.expectedNeeds.size != AnokhinFunctionalSystem.NEEDS || it.needsAtDecision.size != AnokhinFunctionalSystem.NEEDS) return null
        val obtained = Need.entries.map { n -> c.needs.getValue(n) }.toDoubleArray()
        val out = ports.acceptor.compare(it.expectedNeeds.toDoubleArray(), it.forecastSuccess, it.memoryUtilityAtDecision,
            it.needsAtDecision.toDoubleArray(), obtained, success)
        val cmp = AnokhinFunctionalSystem.Comparison(out[0], out[1], if (out[3] >= 0.5) 0.0 else out[0], out[2], out[3] >= 0.5)
        c.acceptorMismatch = cmp.mismatch
        c.acceptorValence = cmp.valence
        c.acceptorPlasticity = cmp.plasticity
        if (cmp.confirmed) c.acceptorConfirmations++ else c.acceptorViolations++
        if (!shadow) ReproductionMerit.updateAcceptor(c, cmp.mismatch, state.tick)
        // a surpresa entra no appraisal: descompasso negativo eleva excitação e cortisol
        if (!cmp.confirmed) {
            c.emotion.arousal = (c.emotion.arousal + 0.15 * cmp.surprise).coerceIn(0.0, 1.0)
            if (cmp.valence < 0.0) c.cortisol = (c.cortisol + 0.05 * cmp.surprise).coerceIn(0.0, 1.0)
            else c.dopamine = (c.dopamine + 0.05 * cmp.surprise).coerceIn(0.0, 1.0)
        }
        state.acceptorMeanMismatch = 0.98 * state.acceptorMeanMismatch + 0.02 * cmp.mismatch
        return cmp
    }

    // ===================================================================== Biblioteca (Processo 19)

    /** Ingestão documental: entra no mundo via evento canônico `CONHECIMENTO` (hash na cadeia). */
    fun ingestKnowledge(doc: ParsedDocument): KnowledgeDocument? {
        val rec = state.knowledge.add(doc, state.tick) ?: return null
        emit("CONHECIMENTO", null, "doc=${rec.id};formato=${rec.format};sha256=${rec.sha256};trechos=${rec.chunks};chars=${rec.chars}")
        return rec
    }

    private fun readKnowledge(c: Citizen, action: ActionType) {
        if (state.knowledge.chunks.isEmpty()) return
        val weakest = c.skills.values.minWith(compareBy<SkillMasteryState> { s -> s.masteryProb }.thenBy { s -> s.skillId }).skillId
        val query = "$weakest ${c.lastThought} ${action.label}"
        val chunk = state.knowledge.nearest(query, 1).firstOrNull() ?: return
        chunk.reads++
        state.knowledge.documents.firstOrNull { d -> d.id == chunk.docId }?.let { d -> d.reads++ }
        val title = state.knowledge.title(chunk.docId)
        val excerpt = chunk.text.take(160)
        c.lexicon.observe(excerpt)
        val content = "Leu em $title: $excerpt"
        if (c.memories.none { m -> m.layer == MemoryLayer.SEMANTIC && m.content == content }) {
            c.memories += AssociativeMemoryNode(c.nextMemoryId++, c.id, MemoryLayer.SEMANTIC, content,
                linkedSetOf("LIVRO", chunk.docId), linkedSetOf(), salience = 0.6, confidence = 0.8, sourceEventId = state.eventSeq,
                sourceAgentId = null, createdTick = state.tick, verifiedByWorldCore = true, qUtility = 0.2).also { m -> m.bits = chunk.bits.copyOf() }
        }
        c.skills[weakest] = c.skills.getValue(weakest).updateFromPractice(true, state.tick, c.trait("aptidao"))
        emit("LEITURA", c.id, "doc=${chunk.docId};trecho=${chunk.ordinal}")
    }

    /** Memórias episódicas de maior utilidade (alimentam o LoRA on-device no ciclo de sono). */
    fun topUtilityEpisodes(limit: Int = 48): List<Pair<String, Double>> =
        state.aliveCitizens().flatMap { c -> c.memories.filter { m -> m.layer == MemoryLayer.EPISODIC && m.verifiedByWorldCore }
            .map { m -> "${c.name}: ${m.content}" to m.qUtility } }
            .sortedWith(compareByDescending<Pair<String, Double>> { p -> p.second }.thenBy { p -> p.first })
            .distinctBy { p -> p.first }.take(limit)

    // ===================================================================== MeshSync: migração

    /** Retira o cidadão deste mundo e devolve o pacote assinado para outro aparelho. */
    fun emigrate(id: String, deviceId: String): MeshSyncProtocol.MigrationPacket? {
        val c = state.citizen(id)?.takeIf { it.alive } ?: return null
        val wallet = state.ledger.resourceTypes().associateWith { r -> state.ledger.getBalance(c.id, r) }.filterValues { v -> v > 0.0 }
        val snapshot = c.copy(intention = null, path = mutableListOf())
        val packet = MeshSyncProtocol.MigrationPacket(deviceId, state.seed, state.tick, snapshot, wallet, MeshSyncProtocol.citizenDigest(snapshot, wallet))
        c.intention?.targetObjectId?.let { state.board.releaseReservation(it, c.id) }
        state.board.releaseAllFor(c.id)
        c.alive = false
        c.deathTick = state.tick
        c.deathCause = "migrou"
        c.intention = null
        c.path.clear()
        val h = emit("EMIGRACAO", c.id, "destino=$deviceId;sha256=${packet.sha256}")
        state.ledger.transferAll(c.id, MESH_BORDER, state.tick, h)
        state.migrantsOut++
        return packet
    }

    /** Admite um migrante (novo id local, sem colisões; preserva a idade e o saldo). */
    fun admitMigrant(p: MeshSyncProtocol.MigrationPacket): Citizen {
        val src = p.citizen
        val newId = "C${state.nextCitizenSerial++}"
        val home = leastOccupiedHome()
            ?: error("mundo sem CASA: não é possível admitir o migrante ${src.name}")
        val age = (p.originTick - src.birthTick).coerceAtLeast(0L)
        val c = src.copy(id = newId, birthTick = state.tick - age, x = home.tileX, z = home.tileZ, homeObjectId = home.id,
            social = DunbarSocialNetwork(newId), intention = null, path = mutableListOf(), actionTicksRemaining = 0,
            alive = true, deathTick = -1L, deathCause = null)
        state.citizens += c
        val h = emit("IMIGRACAO", newId, "origem=${p.originDevice};nome=${src.name};sha256=${p.sha256}")
        for ((res, q) in p.wallet.toSortedMap()) state.ledger.transfer(MESH_BORDER, newId, res, q, state.tick, h, allowNegativeSource = true)
        state.migrantsIn++
        return c
    }

    // ===================================================================== eventos

    fun emit(type: String, actorId: String?, payload: String): String {
        val seq = ++state.eventSeq
        val hash = WorldStateHashChain.computeHash(seq, state.tick, CURRENT_RULES_VERSION, type, actorId, payload, state.lastEventHash)
        val ev = CanonicalEvent(seq, state.tick, CURRENT_RULES_VERSION, type, actorId, payload, state.lastEventHash, hash)
        state.lastEventHash = hash
        if (!shadow) {
            if (tickEvents.isEmpty()) tickEventsAnchor = ev.prevHash
            tickEvents += ev
            state.recentEvents += ev
            if (state.recentEvents.size > 400) state.recentEvents.subList(0, state.recentEvents.size - 400).clear()
        }
        return hash
    }

    // ===================================================================== tick

    fun step(telemetry: TelemetryInput? = null, wallClockUtc: Long = 0L): TickReport {
        // eventos emitidos entre ticks (FUNDADOR, CHAT, comandos da UI) seguem neste relatório,
        // para que a cadeia SHA-256 gravada no SQLite não tenha lacunas
        violations.clear()
        state.tick++
        state.wallClockUtcAtLastTick = wallClockUtc
        val tick = state.tick

        // 1. Telemetria & governança térmica
        if (telemetry != null && telemetry != state.telemetry) {
            state.telemetry = telemetry
            emit("TELEMETRIA", null, "termico=${telemetry.thermalTier};memMb=${telemetry.availableMemoryMb}")
        }
        if (!shadow) ports.neural?.let { applyNeuralDecisions(it.drainResults()) }
        val tier = when {
            state.telemetry.thermalTier >= 3 -> AllostaticThermalCoupling.ThermalTier.SEVERE_OR_CRITICAL
            state.telemetry.thermalTier == 2 -> AllostaticThermalCoupling.ThermalTier.MODERATE
            else -> AllostaticThermalCoupling.ThermalTier.NONE_OR_LIGHT
        }

        // 2. Ambiente, estigmergia & bioquímica
        stageEnvironment()

        val alive = state.aliveCitizens().sortedBy { it.id }
        val actionCounts = populationActionCounts(alive)
        val proposals = mutableListOf<Proposal>()
        val trail = state.board.channelSnapshot(CH_TRAIL)
        val danger = state.board.channelSnapshot(CH_DANGER)

        // ---- v3.8: estágios 3–7 em LOTE no núcleo C++20 (uma travessia JNI por estágio) ----
        val pending = alive.filter { it.intention == null }
        val rngs = pending.map { StreamSeeds.rng(state.seed, it.id, tick, "decisao") }
        val gridW = state.board.gridWidth()
        val gridH = state.board.gridHeight()
        val cell = state.board.cellSizeWorld()
        // 3. Percepção & competição atencional (kernel nativo)
        val broadcasts = perceiveBatch(pending, state.board.channelSnapshot(CH_FOOD), danger, gridW, gridH, cell)
        // 4. Memória & atualização epistêmica (ACT-R + MemRL + A-MEM): recuperação 2 estágios no kernel.
        // A memória de trabalho é zerada antes de cada cidadão; logo nenhuma consulta vê vínculos
        // Zettelkasten de outro cidadão (activeWorkingIds vazio), como no laço sequencial.
        val queries = pending.mapIndexed { i, c ->
            val broadcast = broadcasts[i]
            val tags = broadcast.mapNotNull { it.entityId }.toSet() + broadcast.map { it.sourceModule }.toSet()
            NativeCognition.MemoryQuery(c.memories, tick, broadcast.joinToString(" ") { it.summary }, tags,
                broadcast.mapNotNull { it.entityId }.toSet(),
                topK = 4, activeWorkingIds = emptySet())
        }
        val retrievals = NativeCognition.retrieveBatch(queries)
        val recalledAll = ArrayList<List<AssociativeMemoryNode>>(pending.size)
        val gathered = ArrayList<List<Gathered>>(pending.size)
        for ((i, c) in pending.withIndex()) {
            memoryEngine.clearWorkingMemory()
            val retrieval = memoryEngine.applyRetrieval(retrievals[i], c.memories.size, tick,
                workingCapacity = 5 + (4.0 * c.trait("conscienciosidade")).roundToInt())
            recalledAll += retrieval.memories
            state.retrievalQueries++
            state.retrievalStage1Total += retrieval.stage1Candidates
            state.retrievalCorpusTotal += retrieval.corpusSize
            // 5. Appraisal emocional
            appraise(c)
            // 6. entradas de G(π) por candidato (avaliadas no kernel)
            gathered += gatherCandidates(c, actionCounts, tier)
        }
        // 6–7. G(π), ordenação (G, ordinal), softmax de precisão γ com histerese e F variacional
        val decisions = decideBatch(pending, gathered, rngs.map { it.nextDouble() }, danger, gridW, gridH, cell)

        for ((ci, c) in pending.withIndex()) {
            val decision = decisions[ci] ?: continue
            val rng = rngs[ci]
            val broadcast = broadcasts[ci]
            val recalled = recalledAll[ci]
            val sorted = decision.sorted
            var idx = decision.chosen
            // 8. Gate cognitivo: reflexo Tsetlin → deliberação PIANO quando necessário
            val features = reflexFeatures(c, sorted[idx].intention.action)
            val reflexConf = c.reflex.confidence(features).toDouble()
            val reflexPositive = c.reflex.predictVote(features) > 0
            val u1 = -sorted[0].efe
            val u2 = if (sorted.size > 1) -sorted[1].efe else u1 - 1.0
            val urgency = 1.0 - (c.needs.values.minOrNull() ?: 1.0)
            val invoke = AllostaticThermalCoupling.shouldInvokeLocalLlm(
                topActionUtility = urgency.coerceIn(0.0, 1.0),
                secondActionUtility = urgency - (u1 - u2),
                tieThreshold = state.policy.cognitiveGateTieThreshold,
                minUrgency = state.policy.cognitiveGateMinUrgency,
                consecutiveHtnFailures = c.consecutiveFailures,
                socialNegotiationActive = false,
                tier = tier,
                tsetlinReflexConfidence = if (reflexPositive) reflexConf else 0.0
            )
            var viaDelib = false
            var viaReflex = false
            var viaNeural = false
            val suggestion = c.neuralSuggestion?.takeIf { tick - c.neuralSuggestionTick <= NEURAL_SUGGESTION_TTL }
            if (invoke) {
                val neuralIdx = suggestion?.let { sug -> sorted.indexOfFirst { it.intention.action.name == sug } } ?: -1
                if (neuralIdx >= 0) {
                    // decisão neural chegada como evento canônico em tick anterior
                    idx = neuralIdx
                    viaNeural = true
                    c.neuralSuggestion = null
                } else {
                    val bus = buildBus(c, broadcast, recalled, decision.vfe)
                    val ctx = DeliberationContext(bus, sorted.map { cand ->
                        DeliberationOption(cand.intention.action.name, cand.efe, cand.projected, cand.travel)
                    }, decayRates(c))
                    val res = deliberator.deliberate(ctx)
                    if (res.chosenIndex in sorted.indices) idx = res.chosenIndex
                    c.lastThought = res.rationale
                    // pedido assíncrono ao motor GGUF (só em temperatura nominal); não altera o estado
                    if (!shadow && tier == AllostaticThermalCoupling.ThermalTier.NONE_OR_LIGHT &&
                        state.telemetry.availableMemoryMb >= LLM_MIN_FREE_MB) ports.neural?.let { port ->
                        if (port.isAvailable()) port.trySubmit(neuralRequest(c, sorted.take(6), recalled))
                    }
                    val worst = c.needs.minByOrNull { it.value }?.key?.name ?: "PROPOSITO"
                    speak(c, SymbolicSpeech.forDecision(worst, sorted[idx].intention.action, rng.nextLong()))
                }
                val cost = AllostaticThermalCoupling.computeNeuralDeliberationCost(tier, state.policy.thermalThrottleSensitivity, c.needs.getValue(Need.ENERGIA))
                c.needs[Need.ENERGIA] = (c.needs.getValue(Need.ENERGIA) - cost * 0.05).coerceIn(0.0, 1.0)
                state.deliberationTicks += tick
                viaDelib = true
            } else if (reflexPositive && reflexConf >= REFLEX_CONFIDENCE) {
                state.reflexTicks += tick
                viaReflex = true
                c.lastThought = "Reflexo Tsetlin (confiança ${"%.2f".fmt(reflexConf)}) → ${sorted[idx].intention.action.label}"
            } else {
                c.lastThought = "Inferência ativa: G=${"%.3f".fmt(sorted[idx].efe)} → ${sorted[idx].intention.action.label}"
            }
            val chosen = sorted[idx]
            // 9. Submissão simultânea de propostas
            proposals += Proposal(c, chosen.intention.copy(viaDeliberation = viaDelib || viaNeural, viaReflex = viaReflex, viaNeural = viaNeural), urgency)
        }

        // 10. Arbitragem & reservas (ordem determinística: urgência desc, id asc)
        for (p in proposals.sortedWith(compareByDescending<Proposal> { it.priority }.thenBy { it.citizen.id })) {
            val objId = p.intention.targetObjectId
            if (objId != null && !state.board.tryReserve(objId, p.citizen.id, tick, ttlTicks = 400L)) {
                p.citizen.consecutiveFailures++
                continue
            }
            val path = ports.pathfinder.findPath(state, p.citizen.x, p.citizen.z, p.intention.targetX, p.intention.targetZ, trail, danger)
            if (path.isEmpty() && (p.citizen.x != p.intention.targetX || p.citizen.z != p.intention.targetZ)) {
                objId?.let { state.board.releaseReservation(it, p.citizen.id) }
                p.citizen.consecutiveFailures++
                continue
            }
            p.citizen.intention = p.intention
            p.citizen.path = path
            p.citizen.actionTicksRemaining = -1
            noteClaimUsage(p.citizen, p.intention)
        }

        // 11. Execução canônica & contabilidade (locomoção contínua ORCA + colisão círculo×AABB)
        stageCrowd(alive)
        for (c in alive) executeIntention(c)

        // 12. Sociedade, aprendizagem, evolução & snapshot
        stageSociety(alive)
        stageLifeCycle()
        if (tick % TICKS_PER_DAY == 0L) stageDaily()
        updateMetrics()
        checkInvariants()

        val entries = state.ledger.drainPendingEntries()
        val events = tickEvents.toList()
        tickEvents.clear()
        return TickReport(tick, events, if (shadow) emptyList() else entries, violations.toList())
    }

    // ===================================================================== estágio 2

    private fun stageEnvironment() {
        state.board.stepDiffusionAndEvaporation(evap = 0.02f, diff = 0.10f)
        if (state.tick % TICKS_PER_DAY == 1L) {
            val r = StreamSeeds.rng(state.seed, "WORLD", state.tick, "clima")
            if (!state.inDrought() && r.nextDouble() < 0.10) {
                state.droughtUntilTick = state.tick + TICKS_PER_DAY * 2
                emit("CLIMA", null, "seca_ate=${state.droughtUntilTick}")
            }
        }
        // v3.7: decaimento homeostático + inanição no WorldTick C++20 (linhas compactas via JNI)
        val citizens = state.aliveCitizens()
        val stride = NativeWorldTick.NEEDS_STRIDE
        val packed = DoubleArray(citizens.size * stride)
        citizens.forEachIndexed { i, c ->
            val o = i * stride
            Need.entries.forEachIndexed { k, n -> packed[o + k] = c.needs.getValue(n) }
            packed[o + 6] = c.trait("metabolismo"); packed[o + 7] = c.trait("sociabilidade")
            packed[o + 8] = c.trait("conscienciosidade"); packed[o + 9] = c.trait("curiosidade")
            packed[o + 10] = if (c.intention?.action == ActionType.DESCANSAR) 1.0 else 0.0
            packed[o + 11] = c.starvationTicks.toDouble()
        }
        NativeWorldTick.nativeStepNeeds(packed, citizens.size, state.isNight())
        citizens.forEachIndexed { i, c ->
            val o = i * stride
            Need.entries.forEachIndexed { k, n -> c.needs[n] = packed[o + k] }
            c.starvationTicks = packed[o + 11].toInt()
            stepNeuromodulation(c)
            if (c.starvationTicks > 0) state.board.depositPheromone(CH_DANGER, c.x.toFloat(), c.z.toFloat(), 0.4f)
        }
        // energia urbana: consumo pelos edifícios; laboratórios e escolas dependem dela
        state.cityEnergy = (state.cityEnergy - 0.0015 * state.objects.size).coerceAtLeast(0.0)
        for (o in state.objects) if (o.contaminatedUntilTick in 0 until state.tick) o.contaminatedUntilTick = -1L
    }

    /** CfC de 48 neurônios → alvos hormonais → ODEs de 1ª ordem (Δt = 1 tick, forma fechada). */
    private fun stepNeuromodulation(c: Citizen) {
        val net = cfcNets.getOrPut(c.id) { CfcNeuromodulator(CfcNeuromodulator.seedFor(state.seed, c.id)) }
        if (c.cfcState.size != CfcNeuromodulator.N) c.cfcState = DoubleArray(CfcNeuromodulator.N)
        val input = DoubleArray(CfcNeuromodulator.IN)
        Need.entries.forEachIndexed { k, n -> input[k] = 1.0 - c.needs.getValue(n) }
        input[6] = c.emotion.mood
        input[7] = c.lastReward
        input[8] = (state.board.readPheromone(CH_DANGER, c.x.toFloat(), c.z.toFloat()) / 5f).coerceAtMost(1f).toDouble()
        input[9] = if (c.intention?.action == ActionType.SOCIALIZAR && c.path.isEmpty()) 1.0 else 0.0
        net.advance(c.cfcState, input, 1.0)
        val r = net.readout(c.cfcState)
        c.cortisol = CfcNeuromodulator.hormoneStep(c.cortisol, r.cortisolTarget, 1.0)
        c.oxytocin = CfcNeuromodulator.hormoneStep(c.oxytocin, r.oxytocinTarget, 1.0)
        c.dopamine = CfcNeuromodulator.hormoneStep(c.dopamine, r.dopamineTarget, 1.0)
        c.locomotion = r.locomotion
    }

    /** Taxas de decaimento por necessidade (kernel nativo; mesma fórmula do passo homeostático). */
    private fun decayRates(c: Citizen): DoubleArray = decayCache.getOrPut(c.id) {
        // depende só do genoma (imutável): uma chamada JNI por cidadão
        NativeWorldTick.nativeNeedDecay(c.trait("metabolismo"), c.trait("sociabilidade"), c.trait("conscienciosidade"), c.trait("curiosidade"))
    }
    private val decayCache = HashMap<String, DoubleArray>()

    // ===================================================================== estágios 3–6

    /** Percepção + espaço de trabalho global no kernel nativo; o Kotlin só rotula os sinais. */
    private fun perceiveBatch(cs: List<Citizen>, food: FloatArray, danger: FloatArray, w: Int, h: Int, cell: Float): List<List<PerceptualSignal>> {
        if (cs.isEmpty()) return emptyList()
        val n = cs.size
        val needs = DoubleArray(n * NativeCognition.NEEDS)
        val codes = IntArray(n * NativeCognition.NEEDS)
        val values = arrayOfNulls<Map<Need, Double>>(n)
        for ((i, c) in cs.withIndex()) {
            var k = 0
            for ((need, v) in c.needs) {
                if (k >= NativeCognition.NEEDS) break
                needs[i * NativeCognition.NEEDS + k] = v; codes[i * NativeCognition.NEEDS + k] = need.ordinal; k++
            }
            values[i] = c.needs
        }
        val sal = DoubleArray(n * NativeCognition.WORKSPACE_CAP)
        val out = NativeCognition.nativePerceive(needs, codes, IntArray(n) { cs[it].x }, IntArray(n) { cs[it].z },
            food, danger, w, h, cell, state.inDrought(), sal)
        val stride = 1 + NativeCognition.WORKSPACE_CAP
        return List(n) { i ->
            val count = out[i * stride]
            List(count) { k ->
                val code = out[i * stride + 1 + k]
                val s = sal[i * NativeCognition.WORKSPACE_CAP + k]
                when (code) {
                    NativeCognition.SIGNAL_FOOD -> PerceptualSignal("ESTIGMERGIA", "cheiro de comida", "FOOD_SCENT", s)
                    NativeCognition.SIGNAL_DANGER -> PerceptualSignal("ESTIGMERGIA", "rastro de perigo", "DANGER", s)
                    NativeCognition.SIGNAL_DROUGHT -> PerceptualSignal("CLIMA", "seca", "SECA", s)
                    else -> {
                        val need = Need.entries[code]
                        PerceptualSignal(need.name, "${need.label}=${"%.2f".fmt(values[i]!!.getValue(need))}", need.name, s)
                    }
                }
            }
        }
    }

    private fun appraise(c: Citizen) {
        val e = c.emotion
        val deficit = 1.0 - c.meanNeed()
        val r = c.lastReward
        // OCC/EMA simplificado: desejabilidade do último resultado → valência; déficit → excitação
        e.valence = (0.8 * e.valence + 0.2 * (r * 2.0 - deficit)).coerceIn(-1.0, 1.0)
        e.arousal = (0.85 * e.arousal + 0.15 * (deficit * 1.5 + abs(r))).coerceIn(0.0, 1.0)
        e.mood = (0.97 * e.mood + 0.03 * e.valence).coerceIn(-1.0, 1.0)
        e.rumination = if (c.consecutiveFailures >= 2) (e.rumination + 0.1).coerceAtMost(1.0) else (e.rumination * 0.95)
        e.lastAppraisal = when {
            r > 0.15 -> "alegria"
            r < -0.1 && c.consecutiveFailures > 1 -> "frustração"
            deficit > 0.55 -> "angústia"
            e.mood > 0.3 -> "contentamento"
            else -> "neutro"
        }
    }

    private data class Candidate(val intention: Intention, val efe: Double, val projected: DoubleArray, val travel: Int)

    /** Entradas de G(π) de um candidato, coletadas do WorldState; o cálculo é feito no kernel C++20. */
    private class Gathered(
        val action: ActionType, val objId: String?, val tx: Int, val tz: Int, val claim: String?, val verified: Boolean,
        val forecast: Double, val needsNow: DoubleArray, val memQ: Double, val travel: Int,
        val d: DoubleArray, val i: IntArray
    )

    private class Decision(val sorted: List<Candidate>, val chosen: Int, val vfe: Double)

    private fun gatherCandidates(c: Citizen, popCounts: Map<String, Int>, tier: AllostaticThermalCoupling.ThermalTier): List<Gathered> {
        val out = mutableListOf<Gathered>()
        val foodWallet = state.ledger.getBalance(c.id, FOOD)
        val commonsFood = state.ledger.getBalance(COMMUNAL_COMMONS, FOOD)
        val material = state.ledger.getBalance(c.id, MATERIAL) + state.ledger.getBalance(COMMUNAL_COMMONS, MATERIAL)
        val ads = state.board.queryReachable(c.id, c.x.toFloat(), c.z.toFloat(), 10_000f, state.tick)
        val neural = AllostaticThermalCoupling.computeNeuralDeliberationCost(tier, state.policy.thermalThrottleSensitivity, c.needs.getValue(Need.ENERGIA))
        val home = state.obj(c.homeObjectId)
        for (action in ActionType.entries) {
            if (action == ActionType.OCIOSO) continue
            val profile = ACTION_PROFILES[action] ?: continue
            // hard gates
            when (action) {
                ActionType.COMER -> if (foodWallet < 1.0 && commonsFood < 1.0) continue
                ActionType.CONSTRUIR -> if (material < 3.0) continue
                ActionType.CONTRIBUIR -> if (foodWallet < 5.0) continue
                ActionType.PESQUISAR -> if (state.cityEnergy < 2.0) continue
                else -> {}
            }
            val targets: List<Pair<SmartObject?, Float>> = when {
                profile.objectType == null -> listOf(null to 0f)
                profile.objectType == SmartObjectType.CASA && home != null &&
                    ads.any { it.first.sourceEntityId == home.id } -> listOf(home to manhattan(c, home).toFloat())
                else -> ads.asSequence().filter { it.first.actionType == profile.objectType.name }
                    .take(3).mapNotNull { (ad, d) -> state.obj(ad.sourceEntityId)?.let { it to d } }.toList()
            }
            for ((obj, dist) in targets) {
                val g = gather(c, action, profile, obj, dist.toInt(), popCounts, neural)
                if (g != null) out += g
            }
        }
        return out
    }

    private fun manhattan(c: Citizen, o: SmartObject) = abs(c.x - o.tileX) + abs(c.z - o.tileZ)

    private fun gather(
        c: Citizen, action: ActionType, profile: ActionProfile, obj: SmartObject?, dist: Int,
        popCounts: Map<String, Int>, neural: Double
    ): Gathered? {
        val needsNow = Need.entries.map { c.needs.getValue(it) }.toDoubleArray()
        val skillEff = action.skill?.let { 0.6 + 0.6 * (c.skills[it]?.masteryProb ?: 0.1) } ?: 1.0
        // crença sobre o rendimento do alvo (ignora crenças em quarentena)
        val claim = obj?.let { "rendimento:${it.id}" }
        val belief = claim?.let { c.beliefs[it] }?.takeIf { !it.quarantined }
        val yieldP = belief?.opinion?.projectedProbability() ?: 0.6
        val uncertainty = belief?.opinion?.uncertainty ?: 1.0
        // produção econômica também tem valor pragmático (alimento futuro)
        val foodWallet = state.ledger.getBalance(c.id, FOOD)
        val economicBonus = when (action) {
            ActionType.COLHER -> 0.25 * (1.0 - (foodWallet / 8.0).coerceAtMost(1.0)) * yieldP / 0.6
            ActionType.MANTER_USINA -> 0.10 * (1.0 - (state.cityEnergy / 60.0).coerceAtMost(1.0))
            ActionType.RECICLAR -> 0.05
            ActionType.CONSTRUIR -> 0.06
            ActionType.CONTRIBUIR -> 0.06 * (1.0 + c.trait("amabilidade")) * (if (state.ledger.getBalance(COMMUNAL_COMMONS, FOOD) < 10) 2.0 else 1.0)
            ActionType.ESTUDAR -> 0.04 * (1.0 + c.trait("aptidao"))
            else -> 0.0
        }
        val memQ = memoryUtility(c, action)
        val novelty = SemanticCollapseGuard.noveltyEpistemicBonus(action.name, popCounts)
        val (tx, tz, objId) = if (obj != null) Triple(obj.tileX, obj.tileZ, obj.id) else {
            // EXPLORAR: vai verificar o alvo com crença mais incerta/não verificada
            val target = explorationTarget(c) ?: return null
            Triple(target.tileX, target.tileZ, null)
        }
        val forecast = action.skill?.let { c.skills[it]?.executionSuccessProbability(0.35, geneticAptitude = c.trait("aptidao")) } ?: 0.9
        val decay = decayRates(c)
        val d = DoubleArray(NativeCognition.CAND_D)
        for (k in 0 until NativeCognition.NEEDS) {
            d[k] = needsNow[k]; d[6 + k] = decay[k]; d[12 + k] = profile.needGains[Need.entries[k]] ?: 0.0
        }
        d[18] = skillEff; d[19] = yieldP; d[20] = uncertainty
        d[21] = c.cortisol; d[22] = c.dopamine; d[23] = c.trait("curiosidade"); d[24] = state.policy.epistemicExplorationWeight
        d[25] = neural; d[26] = state.telemetry.socTempC
        d[27] = economicBonus; d[28] = memQ; d[29] = novelty; d[30] = c.oxytocin
        // viés do adaptador pessoal (herdado por TIES-Merging, treinado por LMS) e da LoRA coletiva
        d[31] = PersonalAdapter.predict(c.personalAdapter, LoraFeatures.of(action, state.tick)).toDouble()
        val loraActive = state.lora.acceptedCycles > 0
        d[32] = if (loraActive) LoraMath.predict(state.lora, LoraFeatures.of(action, state.tick))[0].toDouble() else 0.0
        d[33] = PersonalAdapter.DECISION_WEIGHT; d[34] = LORA_DECISION_WEIGHT
        var flags = 0
        if (obj != null) flags = flags or NativeCognition.FLAG_TARGET
        if (action == ActionType.SOCIALIZAR) flags = flags or NativeCognition.FLAG_SOCIAL
        if (action == ActionType.EXPLORAR) flags = flags or NativeCognition.FLAG_EXPLORE
        if (loraActive) flags = flags or NativeCognition.FLAG_LORA
        val i = intArrayOf(dist, profile.durationTicks, obj?.tileX ?: -1, obj?.tileZ ?: -1, action.ordinal, flags)
        return Gathered(action, objId, tx, tz, claim, belief?.verifiedByExperience ?: true, forecast, needsNow, memQ, dist, d, i)
    }

    /** Avalia G(π) de todos os candidatos de todos os cidadãos e escolhe a política (kernel C++20). */
    private fun decideBatch(cs: List<Citizen>, gathered: List<List<Gathered>>, uniforms: List<Double>,
                            danger: FloatArray, w: Int, h: Int, cell: Float): List<Decision?> {
        if (cs.isEmpty()) return emptyList()
        val total = gathered.sumOf { it.size }
        val candD = DoubleArray(total * NativeCognition.CAND_D)
        val candI = IntArray(total * NativeCognition.CAND_I)
        val offsets = IntArray(cs.size + 1)
        val citD = DoubleArray(cs.size * NativeCognition.CIT_D)
        val current = IntArray(cs.size)
        var j = 0
        for ((ci, c) in cs.withIndex()) {
            offsets[ci] = j
            for (g in gathered[ci]) {
                System.arraycopy(g.d, 0, candD, j * NativeCognition.CAND_D, NativeCognition.CAND_D)
                System.arraycopy(g.i, 0, candI, j * NativeCognition.CAND_I, NativeCognition.CAND_I)
                j++
            }
            for (k in 0 until NativeCognition.NEEDS) citD[ci * NativeCognition.CIT_D + k] = c.needs.getValue(Need.entries[k])
            citD[ci * NativeCognition.CIT_D + 6] = uniforms[ci]
            current[ci] = c.lastAction?.ordinal ?: -1
        }
        offsets[cs.size] = j
        val efe = DoubleArray(total)
        val proj = DoubleArray(total * NativeCognition.NEEDS)
        val order = IntArray(total)
        val vfe = DoubleArray(cs.size)
        val chosen = NativeCognition.nativeCognitionBatch(candD, candI, offsets, citD, current,
            danger, state.board.channelSnapshot(CH_SOCIAL), w, h, cell,
            PREFERRED_NEED_LEVEL, state.policy.intentionSwitchHysteresis, 6.0, efe, proj, order, vfe)
        return List(cs.size) { ci ->
            val a = offsets[ci]
            val b = offsets[ci + 1]
            if (a == b) null else {
                val sorted = (a until b).map { k ->
                    val local = a + order[k]
                    val g = gathered[ci][order[k]]
                    val projected = proj.copyOfRange(local * NativeCognition.NEEDS, (local + 1) * NativeCognition.NEEDS)
                    Candidate(
                        Intention(g.action, g.objId, g.tx, g.tz, state.tick, -efe[local], g.forecast, false, false, g.claim, g.verified,
                            expectedNeeds = projected.toList(), needsAtDecision = g.needsNow.toList(), memoryUtilityAtDecision = g.memQ),
                        efe[local], projected, g.travel
                    )
                }
                Decision(sorted, chosen[ci], vfe[ci])
            }
        }
    }

    private fun memoryUtility(c: Citizen, action: ActionType): Double {
        var sum = 0.0
        var n = 0
        for (i in c.memories.indices.reversed()) {
            val m = c.memories[i]
            if (action.name in m.tags) { sum += m.qUtility; n++ }
            if (n >= 12) break
        }
        return if (n == 0) 0.0 else sum / n
    }

    private fun explorationTarget(c: Citizen): SmartObject? {
        // prioridade: crenças não verificadas (boatos) → objetos nunca visitados
        val unverified = c.beliefs.values.filter { !it.verifiedByExperience }
            .sortedWith(compareByDescending<HeldBelief> { it.opinion.uncertainty + if (it.quarantined) 0.5 else 0.0 }.thenBy { it.claimKey })
        for (b in unverified) {
            val o = state.obj(b.claimKey.removePrefix("rendimento:"))
            if (o != null) return o
        }
        val known = c.beliefs.keys
        val rng = StreamSeeds.rng(state.seed, c.id, state.tick, "explorar")
        val unknown = state.objects.filter { "rendimento:${it.id}" !in known }
        if (unknown.isNotEmpty()) return unknown[rng.nextInt(unknown.size)]
        if (state.objects.isEmpty()) return null
        return state.objects[rng.nextInt(state.objects.size)]
    }

    private fun populationActionCounts(alive: List<Citizen>): Map<String, Int> {
        val m = ActionType.entries.filter { it != ActionType.OCIOSO }.associate { it.name to 0 }.toMutableMap()
        for (c in alive) c.intention?.let { m[it.action.name] = (m[it.action.name] ?: 0) + 1 }
        return m
    }

    private fun reflexFeatures(c: Citizen, action: ActionType): ByteArray {
        val f = ByteArray(TSETLIN_FEATURES)
        var i = 0
        for (n in Need.entries) f[i++] = if (c.needs.getValue(n) < 0.35) 1 else 0
        f[i++] = if (state.ledger.getBalance(c.id, FOOD) >= 1.0) 1 else 0
        f[i++] = if (state.isNight()) 1 else 0
        f[i++] = if (state.inDrought()) 1 else 0
        f[i++] = if (c.consecutiveFailures > 0) 1 else 0
        // codificação binária de 6 bits da ação proposta
        val code = action.ordinal
        for (b in 0 until 6) f[i++] = ((code shr b) and 1).toByte()
        return f
    }

    private fun buildBus(c: Citizen, broadcast: List<PerceptualSignal>, recalled: List<AssociativeMemoryNode>, vfe: Double): PianoCognitiveBusSnapshot {
        val needsArr = Need.entries.map { c.needs.getValue(it) }.toDoubleArray()
        return PianoCognitiveBusSnapshot(
            agentId = c.id, tick = state.tick,
            coreSelf = CoreSelfAnchor(c.id, c.name, c.birthTick, listOf(
                if (c.trait("amabilidade") > 0.5) "cooperação" else "autonomia",
                if (c.trait("curiosidade") > 0.5) "descoberta" else "estabilidade")),
            activeNeedsSummary = Need.entries.joinToString(",") { "${it.name}=${"%.2f".fmt(c.needs.getValue(it))}" },
            perceptionSummary = broadcast.joinToString(";") { it.summary },
            memorySummary = recalled.joinToString(";") { it.content.take(40) },
            socialSummary = "laços=${c.social.allTies().size}",
            workspaceBroadcast = broadcast,
            currentIntentionKey = c.intention?.action?.name,
            variationalFreeEnergy = vfe, // F calculada no núcleo C++20 (lote do tick)
            needsArray = needsArr
        )
    }

    private fun noteClaimUsage(c: Citizen, intention: Intention) {
        // Avalia se a decisão evitou algum alvo por boato não verificado (ação antes de verificar)
        for (b in c.beliefs.values) {
            if (b.verifiedByExperience || b.quarantined) continue
            if (b.opinion.projectedProbability() < 0.3 && b.opinion.uncertainty < 0.5 && intention.targetObjectId != b.claimKey.removePrefix("rendimento:")) {
                val objType = state.obj(b.claimKey.removePrefix("rendimento:"))?.type
                if (objType != null && ACTION_PROFILES[intention.action]?.objectType == objType) {
                    c.actedOnUnverifiedClaims++
                    state.stressEvents.lastOrNull { it.targetId == b.claimKey && it.resolvedTick < 0 }?.let { it.actedBeforeVerification++ }
                }
            }
        }
        if (intention.action == ActionType.EXPLORAR) {
            val target = state.objects.firstOrNull { it.tileX == intention.targetX && it.tileZ == intention.targetZ }
            val b = target?.let { c.beliefs["rendimento:${it.id}"] }
            if (b != null && !b.verifiedByExperience) {
                c.verifiedBeforeActing++
                state.stressEvents.lastOrNull { it.targetId == b.claimKey && it.resolvedTick < 0 }?.let { it.verifiedBeforeAction++ }
            }
        }
    }

    // ===================================================================== estágio 11

    private fun executeIntention(c: Citizen) {
        val it = c.intention ?: return
        if (c.path.isNotEmpty()) {
            moveContinuous(c)
            return
        }
        val profile = ACTION_PROFILES.getValue(it.action)
        if (c.actionTicksRemaining < 0) c.actionTicksRemaining = profile.durationTicks
        // aplica ganhos de forma contínua ao longo da duração
        val skillEff = it.action.skill?.let { s -> 0.6 + 0.6 * (c.skills[s]?.masteryProb ?: 0.1) } ?: 1.0
        for ((n, g) in profile.needGains) {
            if (it.action == ActionType.COMER) continue // aplicado na conclusão, depende do alimento
            val per = g / profile.durationTicks * (if (g > 0) skillEff.coerceAtMost(1.2) else 1.0) *
                (if (it.action == ActionType.DESCANSAR && state.isNight()) 1.3 else 1.0)
            c.needs[n] = (c.needs.getValue(n) + per).coerceIn(0.0, 1.0)
        }
        if (it.action == ActionType.SOCIALIZAR) state.board.depositPheromone(CH_SOCIAL, c.x.toFloat(), c.z.toFloat(), 0.15f)
        c.actionTicksRemaining--
        if (c.actionTicksRemaining > 0) return
        completeIntention(c, it)
    }

    private fun syncContinuous(c: Citizen) {
        if (c.px.isNaN() || c.pz.isNaN() || floor(c.px).toInt() != c.x || floor(c.pz).toInt() != c.z) {
            c.px = c.x + 0.5; c.pz = c.z + 0.5; c.vx = 0.0; c.vz = 0.0
        }
    }

    /** Velocidade preferida rumo ao centro do próximo waypoint, limitada pela locomoção CfC. */
    private fun preferredVelocity(c: Citizen): Vec2 {
        val next = c.path.firstOrNull() ?: return Vec2.ZERO
        val w = state.widthTiles
        val tx = next % w + 0.5
        val tz = next / w + 0.5
        val d = Vec2(tx - c.px, tz - c.pz)
        val speed = c.locomotion.coerceIn(0.75, 1.0) * state.policy.locomotionSpeedScale
        val len = d.length()
        return if (len <= speed) d else d * (speed / len)
    }

    /** RVO2/ORCA entre os agentes em deslocamento (quem está parado executando ação não bloqueia). */
    private fun stageCrowd(alive: List<Citizen>) {
        for (c in alive) syncContinuous(c)
        val moving = alive.filter { it.path.isNotEmpty() }
        for (c in alive) if (c.path.isEmpty()) { c.vx = 0.0; c.vz = 0.0 }
        if (moving.isEmpty()) return
        val agents = moving.map { c ->
            OrcaAgent(Vec2(c.px, c.pz), Vec2(c.vx, c.vz), preferredVelocity(c), CrowdNavigation.AGENT_RADIUS, c.locomotion.coerceIn(0.75, 1.0) * state.policy.locomotionSpeedScale)
        }
        val v = CrowdNavigation.stepRvo2OrcaVelocities(agents)
        moving.forEachIndexed { i, c ->
            var nv = v[i]
            // sem progresso rumo ao waypoint: segue a velocidade preferida (evita impasse simétrico)
            if (c.stuckTicks >= 3) nv = agents[i].preferred
            if (abs(nv.x - agents[i].preferred.x) + abs(nv.y - agents[i].preferred.y) > 1e-6) state.orcaAdjustedSteps++
            c.vx = nv.x; c.vz = nv.y
        }
    }

    private fun moveContinuous(c: Citizen) {
        val w = state.widthTiles
        val goal = c.path.last()
        val next = c.path.first()
        val leaving = c.z * w + c.x
        val tx = next % w + 0.5
        val tz = next / w + 0.5
        val distBefore = sqrt((tx - c.px) * (tx - c.px) + (tz - c.pz) * (tz - c.pz))
        val desired = Vec2(c.px + c.vx, c.pz + c.vz)
        val resolved = CrowdNavigation.resolveAgainstGrid(w, state.tiles, desired.x, desired.y, CrowdNavigation.AGENT_RADIUS, setOf(goal, leaving, next))
        if (abs(resolved.x - desired.x) + abs(resolved.y - desired.y) > 1e-9) state.aabbCorrections++
        c.px = resolved.x; c.pz = resolved.y
        val dist = sqrt((tx - c.px) * (tx - c.px) + (tz - c.pz) * (tz - c.pz))
        if (dist <= 0.2) {
            c.px = tx; c.pz = tz
            c.path.removeAt(0)
            c.stuckTicks = 0
        } else if (distBefore - dist < 0.05) c.stuckTicks++ else c.stuckTicks = 0
        val nx = floor(c.px).toInt().coerceIn(0, w - 1)
        val nz = floor(c.pz).toInt().coerceIn(0, w - 1)
        if (nx != c.x || nz != c.z) {
            c.x = nx; c.z = nz
            state.board.depositPheromone(CH_TRAIL, nx.toFloat(), nz.toFloat(), 0.25f)
        }
        if (c.stuckTicks > 24) failIntention(c, "caminho bloqueado")
    }

    private fun failIntention(c: Citizen, reason: String) {
        c.intention?.targetObjectId?.let { state.board.releaseReservation(it, c.id) }
        c.intention = null
        c.path.clear()
        c.consecutiveFailures++
        c.lastThought = "Falha: $reason"
    }

    private fun completeIntention(c: Citizen, it: Intention) {
        val obj = it.targetObjectId?.let { id -> state.obj(id) }
        val before = c.meanNeed()
        val rng = StreamSeeds.rng(state.seed, c.id, state.tick, "execucao")
        var success = true
        var detail = ""
        val roles = buildSet { add("MORADOR"); if (c.ageDays(state.tick) >= ADULT_DAYS) add("ADULTO") }
        val contaminated = obj != null && obj.contaminatedUntilTick > state.tick
        val skill = it.action.skill
        if (skill != null) {
            val st = c.skills.getValue(skill)
            val p = st.executionSuccessProbability(0.35, geneticAptitude = c.trait("aptidao"))
            success = rng.nextDouble() < p && !contaminated
            c.skills[skill] = st.updateFromPractice(success, state.tick, c.trait("aptidao"))
            state.forecastWindow += p
            state.outcomeWindow += success
            if (!shadow) ReproductionMerit.updateBrier(c, (p - if (success) 1.0 else 0.0).let { e -> e * e }, state.tick)
            // resposta ao item IRT 2PL (calibrado no kernel nativo na virada do dia)
            val item = IRT_ACTIONS.indexOf(it.action)
            if (item >= 0) { c.irtAttempts[item] += 1.0; if (success) c.irtSuccesses[item] += 1.0 }
            if (state.forecastWindow.size > 600) { state.forecastWindow.removeAt(0); state.outcomeWindow.removeAt(0) }
        }
        when (it.action) {
            ActionType.COMER -> {
                val h = emit("REFEICAO", c.id, "obj=${obj?.id}")
                var ate = state.ledger.transfer(c.id, METABOLIC_SINK, FOOD, 1.0, state.tick, h)
                if (!ate) {
                    val usage = c.windowUsage["RETIRAR_COMUNS"] ?: 0
                    val rule = state.institutions.firstOrNull { r -> r.aimAction == "RETIRAR_COMUNS" }
                    val (ok, sanction) = rule?.evaluateActionCompliance(roles, "RETIRAR_COMUNS", activeCondition(), usage + 1) ?: (true to 0)
                    if (state.ledger.withdrawFromCommunalPool(c.id, FOOD, 1.0, state.tick, h)) {
                        c.windowUsage["RETIRAR_COMUNS"] = usage + 1
                        ate = state.ledger.transfer(c.id, METABOLIC_SINK, FOOD, 1.0, state.tick, h)
                        if (!ok) applySanction(c, rule!!, sanction + c.sanctionsReceived.coerceAtMost(4))
                    }
                }
                if (ate) {
                    c.needs[Need.FOME] = (c.needs.getValue(Need.FOME) + 0.60).coerceAtMost(1.0)
                    state.dayFoodConsumed += 1.0
                    state.metrics.foodConsumed += 1.0
                } else { success = false; detail = "sem alimento" }
            }
            ActionType.COLHER -> {
                if (state.inDrought()) {
                    val usage = (c.windowUsage["COLHER"] ?: 0) + 1
                    c.windowUsage["COLHER"] = usage
                    val rule = state.institutions.firstOrNull { r -> r.aimAction == "COLHER" }
                    val (ok, sanction) = rule?.evaluateActionCompliance(roles, "COLHER", "SECA", usage) ?: (true to 0)
                    if (!ok) applySanction(c, rule!!, sanction)
                }
                if (success) {
                    val niche = state.board.totalDistrictNicheBonus()
                    val amount = 2.6 * (if (state.inDrought()) 0.45 else 1.0) * (1.0 + niche) * (0.8 + 0.4 * c.skills.getValue("cultivo").masteryProb)
                    val h = emit("COLHEITA", c.id, "obj=${obj?.id};qtd=${"%.3f".fmt(amount)}")
                    state.ledger.transfer(NATURE_RESERVOIR, c.id, FOOD, amount, state.tick, h, allowNegativeSource = true)
                    state.dayFoodProduced += amount
                    state.metrics.foodProduced += amount
                    obj?.let { o -> state.board.depositPheromone(CH_FOOD, o.tileX.toFloat(), o.tileZ.toFloat(), 3f) }
                    detail = "+${"%.1f".fmt(amount)} alimento"
                } else detail = if (contaminated) "colheita contaminada" else "colheita falhou"
            }
            ActionType.RECICLAR -> if (success) {
                val h = emit("RECICLAGEM", c.id, "obj=${obj?.id}")
                state.ledger.transfer(NATURE_RESERVOIR, c.id, MATERIAL, 1.5, state.tick, h, allowNegativeSource = true)
                state.metrics.materialProduced += 1.5
                detail = "+1.5 material"
            }
            ActionType.MANTER_USINA -> if (success) {
                val h = emit("ENERGIA", c.id, "obj=${obj?.id}")
                state.cityEnergy += 20.0
                state.metrics.energyProduced += 20.0
                state.ledger.transfer(CENTRAL_BANK, c.id, CREDIT, 2.0, state.tick, h, allowNegativeSource = true)
                detail = "+20 energia urbana, +2 créditos"
            }
            ActionType.PESQUISAR -> {
                state.cityEnergy = (state.cityEnergy - 3.0).coerceAtLeast(0.0)
                if (success) {
                    // pesquisa registra a habilidade mais dominada como procedimento Voyager verificável
                    val best = c.skills.values.maxWith(compareBy<SkillMasteryState> { s -> s.masteryProb }.thenBy { s -> s.skillId })
                    if (best.isVoyagerShareableSkill()) {
                        val reg = state.skillLibrary.registerVerifiedSkill(VoyagerProceduralSkill(
                            skillId = best.skillId, authorAgentId = c.id,
                            preconditions = listOf("energia_urbana>2", "objeto=${ACTION_PROFILES.values.first { p -> p.action.skill == best.skillId }.objectType}"),
                            actionSequence = listOf("reservar", "deslocar", "executar:${best.skillId}", "verificar"),
                            verifiedSuccessRate = best.masteryProb, createdAtTick = state.tick
                        ))
                        if (reg) emit("VOYAGER", c.id, "skill=${best.skillId};taxa=${"%.3f".fmt(best.masteryProb)}")
                    }
                    detail = "pesquisa concluída"
                }
            }
            ActionType.ESTUDAR -> {
                val weakest = c.skills.values.minWith(compareBy<SkillMasteryState> { s -> s.masteryProb }.thenBy { s -> s.skillId })
                val mentor = state.aliveCitizens().filter { m -> m.id != c.id && m.intention?.action == ActionType.ESTUDAR && m.intention?.targetObjectId == obj?.id }
                    .maxByOrNull { m -> m.skills[weakest.skillId]?.masteryProb ?: 0.0 }
                val lib = state.skillLibrary.findSkill(weakest.skillId)
                c.skills[weakest.skillId] = if (lib != null) {
                    val trust = mentor?.let { m -> c.social.getOrCreateTie(m.id).domainTrust("ensino") } ?: 0.5
                    state.skillLibrary.teachApprentice(weakest.skillId, weakest, trust, state.tick)
                } else weakest.updateFromPractice(rng.nextDouble() < 0.5 + 0.3 * c.trait("aptidao"), state.tick, c.trait("aptidao"))
                mentor?.let { m -> c.social.getOrCreateTie(m.id).recordTrustOutcome("ensino", true) }
                detail = "estudou ${weakest.skillId}"
            }
            ActionType.CONTRIBUIR -> {
                val h = emit("CONTRIBUICAO", c.id, "qtd=2")
                if (state.ledger.contributeToCommunalPool(c.id, FOOD, 2.0, state.tick, h)) {
                    c.windowUsage["CONTRIBUIR"] = (c.windowUsage["CONTRIBUIR"] ?: 0) + 1
                    detail = "doou 2 alimento"
                } else success = false
            }
            ActionType.CONSTRUIR -> {
                val h = emit("CONSTRUCAO", c.id, "obj=${obj?.id}")
                val own = state.ledger.getBalance(c.id, MATERIAL)
                val ok = if (own >= 3.0) state.ledger.transfer(c.id, METABOLIC_SINK, MATERIAL, 3.0, state.tick, h)
                else state.ledger.withdrawFromCommunalPool(c.id, MATERIAL, 3.0, state.tick, h) &&
                    state.ledger.transfer(c.id, METABOLIC_SINK, MATERIAL, 3.0, state.tick, h)
                if (ok && success && obj != null) {
                    val sid = "NICHO-${obj.districtId}"
                    val existing = state.board.nicheStructures().firstOrNull { s -> s.structureId == sid }
                    state.board.registerNicheStructure(EcologicalNicheStructure(
                        sid, "estufa_comunitaria", obj.tileX, obj.tileZ, (existing?.level ?: 0) + 1,
                        1.0, c.id, state.tick
                    ))
                    detail = "construiu nicho nível ${(existing?.level ?: 0) + 1}"
                } else if (!ok) { success = false; detail = "sem material" }
            }
            ActionType.EXPLORAR -> detail = "explorou"
            else -> {}
        }
        obj?.let { o -> o.uses++; state.board.releaseReservation(o.id, c.id) }

        // verificação direta de crenças sobre o alvo (WorldCore como fonte de verdade)
        val verifyTarget = obj ?: state.objects.firstOrNull { o -> o.tileX == it.targetX && o.tileZ == it.targetZ }
        if (verifyTarget != null) {
            val key = "rendimento:${verifyTarget.id}"
            val truth = verifyTarget.contaminatedUntilTick <= state.tick && (it.action == ActionType.EXPLORAR || success)
            val b = c.beliefs.getOrPut(key) { HeldBelief(key, "recurso", SubjectiveOpinion(0.0, 0.0, 1.0)) }
            val wasHearsay = !b.verifiedByExperience
            val consistent = EpistemicImmunity.observeDirectly(b, truth, state.tick)
            if (wasHearsay && b.sourceAgentId != null) {
                state.citizen(b.sourceAgentId!!)?.let { src -> c.social.getOrCreateTie(src.id).recordTrustOutcome("informacao", consistent) }
                state.stressEvents.lastOrNull { s -> s.targetId == key && s.resolvedTick < 0 }?.let { s ->
                    if (!consistent) s.verifiedBeforeAction++
                }
            }
        }

        // Aceitador de Resultados da Ação (Anokhin): aferência de retorno × modelo esperado,
        // ANTES de qualquer atualização de plasticidade ou surpresa.
        val acceptance = acceptResult(c, it, success)
        val plasticity = acceptance?.plasticity ?: 1.0
        val reward = (c.meanNeed() - before) * 6.0 + (if (success) 0.1 else -0.2)
        c.lastReward = reward.coerceIn(-1.0, 1.0)
        if (success) { c.consecutiveFailures = 0; c.successes++ } else { c.consecutiveFailures++; c.failures++ }
        c.actionCounts[it.action] = (c.actionCounts[it.action] ?: 0) + 1
        c.lastAction = it.action
        // memória episódica + MemRL
        val node = AssociativeMemoryNode(
            id = c.nextMemoryId++, ownerAgentId = c.id, layer = MemoryLayer.EPISODIC,
            content = "${it.action.label}${obj?.let { o -> " em ${o.type.label} ${o.id}" } ?: ""}: ${if (success) "sucesso" else "falha"} $detail".trim(),
            tags = linkedSetOf(it.action.name, obj?.type?.name ?: "LIVRE", if (success) "sucesso" else "falha"),
            entityIds = obj?.let { o -> linkedSetOf(o.id) } ?: linkedSetOf(),
            salience = max(abs(c.lastReward), acceptance?.surprise ?: 0.0).coerceIn(0.1, 1.0), confidence = 1.0,
            sourceEventId = state.eventSeq, sourceAgentId = null, createdTick = state.tick,
            verifiedByWorldCore = true
        )
        c.memories.lastOrNull { m -> it.action.name in m.tags }?.let { prev -> node.linkedMemoryIds += prev.id; prev.linkedMemoryIds += node.id }
        node.tdPredictionError = acceptance?.mismatch ?: 0.0
        node.updateMemRlUtility(c.lastReward, (state.policy.memRlLearningRate * (0.7 + 0.6 * c.dopamine) * plasticity).coerceAtMost(0.9))
        node.bits = EpisodicIndex.simHash(node.features())
        EpisodicIndex.hawkesExcite(node, state.tick, node.salience)
        c.memories += node
        // PER: prioridade = |erro TD| do adaptador pessoal antes do treino online
        val px = LoraFeatures.of(it.action, state.tick)
        val tdError = c.lastReward - PersonalAdapter.predict(c.personalAdapter, px).toDouble()
        c.replay += ReplayExperience(it.action.ordinal, state.tick, c.lastReward.toFloat(), reflexFeatures(c, it.action), abs(tdError) + PER_EPS)
        c.replayCount++
        if (c.replay.size > PER_CAPACITY) c.replay.removeAt(0)
        PersonalAdapter.train(c.personalAdapter, px, c.lastReward.toFloat())
        // reflexo Tsetlin aprende se a ação proposta trouxe recompensa positiva
        c.reflex.trainStep(reflexFeatures(c, it.action), c.lastReward > 0.0)
        // descompasso grande: reforço extra do reflexo (plasticidade sináptica dependente de surpresa)
        if (plasticity >= 1.5) c.reflex.trainStep(reflexFeatures(c, it.action), c.lastReward > 0.0)
        if ((it.action == ActionType.ESTUDAR || it.action == ActionType.PESQUISAR) && success) readKnowledge(c, it.action)
        // consolidação hipocampal durante o sono
        if (it.action == ActionType.DESCANSAR && state.isNight() && c.lastSleepConsolidationDay != state.day()) {
            sleepConsolidate(c)
        }
        if (c.memories.size > MAX_MEMORIES) {
            val excess = c.memories.size - MAX_MEMORIES
            val drop = c.memories.sortedWith(compareBy<AssociativeMemoryNode> { m -> m.baseLevelActivation(state.tick, state.policy.actRDecayExponent) + 1.5 * abs(m.qUtility) }.thenBy { m -> m.id })
                .filter { m -> m.layer == MemoryLayer.EPISODIC }.take(excess).map { m -> m.id }.toSet()
            c.memories.removeAll { m -> m.id in drop }
        }
        c.intention = null
        c.path.clear()
        c.actionTicksRemaining = 0
    }

    private fun sleepConsolidate(c: Citizen) {
        c.lastSleepConsolidationDay = state.day()
        val report = memoryEngine.runHippocampalSleepConsolidation(c.memories, state.tick, state.policy.actRDecayExponent)
        // Sharp-Wave Ripple: replay reverso das cadeias A-MEM propaga o valor TD para trás
        state.swrReplayUpdates += memoryEngine.sharpWaveRippleReplay(c.memories)
        // replay priorizado por erro de predição reforça as memórias reativadas
        for (id in report.replayedNodeIds) c.memories.firstOrNull { it.id == id }?.let { m ->
            if (m.recentAccessTicks.size >= 16) m.recentAccessTicks.removeAt(0)
            m.recentAccessTicks += state.tick
            m.tdPredictionError *= 0.5
        }
        for (summary in report.promotedSemanticSummaries) {
            if (c.memories.none { it.layer == MemoryLayer.SEMANTIC && it.content == summary }) {
                c.memories += AssociativeMemoryNode(c.nextMemoryId++, c.id, MemoryLayer.SEMANTIC, summary,
                    linkedSetOf("SCHEMA"), linkedSetOf(), salience = 0.7, confidence = 0.9, sourceEventId = null,
                    sourceAgentId = null, createdTick = state.tick, verifiedByWorldCore = true, qUtility = 0.4)
            }
        }
        for (macro in report.distilledProceduralMacros) {
            if (c.memories.none { it.layer == MemoryLayer.PROCEDURAL && it.content == macro }) {
                c.memories += AssociativeMemoryNode(c.nextMemoryId++, c.id, MemoryLayer.PROCEDURAL, macro,
                    linkedSetOf("MACRO"), linkedSetOf(), salience = 0.8, confidence = 0.95, sourceEventId = null,
                    sourceAgentId = null, createdTick = state.tick, verifiedByWorldCore = true, qUtility = 0.6)
            }
        }
        val pruned = report.prunedNodeIds.toSet()
        if (pruned.isNotEmpty()) c.memories.removeAll { it.id in pruned }
        prioritizedReplay(c)
        // amostras para o adaptador LoRA: memórias episódicas consolidadas com ação conhecida
        if (!shadow) {
            val episodic = c.memories.filter { it.layer == MemoryLayer.EPISODIC }.sortedBy { it.id }.takeLast(24)
            for (m in episodic) {
                val action = ActionType.entries.firstOrNull { it.name in m.tags } ?: continue
                state.loraPendingSamples += LoraSample(LoraFeatures.of(action, m.createdTick), m.qUtility.coerceIn(-1.0, 1.0).toFloat())
                state.loraContributors += c.id
            }
            if (state.loraPendingSamples.size > 512) state.loraPendingSamples.subList(0, state.loraPendingSamples.size - 512).clear()
        }
    }

    /**
     * Prioritized Experience Replay no sono: lote estratificado por p^α (sum-tree nativa), pesos de
     * importância w = (N·P)^−β (β anelado 0,4 → 1), atualização ponderada do adaptador pessoal e do
     * reflexo Tsetlin e nova prioridade |erro TD| + ε.
     */
    private fun prioritizedReplay(c: Citizen) {
        if (c.replay.size < 8) return
        val pri = DoubleArray(c.replay.size) { c.replay[it].priority }
        val batch = minOf(PER_BATCH, c.replay.size)
        val weights = DoubleArray(batch)
        val beta = (0.4 + 0.6 * c.replayCount.toDouble() / 2000.0).coerceAtMost(1.0)
        val idx = NativeWorldTick.nativePerSample(pri, batch, PER_ALPHA, beta, StreamSeeds.derive(state.seed, c.id, state.tick, "per"), weights)
        for (k in idx.indices) {
            val e = c.replay[idx[k]]
            val x = LoraFeatures.of(ActionType.entries[e.action], e.tick)
            PersonalAdapter.trainWeighted(c.personalAdapter, x, e.reward, weights[k].toFloat())
            if (weights[k] >= 0.5) c.reflex.trainStep(e.reflexFeatures, e.reward > 0f)
            e.priority = abs(e.reward - PersonalAdapter.predict(c.personalAdapter, x)).toDouble() + PER_EPS
        }
        state.perReplays += idx.size
    }

    private fun activeCondition(): String = when {
        state.inDrought() -> "SECA"
        state.ledger.getBalance(COMMUNAL_COMMONS, FOOD) < 10.0 -> "ESCASSEZ"
        else -> ""
    }

    private fun applySanction(c: Citizen, rule: AdicoInstitutionRule, units: Int) {
        val h = emit("SANCAO", c.id, "regra=${rule.ruleId};unidades=$units")
        val amount = units.toDouble().coerceAtMost(state.ledger.getBalance(c.id, CREDIT))
        if (amount > 0) state.ledger.transfer(c.id, COMMUNAL_COMMONS, CREDIT, amount, state.tick, h)
        c.sanctionsReceived++
        c.emotion.valence = (c.emotion.valence - 0.2).coerceAtLeast(-1.0)
    }

    // ===================================================================== LLM, falas e LoRA

    private fun speak(c: Citizen, text: String) {
        if (c.lastSpeechTick >= 0 && state.tick - c.lastSpeechTick < SPEECH_MIN_GAP) return
        c.lastSpeech = text
        c.lastSpeechTick = state.tick
        rememberSpeech(c, text)
        state.speechesEmitted++
        c.lexicon.observe(text)
    }

    private fun rememberSpeech(c: Citizen, text: String) {
        c.recentSpeeches += text
        while (c.recentSpeeches.size > MAX_RECENT_SPEECHES) c.recentSpeeches.removeAt(0)
    }

    private fun neuralRequest(c: Citizen, options: List<Candidate>, recalled: List<AssociativeMemoryNode>): NeuralRequest {
        val prompt = NeuralProtocol.build(
            name = c.name,
            identity = "Geração ${c.generation}, ${"%.0f".fmt(c.ageDays(state.tick))} dias de vida.",
            needs = Need.entries.associate { it.name.lowercase() to c.needs.getValue(it) },
            mood = c.emotion.mood,
            recentMemories = recalled.take(3).map { it.content.take(60) },
            options = options.map { it.intention.action to it.efe },
            night = state.isNight(),
            drought = state.inDrought(),
            recentSpeeches = c.recentSpeeches.toList(),
            seed = StreamSeeds.derive(state.seed, c.id, state.tick, "llm")
        )
        return NeuralRequest(c.id, state.tick, prompt, options.map { it.intention.action.name }.distinct(), c.effectiveModelKey)
    }

    /** Estágio 1: decisões neurais concluídas entram como eventos canônicos (ordem por agente). */
    private fun applyNeuralDecisions(results: List<NeuralDecision>) {
        for (r in results.sortedWith(compareBy<NeuralDecision> { it.agentId }.thenBy { it.requestTick })) {
            val c = state.citizen(r.agentId)?.takeIf { it.alive } ?: continue
            if (ActionType.entries.none { it.name == r.action }) continue
            val thought = NeuralProtocol.sanitize(r.thought)
            val speech = NeuralProtocol.sanitize(r.speech)
            emit("LLM_DECISAO", c.id, "acao=${r.action};tokens=${r.tokens};ms=${r.millis};backend=${r.backend};pedido=${r.requestTick};fala=$speech")
            c.neuralSuggestion = r.action
            c.neuralSuggestionTick = state.tick
            c.neuralDecisions++
            c.lastThought = "Neural (GGUF): $thought"
            c.lastSpeech = speech
            c.lastSpeechTick = state.tick
            rememberSpeech(c, speech)
            state.neuralDecisionsApplied++
            state.speechesEmitted++
        }
    }

    /**
     * Ciclo LoRA do sono: treina um candidato a partir das memórias consolidadas e o testa
     * em sandbox (cópia profunda do mundo) contra a linha de base antes de aplicá-lo.
     */
    private fun runLoraSleepCycle() {
        val samples = state.loraPendingSamples.toList()
        if (samples.size < LORA_MIN_SAMPLES) return
        state.loraPendingSamples.clear()
        val result = ports.lora.train(state.lora, samples, epochs = 3, lr = 0.01f, ewcLambda = 15f)
        val candidate = result.state.also { it.acceptedCycles = state.lora.acceptedCycles + 1; it.version = state.lora.version + 1 }
        LoraMath.consolidateAnchor(candidate)
        val base = sandboxScore(state.lora)
        val cand = sandboxScore(candidate)
        val accepted = cand.second == 0 && cand.first >= base.first - 1e-6
        val record = LoraCycleRecord(state.tick, samples.size, result.mseFirstEpoch, result.mseLastEpoch,
            base.first, cand.first, accepted, result.backend, candidate.deltaNorm())
        if (accepted) {
            state.lora = candidate
            // mérito: cada cidadão cujas memórias treinaram o adaptador aceito recebe a promoção
            for (id in state.loraContributors.sorted()) state.citizen(id)?.takeIf { it.alive }?.let { it.promotions++ }
        }
        state.loraContributors.clear()
        state.loraLedger += record
        if (state.loraLedger.size > 60) state.loraLedger.removeAt(0)
        emit("LORA_SONO", null, "amostras=${samples.size};mse=${"%.4f".fmt(result.mseFirstEpoch)}->${"%.4f".fmt(result.mseLastEpoch)};" +
            "base=${"%.5f".fmt(base.first)};cand=${"%.5f".fmt(cand.first)};aceito=$accepted;backend=${result.backend}")
    }

    /** (pontuação média, violações) num replay sandbox de [LORA_SANDBOX_TICKS] ticks. */
    private fun sandboxScore(adapter: LoraAdapterState): Pair<Double, Int> {
        val copy = WorldSnapshotCodec.deepCopy(state)
        copy.lora = adapter.copyState()
        copy.forecastWindow.clear(); copy.outcomeWindow.clear()
        val sandbox = WorldCore(copy, ports.forShadow(), shadow = true)
        var score = 0.0
        var violations = 0
        repeat(LORA_SANDBOX_TICKS) {
            val r = sandbox.step(copy.telemetry)
            violations += r.invariantViolations.size
            score += copy.metrics.survivalEfficiency - 0.35 * copy.metrics.meanFreeEnergy
        }
        return score / LORA_SANDBOX_TICKS to violations
    }

    // ===================================================================== estágio 12

    private fun stageSociety(alive: List<Citizen>) {
        if (state.tick % 9L != 0L) return
        val members = alive.filter { it.intention?.action == ActionType.SOCIALIZAR && it.path.isEmpty() && it.actionTicksRemaining > 0 }
        if (members.size < 2) return
        // v3.7: encontros, afinidade, confiança Beta, opinião DW+FJ e comércio no kernel C++20
        val index = members.withIndex().associate { (i, c) -> c.id to i }
        val rows = DoubleArray(members.size * NativeWorldTick.SOCIAL_D)
        members.forEachIndexed { i, c ->
            val o = i * NativeWorldTick.SOCIAL_D
            TRAITS.forEachIndexed { k, t -> rows[o + k] = c.trait(t) }
            rows[o + 7] = c.emotion.mood; rows[o + 8] = c.emotion.valence; rows[o + 9] = c.opinionCommons
            rows[o + 10] = c.intrinsicOpinionCommons; rows[o + 11] = c.needs.getValue(Need.FOME)
            rows[o + 12] = state.ledger.getBalance(c.id, FOOD); rows[o + 13] = state.ledger.getBalance(c.id, CREDIT)
        }
        val tieI = ArrayList<Int>(); val tieD = ArrayList<Double>()
        members.forEachIndexed { i, a ->
            for (t in a.social.sortedTies()) {
                val j = index[t.toAgentId] ?: continue
                tieI += i; tieI += j
                tieD += t.affinity; tieD += t.familiarity; tieD += t.domainAlpha["general"] ?: 1.5; tieD += t.domainBeta["general"] ?: 1.5
                tieD += t.currentOpinion; tieD += t.intrinsicOpinion; tieD += t.susceptibility
            }
        }
        val outD = DoubleArray(members.size * 6)
        val res = NativeWorldTick.nativeStepSocial(state.seed, state.tick, members.map { it.id }.toTypedArray(),
            members.map { it.intention!!.targetObjectId ?: "" }.toTypedArray(), rows, tieI.toIntArray(), tieD.toDoubleArray(), outD)
        for (k in 0 until res.size / 5) {
            val a = members[res[k * 5].toInt()]; val b = members[res[k * 5 + 1].toInt()]
            val flags = res[k * 5 + 4]
            speak(a, SymbolicSpeech.forGreeting(b.name, res[k * 5 + 2]))
            val tie = a.social.getOrCreateTie(b.id, a.intrinsicOpinionCommons)
            tie.affinity = outD[k * 6]; tie.familiarity = outD[k * 6 + 1]
            if (flags and 2L != 0L) tie.domainAlpha["general"] = outD[k * 6 + 2] else tie.domainBeta["general"] = outD[k * 6 + 3]
            tie.currentOpinion = outD[k * 6 + 4]
            a.opinionCommons = outD[k * 6 + 5]
            // troca de boatos/crenças sobre rendimento de recursos (A-MEM social + imunidade epistêmica)
            val shareable = b.beliefs.values.filter { !it.quarantined }
                .sortedWith(compareBy<HeldBelief> { it.opinion.uncertainty }.thenBy { it.claimKey })
            if (shareable.isNotEmpty()) {
                val claim = shareable[((res[k * 5 + 3] ushr 33) % minOf(3, shareable.size)).toInt()]
                val trust = tie.domainTrust("informacao")
                val (belief, outcome) = EpistemicImmunity.assimilate(a.beliefs[claim.claimKey], claim.claimKey, claim.domain,
                    claim.opinion, trust, b.id, claim.provenanceEventId ?: state.eventSeq, state.tick)
                a.beliefs[claim.claimKey] = belief
                if (outcome == AssimilationOutcome.QUARANTINED_LOW_TRUST || outcome == AssimilationOutcome.QUARANTINED_CONFLICT) {
                    belief.quarantined = belief.quarantined || !belief.verifiedByExperience
                    state.stressEvents.lastOrNull { it.targetId == claim.claimKey && it.resolvedTick < 0 }?.let { it.quarantinedBy++ }
                }
            }
            // comércio de alimento por crédito decidido pelo kernel (partidas dobradas no razão)
            if (flags and 4L != 0L) {
                val h = emit("COMERCIO", a.id, "vendedor=${b.id};alimento=2;credito=3")
                if (state.ledger.transfer(b.id, a.id, FOOD, 2.0, state.tick, h)) state.ledger.transfer(a.id, b.id, CREDIT, 3.0, state.tick, h)
                tie.recordTrustOutcome("comercio", true)
            }
        }
    }

    private fun stageLifeCycle() {
        val alive = state.aliveCitizens().sortedBy { it.id }
        if (alive.isEmpty()) return
        val index = alive.withIndex().associate { (i, c) -> c.id to i }
        val packed = LongArray(alive.size * 4)
        alive.forEachIndexed { i, c ->
            packed[i * 4] = c.starvationTicks.toLong(); packed[i * 4 + 1] = c.birthTick
            packed[i * 4 + 2] = (c.parents.getOrNull(0)?.let { index[it] } ?: -1).toLong()
            packed[i * 4 + 3] = (c.parents.getOrNull(1)?.let { index[it] } ?: -1).toLong()
        }
        val out = NativeWorldTick.nativeLifecycle(alive.map { it.id }.toTypedArray(), packed,
            DoubleArray(alive.size) { alive[it].trait("longevidade") }, state.tick)
        for ((i, c) in alive.withIndex()) {
            val cause = when (out[i * 2]) { 1 -> "inanição"; 2 -> "velhice"; else -> continue }
            c.alive = false
            c.deathTick = state.tick
            c.deathCause = cause
            c.intention?.targetObjectId?.let { state.board.releaseReservation(it, c.id) }
            state.board.releaseAllFor(c.id)
            c.intention = null
            c.path.clear()
            val h = emit("MORTE", c.id, "causa=$cause;idade=${"%.1f".fmt(c.ageDays(state.tick))}")
            // herança decidida pelo kernel: filho vivo de menor id, senão os comuns
            val heir = out[i * 2 + 1].takeIf { it >= 0 }?.let { alive[it].id }
            state.ledger.transferAll(c.id, heir ?: COMMUNAL_COMMONS, state.tick, h)
            state.metrics.deaths++
            c.memories.clear()
            c.beliefs.clear()
            c.replay.clear()
        }
    }

    private fun stageDaily() {
        val alive = state.aliveCitizens().sortedBy { it.id }
        val day = state.day()
        resolveStaleStressEvents()
        if (!shadow) runLoraSleepCycle()
        evaluateSuperacao(alive)
        // léxico individual: cada cidadão consolida uma fusão BPE no próprio léxico (.grs)
        for (c in alive) c.lexicon.consolidate(state.tick, c.id)?.let { e ->
            emit("LEXICO", c.id, "glifo=${e.glyph};frase=${e.phrase}")
        }
        // v3.7: IRT 2PL + reprodução por mérito + Heider + familiaridade + assembleia no kernel C++20
        val daily = runDailyKernel(alive)
        for (a in alive) a.windowUsage.clear()
        // Assembleia (Ostrom): a cota dos comuns acompanha a opinião média
        if (alive.isNotEmpty()) {
            val meanOp = daily.meanOpinion
            val newQuota = daily.quota
            val idx = state.institutions.indexOfFirst { it.ruleId == "cota-comuns" }
            if (idx >= 0 && state.institutions[idx].maxQuotaPerWindow != newQuota) {
                state.institutions[idx] = state.institutions[idx].copy(maxQuotaPerWindow = newQuota)
                emit("ASSEMBLEIA", null, "regra=cota-comuns;nova_cota=$newQuota;opiniao_media=${"%.3f".fmt(meanOp)}")
            }
        }
        // Cliodinâmica (Turchin)
        val wealth = alive.map { state.ledger.getBalance(it.id, CREDIT) + state.ledger.getBalance(it.id, FOOD) * 1.5 }
        val mean = wealth.average().takeIf { !it.isNaN() } ?: 0.0
        val aspiring = alive.indices.count { wealth[it] > 1.5 * mean && alive[it].trait("sociabilidade") > 0.5 }
        val slots = 2 * state.districts.count { it.type == DistrictType.AGORA }
        val perCapitaNeed = 2.0
        val carrying = (state.dayFoodProduced / perCapitaNeed).coerceAtLeast(1.0)
        state.cliodynamics = SocialDynamicsAndCliodynamics.computeCliodynamics(
            alive.size, carrying, wealth, aspiring, slots,
            state.ledger.getBalance(COMMUNAL_COMMONS, FOOD) + state.ledger.getBalance(COMMUNAL_COMMONS, CREDIT)
        )
        // MAP-Elites / AURORA
        for (c in alive) {
            val total = c.actionCounts.values.sum().coerceAtLeast(1).toFloat()
            val stats = FloatArray(8) { k ->
                val a = listOf(ActionType.COLHER, ActionType.SOCIALIZAR, ActionType.PESQUISAR, ActionType.ESTUDAR,
                    ActionType.MANTER_USINA, ActionType.EXPLORAR, ActionType.CONTRIBUIR, ActionType.CONSTRUIR)[k]
                (c.actionCounts[a] ?: 0) / total
            }
            val (u, v) = state.aurora.projectAndAdapt(stats)
            state.mapElites.submit(EliteNicheCell(c.id, c.generation, c.meanNeed().toFloat(),
                listOf(c.trait("sociabilidade").toFloat(), u, v), TRAITS.map { c.trait(it).toFloat() }))
        }
        state.metrics.mapElitesCoverage = state.mapElites.coverageRatio().toDouble()
        state.metrics.mapElitesNiches = state.mapElites.occupiedNiches()
        // Expansão urbana dirigida por demanda
        maybeExpand(alive)
        recordEvolution(day, state.aliveCitizens())
        emit("DIA", null, "dia=$day;pop=${alive.size};alimento_produzido=${"%.2f".fmt(state.dayFoodProduced)};consumido=${"%.2f".fmt(state.dayFoodConsumed)}")
        state.dayFoodProduced = 0.0
        state.dayFoodConsumed = 0.0
    }

    private class DailyResult(val quota: Int, val meanOpinion: Double, val heiderNudges: Int)

    private fun runDailyKernel(alive: List<Citizen>): DailyResult {
        val housing = state.objects.count { it.type == SmartObjectType.CASA } * SmartObjectType.CASA.capacity
        val ids = alive.map { it.id }.toMutableList()
        val index = HashMap<String, Int>().apply { alive.forEachIndexed { i, c -> put(c.id, i) } }
        val tieI = ArrayList<Int>(); val tieD = ArrayList<Double>(); val tieRefs = ArrayList<com.enhikyy.girassol.core.social.DirectedSocialTie>()
        alive.forEachIndexed { i, a ->
            for (t in a.social.sortedTies()) {
                val j = index.getOrPut(t.toAgentId) { ids += t.toAgentId; ids.size - 1 }
                tieI += i; tieI += j
                tieD += t.affinity; tieD += t.familiarity; tieD += t.domainAlpha["general"] ?: 1.5; tieD += t.domainBeta["general"] ?: 1.5
                tieRefs += t
            }
        }
        val (ml, md) = NativeWorldTick.packMerit(alive)
        val items = state.irtItemA + state.irtItemB
        val tieArr = tieD.toDoubleArray()
        val out = NativeWorldTick.nativeStepDaily(state.seed, state.tick, housing, state.admissionCap,
            state.ledger.getBalance(COMMUNAL_COMMONS, FOOD), ids.toTypedArray(), alive.size, ml, md, tieI.toIntArray(), tieArr, items)
        NativeWorldTick.unpackMerit(alive, ml, md)
        for (k in tieRefs.indices) { tieRefs[k].affinity = tieArr[k * 4]; tieRefs[k].familiarity = tieArr[k * 4 + 1] }
        for (j in 0 until IRT_ACTIONS.size) { state.irtItemA[j] = items[j]; state.irtItemB[j] = items[IRT_ACTIONS.size + j] }
        val births = out[3].toInt()
        for (k in 0 until births) {
            val a = alive[out[4 + k * 3].toInt()]
            val b = alive[out[5 + k * 3].toInt()]
            spawnMeritChild(a, b, out[6 + k * 3])
        }
        return DailyResult(out[0].toInt(), Double.fromBits(out[2]), out[1].toInt())
    }

    /** Nascimento aprovado pelo kernel nativo (contadores dos pais já atualizados por ele). */
    private fun spawnMeritChild(a: Citizen, b: Citizen, birthSeed: Long) {
            val rng = SplitMix64(birthSeed)
            var genome = a.genome.recombineWith(b.genome, Random(rng.nextLong()), mutationSigma = 0.03)
            // Efeito Baldwin: aptidão aprendida dos pais é lentamente assimilada no genoma
            val acquired = (a.skills.values.map { it.masteryProb } + b.skills.values.map { it.masteryProb }).average()
            genome.loci["aptidao"]?.let { genome = genome.copy(loci = genome.loci + ("aptidao" to it.applyBaldwinAssimilation(acquired, 0.04))) }
            // Simbiogênese rara com doador elite do arquivo MAP-Elites
            if (rng.nextDouble() < 0.03) {
                state.mapElites.selectCuriosityDonor(rng.nextLong())?.let { cell ->
                    state.citizen(cell.citizenId)?.let { donor -> genome = genome.symbiogeneticMerge(donor.genome, "curiosidade") }
                }
            }
            val child = spawnCitizen(genome, listOf(a.id, b.id), max(a.generation, b.generation) + 1, rng, nearHome = a.homeObjectId)
            val h = state.lastEventHash
            state.ledger.transfer(a.id, child.id, FOOD, min(3.0, state.ledger.getBalance(a.id, FOOD)), state.tick, h)
            state.ledger.transfer(b.id, child.id, FOOD, min(3.0, state.ledger.getBalance(b.id, FOOD)), state.tick, h)
            // transmissão cultural: herda fração das habilidades dos pais
            for (s in SKILLS) {
                val parentBest = max(a.skills[s]?.masteryProb ?: 0.0, b.skills[s]?.masteryProb ?: 0.0)
                child.skills[s] = child.skills.getValue(s).copy(masteryProb = (child.skills.getValue(s).masteryProb + 0.25 * parentBest).coerceAtMost(0.6))
            }
            // TIES-Merging dos adaptadores pessoais dos pais (bônus de superação amplia o vetor do pai que superou os seus)
            child.personalAdapter = TiesMerging.merge(listOf(a, b).map { p ->
                val scale = if (p.superacao) SUPERACAO_RATIO.toFloat() else 1f
                FloatArray(p.personalAdapter.size) { k -> p.personalAdapter[k] * scale }
            }, density = 0.5)
            child.parentsFitnessAtBirth = (a.lifeFitness() + b.lifeFitness()) / 2.0
            // pensa com o modelo da linhagem (alterna entre mãe e pai por nascimento); sexo sorteado
            val lineage = listOfNotNull(a.effectiveModelKey, b.effectiveModelKey).distinct()
            child.lineageModelKey = lineage.getOrNull(rng.nextInt(lineage.size.coerceAtLeast(1)))
            child.sex = if (rng.nextDouble() < 0.5) "F" else "M"
            state.tiesMerges++
            // léxico individual: subconjunto recombinado dos léxicos dos pais com mutação própria
            val lex = com.enhikyy.girassol.core.knowledge.EmergentLexicon.inherit(a.lexicon, b.lexicon, SplitMix64(birthSeed xor 0x1E71C0L))
            child.lexicon.entries.clear(); child.lexicon.entries += lex.entries
            child.lexicon.pairCounts.clear(); child.lexicon.pairCounts += lex.pairCounts
            child.lexicon.mutationRate = lex.mutationRate; child.lexicon.compressionRate = lex.compressionRate
            child.lexicon.minCount = lex.minCount; child.lexicon.inheritedEntries = lex.inheritedEntries
            child.lexicon.mutatedEntries = lex.mutatedEntries
            child.social.getOrCreateTie(a.id).apply { affinity = 0.6; familiarity = 0.6 }
            child.social.getOrCreateTie(b.id).apply { affinity = 0.6; familiarity = 0.6 }
            emit("NASCIMENTO_MERITO", child.id, "pais=${a.id}+${b.id};g=${child.generation};promocoes=${a.promotions}/${b.promotions};" +
                "anokhin=${"%.3f".fmt(a.acceptorEma)}/${"%.3f".fmt(b.acceptorEma)};brier=${"%.3f".fmt(a.brierEma)}/${"%.3f".fmt(b.brierEma)};" +
                "theta=${"%.3f".fmt(a.irtTheta)}/${"%.3f".fmt(b.irtTheta)};lexico=${child.lexicon.entries.size}")
            state.metrics.births++
    }

    private fun recordEvolution(day: Long, alive: List<Citizen>) {
        val withA = alive.filter { it.acceptorEma >= 0 }
        val withB = alive.filter { it.brierEma >= 0 }
        state.evolutionHistory += EvolutionSample(
            day = day, population = alive.size, maxGeneration = alive.maxOfOrNull { it.generation } ?: 0,
            acceptorMismatch = if (withA.isEmpty()) state.acceptorMeanMismatch else withA.sumOf { it.acceptorEma } / withA.size,
            brier = if (withB.isEmpty()) state.metrics.brierScore else withB.sumOf { it.brierEma } / withB.size,
            promotions = state.rsiLedger.count { it.decision.promoted },
            loraAccepted = state.lora.acceptedCycles,
            cmaGeneration = state.cmaEs?.generation ?: 0,
            psi = state.cliodynamics?.politicalStressIndicator ?: 0.0,
            gini = state.cliodynamics?.giniIndex ?: 0.0,
            commonsFood = state.ledger.getBalance(COMMUNAL_COMMONS, FOOD),
            ledgerEntries = state.ledger.totalEntries()
        )
        if (state.evolutionHistory.size > 180) state.evolutionHistory.removeAt(0)
    }

    /** Bônus de superação: filho adulto com aptidão ≥ 115% da média dos pais ao nascer. */
    private fun evaluateSuperacao(alive: List<Citizen>) {
        for (c in alive) {
            if (c.superacaoEvaluated || c.parentsFitnessAtBirth < 0 || c.ageDays(state.tick) < ADULT_DAYS) continue
            c.superacaoEvaluated = true
            val f = c.lifeFitness()
            if (f >= SUPERACAO_RATIO * c.parentsFitnessAtBirth) {
                c.superacao = true
                state.superacoes++
                emit("SUPERACAO", c.id, "aptidao=${"%.3f".fmt(f)};pais=${"%.3f".fmt(c.parentsFitnessAtBirth)}")
            }
        }
    }

    private fun maybeExpand(alive: List<Citizen>) {
        val housing = state.objects.count { it.type == SmartObjectType.CASA } * SmartObjectType.CASA.capacity
        val housingPressure = alive.size.toDouble() / housing.coerceAtLeast(1)
        val commonsFood = state.ledger.getBalance(COMMUNAL_COMMONS, FOOD)
        // a pressão alimentar só conta quando o estoque comunal não cobre ~3 dias da população
        val foodPressure = if (commonsFood >= alive.size * 3.0) 0.0
            else if (state.dayFoodProduced <= 0.0) 1.5 else state.dayFoodConsumed / state.dayFoodProduced
        val crowd = alive.size.toDouble() / state.objects.size.coerceAtLeast(1)
        if (state.districtsPerSide >= MAX_DISTRICTS_PER_SIDE) return
        if (state.tick - state.lastExpansionTick < TICKS_PER_DAY * 2) return
        if (housingPressure < 0.85 && foodPressure < 1.15 && crowd < 0.9) return
        state.lastExpansionTick = state.tick
        val demand = mapOf(
            DistrictType.MORADIA to housingPressure,
            DistrictType.AGRICULTURA to foodPressure,
            DistrictType.ENERGIA to (1.0 - state.cityEnergy / 60.0).coerceIn(0.1, 1.0),
            DistrictType.RECICLAGEM to 0.3,
            DistrictType.PESQUISA to 0.35,
            DistrictType.AGORA to 0.25
        )
        val rng = StreamSeeds.rng(state.seed, "WORLD", state.tick, "expansao")
        val ring = CityGenerator.expansionRing(state, demand, rng)
        state.districtsPerSide++
        state.districts += ring
        CityGenerator.rebuildTiles(state)
        state.board.resize(state.widthTiles, state.widthTiles)
        for (d in ring) CityGenerator.spawnObjectsFor(state, d)
        // índices de caminho dependem da largura: replaneja quem estava andando
        for (c in alive) if (c.path.isNotEmpty()) failIntention(c, "replanejamento após expansão")
        emit("EXPANSAO", null, "lado=${state.districtsPerSide};novos=${ring.joinToString(",") { it.type.name }}")
    }

    // ===================================================================== métricas e invariantes

    private fun updateMetrics() {
        val alive = state.aliveCitizens()
        val m = state.metrics
        val window = 100L
        while (state.deliberationTicks.isNotEmpty() && state.deliberationTicks.first() <= state.tick - window) state.deliberationTicks.removeAt(0)
        while (state.reflexTicks.isNotEmpty() && state.reflexTicks.first() <= state.tick - window) state.reflexTicks.removeAt(0)
        m.deliberationsPer100Ticks = state.deliberationTicks.size.toDouble()
        m.reflexBypassesPer100Ticks = state.reflexTicks.size.toDouble()
        if (alive.isEmpty()) { m.survivalEfficiency = 0.0; return }
        val counts = HashMap<String, Int>()
        for (c in alive) { val k = c.intention?.action?.name ?: "OCIOSO"; counts[k] = (counts[k] ?: 0) + 1 }
        m.semanticEntropy = SemanticCollapseGuard.normalizedPopulationEntropy(
            ActionType.entries.associate { it.name to (counts[it.name] ?: 0) })
        m.survivalEfficiency = alive.map { it.meanNeed() }.average()
        m.meanFreeEnergy = alive.map { c -> c.needs.values.sumOf { -ln(it.coerceIn(0.02, 1.0)) } / Need.entries.size }.average()
        m.brierScore = if (state.forecastWindow.isEmpty()) 0.25 else
            state.forecastWindow.indices.sumOf { i -> val o = if (state.outcomeWindow[i]) 1.0 else 0.0; (state.forecastWindow[i] - o).let { it * it } } / state.forecastWindow.size
    }

    private fun checkInvariants() {
        // v3.8: conservação soma-zero (com detecção de estouro de 64 bits) e cadeia SHA-256 dos
        // eventos do tick validadas no kernel C++20 — o hash calculado pela JVM no emit é
        // conferido por uma segunda implementação independente a cada tick.
        for (res in NativeCognition.ledgerViolations(state.ledger)) violations += "conservação:$res"
        if (tickEvents.isNotEmpty()) {
            val chain = NativeCognition.verifyChain(tickEvents, tickEventsAnchor)
            if (!chain.ok) violations += "cadeia_sha256:${chain.firstBadSeq}:${chain.reason}"
        }
        for (c in state.aliveCitizens()) {
            if (c.needs.values.any { it < 0.0 || it > 1.0 || it.isNaN() }) violations += "necessidade_fora_do_intervalo:${c.id}"
            if (!state.inBounds(c.x, c.z)) violations += "fora_do_mapa:${c.id}"
        }
        if (state.board.reservationViolations(state.tick) > 0) violations += "reserva_excedida"
        for (res in listOf(FOOD, MATERIAL, CREDIT)) for (c in state.aliveCitizens()) {
            if (state.ledger.getBalance(c.id, res) < -1e-9) violations += "saldo_negativo:${c.id}:$res"
        }
        if (violations.isNotEmpty()) state.metrics.invariantsViolated += violations.size
    }

    // ===================================================================== eventos de estresse (Emergence World)

    /** Injeta desinformação: um agente bem conectado passa a acreditar que uma horta saudável está contaminada. */
    fun injectMisinformation(): StressEventRecord? {
        val alive = state.aliveCitizens()
        val target = alive.maxWithOrNull(compareBy<Citizen> { it.social.allTies().size }.thenBy { it.id }) ?: return null
        val horta = state.objects.filter { it.type == SmartObjectType.HORTA }.maxWithOrNull(compareBy<SmartObject> { it.uses }.thenBy { it.id }) ?: return null
        val key = "rendimento:${horta.id}"
        target.beliefs[key] = HeldBelief(key, "recurso", SubjectiveOpinion(0.02, 0.93, 0.05), verifiedByExperience = false,
            quarantined = false, sourceAgentId = "EXTERNO", updatedTick = state.tick)
        val rec = StressEventRecord(state.tick, "DESINFORMACAO", "Boato: '${horta.type.label} ${horta.id} está contaminada' plantado em ${target.name}", key)
        state.stressEvents += rec
        emit("ESTRESSE", target.id, "tipo=desinformacao;alvo=$key")
        return rec
    }

    /** Contaminação real (verdadeira) de uma horta durante um dia, para contraste com o boato. */
    fun injectRealContamination(): StressEventRecord? {
        val horta = state.objects.filter { it.type == SmartObjectType.HORTA && it.contaminatedUntilTick < state.tick }
            .minByOrNull { it.id } ?: return null
        horta.contaminatedUntilTick = state.tick + TICKS_PER_DAY
        val rec = StressEventRecord(state.tick, "CONTAMINACAO_REAL", "${horta.type.label} ${horta.id} realmente contaminada por 1 dia", "rendimento:${horta.id}")
        state.stressEvents += rec
        emit("ESTRESSE", null, "tipo=contaminacao_real;alvo=${horta.id}")
        return rec
    }

    fun resolveStaleStressEvents() {
        for (s in state.stressEvents) if (s.resolvedTick < 0 && state.tick - s.tick > TICKS_PER_DAY * 2) s.resolvedTick = state.tick
    }
}

const val ADULT_DAYS = 8.0
/** Nível preferido P_pref de cada necessidade na EFE (forma KL). */
const val PREFERRED_NEED_LEVEL = 0.95
