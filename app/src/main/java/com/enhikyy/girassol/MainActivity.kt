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
import com.enhikyy.girassol.ui.screens.SystemScreen
import com.enhikyy.girassol.ui.screens.ThemeGrid
import com.enhikyy.girassol.render3d.WorldFocus
import com.enhikyy.girassol.ui.glass.GlassHost
import com.enhikyy.girassol.ui.glass.GlassMotion
import com.enhikyy.girassol.ui.glass.GlassTone
import com.enhikyy.girassol.ui.glass.liquidGlass
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
        SimulationHub.ensureLoaded(this)   // v3.9: já inicia o relógio a 1x (fim do mundo congelado no tick 0)
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
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    val openMenu: () -> Unit = { drawerOpen = true }

    // v3.9: as telas são o "fundo" do vidro; navegação flutuante e gaveta refratam o conteúdo real.
    GlassHost(
        modifier = Modifier.fillMaxSize().background(Lumon.Background),
        background = {
            when (tab) {
                Tab.BIOSFERA -> WorldScreen(onOpenCitizen = { selectedCitizen = it }, onOpenMenu = openMenu)
                else -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    TabHeader(tab.label, openMenu)
                    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 84.dp)) {
                        when (tab) {
                            Tab.CHAT -> ChatScreen(onOpenCitizen = { selectedCitizen = it },
                                onFocusCitizen = { id -> tab = Tab.BIOSFERA; selectedCitizen = null; id?.let(WorldFocus::focus) })
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
        }
    ) {
        FloatingNavBar(tab, { tab = it; selectedCitizen = null; drawerOpen = false }, Modifier.align(Alignment.BottomCenter))
        GlassDrawer(drawerOpen, onClose = { drawerOpen = false }) { t -> tab = t; selectedCitizen = null; drawerOpen = false }
    }
}

/** Gaveta Liquid Glass: navegação limpa + seletor dos 15 temas nativos (grade de 2 colunas). */
@Composable
private fun GlassDrawer(open: Boolean, onClose: () -> Unit, onNavigate: (Tab) -> Unit) {
    val progress by animateFloatAsState(if (open) 1f else 0f, GlassMotion.spec(), label = "drawer")
    if (progress <= 0.001f && !open) return
    if (open) BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f * progress))
            .pointerInput(Unit) { detectTapGestures { onClose() } }.testTag("drawer_scrim"))
        BoxWithConstraints(Modifier.fillMaxHeight()) {
            val w = minOf(340.dp, maxWidth * 0.86f)
            Column(
                Modifier.fillMaxHeight().width(w)
                    .graphicsLayer { translationX = -(1f - progress) * w.toPx() }
                    .statusBarsPadding().navigationBarsPadding().padding(start = 8.dp, top = 8.dp, bottom = 8.dp)
                    .liquidGlass(Shapes.Card, tone = GlassTone.DENSE, elevation = 24.dp)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("main_drawer"),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("GIRASSOL", style = MaterialTheme.typography.titleLarge, color = Lumon.Sunflower, modifier = Modifier.padding(4.dp))
                for (t in Tab.entries) {
                    Row(
                        Modifier.fillMaxWidth().clip(Shapes.Capsule).clickable(role = Role.Button) { onNavigate(t) }
                            .padding(horizontal = 12.dp, vertical = 10.dp).testTag("drawer_tab_${t.name}"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(t.icon, contentDescription = null, tint = Lumon.Cream, modifier = Modifier.size(22.dp))
                        Text(t.label, style = MaterialTheme.typography.titleSmall, color = Lumon.Cream)
                    }
                }
                Text("Temas", style = MaterialTheme.typography.labelLarge, color = Lumon.CreamDim, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
                ThemeGrid()
                Text("Biblioteca de Conhecimento e MeshSync LAN ficam na aba Sistema.", style = MaterialTheme.typography.bodySmall,
                    color = Lumon.CreamDim, modifier = Modifier.padding(4.dp))
            }
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
            .liquidGlass(Shapes.Capsule, tone = GlassTone.REGULAR, elevation = 22.dp)
            .padding(horizontal = 6.dp, vertical = 6.dp).testTag("floating_nav"),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
    ) {
        for (t in Tab.entries) {
            val sel = t == current
            val selAnim by animateFloatAsState(if (sel) 1f else 0f, GlassMotion.spec(), label = "tab")
            Column(
                Modifier.weight(1f).clip(Shapes.Capsule)
                    .background(Lumon.Sunflower.copy(alpha = 0.18f * selAnim))
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
