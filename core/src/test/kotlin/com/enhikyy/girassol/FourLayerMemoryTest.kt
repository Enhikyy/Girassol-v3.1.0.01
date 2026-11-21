package com.enhikyy.girassol

import com.enhikyy.girassol.core.memory.AssociativeMemoryNode
import com.enhikyy.girassol.core.memory.FourLayerMemoryAndSleepEngine
import com.enhikyy.girassol.core.memory.MemoryLayer
import com.enhikyy.girassol.core.world.WorldCore
import com.enhikyy.girassol.core.world.WorldSnapshotCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FourLayerMemoryTest {

    private fun episode(id: Long, content: String, ownerId: String = "C1") = AssociativeMemoryNode(
        id = id,
        ownerAgentId = ownerId,
        layer = MemoryLayer.EPISODIC,
        content = content,
        tags = setOf("horta", "COLHER"),
        entityIds = setOf("HORTA_1"),
        salience = 0.8,
        confidence = 0.9,
        sourceEventId = id,
        sourceAgentId = ownerId,
        createdTick = id * 10,
        qUtility = 0.8,
        verifiedByWorldCore = true
    )

    @Test
    fun verifiedEpisodesProduceSemanticAndProceduralConsolidationCandidates() {
        val memories = mutableListOf(
            episode(1L, "Colhi tomates maduros na horta."),
            episode(2L, "A horta produziu mais após irrigação."),
            episode(3L, "A colheita melhorou com cuidado diário.")
        )

        val report = FourLayerMemoryAndSleepEngine().runHippocampalSleepConsolidation(
            memories = memories,
            currentTick = 200L
        )

        assertTrue(report.promotedSemanticSummaries.any { it.contains("horta") })
        assertFalse(report.distilledProceduralMacros.isEmpty())
        assertTrue(report.replayedNodeIds.isNotEmpty())
        val schema = report.semanticRelations.single { it.topicTag == "horta" }
        assertEquals(setOf(1L, 2L, 3L), schema.sourceEpisodeIds)
        assertEquals(setOf("HORTA_1"), schema.entityIds)
        assertTrue(report.proceduralRelations.all { it.sourceEpisodeId in memories.map { node -> node.id } })
    }

    @Test
    fun semanticAndProceduralMemoryNodesSurviveWorldSnapshotRoundTrip() {
        val core = WorldCore.create(seed = 42L, initialPopulation = 1)
        val citizen = core.state.citizens.single()
        citizen.memories += episode(3L, "Episódio fonte: irrigação verificada.", citizen.id)
        citizen.memories += AssociativeMemoryNode(
            id = 1L,
            ownerAgentId = citizen.id,
            layer = MemoryLayer.SEMANTIC,
            content = "SCHEMA[horta]: episódios verificados indicam cuidado diário.",
            tags = setOf("SCHEMA", "horta"),
            entityIds = setOf("HORTA_1"),
            linkedMemoryIds = linkedSetOf(3L),
            salience = 0.7,
            confidence = 0.9,
            sourceEventId = null,
            sourceAgentId = null,
            createdTick = 100L,
            verifiedByWorldCore = true,
            qUtility = 0.4
        )
        citizen.memories += AssociativeMemoryNode(
            id = 2L,
            ownerAgentId = citizen.id,
            layer = MemoryLayer.PROCEDURAL,
            content = "SKILL_MACRO: irrigar antes da colheita.",
            tags = setOf("MACRO", "cultivo"),
            entityIds = setOf("HORTA_1"),
            linkedMemoryIds = linkedSetOf(3L),
            salience = 0.8,
            confidence = 0.95,
            sourceEventId = null,
            sourceAgentId = null,
            createdTick = 110L,
            verifiedByWorldCore = true,
            qUtility = 0.6
        )
        citizen.nextMemoryId = 4L

        val restored = WorldSnapshotCodec.decode(WorldSnapshotCodec.encode(core.state))
        val restoredMemories = restored.citizens.single { it.id == citizen.id }.memories

        assertEquals(
            setOf(MemoryLayer.EPISODIC, MemoryLayer.SEMANTIC, MemoryLayer.PROCEDURAL),
            restoredMemories.map { it.layer }.toSet()
        )
        assertEquals(3, restoredMemories.size)
        assertEquals(setOf(3L), restoredMemories.single { it.layer == MemoryLayer.SEMANTIC }.linkedMemoryIds)
        assertEquals(setOf(3L), restoredMemories.single { it.layer == MemoryLayer.PROCEDURAL }.linkedMemoryIds)
        assertEquals(setOf("HORTA_1"), restoredMemories.single { it.layer == MemoryLayer.SEMANTIC }.entityIds)
        assertEquals(4L, restored.citizens.single { it.id == citizen.id }.nextMemoryId)
        assertTrue(restoredMemories.all { it.verifiedByWorldCore })
    }

    @Test
    fun workingMemoryIsPerCitizenBoundedAndRestoredWithWorldSnapshot() {
        val core = WorldCore.create(seed = 77L, initialPopulation = 2)
        val first = core.state.citizens[0]
        val second = core.state.citizens[1]
        first.memories += (1L..12L).map { id -> episode(id, "horta colheita irrigação episódio $id", first.id) }
        second.memories += (101L..112L).map { id -> episode(id, "horta socialização conversa episódio $id", second.id) }
        first.nextMemoryId = 13L
        second.nextMemoryId = 113L

        val engine = FourLayerMemoryAndSleepEngine()
        repeat(3) { pass ->
            engine.retrieveTwoStage(
                nodes = first.memories,
                currentTick = 200L + pass,
                queryText = "horta colheita irrigação",
                queryTags = setOf("horta", "COLHER"),
                queryEntities = setOf("HORTA_1"),
                topK = 4,
                workingCapacity = 5,
                workingMemoryIds = first.workingMemoryIds
            )
        }
        engine.retrieveTwoStage(
            nodes = second.memories,
            currentTick = 210L,
            queryText = "horta socialização conversa",
            queryTags = setOf("horta", "SOCIAL"),
            queryEntities = setOf("HORTA_1"),
            topK = 4,
            workingCapacity = 5,
            workingMemoryIds = second.workingMemoryIds
        )

        val secondBeforeFirstChangesAgain = second.workingMemoryIds.toList()
        engine.retrieveTwoStage(
            nodes = first.memories,
            currentTick = 220L,
            queryText = "colheita irrigação sementes",
            queryTags = setOf("horta", "COLHER"),
            queryEntities = setOf("HORTA_1"),
            topK = 4,
            workingCapacity = 5,
            workingMemoryIds = first.workingMemoryIds
        )

        assertTrue(first.workingMemoryIds.size <= 5)
        assertTrue(second.workingMemoryIds.size <= 5)
        assertEquals(first.workingMemoryIds.size, first.workingMemoryIds.distinct().size)
        assertEquals(secondBeforeFirstChangesAgain, second.workingMemoryIds)
        assertTrue(first.workingMemoryIds.all { id -> first.memories.any { it.id == id } })
        assertTrue(second.workingMemoryIds.all { id -> second.memories.any { it.id == id } })

        val restored = WorldSnapshotCodec.decode(WorldSnapshotCodec.encode(core.state))
        val restoredFirst = restored.citizens.single { it.id == first.id }
        val restoredSecond = restored.citizens.single { it.id == second.id }
        assertEquals(first.workingMemoryIds, restoredFirst.workingMemoryIds)
        assertEquals(second.workingMemoryIds, restoredSecond.workingMemoryIds)
    }
}
