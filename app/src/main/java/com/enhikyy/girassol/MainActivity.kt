package com.enhikyy.girassol

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import com.enhikyy.girassol.llm.LlmStatus
import com.enhikyy.girassol.llm.LocalLlmManager
import com.enhikyy.girassol.render3d.Render3dTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enhikyy.girassol.sim.SimulationHub
import com.enhikyy.girassol.ui.components.Dot
import com.enhikyy.girassol.ui.components.f
import com.enhikyy.girassol.ui.screens.CitizensScreen
import com.enhikyy.girassol.ui.screens.MindScreen
import com.enhikyy.girassol.ui.screens.SocietyScreen
import com.enhikyy.girassol.ui.screens.SystemScreen
import com.enhikyy.girassol.ui.screens.WorldScreen
import com.enhikyy.girassol.ui.theme.GirassolTheme
import com.enhikyy.girassol.ui.theme.Lumon
import com.enhikyy.girassol.ui.theme.MonoStyle

internal enum class Tab(val label: String, val icon: ImageVector) {
    MUNDO("Mundo", Icons.Outlined.Public),
    CIDADAOS("Cidadãos", Icons.Outlined.Groups),
    SOCIEDADE("Sociedade", Icons.Outlined.AccountTree),
    MENTE("Mente", Icons.Outlined.Psychology),
    SISTEMA("Sistema", Icons.Outlined.Memory)
}

class MainActivity : ComponentActivity() {
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SimulationHub.ensureLoaded(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { GirassolTheme { GirassolApp() } }
    }
}

@Composable
private fun GirassolApp() {
    var tab by rememberSaveable { mutableStateOf(Tab.MUNDO) }
    var selectedCitizen by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(containerColor = Lumon.Background, topBar = { TopStatusBar() }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            Box(Modifier.fillMaxSize().padding(bottom = 84.dp)) {
                when (tab) {
                    Tab.MUNDO -> WorldScreen(onOpenCitizen = { selectedCitizen = it; tab = Tab.CIDADAOS })
                    Tab.CIDADAOS -> CitizensScreen(selectedId = selectedCitizen, onSelect = { selectedCitizen = it })
                    Tab.SOCIEDADE -> SocietyScreen()
                    Tab.MENTE -> MindScreen()
                    Tab.SISTEMA -> SystemScreen()
                }
            }
            FloatingNavBar(tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Barra de navegação inferior flutuante (vidro escuro sobre preto OLED). */
@Composable
private fun FloatingNavBar(current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier) {
    Row(
        modifier.navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth()
            .clip(RoundedCornerShape(28.dp)).background(Lumon.Glass)
            .border(1.dp, Lumon.Outline, RoundedCornerShape(28.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp).testTag("floating_nav"),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
    ) {
        for (t in Tab.entries) {
            val sel = t == current
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(22.dp))
                    .background(if (sel) Lumon.Sunflower.copy(alpha = 0.16f) else Lumon.Glass.copy(alpha = 0f))
                    .clickable(role = Role.Tab) { onSelect(t) }
                    .semantics { selected = sel; contentDescription = "aba ${t.label}" }
                    .testTag("tab_${t.name}")
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(t.icon, contentDescription = null, tint = if (sel) Lumon.Sunflower else Lumon.CreamDim, modifier = Modifier.size(22.dp))
                Text(t.label, fontSize = 10.sp, color = if (sel) Lumon.Sunflower else Lumon.CreamDim, maxLines = 1)
            }
        }
    }
}

/** VmRSS do próprio processo (/proc/self/status), em MB. */
internal fun readRssMb(): Double = runCatching {
    File("/proc/self/status").useLines { lines ->
        lines.firstOrNull { it.startsWith("VmRSS:") }?.filter { it.isDigit() }?.toLong()?.div(1024.0) ?: 0.0
    }
}.getOrDefault(0.0)

@Composable
private fun TopStatusBar() {
    val render by SimulationHub.render.collectAsStateWithLifecycle()
    val status by SimulationHub.status.collectAsStateWithLifecycle()
    val dash by SimulationHub.dashboard.collectAsStateWithLifecycle()
    val r3d by Render3dTelemetry.stats.collectAsStateWithLifecycle()
    val llm by LocalLlmManager.stats.collectAsStateWithLifecycle()
    val rss by produceState(0.0) { while (true) { value = withContext(Dispatchers.IO) { readRssMb() }; delay(2000) } }
    Column(Modifier.fillMaxWidth().padding(top = 36.dp, start = 16.dp, end = 16.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("GIRASSOL", style = MaterialTheme.typography.titleLarge, color = Lumon.Sunflower)
            Spacer(Modifier.width(10.dp))
            Dot(if (status.running) Lumon.Ok else Lumon.CreamDim)
            Spacer(Modifier.width(6.dp))
            Text(if (status.running) "em execução" else "pausado", style = MaterialTheme.typography.labelSmall, color = Lumon.CreamDim)
        }
        val r = render
        Text(
            if (r == null) "carregando mundo…" else
                "dia ${r.day} · tick ${r.tick} · ${r.population} cidadãos · ${if (r.night) "noite" else "dia"}${if (r.drought) " · seca" else ""} · ${status.effectiveTps.f(1)} t/s",
            style = MonoStyle, color = Lumon.CreamDim
        )
        val llmColor = when (llm.status) { LlmStatus.READY, LlmStatus.BUSY -> Lumon.Teal; LlmStatus.ERROR -> Lumon.Danger; else -> Lumon.CreamDim }
        Row(Modifier.fillMaxWidth().testTag("telemetry_strip"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (r3d.active) "3D ${r3d.fps.f(0)} fps" else "2D eco", style = MonoStyle.copy(fontSize = 10.sp), color = if (r3d.active) Lumon.Teal else Lumon.Sunflower)
            Text("llama: ${llm.status.label}${if (llm.tokensPerSecond > 0) " ${llm.tokensPerSecond.f(1)} tok/s" else ""}", style = MonoStyle.copy(fontSize = 10.sp), color = llmColor, maxLines = 1)
            Text("LoRA ${dash?.mind?.loraAcceptedCycles ?: 0}", style = MonoStyle.copy(fontSize = 10.sp), color = Lumon.Violet)
            Text("RSS ${rss.f(0)} MB", style = MonoStyle.copy(fontSize = 10.sp), color = Lumon.CreamDim)
        }
    }
}
