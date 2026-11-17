package com.taskai.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable fun ChatScreen(vm: AppViewModel, onSettings: () -> Unit) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val chatting by vm.chatting.collectAsStateWithLifecycle()
    val pending by vm.pendingConfirmation.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val savingVoice by vm.voicePreferenceSaving.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    var history by remember { mutableStateOf(false) }
    val voice = rememberChatVoice(vm) { recognized ->
        val message = listOf(text.trim(), recognized.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        text = message
        if (message.isNotEmpty()) {
            vm.send(message)
            if (vm.settings.value.apiKey.isNotBlank()) text = ""
        }
    }
    val capturing = voice.state.listening || voice.state.processing
    val state = rememberLazyListState()
    val visible = messages.filter { it.role.lowercase() in listOf("user", "assistant") }
    LaunchedEffect(visible.size, chatting, pending, voice.state.listening, voice.state.processing, voice.error) { if (state.layoutInfo.totalItemsCount > 0) state.animateScrollToItem(state.layoutInfo.totalItemsCount - 1) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${settings.provider.lowercase()} · ${settings.model}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            IconButton(onClick = { voice.cancel(); history = true }, enabled = !chatting && !capturing) { Icon(Icons.Outlined.History, "Histórico de conversas") }
            IconButton(onClick = { voice.cancel(); vm.newConversation() }, enabled = !chatting && !capturing) { Icon(Icons.Outlined.AddComment, "Nova conversa") }
        }
        SpokenRepliesSwitch(settings.spokenRepliesEnabled, savingVoice, voice.toggle)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text("O microfone usa o serviço de voz do Android, que pode exigir internet. Somente a fala final é enviada à assistente.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (settings.apiKey.isBlank()) item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) { Text("Conecte sua inteligência artificial", style = MaterialTheme.typography.titleSmall); Text("Informe uma chave de API nas configurações. Sem ela, o app não simula respostas nem executa pedidos por chat.", style = MaterialTheme.typography.bodySmall); TextButton(onClick = onSettings) { Text("Configurar IA") } }
                }
            }
            if (visible.isEmpty()) item {
                EmptyState("Fale para organizar sua rotina", "Toque no microfone e conte o que precisa fazer. Se houver dúvida, a assistente pergunta. Você pode responder por voz ou texto.", Icons.Outlined.Mic)
                listOf("Quais são minhas tarefas de hoje?", "O que está atrasado?", "Ajude a planejar minha semana").forEach { suggestion ->
                    OutlinedButton(onClick = { text = suggestion }, modifier = Modifier.fillMaxWidth()) { Text(suggestion) }
                }
            }
            items(visible, key = { it.id }) { message ->
                val user = message.role.lowercase() == "user"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
                    Surface(Modifier.widthIn(max = 340.dp), color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (user) "Você" else "Task AI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Text(message.content, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (chatting) item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text("Consultando e verificando...", style = MaterialTheme.typography.bodySmall) } }
            item { ChatVoiceStatus(voice) }
            if (pending) item {
                OutlinedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Confirmação necessária", style = MaterialTheme.typography.titleSmall)
                    Text("Confira os alvos descritos acima. A exclusão ainda não foi executada.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { vm.confirmAction() }, enabled = !chatting, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Confirmar exclusão") }; TextButton(onClick = vm::cancelAction, enabled = !chatting) { Text("Cancelar") } }
                } }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { text = it }, modifier = Modifier.weight(1f), label = { Text("Mensagem para o assistente") }, maxLines = 4, enabled = !chatting && !pending && !capturing, shape = RoundedCornerShape(20.dp))
            FilledTonalIconButton(onClick = { if (voice.state.listening) voice.finish() else voice.begin() }, enabled = !chatting && !pending && !voice.state.processing, modifier = Modifier.padding(bottom = 5.dp)) {
                Icon(if (voice.state.listening) Icons.Outlined.StopCircle else Icons.Outlined.Mic, if (voice.state.listening) "Parar e enviar áudio" else "Falar tarefa")
            }
            FilledIconButton(onClick = { val message = text.trim(); if (message.isNotBlank()) { voice.stopSpeaking(); vm.send(message); if (vm.settings.value.apiKey.isNotBlank()) text = "" } }, enabled = text.isNotBlank() && !chatting && !pending && !capturing, modifier = Modifier.padding(bottom = 5.dp)) { Icon(Icons.AutoMirrored.Outlined.Send, "Enviar mensagem") }
        }
    }
    if (history) AlertDialog(onDismissRequest = { history = false }, title = { Text("Conversas salvas") }, text = {
        LazyColumn { if (conversations.isEmpty()) item { Text("Nenhuma conversa ainda.") }; items(conversations, key = { it.id }) { conversation -> TextButton(onClick = { vm.selectConversation(conversation.id); history = false }, modifier = Modifier.fillMaxWidth()) { Text(conversation.title, modifier = Modifier.fillMaxWidth()) } } }
    }, confirmButton = { TextButton(onClick = { history = false }) { Text("Fechar") } })
}
