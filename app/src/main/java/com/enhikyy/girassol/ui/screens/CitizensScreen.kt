package com.enhikyy.girassol.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enhikyy.girassol.core.world.CitizenDetail
import com.enhikyy.girassol.core.world.CitizenSummary
import com.enhikyy.girassol.sim.SimulationHub
import com.enhikyy.girassol.ui.components.EmptyState
import com.enhikyy.girassol.ui.components.KeyValue
import com.enhikyy.girassol.ui.components.LabeledBar
import com.enhikyy.girassol.ui.components.Pill
import com.enhikyy.girassol.ui.components.SectionCard
import com.enhikyy.girassol.ui.components.f
import com.enhikyy.girassol.ui.theme.Lumon
import com.enhikyy.girassol.ui.theme.MonoStyle
import kotlinx.coroutines.delay

@Composable
fun CitizensScreen(selectedId: String?, onSelect: (String?) -> Unit) {
    if (selectedId != null) {
        BackHandler { onSelect(null) }
        CitizenDetailView(selectedId) { onSelect(null) }
        return
    }
    val dash by SimulationHub.dashboard.collectAsStateWithLifecycle()
    val d = dash ?: return EmptyState("Carregando cidadãos…")
    val alive = d.citizens.count { it.alive }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("$alive vivos · ${d.citizens.size - alive} falecidos recentes", style = MaterialTheme.typography.labelSmall, color = Lumon.CreamDim,
                modifier = Modifier.padding(vertical = 4.dp))
        }
        items(d.citizens, key = { it.id }) { c -> CitizenRow(c) { onSelect(c.id) } }
    }
}

@Composable
private fun CitizenRow(c: CitizenSummary, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Lumon.Surface).clickable(onClick = onClick).padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.name, color = if (c.alive) Lumon.Cream else Lumon.CreamDim, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (!c.alive) Pill("falecido(a)", Lumon.Danger) else Pill(c.action, Lumon.Teal)
            Spacer(Modifier.width(6.dp))
            Text("g${c.generation} · ${c.ageDays.f(1)}d", style = MonoStyle, color = Lumon.CreamDim)
        }
        Text(if (c.alive) c.lastThought else "Causa: ${c.deathCause ?: "-"}", style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (c.alive) LabeledBar("bem-estar", c.meanNeed)
    }
}

@Composable
private fun CitizenDetailView(id: String, onBack: () -> Unit) {
    var detail by remember(id) { mutableStateOf<CitizenDetail?>(null) }
    LaunchedEffect(id) {
        while (true) {
            detail = SimulationHub.citizenDetail(id)
            delay(1500)
        }
    }
    val d = detail ?: return EmptyState("Carregando ficha…")
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Lista") }
                Text(d.summary.name, style = MaterialTheme.typography.titleLarge, color = Lumon.Sunflower, modifier = Modifier.weight(1f))
            }
            Text(d.summary.lastThought, style = MaterialTheme.typography.bodyMedium, color = Lumon.Cream)
        }
        item {
            SectionCard("Identidade") {
                KeyValue("Geração", d.summary.generation.toString())
                KeyValue("Idade (dias do mundo)", d.summary.ageDays.f(1))
                KeyValue("Pais", d.parents.ifEmpty { listOf("fundador(a)") }.joinToString(", "))
                KeyValue("Filhos", d.children.toString())
                KeyValue("Casa / posição", "${d.homeObjectId} · (${d.position.first},${d.position.second})")
                KeyValue("Sucessos / falhas", "${d.successes} / ${d.failures}")
                KeyValue("Sanções ADICO", d.sanctions.toString())
                KeyValue("Opinião sobre os comuns", d.opinionCommons.f(2))
                KeyValue("Literais Tsetlin incluídos", d.reflexIncludedLiterals.toString())
            }
        }
        item {
            SectionCard("Necessidades homeostáticas") {
                d.needs.forEach { LabeledBar(it.label, it.value, if (it.value < 0.3) Lumon.Danger else Lumon.Teal) }
            }
        }
        item {
            SectionCard("Emoção (OCC/PAD)") {
                KeyValue("Valência", d.emotion.valence.f(2))
                KeyValue("Excitação", d.emotion.arousal.f(2))
                KeyValue("Humor", d.emotion.mood.f(2))
                KeyValue("Ruminação", d.emotion.rumination.f(2))
                KeyValue("Última avaliação", d.emotion.lastAppraisal)
            }
        }
        item {
            SectionCard("Genoma diploide (expressão)") { d.traits.forEach { LabeledBar(it.label, it.value, Lumon.Violet) } }
        }
        item {
            SectionCard("Inventário (livro-razão)") { d.inventory.forEach { KeyValue(it.label, it.value.f(2)) } }
        }
        item {
            SectionCard("Habilidades (BKT + FSRS)") {
                d.skills.forEach { LabeledBar("${it.skill} · ${it.verifiedExecutions} exec.", it.mastery, Lumon.Sunflower) }
            }
        }
        item {
            SectionCard("Crenças (lógica subjetiva)") {
                if (d.beliefs.isEmpty()) Text("Nenhuma crença registrada", color = Lumon.CreamDim)
                d.beliefs.forEach { b ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(b.claim, style = MonoStyle, color = Lumon.Cream, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        when {
                            b.quarantined -> Pill("quarentena", Lumon.Danger)
                            b.verified -> Pill("verificada", Lumon.Ok)
                            else -> Pill("boato", Lumon.Sunflower)
                        }
                    }
                    Text("P=${b.probability.f(2)} · u=${b.uncertainty.f(2)} · fonte ${b.source ?: "própria"}", style = MonoStyle, color = Lumon.CreamDim)
                }
            }
        }
        item {
            SectionCard("Rede social (Dunbar)") {
                if (d.ties.isEmpty()) Text("Sem laços ainda", color = Lumon.CreamDim)
                d.ties.forEach { t ->
                    KeyValue("${t.otherName} · ${t.ring}", "afin ${t.affinity.f(2)} · fam ${t.familiarity.f(2)} · conf ${t.trustInfo.f(2)}")
                }
            }
        }
        item {
            SectionCard("Memórias recentes") {
                if (d.memories.isEmpty()) Text("Sem memórias", color = Lumon.CreamDim)
                d.memories.forEach { m ->
                    Text("[${m.layer.lowercase()} · t${m.tick}${if (m.verified) " · ✓" else ""}] ${m.content}", style = MaterialTheme.typography.bodySmall, color = Lumon.Cream)
                }
            }
        }
        item { Spacer(Modifier.padding(8.dp)) }
    }
}
