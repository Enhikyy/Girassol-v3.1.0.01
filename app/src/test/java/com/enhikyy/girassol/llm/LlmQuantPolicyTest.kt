package com.enhikyy.girassol.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class LlmQuantPolicyTest {
    private val gb = 1024L * 1024 * 1024

    /** v4.2: F16 é o tipo preferido em TODOS os perfis (treino contínuo LoRA/RSI). */
    @Test fun kvCacheF16PreferidoEmTodosOsPerfis() {
        assertEquals(LlmQuantPolicy.KV_F16, LlmQuantPolicy.kvTypeFor(4 * gb - 200L * 1024 * 1024))
        assertEquals(LlmQuantPolicy.KV_F16, LlmQuantPolicy.kvTypeFor(4 * gb))
        assertEquals(LlmQuantPolicy.KV_F16, LlmQuantPolicy.kvTypeFor(8 * gb))
        assertEquals(LlmQuantPolicy.KV_F16, LlmQuantPolicy.kvTypeFor(12 * gb))
        for (ram in listOf(3631L, 8 * 1024L, 12 * 1024L)) assertEquals(LlmQuantPolicy.KV_F16, LlmQuantPolicy.profileFor(ram * 1024 * 1024).kvType)
        assertEquals("f16", LlmQuantPolicy.kvName(LlmQuantPolicy.KV_F16))
        assertEquals("q4_0", LlmQuantPolicy.kvName(LlmQuantPolicy.KV_Q4_0))
        assertEquals("q8_0", LlmQuantPolicy.kvName(LlmQuantPolicy.KV_Q8_0))
        assertEquals("f8_e4m3", LlmQuantPolicy.kvName(LlmQuantPolicy.KV_F8_E4M3))
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
            assertTrue("estimativa $est MB para $size MB", est in 250..550)
        }
    }

    @Test fun perfisPorRam() {
        val gb = 1024L * mb
        assertEquals(1024, LlmQuantPolicy.profileFor(4 * gb).nCtx)
        assertEquals(2048, LlmQuantPolicy.profileFor(8 * gb).nCtx)
        assertEquals(4096, LlmQuantPolicy.profileFor(12 * gb).nCtx)
        assertEquals(LlmQuantPolicy.KV_F16, LlmQuantPolicy.profileFor(4 * gb).kvType)
    }

    /** v4.2: nenhum .gguf é recusado; arquivo gigante só gera AVISO com o degrau esperado. */
    @Test fun arquivoGiganteAceitoComAviso() {
        val p = LlmQuantPolicy.profileFor(a22)
        val w = LlmQuantPolicy.admission(3_000L * mb, a22, p.nCtx, p.kvType)
        assertTrue(w != null && w.startsWith("Aviso"))
        assertTrue(w!!.contains("mapeável"))  // 3 GB cabe em F16 no orçamento, mas excede o mapeável estimado
        val absurdo = LlmQuantPolicy.admission(20_000L * mb, a22, p.nCtx, p.kvType)
        assertTrue(absurdo != null && absurdo.startsWith("Aviso") && absurdo.contains("disjuntor"))
    }

    /** v4.2: F16 sempre que couber; Q8_0 → F8_E4M3 → Q4_0 só quando o disjuntor não comporta. */
    @Test fun escadaDeSobrevivenciaRespeitaDisjuntor() {
        // arquivo 0 (residente) e n_ctx 1024: F16 264 MB, Q8_0 186, F8 181, Q4_0 144
        fun pick(budgetMb: Long) = LlmQuantPolicy.chooseKv(0L, 1024) { it <= budgetMb * mb }
        assertEquals(LlmQuantPolicy.KV_F16, pick(4_000))
        assertEquals(LlmQuantPolicy.KV_F16, pick(264))
        assertEquals(LlmQuantPolicy.KV_Q8_0, pick(200))
        assertEquals(LlmQuantPolicy.KV_F8_E4M3, pick(183))
        assertEquals(LlmQuantPolicy.KV_Q4_0, pick(150))
        assertNull(pick(100))
        // BabyLM/LFM compacto em F16 no Moto G62 (4 GB) não é rebaixado preventivamente
        val p = LlmQuantPolicy.profileFor(a22)
        assertEquals(LlmQuantPolicy.KV_F16, LlmQuantPolicy.chooseKv(462L * mb, p.nCtx) { it <= LlmQuantPolicy.budgetFor(a22) })
        // a escada pode começar num degrau (ex.: motor recusou F16) e nunca sobe
        assertEquals(LlmQuantPolicy.KV_F8_E4M3, LlmQuantPolicy.chooseKv(0L, 1024, LlmQuantPolicy.KV_F8_E4M3) { true })
    }

    @Test fun fidelidadeDeTreino() {
        val pura = LlmQuantPolicy.parseFidelity("f16|kv=f16|pedido=f16|pesos=F16|hd=64")
        assertTrue(pura.pureF16)
        assertTrue(pura.label.startsWith("F16 puro"))
        val ram = LlmQuantPolicy.parseFidelity("degradado|kv=q8_0|pedido=q8_0|pesos=F16|hd=64|motivo=kv", "disjuntor de RAM")
        assertFalse(ram.pureF16)
        assertEquals("q8_0", ram.kv)
        assertTrue(ram.label.contains("disjuntor de RAM"))
        val hd = LlmQuantPolicy.parseFidelity("degradado|kv=f8_e4m3|pedido=q8_0|pesos=Q4_0|hd=8|motivo=pesos,kv_head_dim")
        assertTrue(hd.reason.contains("pesos quantizados") && hd.reason.contains("head_dim"))
        assertFalse(LlmQuantPolicy.parseFidelity("").pureF16)
        assertEquals("sem sessão", LlmQuantPolicy.parseFidelity("").label)
    }
}

