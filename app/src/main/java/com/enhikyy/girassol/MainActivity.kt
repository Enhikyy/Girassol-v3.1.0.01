package com.enhikyy.girassol

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoGraph
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enhikyy.girassol.sim.SimulationHub
import com.enhikyy.girassol.ui.components.Shapes
import com.enhikyy.girassol.ui.screens.ChatScreen
import com.enhikyy.girassol.ui.screens.CitizenDetailView
import com.enhikyy.girassol.ui.screens.EvolutionScreen
import com.enhikyy.girassol.ui.screens.KnowledgeLibraryPanel
import com.enhikyy.girassol.ui.screens.MeshSyncPanel
import com.enhikyy.girassol.ui.screens.SystemScreen
import com.enhikyy.girassol.ui.screens.ThemesPanel
import com.enhikyy.girassol.ui.screens.WorldScreen
import com.enhikyy.girassol.ui.theme.GirassolTheme
import com.enhikyy.girassol.ui.theme.Lumon
import kotlinx.coroutines.launch

/** v3.6: quatro abas, Chat como principal. */
internal enum class Tab(val label: String, val icon: ImageVector) {
    CHAT("Chat", Icons.AutoMirrored.Outlined.Chat),
    BIOSFERA("Biosfera", Icons.Outlined.Public),
    EVOLUCAO("Evolução", Icons.Outlined.AutoGraph),
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
        com.enhikyy.girassol.render3d.GpuCalibration.ensure(this)
        com.enhikyy.girassol.ui.theme.ThemeController.load(this)
        setContent { GirassolTheme { GirassolApp() } }
    }
}

@Composable
private fun GirassolApp() {
    var tab by rememberSaveable { mutableStateOf(Tab.CHAT) }
    var selectedCitizen by rememberSaveable { mutableStateOf<String?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val openMenu: () -> Unit = { scope.launch { drawer.open() } }
    val dash by SimulationHub.dashboard.collectAsStateWithLifecycle()

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen || tab != Tab.BIOSFERA,
        drawerContent = {
            ModalDrawerSheet(
                drawerShape = Shapes.Card, drawerContainerColor = Lumon.Background,
                modifier = Modifier.fillMaxHeight().width(330.dp).testTag("main_drawer")
            ) {
                LazyColumn(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("GIRASSOL", style = MaterialTheme.typography.titleLarge, color = Lumon.Sunflower, modifier = Modifier.padding(8.dp)) }
                    item { ThemesPanel() }
                    item { KnowledgeLibraryPanel(dash) }
                    item { MeshSyncPanel(dash) }
                    item { Box(Modifier.size(24.dp)) }
                }
            }
        }
    ) {
        Box(Modifier.fillMaxSize().background(Lumon.Background)) {
            when (tab) {
                Tab.BIOSFERA -> WorldScreen(onOpenCitizen = { selectedCitizen = it }, onOpenMenu = openMenu)
                else -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    TabHeader(tab.label, openMenu)
                    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 84.dp)) {
                        when (tab) {
                            Tab.CHAT -> ChatScreen(onOpenCitizen = { selectedCitizen = it })
                            Tab.EVOLUCAO -> EvolutionScreen(onOpenCitizen = { selectedCitizen = it })
                            Tab.SISTEMA -> SystemScreen()
                            Tab.BIOSFERA -> Unit
                        }
                    }
                }
            }
            val sel = selectedCitizen
            if (sel != null) {
                BackHandler { selectedCitizen = null }
                Box(Modifier.fillMaxSize().background(Lumon.Background).statusBarsPadding().navigationBarsPadding().padding(bottom = 84.dp).testTag("citizen_overlay")) {
                    CitizenDetailView(sel) { selectedCitizen = null }
                }
            }
            FloatingNavBar(tab, { tab = it; selectedCitizen = null }, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun TabHeader(title: String, onMenu: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMenu, modifier = Modifier.testTag("open_drawer")) {
            Icon(Icons.Outlined.Menu, contentDescription = "abrir menu", tint = Lumon.Cream)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, color = Lumon.Sunflower)
    }
}

/** Barra inferior flutuante em cápsula (CircleShape). */
@Composable
private fun FloatingNavBar(current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier) {
    Row(
        modifier.navigationBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp).fillMaxWidth()
            .clip(Shapes.Capsule).background(Lumon.Glass)
            .border(1.dp, Lumon.Outline, Shapes.Capsule)
            .padding(horizontal = 6.dp, vertical = 6.dp).testTag("floating_nav"),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
    ) {
        for (t in Tab.entries) {
            val sel = t == current
            Column(
                Modifier.weight(1f).clip(Shapes.Capsule)
                    .background(if (sel) Lumon.Sunflower.copy(alpha = 0.16f) else Lumon.Glass.copy(alpha = 0f))
                    .clickable(role = Role.Tab) { onSelect(t) }
                    .semantics { selected = sel; contentDescription = "aba ${t.label}" }
                    .testTag("tab_${t.name}")
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(t.icon, contentDescription = null, tint = if (sel) Lumon.Sunflower else Lumon.CreamDim, modifier = Modifier.size(22.dp))
                Text(t.label, fontSize = 11.sp, color = if (sel) Lumon.Sunflower else Lumon.CreamDim, maxLines = 1)
            }
        }
    }
}
