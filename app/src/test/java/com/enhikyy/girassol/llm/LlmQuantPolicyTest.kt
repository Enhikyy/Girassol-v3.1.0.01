package com.enhikyy.girassol.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertTrue(LlmQuantPolicy.weightAdvice("model-Q4_K_M.gguf").contains("prefira Q4_0/Q8_0"))
        assertTrue(LlmQuantPolicy.weightAdvice("model-f16.gguf").contains("prefira"))
    }
}
