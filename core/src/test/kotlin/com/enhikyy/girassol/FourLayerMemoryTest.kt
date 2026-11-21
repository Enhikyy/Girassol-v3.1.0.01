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

    private fun episode(id: Long, content: String) = AssociativeMemoryNode(
        id = id,
        ownerAgentId = "C1",
        layer = MemoryLayer.EPISODIC,
        content = content,
        tags = setOf("horta", "COLHER"),
        entityIds = setOf("HORTA_1"),
        salience = 0.8,
        confidence = 0.9,
        sourceEventId = id,
        sourceAgentId = "C1",
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
    }

    @Test
    fun semanticAndProceduralMemoryNodesSurviveWorldSnapshotRoundTrip() {
        val core = WorldCore.create(seed = 42L, initialPopulation = 1)
        val citizen = core.state.citizens.single()
        citizen.memories += AssociativeMemoryNode(
            id = 1L,
            ownerAgentId = citizen.id,
            layer = MemoryLayer.SEMANTIC,
            content = "SCHEMA[horta]: episódios verificados indicam cuidado diário.",
            tags = setOf("SCHEMA", "horta"),
            entityIds = setOf("HORTA_1"),
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
            salience = 0.8,
            confidence = 0.95,
            sourceEventId = null,
            sourceAgentId = null,
            createdTick = 110L,
            verifiedByWorldCore = true,
            qUtility = 0.6
        )
        citizen.nextMemoryId = 3L

        val restored = WorldSnapshotCodec.decode(WorldSnapshotCodec.encode(core.state))
        val restoredMemories = restored.citizens.single { it.id == citizen.id }.memories

        assertEquals(
            setOf(MemoryLayer.SEMANTIC, MemoryLayer.PROCEDURAL),
            restoredMemories.map { it.layer }.toSet()
        )
        assertEquals(2, restoredMemories.size)
        assertEquals(3L, restored.citizens.single { it.id == citizen.id }.nextMemoryId)
        assertTrue(restoredMemories.all { it.verifiedByWorldCore })
    }
}
