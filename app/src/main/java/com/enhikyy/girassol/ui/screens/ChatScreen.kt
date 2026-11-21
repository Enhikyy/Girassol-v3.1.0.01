package com.enhikyy.girassol.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enhikyy.girassol.audio.CitizenVoice
import com.enhikyy.girassol.audio.MicDictation
import com.enhikyy.girassol.core.world.ChatMessage
import com.enhikyy.girassol.core.world.CitizenSummary
import com.enhikyy.girassol.core.world.DashboardSnapshot
import com.enhikyy.girassol.sim.SimClock
import com.enhikyy.girassol.sim.SimulationHub
import com.enhikyy.girassol.sim.WorldSpeech
import com.enhikyy.girassol.ui.components.CommandCapsule
import com.enhikyy.girassol.ui.components.GlassPill
import com.enhikyy.girassol.ui.components.ReasoningChip
import com.enhikyy.girassol.ui.components.Recipient
import com.enhikyy.girassol.ui.components.Shapes
import com.enhikyy.girassol.ui.glass.GlassTone
import com.enhikyy.girassol.ui.glass.liquidGlass
import com.enhikyy.girassol.ui.theme.Lumon
import com.enhikyy.girassol.ui.theme.MonoStyle
import kotlinx.coroutines.flow.StateFlow

/** Fonte de dados do Chat. Produção = fila real do llama.cpp via [SimulationHub]; testes = fake. */
interface ChatBackend {
    val dashboard: StateFlow<DashboardSnapshot?>
    val messages: StateFlow<List<ChatMessage>>
    val worldSpeech: StateFlow<List<WorldSpeech>>
    val pending: StateFlow<Set<String>>
    fun send(target: String?, text: String)
    fun stop()
}

object HubChatBackend : ChatBackend {
    override val dashboard get() = SimulationHub.dashboard
    override val messages get() = SimulationHub.chat
    override val worldSpeech get() = SimulationHub.worldSpeech
    override val pending get() = SimulationHub.chatPending
    override fun send(target: String?, text: String) { SimulationHub.sendChat(target, text) }
    override fun stop() = SimulationHub.stopChat()
}

/** Item do feed: mensagem do Chat (persistida) ou fala autônoma na cena (ao vivo). */
private sealed interface FeedItem {
    val key: String
    val tick: Long
    val at: Long
    data class Msg(val m: ChatMessage) : FeedItem {
        override val key = "m${m.id}"; override val tick = m.tick; override val at = m.wallClockMs
    }
    data class Speech(val s: WorldSpeech) : FeedItem {
        override val key = "s${s.key}"; override val tick = s.tick; override val at = s.wallClockMs
    }
}

/**
 * Aba Chat (principal): canal aberto com TODAS as falas autônomas dos cidadãos ao vivo +
 * conversas persistidas; cada envio entra imediatamente na fila de inferência do .gguf.
 */
