package com.enhikyy.girassol

import com.enhikyy.girassol.core.memory.AssimilationOutcome
import com.enhikyy.girassol.core.memory.EpistemicImmunity
import com.enhikyy.girassol.core.memory.SubjectiveOpinion
import com.enhikyy.girassol.core.reflex.KotlinTsetlinMachine
import com.enhikyy.girassol.core.rsi.RsiCoordinator
import com.enhikyy.girassol.core.world.FOOD
import com.enhikyy.girassol.core.world.MATERIAL
import com.enhikyy.girassol.core.world.TICKS_PER_DAY
import com.enhikyy.girassol.core.world.WorldCore
import com.enhikyy.girassol.core.world.WorldRenderSnapshot
import com.enhikyy.girassol.core.world.WorldSnapshotCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldCoreTest {

    private fun run(core: WorldCore, ticks: Int) = repeat(ticks) { core.step() }

    @Test
    fun mesmaSementeProduzMesmaCadeiaDeEventos() {
        val a = WorldCore.create(42L, 24)
        val b = WorldCore.create(42L, 24)
        run(a, 700); run(b, 700)
        assertEquals(a.state.lastEventHash, b.state.lastEventHash)
        assertEquals(WorldSnapshotCodec.stateHash(a.state), WorldSnapshotCodec.stateHash(b.state))
    }

    @Test
    fun sementesDiferentesDivergem() {
        val a = WorldCore.create(1L, 24)
        val b = WorldCore.create(2L, 24)
        run(a, 200); run(b, 200)
        assertNotEquals(a.state.lastEventHash, b.state.lastEventHash)
    }

    @Test
    fun snapshotRestauradoContinuaIdenticoAoMundoVivo() {
        val live = WorldCore.create(99L, 24)
        run(live, 350)
        val bytes = WorldSnapshotCodec.encode(live.state)
        val restored = WorldCore(WorldSnapshotCodec.decode(bytes))
        assertEquals(WorldSnapshotCodec.stateHash(live.state), WorldSnapshotCodec.stateHash(restored.state))
        run(live, 400); run(restored, 400)
        assertEquals(live.state.lastEventHash, restored.state.lastEventHash)
        assertEquals(WorldSnapshotCodec.stateHash(live.state), WorldSnapshotCodec.stateHash(restored.state))
    }

    @Test(expected = IllegalStateException::class)
    fun snapshotCorrompidoERejeitado() {
        val core = WorldCore.create(5L, 24)
        val bytes = WorldSnapshotCodec.encode(core.state)
        bytes[10] = (bytes[10].toInt() xor 0xFF).toByte()
        WorldSnapshotCodec.decode(bytes)
    }

    @Test
    fun doisDiasSemViolarInvariantesEConservandoRecursos() {
        val core = WorldCore.create(2026L, 24)
        run(core, (TICKS_PER_DAY * 2).toInt())
        val s = core.state
        assertEquals(0, s.metrics.invariantsViolated)
        assertTrue(s.ledger.verifyZeroSumConservation(FOOD))
        assertTrue(s.ledger.verifyZeroSumConservation(MATERIAL))
        assertTrue(s.aliveCitizens().isNotEmpty())
        assertTrue(s.metrics.semanticEntropy in 0.0..1.0)
        val snap = WorldRenderSnapshot.of(s)
        assertEquals(s.widthTiles * s.widthTiles, snap.tiles.size)
        assertEquals(snap.population, snap.agents.size)
    }

    @Test
    fun tsetlinAprendePadraoESerializa() {
        val tm = KotlinTsetlinMachine(4, 20, 64, 3.0f, 8, 7L)
        val pos = byteArrayOf(1, 1, 0, 0)
        val neg = byteArrayOf(0, 0, 1, 1)
        repeat(400) { tm.trainStep(pos, true); tm.trainStep(neg, false) }
        assertTrue(tm.predictVote(pos) > 0)
        assertTrue(tm.predictVote(neg) < 0)
        val copy = KotlinTsetlinMachine.deserialize(tm.serialize())
        assertNotNull(copy)
        assertEquals(tm.predictVote(pos), copy!!.predictVote(pos))
        assertEquals(tm.predictVote(neg), copy.predictVote(neg))
    }

    @Test
    fun imunidadeEpistemicaQuarentenaFonteNaoConfiavelEConflito() {
        val heard = SubjectiveOpinion(0.05, 0.9, 0.05)
        val (b1, o1) = EpistemicImmunity.assimilate(null, "rendimento:H1", "recurso", heard, 0.2, "C9", null, 10)
        assertEquals(AssimilationOutcome.QUARANTINED_LOW_TRUST, o1)
        assertTrue(b1.quarantined)
        // evidência própria positiva verificada
        repeat(6) { EpistemicImmunity.observeDirectly(b1, true, 20) }
        val (_, o2) = EpistemicImmunity.assimilate(b1, "rendimento:H1", "recurso", heard, 0.95, "C3", null, 30)
        assertEquals(AssimilationOutcome.QUARANTINED_CONFLICT, o2)
        assertTrue(b1.opinion.projectedProbability() > 0.6)
    }

    @Test
    fun cicloRsiExecutaShadowReplaySemAlterarMundoVivo() {
        val core = WorldCore.create(77L, 24)
        run(core, 300)
        val before = WorldSnapshotCodec.stateHash(core.state)
        val coordinator = RsiCoordinator(replayTicks = 120)
        val snapshot = WorldSnapshotCodec.deepCopy(core.state)
        coordinator.shadowReplay(snapshot, core.state.policy)
        assertEquals(before, WorldSnapshotCodec.stateHash(core.state))
        val record = coordinator.runCycle(core)
        assertEquals(1, core.state.rsiLedger.size)
        assertEquals(core.state.tick, record.tick)
        if (!record.decision.promoted) assertEquals(snapshot.policy, core.state.policy)
    }

    @Test
    fun desinformacaoGeraEventoDeEstresseRastreavel() {
        val core = WorldCore.create(11L, 24)
        run(core, 100)
        val rec = core.injectMisinformation()
        assertNotNull(rec)
        val holders = core.state.aliveCitizens().count { rec!!.targetId in it.beliefs }
        assertTrue(holders >= 1)
        run(core, TICKS_PER_DAY.toInt())
        assertEquals(0, core.state.metrics.invariantsViolated)
        assertNotNull(core.injectRealContamination())
    }
}
