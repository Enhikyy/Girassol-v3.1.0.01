package com.enhikyy.girassol.nativebridge

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmVariantSelectorTest {
    @Test
    fun snapdragon695AndHelioG99GetDotprodNeverI8mm() {
        // Cortex-A78/A76 + A55: dotprod + fp16, sem i8mm
        val f = CpuSimdFlags(neon = true, dotprod = true, fp16 = true, i8mm = false, aarch64 = true)
        assertEquals(listOf("dotprod", "base"), LlmVariantSelector.candidates(f))
    }

    @Test
    fun armv9CoresGetI8mmFirstWithFallbacks() {
        val f = CpuSimdFlags(neon = true, dotprod = true, fp16 = true, i8mm = true, sve = true, sve2 = true, aarch64 = true)
        assertEquals(listOf("i8mm", "dotprod", "base"), LlmVariantSelector.candidates(f))
    }

    @Test
    fun i8mmWithoutFp16FallsToBase() {
        val f = CpuSimdFlags(neon = true, dotprod = false, fp16 = false, i8mm = true, aarch64 = true)
        assertEquals(listOf("base"), LlmVariantSelector.candidates(f))
    }

    @Test
    fun cortexA53AndX86GetBase() {
        assertEquals(listOf("base"), LlmVariantSelector.candidates(CpuSimdFlags(neon = true, aarch64 = true)))
        assertEquals(listOf("base"), LlmVariantSelector.candidates(CpuSimdFlags(dotprod = true, fp16 = true, i8mm = true, aarch64 = false)))
    }

    @Test
    fun decodesNativeArray() {
        val f = CpuSimdFlags.fromNative(intArrayOf(1, 1, 1, 0, 0, 0, 1, 1))
        assertEquals("NEON=1 DOTPROD=1 FP16=1 I8MM=0 SVE=0 SVE2=0", f.label())
        assertEquals(CpuSimdFlags(), CpuSimdFlags.fromNative(null))
    }
}
