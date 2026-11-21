package com.enhikyy.girassol.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enhikyy.girassol.audio.CitizenVoice
import com.enhikyy.girassol.core.world.ChatMessage
import com.enhikyy.girassol.sim.SimulationHub
import com.enhikyy.girassol.ui.components.Shapes
import com.enhikyy.girassol.ui.theme.Lumon
import com.enhikyy.girassol.ui.theme.MonoStyle

/**
 * Aba Chat (principal): seletor em pílulas no topo (canal aberto + cada cidadão vivo), busca,
 * histórico persistido (SQLite `chat_messages` + .grs) e resposta de cada cidadão pelo próprio .gguf.
 */
@Composable
fun ChatScreen(onOpenCitizen: (String) -> Unit) {
    val dash by SimulationHub.dashboard.collectAsStateWithLifecycle()
    val chat by SimulationHub.chat.collectAsStateWithLifecycle()
    val pending by SimulationHub.chatPending.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val importer = rememberFounderImporter()
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    val alive = dash?.citizens?.filter { it.alive }.orEmpty()
    val sexOf = remember(alive) { alive.associate { it.id to it.sex } }
    if (target != null && alive.none { it.id == target }) target = null

    val visible = remember(chat, target, query) {
        chat.filter { m -> target == null || m.citizenId == target || (m.citizenId == null && m.author == target) }
            .filter { query.isBlank() || it.text.contains(query, ignoreCase = true) || it.authorName.contains(query, ignoreCase = true) }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(visible.size) { if (visible.isNotEmpty()) listState.animateScrollToItem(visible.size - 1) }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp).testTag("chat_selector"),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SelectorPill("Canal aberto", target == null, "chat_target_open") { target = null }
            for (c in alive) SelectorPill("${c.name} · g${c.generation}", target == c.id, "chat_target_${c.id}") { target = c.id }
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }, modifier = Modifier.testTag("chat_search_toggle")) {
                Icon(Icons.Outlined.Search, contentDescription = "buscar", tint = if (searching) Lumon.Sunflower else Lumon.CreamDim)
            }
        }
        if (searching) {
            OutlinedTextField(query, { query = it }, singleLine = true, shape = Shapes.Control, placeholder = { Text("Buscar no histórico") },
                colors = fieldColors(), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("chat_search"))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (alive.isEmpty() && chat.isEmpty()) {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Ainda não há ninguém no mundo.", style = MaterialTheme.typography.titleMedium, color = Lumon.Cream)
                    Text("Cada arquivo .gguf importado vira exatamente um cidadão fundador (g0). Com dois fundadores que evoluem, nasce a geração g1.",
                        style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
                    Button(onClick = { importer(null) }, shape = Shapes.Control, modifier = Modifier.testTag("chat_import_founder"),
                        colors = ButtonDefaults.buttonColors(containerColor = Lumon.Sunflower, contentColor = Lumon.Background)) { Text("Importar .gguf") }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp).testTag("chat_list"), state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visible, key = { it.id }) { m ->
                        MessageBubble(m, onPlay = { CitizenVoice.speakOnDemand(context, m.author, sexOf[m.author] ?: "", m.text) },
                            onOpenAuthor = { if (m.author != "usuario") onOpenCitizen(m.author) })
                    }
                    val thinking = alive.filter { it.id in pending && (target == null || it.id == target) }
                    if (thinking.isNotEmpty()) item(key = "pending") {
                        Text(thinking.joinToString(", ") { it.name } + " pensando…", style = MaterialTheme.typography.bodySmall,
                            color = Lumon.CreamDim, modifier = Modifier.padding(4.dp).testTag("chat_pending"))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(draft, { draft = it.take(400) }, shape = Shapes.Control, maxLines = 3, enabled = alive.isNotEmpty(),
                placeholder = { Text(if (target == null) "Fale com todos" else "Fale com ${alive.firstOrNull { it.id == target }?.name ?: ""}") },
                colors = fieldColors(), modifier = Modifier.weight(1f).testTag("chat_input"))
            IconButton(enabled = draft.isNotBlank() && alive.isNotEmpty(), modifier = Modifier.testTag("chat_send"), onClick = {
                SimulationHub.sendChat(target, draft.trim()); draft = ""
            }) { Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "enviar", tint = if (draft.isNotBlank()) Lumon.Sunflower else Lumon.CreamDim) }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Lumon.Sunflower, unfocusedBorderColor = Lumon.Outline,
    focusedTextColor = Lumon.Cream, unfocusedTextColor = Lumon.Cream, cursorColor = Lumon.Sunflower,
    focusedContainerColor = Lumon.Surface, unfocusedContainerColor = Lumon.Surface, disabledContainerColor = Lumon.Surface
)

@Composable
private fun SelectorPill(label: String, on: Boolean, tag: String, onClick: () -> Unit) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) Lumon.Background else Lumon.Cream, maxLines = 1,
        modifier = Modifier.clip(Shapes.Capsule).background(if (on) Lumon.Sunflower else Lumon.Surface)
            .border(1.dp, if (on) Lumon.Sunflower else Lumon.Outline, Shapes.Capsule)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp).testTag(tag))
}

@Composable
private fun MessageBubble(m: ChatMessage, onPlay: () -> Unit, onOpenAuthor: () -> Unit) {
    val mine = m.author == "usuario"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 320.dp).clip(Shapes.Control)
                .background(if (mine) Lumon.Sunflower.copy(alpha = 0.18f) else Lumon.Surface)
                .border(1.dp, if (mine) Lumon.Sunflower.copy(alpha = 0.5f) else Lumon.Outline, Shapes.Control)
                .padding(horizontal = 14.dp, vertical = 10.dp).testTag("chat_msg_${m.id}")
        ) {
            if (!mine) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.authorName, style = MaterialTheme.typography.labelMedium, color = Lumon.Sunflower,
                    modifier = Modifier.clickable(onClick = onOpenAuthor))
                Text("  " + engineLabel(m.engine), style = MonoStyle, color = Lumon.CreamDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                IconButton(onClick = onPlay, modifier = Modifier.padding(start = 2.dp).testTag("chat_play_${m.id}")) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = "ouvir", tint = Lumon.Teal)
                }
            }
            Text(m.text, style = MaterialTheme.typography.bodyMedium, color = Lumon.Cream)
            Text("tick ${m.tick}", style = MonoStyle, color = Lumon.CreamDim)
        }
    }
}

private fun engineLabel(engine: String) = when {
    engine.startsWith("gguf:") -> engine.removePrefix("gguf:").removeSuffix(".gguf")
    engine == "simbolico" -> "simbólico"
    else -> ""
}
