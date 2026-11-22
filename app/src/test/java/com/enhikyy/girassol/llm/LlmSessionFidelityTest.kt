package com.enhikyy.girassol.llm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * v4.2 — a fidelidade de treino vem de UMA fonte atômica ([LlmSessionFidelity]); refreshes
 * concorrentes de [LlmStats] nunca a sobrescrevem com estado vazio ou anterior.
 */
class LlmSessionFidelityTest {
    private val pura = LlmQuantPolicy.parseFidelity("f16|kv=f16|pedido=f16|pesos=F16|hd=64")
    private val degradada = LlmQuantPolicy.parseFidelity("degradado|kv=q8_0|pedido=q8_0|pesos=F16|hd=64|motivo=kv", "disjuntor de RAM")

    @After fun reset() = LlmSessionFidelity.publish(LlmQuantPolicy.TrainingFidelity())

    @Test fun copiaAntigaOuVaziaRecebeAFidelidadeAtual() {
        LlmSessionFidelity.publish(pura)
        // um refresh que parte de um snapshot vazio (ou de antes da carga) não apaga a fidelidade
        val vazio = LlmStats()
        val stale = LlmStats(trainingF16 = false, trainingFidelity = degradada.label)
        for (s in listOf(vazio, stale)) {
            val r = s.copy(status = LlmStatus.READY).withSessionFidelity(LlmSessionFidelity.current)
            assertTrue(r.trainingF16)
            assertEquals(pura.label, r.trainingFidelity)
        }
        // já coerente: devolve a mesma instância (sem alocação extra no refresh)
        val ok = vazio.withSessionFidelity(pura)
        assertTrue(ok === ok.withSessionFidelity(pura))
    }

    @Test fun refreshesConcorrentesNuncaPerdemAUltimaPublicacao() {
        val flow = MutableStateFlow(LlmStats())
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        repeat(3) { w ->
            pool.execute {
                start.await()
                repeat(5_000) { i ->
                    flow.update { it.copy(decisions = it.decisions + 1, lastError = "refresh $w/$i").withSessionFidelity(LlmSessionFidelity.current) }
                }
            }
        }
        pool.execute {
            start.await()
            repeat(500) { i -> LlmSessionFidelity.publish(if (i % 2 == 0) degradada else pura) }
            LlmSessionFidelity.publish(pura)
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        // qualquer escrita posterior (o próximo refresh) converge para a última publicação
        flow.update { it.withSessionFidelity(LlmSessionFidelity.current) }
        assertEquals(15_000, flow.value.decisions)  // CAS: nenhum refresh perdido
        assertTrue(flow.value.trainingF16)
        assertEquals(pura.label, flow.value.trainingFidelity)
    }

    @Test fun semSessaoNaoEhF16() {
        val f = LlmSessionFidelity.current
        assertFalse(f.pureF16)
        assertEquals("sem sessão", f.label)
    }
}
