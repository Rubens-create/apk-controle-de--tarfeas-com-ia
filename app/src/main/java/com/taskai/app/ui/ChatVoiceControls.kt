package com.taskai.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable internal fun SpokenRepliesSwitch(enabled: Boolean, saving: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (enabled) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Respostas por voz", Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
        Switch(enabled, onCheckedChange = onChange, enabled = !saving, modifier = Modifier.testTag("spoken_replies_toggle").semantics { contentDescription = "Respostas por voz" })
    }
}

@Composable internal fun ChatVoiceStatus(voice: ChatVoiceHandle) {
    val state = voice.state
    if (!state.listening && !state.processing && !state.speaking && voice.error == null) return
    OutlinedCard(Modifier.fillMaxWidth().testTag("voice_status")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                state.listening -> {
                    Text("Ouvindo sua tarefa...", style = MaterialTheme.typography.titleSmall)
                    Text(state.partialText.ifBlank { "Fale naturalmente. Ao terminar, o pedido será enviado para a assistente." }, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = voice.finish) { Text("Parar e enviar") }
                        TextButton(onClick = voice.cancel) { Text("Cancelar áudio") }
                    }
                }
                state.processing -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Transcrevendo sua fala...", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = voice.cancel) { Text("Cancelar áudio") }
                }
                state.speaking -> {
                    Text("Lendo a resposta...", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = voice.stopSpeaking) { Text("Parar voz") }
                }
                else -> {
                    Text(voice.error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (voice.permissionDenied) TextButton(onClick = voice.openPermissions) { Text("Permitir microfone") }
                }
            }
        }
    }
}
