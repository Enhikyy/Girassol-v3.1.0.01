package com.enhikyy.girassol.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.enhikyy.girassol.ui.glass.GlassMotion
import com.enhikyy.girassol.ui.glass.GlassTone
import com.enhikyy.girassol.ui.glass.liquidGlass
import com.enhikyy.girassol.ui.theme.Lumon
import com.enhikyy.girassol.ui.theme.MonoStyle

/** Destinatário da cápsula: id null = "Todos" (canal aberto). */
data class Recipient(val id: String?, val name: String)

/**
 * v3.9 — Cápsula de comando Liquid Glass (CircleShape): "+" (Biblioteca / novo cidadão .gguf),
 * chip de destinatário, campo de texto, microfone offline, foco na Biosfera e Enviar/Parar.
 */
@Composable
fun CommandCapsule(
    draft: String,
    onDraft: (String) -> Unit,
    recipients: List<Recipient>,
    selected: String?,
    onSelect: (String?) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    micActive: Boolean = false,
    micBusy: Boolean = false,
    onMic: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
    onAttachDocument: (() -> Unit)? = null,
    onImportCitizen: (() -> Unit)? = null,
    placeholder: String = "Converse com os cidadãos ou dê uma diretriz…"
) {
    var plusOpen by remember { mutableStateOf(false) }
    var recipientsOpen by remember { mutableStateOf(false) }
    val current = recipients.firstOrNull { it.id == selected } ?: recipients.firstOrNull() ?: Recipient(null, "Todos")
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .liquidGlass(CircleShape, tone = GlassTone.REGULAR, elevation = 20.dp)
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .testTag("command_capsule"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box {
            CapsuleIcon(Icons.Outlined.Add, "anexar ou importar", "capsule_plus", enabled = onAttachDocument != null || onImportCitizen != null) { plusOpen = true }
            DropdownMenu(plusOpen, { plusOpen = false }, shape = Shapes.Control, containerColor = Lumon.SurfaceHigh) {
                if (onAttachDocument != null) DropdownMenuItem(text = { Text("Documento para a Biblioteca (TXT, EPUB, PDF, áudio)") },
                    onClick = { plusOpen = false; onAttachDocument() }, modifier = Modifier.testTag("capsule_attach_doc"))
                if (onImportCitizen != null) DropdownMenuItem(text = { Text("Novo cidadão a partir de um .gguf") },
                    onClick = { plusOpen = false; onImportCitizen() }, modifier = Modifier.testTag("capsule_import_gguf"))
            }
        }
        Box {
            Row(
                Modifier.clip(CircleShape).background(Lumon.Sunflower.copy(alpha = 0.16f))
                    .clickable(enabled = recipients.size > 1) { recipientsOpen = true }
                    .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                    .widthIn(max = 96.dp).testTag("capsule_recipient"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(current.name, style = MaterialTheme.typography.labelMedium, color = Lumon.Sunflower, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.Outlined.ExpandMore, null, tint = Lumon.Sunflower, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(recipientsOpen, { recipientsOpen = false }, shape = Shapes.Control, containerColor = Lumon.SurfaceHigh) {
                for (r in recipients) DropdownMenuItem(text = { Text(r.name) }, onClick = { recipientsOpen = false; onSelect(r.id) },
                    modifier = Modifier.testTag("capsule_recipient_${r.id ?: "todos"}"))
            }
        }
        Box(Modifier.weight(1f).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
            if (draft.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = Lumon.CreamDim, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value = draft, onValueChange = { onDraft(it.take(400)) }, enabled = enabled,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Lumon.Cream), maxLines = 4,
                cursorBrush = SolidColor(Lumon.Sunflower),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (draft.isNotBlank() && !busy) onSend() }),
                modifier = Modifier.fillMaxWidth().testTag("chat_input")
            )
        }
        if (onMic != null) {
            val pulse by animateFloatAsState(if (micActive) 1.12f else 1f, GlassMotion.spec(), label = "mic")
            CapsuleIcon(Icons.Outlined.Mic, if (micActive) "parar ditado" else "ditar por voz", "capsule_mic",
                tint = if (micActive) Lumon.Halation else if (micBusy) Lumon.Sunflower else Lumon.Cream,
                modifier = Modifier.scale(pulse), onClick = onMic)
        }
        if (onFocus != null) CapsuleIcon(Icons.Outlined.CenterFocusStrong, "focar na Biosfera", "capsule_focus", onClick = onFocus)
        val canSend = enabled && draft.isNotBlank()
        val bg by animateColorAsState(if (busy) Lumon.Halation else if (canSend) Lumon.Sunflower else Lumon.Outline, GlassMotion.spec(), label = "send")
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(bg)
                .clickable(enabled = busy || canSend) { if (busy) onStop() else onSend() }
                .semantics { contentDescription = if (busy) "parar inferência" else "enviar" }
                .testTag(if (busy) "chat_stop" else "chat_send"),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(busy, transitionSpec = { fadeIn(GlassMotion.spec()) togetherWith fadeOut(GlassMotion.spec()) }, label = "sendIcon") { b ->
                Icon(if (b) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.Send, null, tint = Lumon.Background, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun CapsuleIcon(icon: ImageVector, desc: String, tag: String, modifier: Modifier = Modifier, enabled: Boolean = true,
                        tint: androidx.compose.ui.graphics.Color = Lumon.Cream, onClick: () -> Unit) {
    Box(
        modifier.size(40.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = desc }.testTag(tag),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = if (enabled) tint else Lumon.CreamDim, modifier = Modifier.size(22.dp)) }
}

/**
 * Chip "Raciocínio cognitivo ▾": telemetria técnica (Anokhin, Brier, glifos, LoRA/RSI) fica
 * aqui dentro, recolhida por padrão; a superfície principal mostra só português natural.
 */
@Composable
fun ReasoningChip(lines: List<Pair<String, String>>, modifier: Modifier = Modifier, title: String = "Raciocínio cognitivo", tag: String = "reasoning_chip") {
    if (lines.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Row(
            Modifier.clip(CircleShape).liquidGlass(CircleShape, tone = GlassTone.CLEAR, elevation = 0.dp, backdrop = false)
                .clickable { open = !open }.padding(horizontal = 12.dp, vertical = 6.dp).testTag(tag),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = Lumon.CreamDim)
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = Lumon.CreamDim, modifier = Modifier.size(16.dp))
        }
        AnimatedVisibility(open, enter = expandVertically(GlassMotion.spec()) + fadeIn(), exit = shrinkVertically(GlassMotion.spec()) + fadeOut()) {
            Column(Modifier.padding(top = 6.dp).testTag("${tag}_body"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for ((k, v) in lines) Row {
                    Text(k, style = MonoStyle, color = Lumon.CreamDim, modifier = Modifier.weight(0.42f))
                    Text(v, style = MonoStyle, color = Lumon.Cream, modifier = Modifier.weight(0.58f))
                }
            }
        }
    }
}

/** Pílula de vidro selecionável (velocidades, modos, filtros). */
@Composable
fun GlassPill(label: String, selected: Boolean, tag: String, modifier: Modifier = Modifier, backdrop: Boolean = false, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Lumon.Sunflower else Lumon.Sunflower.copy(alpha = 0f), GlassMotion.spec(), label = "pill")
    Box(
        modifier.clip(CircleShape).liquidGlass(CircleShape, tone = GlassTone.CLEAR, elevation = if (selected) 6.dp else 0.dp, backdrop = backdrop)
            .background(bg, CircleShape).clickable(onClick = onClick)
            .semantics { contentDescription = "$label${if (selected) " selecionado" else ""}" }
            .padding(horizontal = 14.dp, vertical = 8.dp).testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) Lumon.Background else Lumon.Cream, maxLines = 1)
    }
}