@Composable
fun ChatScreen(
    onOpenCitizen: (String) -> Unit,
    onFocusCitizen: (String?) -> Unit = {},
    backend: ChatBackend = HubChatBackend,
    voiceEnabled: Boolean = true
) {
    val dash by backend.dashboard.collectAsStateWithLifecycle()
    val chat by backend.messages.collectAsStateWithLifecycle()
    val speech by backend.worldSpeech.collectAsStateWithLifecycle()
    val pending by backend.pending.collectAsStateWithLifecycle()
    val mic by MicDictation.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val importer = rememberFounderImporter()
    var libraryMsg by remember { mutableStateOf("") }
    val ingest = rememberDocumentIngester { libraryMsg = it }
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    val alive = dash?.citizens?.filter { it.alive }.orEmpty()
    val byId = remember(alive) { alive.associateBy { it.id } }
    if (target != null && alive.none { it.id == target }) target = null

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) MicDictation.start(context) { t -> draft = (draft + " " + t).trim().take(400) }
    }
    val onMic: () -> Unit = {
        when {
            mic.phase == MicDictation.Phase.RECORDING -> MicDictation.stop()
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                MicDictation.start(context) { t -> draft = (draft + " " + t).trim().take(400) }
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val feed = remember(chat, speech, target, query) {
        val msgs = chat.filter { m -> target == null || m.citizenId == target || (m.citizenId == null && m.author == target) }
            .map { FeedItem.Msg(it) }
        val sp = speech.filter { target == null || it.citizenId == target }.map { FeedItem.Speech(it) }
        (msgs + sp).sortedWith(compareBy<FeedItem> { it.tick }.thenBy { it.at })
            .filter { item ->
                query.isBlank() || when (item) {
                    is FeedItem.Msg -> item.m.text.contains(query, true) || item.m.authorName.contains(query, true)
                    is FeedItem.Speech -> item.s.text.contains(query, true) || item.s.name.contains(query, true)
                }
            }
    }
    val listState = rememberLazyListState()
    val thinking = alive.filter { it.id in pending && (target == null || it.id == target) }
    LaunchedEffect(feed.size, thinking.size) {
        val n = feed.size + if (thinking.isNotEmpty()) 1 else 0
        if (n > 0) listState.animateScrollToItem(n - 1)
    }
    val send: () -> Unit = {
        val t = draft.trim()
        if (t.isNotEmpty()) { backend.send(target, t); draft = "" }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp).testTag("chat_selector"),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassPill("Canal aberto", target == null, "chat_target_open") { target = null }
            for (c in alive) GlassPill(c.name, target == c.id, "chat_target_${c.id}") { target = c.id }
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }, modifier = Modifier.testTag("chat_search_toggle")) {
                Icon(Icons.Outlined.Search, contentDescription = "buscar", tint = if (searching) Lumon.Sunflower else Lumon.CreamDim)
            }
        }
        if (searching) {
            OutlinedTextField(query, { query = it }, singleLine = true, shape = Shapes.Control, placeholder = { Text("Buscar no histórico") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Lumon.Sunflower, unfocusedBorderColor = Lumon.Outline,
                    focusedTextColor = Lumon.Cream, unfocusedTextColor = Lumon.Cream, cursorColor = Lumon.Sunflower,
                    focusedContainerColor = Lumon.Surface, unfocusedContainerColor = Lumon.Surface),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("chat_search"))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (alive.isEmpty() && feed.isEmpty()) {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Ainda não há ninguém no mundo.", style = MaterialTheme.typography.titleMedium, color = Lumon.Cream)
                    Text("Cada arquivo .gguf importado vira exatamente um cidadão fundador (g0). Com dois fundadores que evoluem, nasce a geração g1.",
                        style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
                    Button(onClick = { importer(null) }, shape = Shapes.Control, modifier = Modifier.testTag("chat_import_founder"),
                        colors = ButtonDefaults.buttonColors(containerColor = Lumon.Sunflower, contentColor = Lumon.Background)) { Text("Importar .gguf") }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().testTag("chat_list"), state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(feed, key = { it.key }) { item ->
                        when (item) {
                            is FeedItem.Msg -> MessageBubble(item.m, byId[item.m.author],
                                onPlay = { CitizenVoice.speakOnDemand(context, item.m.author, byId[item.m.author]?.sex ?: "", item.m.text) },
                                onOpenAuthor = { if (item.m.author != "usuario") onOpenCitizen(item.m.author) })
                            is FeedItem.Speech -> SpeechBubble(item.s, onOpenAuthor = { onOpenCitizen(item.s.citizenId) },
                                onFocus = { onFocusCitizen(item.s.citizenId) })
                        }
                    }
                    if (thinking.isNotEmpty()) item(key = "pending") { ThinkingIndicator(thinking) }
                }
            }
        }
        if (libraryMsg.isNotEmpty()) Text(libraryMsg, style = MaterialTheme.typography.bodySmall, color = Lumon.Teal,
            modifier = Modifier.padding(horizontal = 20.dp).testTag("chat_library_msg"))
        CommandCapsule(
            draft = draft, onDraft = { draft = it },
            recipients = listOf(Recipient(null, "Todos")) + alive.map { Recipient(it.id, it.name) },
            selected = target, onSelect = { target = it },
            busy = thinking.isNotEmpty(), onSend = send, onStop = { backend.stop() },
            enabled = alive.isNotEmpty(),
            micActive = mic.phase == MicDictation.Phase.RECORDING, micBusy = mic.phase == MicDictation.Phase.TRANSCRIBING,
            onMic = if (voiceEnabled) onMic else null,
            onFocus = { onFocusCitizen(target) },
            onAttachDocument = ingest, onImportCitizen = { importer(null) },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
        if (mic.error.isNotEmpty()) Text(mic.error, style = MaterialTheme.typography.bodySmall, color = Lumon.Danger,
            modifier = Modifier.padding(horizontal = 20.dp))
    }
}

/** Cor estável por cidadão (avatar). */
internal fun citizenColor(id: String): Color {
    val palette = listOf(Color(0xFFF2B33D), Color(0xFF4FC3B5), Color(0xFFE5735A), Color(0xFF9C8CF0), Color(0xFF7BC86C), Color(0xFF5FA8E8))
    return palette[(id.hashCode() and 0x7fffffff) % palette.size]
}

@Composable
internal fun CitizenAvatar(id: String, name: String, size: Int = 34) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(citizenColor(id).copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = Color(0xFF1A1206))
    }
}

