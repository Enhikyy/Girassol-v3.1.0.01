package com.enhikyy.girassol

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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

private enum class Tab(val label: String, val icon: ImageVector) {
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
    Scaffold(
        containerColor = Lumon.Background,
        topBar = { TopStatusBar() },
        bottomBar = {
            NavigationBar(containerColor = Lumon.Surface) {
                for (t in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Lumon.Background, indicatorColor = Lumon.Teal,
                            selectedTextColor = Lumon.Teal, unselectedIconColor = Lumon.CreamDim, unselectedTextColor = Lumon.CreamDim
                        )
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.MUNDO -> WorldScreen(onOpenCitizen = { selectedCitizen = it; tab = Tab.CIDADAOS })
                Tab.CIDADAOS -> CitizensScreen(selectedId = selectedCitizen, onSelect = { selectedCitizen = it })
                Tab.SOCIEDADE -> SocietyScreen()
                Tab.MENTE -> MindScreen()
                Tab.SISTEMA -> SystemScreen()
            }
        }
    }
}

@Composable
private fun TopStatusBar() {
    val render by SimulationHub.render.collectAsStateWithLifecycle()
    val status by SimulationHub.status.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().padding(top = 36.dp, start = 16.dp, end = 16.dp, bottom = 8.dp)) {
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
    }
}
