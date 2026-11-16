package com.taskai.app.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate

@Composable fun BackupScreen(vm: AppViewModel) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val chatting by vm.chatting.collectAsStateWithLifecycle()
    val preview by vm.restorePreview.collectAsStateWithLifecycle()
    var confirmation by remember(preview) { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.inspectBackup(uri)
    }
    // A preview is authorization-sensitive, not navigation state. Leaving requires selecting again.
    DisposableEffect(vm) { onDispose { vm.cancelRestore() } }
    BackHandler(enabled = preview != null) { if (!busy) vm.cancelRestore() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.PrivacyTip, null, tint = MaterialTheme.colorScheme.primary)
                Text("Seus dados, sob seu cuidado", style = MaterialTheme.typography.titleMedium)
                Text("A chave de API não faz parte do backup.", style = MaterialTheme.typography.bodyMedium)
                Text("O arquivo contém tarefas, categorias, projetos, conversas, mensagens e histórico de atividades. Ele não é criptografado e pode conter informações pessoais. Guarde-o em um local privado e compartilhe apenas com quem você confia.", style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("Guardar uma cópia")
                Text("Escolha onde salvar o arquivo JSON pelo seletor do Android. As configurações e credenciais não são exportadas.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = {
                    try { export.launch("task-ai-backup-${LocalDate.now()}.json") }
                    catch (_: ActivityNotFoundException) { vm.notice.value = "Nenhum seletor de arquivos disponível para exportar o backup." }
                }, enabled = !busy && preview == null, modifier = Modifier.fillMaxWidth()) { Text("Exportar backup") }
            }
        }
        OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("Restaurar uma cópia")
                Text("Selecione um backup do Task AI (até 10 MiB). O arquivo será validado e um resumo aparecerá antes de qualquer alteração.", style = MaterialTheme.typography.bodyMedium)
                Text("A restauração substitui todas as tarefas, categorias, projetos, conversas e atividades atuais. Não mescla os dados. Suas configurações e a chave de API atual são mantidas. Exporte uma cópia antes de continuar.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = {
                    vm.cancelRestore()
                    try { importer.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
                    catch (_: ActivityNotFoundException) { vm.notice.value = "Nenhum seletor de arquivos disponível para importar o backup." }
                }, enabled = !busy && !chatting && preview == null, modifier = Modifier.fillMaxWidth()) { Text("Importar backup") }
                if (chatting) Text("Aguarde o assistente terminar antes de importar ou restaurar.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Cancelar a seleção não altera seus dados. O app só informa a conclusão depois que a exportação ou a restauração termina.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    preview?.let { inspected ->
        AlertDialog(onDismissRequest = { if (!busy) vm.cancelRestore() },
            title = { Text("Revisar backup") },
            text = {
                Column(Modifier.imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(inspected, style = MaterialTheme.typography.bodyMedium)
                    Text("Esta ação substitui os dados locais e não pode ser desfeita sem outro backup. Configurações e chave de API não serão alteradas.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    OutlinedTextField(confirmation, { confirmation = it }, label = { Text("Digite RESTAURAR") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false))
                    Text("Digite exatamente RESTAURAR, em maiúsculas, para liberar a confirmação.", style = MaterialTheme.typography.bodySmall)
                    if (chatting) Text("Aguarde o assistente terminar antes de restaurar.", style = MaterialTheme.typography.bodySmall)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (confirmation == "RESTAURAR" && !vm.busy.value && !vm.chatting.value) vm.restore(confirmation)
                }, enabled = confirmation == "RESTAURAR" && !busy && !chatting,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Confirmar restauração") }
            },
            dismissButton = { TextButton(onClick = { vm.cancelRestore() }, enabled = !busy) { Text("Cancelar") } })
    }
}
