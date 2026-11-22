package com.enhikyy.girassol

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.enhikyy.girassol.data.GirassolRecordCodec
import com.enhikyy.girassol.nativebridge.SynapticBudgetNative
import com.enhikyy.girassol.synaptic.SynapticBudgetEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Orçamento Sináptico no aparelho (arm64-v8a em hardware real; x86_64 no emulador do CI):
 * T1–T18 via JNI com o hash canônico do T13, .grs ida-e-volta pelo motor e pelo codec, e a
 * projeção (I − P) anulando as direções protegidas.
 */
@RunWith(AndroidJUnit4::class)
class SynapticBudgetArm64Test {
    private val tag = "SynapticBudgetTest"

    @Test
    fun selfTestsT1toT18MatchCanonicalHash(): Unit = runBlocking {
        assertTrue("libgirassol_sb não carregou: ${SynapticBudgetNative.loadError}", SynapticBudgetNative.loaded)
        val r = SynapticBudgetEngine.runSelfTests()
        Log.i(tag, "ABI=${r.abi} (aparelho ${Build.SUPPORTED_ABIS.joinToString()}) checks=${r.checks} fails=${r.fails} T13_HASH=${r.t13Hash}")
        Log.i(tag, r.log.takeLast(2000))
        assertEquals("falhas totais", 0, r.fails)
        assertTrue("verificações insuficientes: ${r.checks}", r.checks >= 200)
        assertEquals("T13", 0, r.perTestFails[13])
        assertEquals("T17 (rotações não-aninhadas)", 0, r.perTestFails[17])
        assertEquals("T18 (MultiLayerBudget)", 0, r.perTestFails[18])
        assertEquals("hash T13", SynapticBudgetNative.CANONICAL_T13_HASH, r.t13Hash)
    }

    @Test
    fun grsRoundTripThroughEngineAndCodec(): Unit = runBlocking {
        SynapticBudgetEngine.start(InstrumentationRegistry.getInstrumentation().targetContext)
        val t = withTimeout(30_000) { SynapticBudgetEngine.telemetry.first { it.ready } }
        val ram = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(android.app.ActivityManager::class.java)
            .let { am -> android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem / (1024 * 1024) }
        assertEquals("D pela RAM", SynapticBudgetEngine.dimensionForRam(ram), t.dim)
        // alimenta o motor com um lote real (mesmo caminho do LoRA do mundo)
        val n = 24; val inDim = 18
        val xs = FloatArray(n * inDim) { i -> ((i * 2654435761L) % 1000).toFloat() / 1000f - 0.5f }
        SynapticBudgetEngine.observeWorldBatch(xs, n, inDim)
        withTimeout(30_000) { SynapticBudgetEngine.telemetry.first { it.worldObservations >= n } }

        val blob = SynapticBudgetEngine.serialize()!!
        val info = GirassolRecordCodec.inspectGrsb(blob)
        assertEquals(t.dim, info.dim)
        val world = ByteArray(4096) { (it * 31).toByte() }
        val container = GirassolRecordCodec.encode(world, blob)
        val decoded = GirassolRecordCodec.decode(container)
        assertArrayEquals(world, decoded.world)
        assertArrayEquals(blob, decoded.synaptic)
        val msg = SynapticBudgetEngine.restore(decoded.synaptic!!)
        assertTrue(msg, msg.startsWith("orçamento sináptico restaurado"))
        assertArrayEquals("re-serialização idêntica", blob, SynapticBudgetEngine.serialize())
        // bloco corrompido é rejeitado sem tocar no estado
        val bad = blob.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x5A).toByte() }
        assertTrue(SynapticBudgetEngine.restore(bad).startsWith("bloco inválido"))
        assertArrayEquals(blob, SynapticBudgetEngine.serialize())
        Log.i(tag, "GRSB D=${info.dim} ${blob.size} B FNV=${info.checksumHex}")
    }

    @Test
    fun projectionNullsProtectedDirections() {
        var err: Throwable? = null
        val th = Thread(null, {
            err = runCatching {
                val d = 64
                val buf = ByteBuffer.allocateDirect((SynapticBudgetNative.nativeStateBytes(d) + 64).toInt()).order(ByteOrder.nativeOrder())
                val off = SynapticBudgetNative.nativeAlignOffset(buf)
                assertEquals(0, SynapticBudgetNative.nativeInit(buf, off, d))
                val rows = 4
                val x = DoubleArray(rows * d)
                for (r in 0 until rows) x[r * d + r * 3] = 1.0 + r
                assertEquals(0, SynapticBudgetNative.nativeObserve(buf, off, d, x, rows, 1.0))
                assertTrue(SynapticBudgetNative.nativeCommit(buf, off, d, rows) >= 0)
                val g = x.copyOf()
                assertEquals(0, SynapticBudgetNative.nativeProject(buf, off, d, g, rows))
                val norm = sqrt(g.sumOf { it * it })
                assertTrue("resíduo na direção protegida: $norm", norm < 1e-9)
                val free = DoubleArray(d).also { it[d - 1] = 1.0 }
                SynapticBudgetNative.nativeProject(buf, off, d, free, 1)
                assertEquals(1.0, free[d - 1], 1e-12)
            }.exceptionOrNull()
        }, "sb-test", 64L shl 20)
        th.start(); th.join()
        err?.let { throw it }
    }
}