@Composable
private fun MessageBubble(m: ChatMessage, author: CitizenSummary?, onPlay: () -> Unit, onOpenAuthor: () -> Unit) {
    val mine = m.author == "usuario"
    if (mine) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(
                Modifier.widthIn(max = 300.dp)
                    .liquidGlass(RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp), tone = GlassTone.REGULAR, elevation = 4.dp,
                        tint = Lumon.Sunflower.copy(alpha = 0.9f), backdrop = false)
                    .padding(horizontal = 14.dp, vertical = 10.dp).testTag("chat_msg_${m.id}")
            ) {
                Text(m.text, style = MaterialTheme.typography.bodyMedium, color = Lumon.Cream)
                Text(SimClock.label(m.tick), style = MonoStyle, color = Lumon.CreamDim, modifier = Modifier.align(Alignment.End))
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(Modifier.clickable(onClick = onOpenAuthor)) { CitizenAvatar(m.author, m.authorName) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).testTag("chat_msg_${m.id}")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.authorName, style = MaterialTheme.typography.labelLarge, color = Lumon.Sunflower, modifier = Modifier.clickable(onClick = onOpenAuthor))
                Text("  " + SimClock.label(m.tick), style = MonoStyle, color = Lumon.CreamDim, modifier = Modifier.weight(1f))
                IconButton(onClick = onPlay, modifier = Modifier.size(32.dp).testTag("chat_play_${m.id}")) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = "ouvir", tint = Lumon.Teal, modifier = Modifier.size(20.dp))
                }
            }
            Column(
                Modifier.fillMaxWidth().liquidGlass(RoundedCornerShape(6.dp, 22.dp, 22.dp, 22.dp), tone = GlassTone.REGULAR, elevation = 2.dp, backdrop = false)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(m.text, style = MaterialTheme.typography.bodyMedium, color = Lumon.Cream)
            }
            ReasoningChip(
                listOfNotNull(
                    "Motor" to engineLabel(m.engine),
                    author?.action?.let { "Ação atual" to it },
                    author?.lastThought?.takeIf { it.isNotBlank() }?.let { "Pensamento" to it.take(160) },
                    "Tick" to m.tick.toString()
                ),
                modifier = Modifier.padding(top = 6.dp), tag = "reasoning_${m.id}"
            )
        }
    }
}

@Composable
private fun SpeechBubble(s: WorldSpeech, onOpenAuthor: () -> Unit, onFocus: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("world_speech_${s.key}"), verticalAlignment = Alignment.Top) {
        Box(Modifier.clickable(onClick = onOpenAuthor)) { CitizenAvatar(s.citizenId, s.name) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, style = MaterialTheme.typography.labelLarge, color = Lumon.Sunflower, modifier = Modifier.clickable(onClick = onOpenAuthor))
                Text("  " + SimClock.label(s.tick) + (s.action?.let { " · $it" } ?: ""), style = MonoStyle, color = Lumon.CreamDim, maxLines = 2)
            }
            Column(
                Modifier.fillMaxWidth().liquidGlass(RoundedCornerShape(6.dp, 22.dp, 22.dp, 22.dp), tone = GlassTone.CLEAR, elevation = 2.dp, backdrop = false)
                    .clickable(onClick = onFocus).padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text("“${s.text}”", style = MaterialTheme.typography.bodyMedium, color = Lumon.Cream)
                Text("na Biosfera · tocar para ver", style = MaterialTheme.typography.labelSmall, color = Lumon.Teal)
            }
            ReasoningChip(
                listOfNotNull(
                    "Origem" to if (s.neural) "fala neural (.gguf)" else "fala do deliberador",
                    s.action?.let { "Ação" to it },
                    s.thought.takeIf { it.isNotBlank() }?.let { "Pensamento" to it.take(160) }
                ),
                modifier = Modifier.padding(top = 6.dp), tag = "reasoning_s${s.key}"
            )
        }
    }
}

@Composable
private fun ThinkingIndicator(who: List<CitizenSummary>) {
    val t = rememberInfiniteTransition(label = "thinking")
    val phase by t.animateFloat(0f, 3f, infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "dots")
    Row(Modifier.fillMaxWidth().testTag("chat_pending"), verticalAlignment = Alignment.CenterVertically) {
        CitizenAvatar(who.first().id, who.first().name, size = 28)
        Spacer(Modifier.width(10.dp))
        Row(
            Modifier.liquidGlass(CircleShape, tone = GlassTone.CLEAR, elevation = 0.dp, backdrop = false).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(who.joinToString(", ") { it.name } + " pensando", style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
            for (i in 0 until 3) {
                val a = if (phase.toInt() == i) 1f else 0.3f
                Box(Modifier.padding(start = 4.dp).size(5.dp).alpha(a).clip(CircleShape).background(Lumon.Sunflower))
            }
        }
    }
}

private fun engineLabel(engine: String) = when {
    engine.startsWith("gguf:") -> engine.removePrefix("gguf:").removeSuffix(".gguf")
    engine == "simbolico" -> "deliberador simbólico"
    engine.isBlank() -> "-"
    else -> engine
}
