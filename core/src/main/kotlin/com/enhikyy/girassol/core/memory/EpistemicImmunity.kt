package com.enhikyy.girassol.core.memory

import com.enhikyy.girassol.core.world.HeldBelief
import kotlin.math.abs

/** Resultado da assimilação de uma afirmação recebida de outro agente. */
enum class AssimilationOutcome { FUSED, QUARANTINED_LOW_TRUST, QUARANTINED_CONFLICT, CREATED }

/**
 * Imunidade epistêmica integrada ao fluxo de assimilação (OpenLife / Emergence World):
 * 1. a opinião recebida é descontada pela confiança no domínio "informacao" (⊗ de Jøsang);
 * 2. fontes com confiança baixa ou opiniões que conflitam com evidência própria verificada
 *    vão para quarentena — não entram nas decisões até serem verificadas pelo WorldCore;
 * 3. caso contrário, a opinião é combinada por fusão por média (rumores correlacionados,
 *    sem dupla contagem).
 */
object EpistemicImmunity {
    const val MIN_SOURCE_TRUST = 0.35
    const val CONFLICT_THRESHOLD = 0.40

    fun assimilate(
        existing: HeldBelief?,
        claimKey: String,
        domain: String,
        heard: SubjectiveOpinion,
        sourceTrust: Double,
        sourceAgentId: String,
        provenanceEventId: Long?,
        tick: Long
    ): Pair<HeldBelief, AssimilationOutcome> {
        val discounted = heard.discountByTrust(sourceTrust)
        if (existing == null) {
            val quarantine = sourceTrust < MIN_SOURCE_TRUST
            return HeldBelief(
                claimKey = claimKey, domain = domain, opinion = discounted,
                verifiedByExperience = false, quarantined = quarantine,
                sourceAgentId = sourceAgentId, provenanceEventId = provenanceEventId, updatedTick = tick
            ) to (if (quarantine) AssimilationOutcome.QUARANTINED_LOW_TRUST else AssimilationOutcome.CREATED)
        }
        if (sourceTrust < MIN_SOURCE_TRUST) {
            return existing to AssimilationOutcome.QUARANTINED_LOW_TRUST
        }
        if (existing.verifiedByExperience &&
            abs(existing.opinion.projectedProbability() - discounted.projectedProbability()) > CONFLICT_THRESHOLD
        ) {
            return existing to AssimilationOutcome.QUARANTINED_CONFLICT
        }
        existing.opinion = SubjectiveOpinion.averagingFuse(existing.opinion, discounted)
        if (!existing.verifiedByExperience) existing.sourceAgentId = sourceAgentId
        existing.updatedTick = tick
        return existing to AssimilationOutcome.FUSED
    }

    /** Evidência direta (verificada pelo WorldCore): atualização Beta e liberação da quarentena. */
    fun observeDirectly(belief: HeldBelief, success: Boolean, tick: Long): Boolean {
        val priorProjected = belief.opinion.projectedProbability()
        if (success) belief.alphaEvidence += 1.0 else belief.betaEvidence += 1.0
        belief.opinion = SubjectiveOpinion.fromBeta(belief.alphaEvidence, belief.betaEvidence, belief.opinion.baseRate)
        val wasHearsay = !belief.verifiedByExperience
        belief.verifiedByExperience = true
        belief.quarantined = false
        belief.updatedTick = tick
        // retorna se o boato anterior era consistente com a verificação
        return !wasHearsay || (priorProjected >= 0.5) == success
    }
}
