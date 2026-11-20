package com.enhikyy.girassol.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.enhikyy.girassol.core.world.WorldState

/**
 * Linhas das tabelas de projeção (consultáveis por SQL), extraídas na thread da
 * simulação junto com o snapshot. São derivadas — a fonte da verdade é o snapshot
 * + a cadeia de eventos canônicos.
 */
class ProjectionRows private constructor(
    private val tables: Map<String, List<ContentValues>>,
    private val memoryFts: List<ContentValues> = emptyList()
) {

    fun writeTo(db: SQLiteDatabase) {
        for ((table, rows) in tables) {
            db.delete(table, null, null)
            for (r in rows) db.insertWithOnConflict(table, null, r, SQLiteDatabase.CONFLICT_REPLACE)
        }
        EpisodicFts.replaceAll(db, memoryFts)
    }

    companion object {
        private fun numericId(id: String): Long = id.filter { it.isDigit() }.toLongOrNull() ?: 0L

        fun of(state: WorldState): ProjectionRows {
            val alive = state.aliveCitizens()
            val beliefs = alive.flatMap { c ->
                c.beliefs.values.map { b ->
                    ContentValues().apply {
                        put("observer_agent_id", c.id); put("claim_key", b.claimKey); put("domain", b.domain)
                        put("belief", b.opinion.belief); put("disbelief", b.opinion.disbelief)
                        put("uncertainty", b.opinion.uncertainty); put("base_rate", b.opinion.baseRate)
                        b.provenanceEventId?.let { put("provenance_event_id", it) }; put("updated_tick", b.updatedTick)
                    }
                }
            }
            val automata = alive.map { c ->
                ContentValues().apply {
                    put("citizen_id", numericId(c.id)); put("num_features", c.reflex.numFeatures)
                    put("num_clauses", c.reflex.numClauses); put("automata_blob", c.reflex.serialize()); put("updated_tick", state.tick)
                }
            }
            val mastery = alive.flatMap { c ->
                c.skills.values.map { s ->
                    ContentValues().apply {
                        put("citizen_id", numericId(c.id)); put("skill_id", s.skillId); put("mastery_prob", s.masteryProb)
                        put("difficulty_d", s.difficultyD); put("stability_s", s.stabilityS)
                        put("last_reviewed_tick", s.lastReviewedTick); put("verified_executions", s.verifiedExecutions)
                    }
                }
            }
            val voyager = state.skillLibrary.all().map { s ->
                ContentValues().apply {
                    put("skill_id", s.skillId); put("author_agent_id", s.authorAgentId)
                    put("preconditions_json", s.preconditions.joinToString(",", "[", "]") { "\"$it\"" })
                    put("action_sequence_json", s.actionSequence.joinToString(",", "[", "]") { "\"$it\"" })
                    put("verified_success_rate", s.verifiedSuccessRate); put("created_at_tick", s.createdAtTick)
                }
            }
            val adico = state.institutions.map { r ->
                ContentValues().apply {
                    put("rule_id", r.ruleId); put("attributes_json", r.attributes.sorted().joinToString(",", "[", "]") { "\"$it\"" })
                    put("deontic_op", r.deontic.name); put("aim_action", r.aimAction); put("conditions_text", r.conditions)
                    put("sanction_units", r.orElseSanctionUnits); put("max_quota", r.maxQuotaPerWindow)
                }
            }
            val structures = state.objects.map { o ->
                ContentValues().apply {
                    put("structure_id", o.id); put("structure_type", o.type.name); put("grid_x", o.tileX); put("grid_z", o.tileZ)
                    put("level", 1); put("structural_integrity", o.condition); put("builder_agent_id", "CIDADE"); put("built_at_tick", 0)
                }
            }
            return ProjectionRows(mapOf(
                "subjective_beliefs" to beliefs, "tsetlin_automata_blobs" to automata, "bkt_fsrs_mastery" to mastery,
                "voyager_skill_library" to voyager, "adico_institutions" to adico, "ecological_niche_structures" to structures
            ), EpisodicFts.rows(state))
        }
    }
}
