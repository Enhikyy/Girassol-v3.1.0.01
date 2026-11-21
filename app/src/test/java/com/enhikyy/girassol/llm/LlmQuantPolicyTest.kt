package com.enhikyy.girassol.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class LlmQuantPolicyTest {
    private val gb = 1024L * 1024 * 1024

    @Test fun kvCacheQ4EmQuatroGbEQ8Acima() {
        assertEquals(LlmQuantPolicy.KV_Q4_0, LlmQuantPolicy.kvTypeFor(4 * gb - 200L * 1024 * 1024))
        assertEquals(LlmQuantPolicy.KV_Q4_0, LlmQuantPolicy.kvTypeFor(4 * gb))
        assertEquals(LlmQuantPolicy.KV_Q8_0, LlmQuantPolicy.kvTypeFor(8 * gb))
        assertEquals(LlmQuantPolicy.KV_Q8_0, LlmQuantPolicy.kvTypeFor(12 * gb))
        assertEquals("q4_0", LlmQuantPolicy.kvName(LlmQuantPolicy.KV_Q4_0))
        assertEquals("q8_0", LlmQuantPolicy.kvName(LlmQuantPolicy.KV_Q8_0))
    }

    @Test fun lazySomenteEmAparelhosPequenos() {
        assertTrue(LlmQuantPolicy.lazyFor(3 * gb))
        assertFalse(LlmQuantPolicy.lazyFor(8 * gb))
    }

    @Test fun regraDeOuroKleidiAi() {
        assertTrue(LlmQuantPolicy.weightAdvice("qwen2.5-0.5b-instruct-q4_0.gguf").contains("compatível KleidiAI"))
        assertTrue(LlmQuantPolicy.weightAdvice("llama 1B Q8_0").contains("compatível KleidiAI"))
        assertTrue(LlmQuantPolicy.weightAdvice("model-Q4_K_M.gguf").contains("Q4_0/Q8_0 é mais rápido"))
        // v3.5: F16/FP16 é aceito como primeira classe; o tipo real do GGUF (ftype) tem prioridade sobre o nome
        assertTrue(LlmQuantPolicy.weightAdvice("model-f16.gguf", fp16Cpu = true).contains("aceleração nativa ARMv8.2 fp16"))
        assertTrue(LlmQuantPolicy.weightAdvice("qualquer-nome.gguf", ftype = "F16").contains("F16 (FP16)"))
        assertTrue(LlmQuantPolicy.weightAdvice("modelo-sem-pista.gguf").contains("não identificado"))
    }

    private val mb = 1024L * 1024
    private val a22 = 3631L * mb  // SM-A226BR

    /** v3.6: os três .gguf do relato de campo são admitidos no A22 (pesos em mmap, não heap). */
    @Test fun lfm25AdmitidosEmA22() {
        val p = LlmQuantPolicy.profileFor(a22)
        assertEquals("4 GB", p.label)
        for (size in listOf(462L, 711L, 260L)) {
            val bytes = size * mb
            assertNull("$size MB recusado", LlmQuantPolicy.admission(bytes, a22, p.nCtx, p.kvType))
            val est = LlmQuantPolicy.activeRamEstimate(bytes, p.nCtx, p.kvType) / mb
            assertTrue("estimativa $est MB para $size MB", est in 150..450)
        }
    }

    @Test fun perfisPorRam() {
        val gb = 1024L * mb
        assertEquals(1024, LlmQuantPolicy.profileFor(4 * gb).nCtx)
        assertEquals(2048, LlmQuantPolicy.profileFor(8 * gb).nCtx)
        assertEquals(4096, LlmQuantPolicy.profileFor(12 * gb).nCtx)
        assertEquals(LlmQuantPolicy.KV_Q4_0, LlmQuantPolicy.profileFor(4 * gb).kvType)
    }

    @Test fun arquivoGiganteAindaRecusado() {
        val p = LlmQuantPolicy.profileFor(a22)
        assertTrue(LlmQuantPolicy.admission(3_000L * mb, a22, p.nCtx, p.kvType) != null)
    }
}
