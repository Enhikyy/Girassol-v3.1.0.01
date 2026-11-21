package com.enhikyy.girassol.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.enhikyy.girassol.llm.LocalLlmManager
import com.enhikyy.girassol.sim.SimulationHub
import com.enhikyy.girassol.ui.components.Shapes
import com.enhikyy.girassol.ui.theme.Lumon
import kotlinx.coroutines.launch

/**
 * Fluxo "1 .gguf = 1 cidadão fundador": abre o seletor de arquivos e, escolhido o .gguf,
 * pergunta nome e sexo (ou gera a identidade). Devolve a função que inicia o fluxo.
 * [existingModelKey] != null cria o fundador de um modelo já importado (sem copiar arquivo).
 */
@Composable
fun rememberFounderImporter(): (existingModelKey: String?) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingKey by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) pendingUri = uri }

    if (pendingUri != null || pendingKey != null) {
        var name by remember { mutableStateOf("") }
        var sex by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!busy) { pendingUri = null; pendingKey = null } },
            shape = Shapes.Card,
            containerColor = Lumon.Surface,
            title = { Text("Novo cidadão fundador", color = Lumon.Cream) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(pendingKey?.let { "Modelo: $it" } ?: "Este .gguf será a mente exclusiva desta pessoa.",
                        style = MaterialTheme.typography.bodySmall, color = Lumon.CreamDim)
                    OutlinedTextField(name, { name = it.take(40) }, singleLine = true, shape = Shapes.Control,
                        label = { Text("Nome (vazio = gerar)") }, modifier = Modifier.fillMaxWidth().testTag("founder_name"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((code, label) in listOf("F" to "Feminino", "M" to "Masculino", "" to "Gerar")) {
                            FilterChip(selected = sex == code, onClick = { sex = code }, label = { Text(label) }, shape = Shapes.Capsule,
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Lumon.Sunflower, selectedLabelColor = Lumon.Background,
                                    labelColor = Lumon.CreamDim))
                        }
                    }
                }
            },
            confirmButton = {
                Button(enabled = !busy, shape = Shapes.Control, modifier = Modifier.testTag("founder_confirm"),
                    colors = ButtonDefaults.buttonColors(containerColor = Lumon.Sunflower, contentColor = Lumon.Background),
                    onClick = {
                        busy = true
                        val uri = pendingUri; val key = pendingKey
                        scope.launch {
                            val msg = if (key != null) SimulationHub.spawnFounder(key, name.ifBlank { null }, sex.ifBlank { null })
                            else SimulationHub.importFounder(context, uri!!, name.ifBlank { null }, sex.ifBlank { null })
                            busy = false; pendingUri = null; pendingKey = null
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    }) { Text(if (busy) "Copiando…" else "Dar vida") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { pendingUri = null; pendingKey = null }) { Text("Cancelar") } }
        )
    }
    return { key ->
        if (key != null) pendingKey = key
        else if (!LocalLlmManager.engineCompiled) Toast.makeText(context, "Motor llama.cpp indisponível neste build", Toast.LENGTH_LONG).show()
        else picker.launch(arrayOf("application/octet-stream", "*/*"))
    }
}
