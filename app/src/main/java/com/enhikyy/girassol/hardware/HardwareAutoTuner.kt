package com.enhikyy.girassol.hardware

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.enhikyy.girassol.nativebridge.GeaNativeBridge

/** Perfil de hardware detectado em tempo de execução (nada é presumido por modelo). */
data class HardwareProfile(
    val manufacturer: String,
    val model: String,
    val socManufacturer: String,
    val socModel: String,
    val abis: List<String>,
    val cpuCores: Int,
    val totalRamMb: Long,
    val nativeLoaded: Boolean,
    val nativeCapabilities: Map<String, String>,
    val performanceClass: Int,
    val tier: Tier,
    val maxTicksPerSecond: Int
) {
    enum class Tier(val label: String) { ALTO("Alto"), MEDIO("Médio"), BASICO("Básico") }

    fun capability(key: String): Boolean = nativeCapabilities[key] == "1"
}

/**
 * Ajuste automático: escolhe o teto de ticks/s a partir de núcleos, RAM e recursos
 * do SoC (dotprod/i8mm/SVE2 em arm64; AVX2 em x86_64). O teto só altera a
 * VELOCIDADE do relógio do mundo — nunca o resultado de um tick nem do Shadow-Replay,
 * preservando o determinismo entre aparelhos.
 */
object HardwareAutoTuner {
    fun detect(context: Context): HardwareProfile {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val ramMb = mi.totalMem / (1024 * 1024)
        val cores = Runtime.getRuntime().availableProcessors()
        val caps = if (GeaNativeBridge.isNativeLoaded) {
            runCatching { GeaNativeBridge.nativeProbeHardware() }.getOrDefault("")
                .split(';').mapNotNull { kv -> kv.split('=').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        } else emptyMap()
        val simdBoost = caps["dotprod"] == "1" || caps["i8mm"] == "1" || caps["sve2"] == "1" || caps["avx2"] == "1"
        val score = cores * 2 + (ramMb / 1024).toInt() + (if (simdBoost) 4 else 0) + Build.VERSION.MEDIA_PERFORMANCE_CLASS / 10
        val tier = when {
            score >= 30 -> HardwareProfile.Tier.ALTO
            score >= 18 -> HardwareProfile.Tier.MEDIO
            else -> HardwareProfile.Tier.BASICO
        }
        val maxTps = when (tier) {
            HardwareProfile.Tier.ALTO -> 120
            HardwareProfile.Tier.MEDIO -> 60
            HardwareProfile.Tier.BASICO -> 30
        }
        return HardwareProfile(
            manufacturer = Build.MANUFACTURER, model = Build.MODEL,
            socManufacturer = Build.SOC_MANUFACTURER, socModel = Build.SOC_MODEL,
            abis = Build.SUPPORTED_ABIS.toList(), cpuCores = cores, totalRamMb = ramMb,
            nativeLoaded = GeaNativeBridge.isNativeLoaded, nativeCapabilities = caps,
            performanceClass = Build.VERSION.MEDIA_PERFORMANCE_CLASS, tier = tier, maxTicksPerSecond = maxTps
        )
    }
}
