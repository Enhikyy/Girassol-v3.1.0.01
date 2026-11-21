package com.enhikyy.girassol.system

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v3.8 — o R8 do build Release removia/renomeava as configs do sherpa-onnx (lidas por
 * GetFieldID no JNI) e quebrava ASR/TTS. Estas regras precisam existir; a verificação no APK
 * real é feita no CI por scripts/check_r8_jni.py (dexdump).
 */
class ProguardRulesTest {
    private val rules: String by lazy {
        val f = listOf(File("proguard-rules.pro"), File("app/proguard-rules.pro")).first { it.isFile }
        f.readLines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
    }

    private fun keeps(pattern: String) = Regex("-keep(classmembers|classeswithmembers)?[^\\n]*" + Regex.escape(pattern)).containsMatchIn(rules)

    @Test
    fun sherpaOnnxClassesAndMembersAreKept() {
        assertTrue(keeps("com.k2fsa.sherpa.onnx.**"))
        assertTrue(Regex("-keepclassmembers[^\\n]*com\\.k2fsa\\.sherpa\\.onnx\\.\\*\\*").containsMatchIn(rules))
        assertTrue(rules.contains("-dontwarn com.k2fsa.sherpa.onnx.**"))
    }

    @Test
    fun nativeBridgesAreKept() {
        assertTrue("métodos native", Regex("native\\s+<methods>").containsMatchIn(rules))
        for (p in listOf("com.enhikyy.girassol.nativebridge", "com.enhikyy.girassol.llm", "com.enhikyy.girassol.core", "com.enhikyy.girassol.knowledge")) {
            assertTrue("falta -keep para $p", keeps(p))
        }
    }

    @Test
    fun callbackInterfaceUsedFromJniIsKept() {
        assertTrue(keeps("kotlin.jvm.functions.Function1"))
    }
}