class LlmPoolPolicyTest {
    private val mb = 1024L * 1024

    @Test fun perfil4GbSingleModel8e12Pool() {
        assertEquals(1, LlmQuantPolicy.profileFor(3631 * mb).poolCapacity)
        assertEquals(2, LlmQuantPolicy.profileFor(8 * 1024 * mb).poolCapacity)
        assertEquals(3, LlmQuantPolicy.profileFor(12 * 1024 * mb).poolCapacity)
    }

    @Test fun poolEncolheQuandoNaoCabeNoOrcamento() {
        val p12 = LlmQuantPolicy.profileFor(12 * 1024 * mb)
        val budget = LlmQuantPolicy.budgetFor(12 * 1024 * mb)
        assertEquals(3, LlmQuantPolicy.fitPool(3, 400 * mb, listOf(400 * mb, 400 * mb), budget, p12.nCtx, p12.kvType))
        assertTrue(LlmQuantPolicy.fitPool(3, 3000 * mb, listOf(3000 * mb, 3000 * mb), budget, p12.nCtx, p12.kvType) < 3)
        assertEquals(1, LlmQuantPolicy.fitPool(1, 100 * mb, listOf(100 * mb), budget, p12.nCtx, p12.kvType))
    }

    @Test fun statusDoPoolNativoDecodifica() {
        val s = LlmQuantPolicy.parsePool("3|2|5|3|1|/d/a.gguf|/d/a.gguf:1000;/d/b|x.gguf:2000")
        assertEquals(3, s.capacity); assertEquals(2, s.resident); assertEquals(5L, s.switches)
        assertEquals(3L, s.coldLoads); assertEquals(1L, s.evictions); assertEquals("/d/a.gguf", s.active)
        assertEquals(listOf("/d/a.gguf", "/d/b|x.gguf"), s.paths)
        assertEquals(listOf(1000L, 2000L), s.bytes)
        assertEquals(1, LlmQuantPolicy.parsePool("").capacity)
    }
}
