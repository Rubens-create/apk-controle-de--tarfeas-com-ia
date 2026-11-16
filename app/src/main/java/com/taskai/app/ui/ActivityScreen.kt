package com.taskai.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskai.app.data.ActivityLogEntity
import java.time.Instant
import java.time.ZoneId

import java.time.format.DateTimeFormatter
import java.util.Locale

private val activityLocale: Locale = Locale.forLanguageTag("pt-BR")
private val activityDateTime: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm:ss", activityLocale)
private val activityDay: DateTimeFormatter = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", activityLocale)

private fun activityEntityLabel(type: String): String = when (type) {
    "TASK" -> "Tarefa"
    "CATEGORY" -> "Categoria"
    "PROJECT" -> "Projeto"
    "CONVERSATION" -> "Conversa"
    "MESSAGE" -> "Mensagem"
    "BACKUP" -> "Backup"
    else -> "Registro"
}

private fun activityActionLabel(log: ActivityLogEntity): String {
    val entity = activityEntityLabel(log.entityType)
    val feminine = log.entityType in setOf("TASK", "CATEGORY", "CONVERSATION", "MESSAGE")
    return when (log.action) {
        "CREATE" -> "$entity ${if (feminine) "criada" else "criado"}"
        "UPDATE" -> "$entity ${if (feminine) "atualizada" else "atualizado"}"
        "DELETE" -> "$entity ${if (feminine) "excluída" else "excluído"}"
        "COMPLETE" -> "$entity ${if (feminine) "concluída" else "concluído"}"
        "REOPEN" -> "$entity ${if (feminine) "reaberta" else "reaberto"}"
        "RECURRENCE_CREATED" -> "Próxima ocorrência gerada"
        "MESSAGE" -> "Mensagem registrada"
        "CLEAR" -> if (log.entityType == "CONVERSATION") "Conversas excluídas" else "Registros excluídos"
        "RESTORE" -> "Backup restaurado"
        "EXPORT" -> "Backup exportado"
        else -> "Atividade registrada"
    }
}

private fun activityIcon(action: String): ImageVector = when (action) {
    "CREATE" -> Icons.Outlined.AddCircleOutline
    "UPDATE" -> Icons.Outlined.Edit
    "DELETE", "CLEAR" -> Icons.Outlined.DeleteOutline
    "COMPLETE" -> Icons.Outlined.CheckCircle
    "REOPEN" -> Icons.Outlined.Undo
    "RECURRENCE_CREATED" -> Icons.Outlined.Repeat
    "MESSAGE" -> Icons.Outlined.ChatBubbleOutline
    "RESTORE", "EXPORT" -> Icons.Outlined.SaveAlt
    else -> Icons.Outlined.History
}

private fun localizedActivityDetail(log: ActivityLogEntity): String {
    if (log.action != "MESSAGE") return log.details
    // Only translate the repository's exact message template, never user-provided entity names.
    return when (log.details) {
        "Mensagem registrada (user)." -> "Mensagem do usuário registrada."
        "Mensagem registrada (assistant)." -> "Mensagem do assistente registrada."
        "Mensagem registrada (tool)." -> "Resultado de ferramenta registrado."
        "Mensagem registrada (system)." -> "Mensagem do sistema registrada."
        else -> log.details
    }
}

@Composable
fun ActivityScreen(vm: AppViewModel, zoneId: String) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val zoneResult = remember(zoneId) { runCatching { ZoneId.of(zoneId) } }
    val zone = zoneResult.getOrDefault(ZoneId.of("UTC"))
    val ordered = remember(logs) { logs.sortedByDescending { it.createdAt } }
    val grouped = remember(ordered, zone) { ordered.groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() } }
    val entityNames = remember(tasks, categories, projects, conversations) {
        buildMap<Pair<String, String>, String> {
            tasks.forEach { put("TASK" to it.id, it.title) }
            categories.forEach { put("CATEGORY" to it.id, it.name) }
            projects.forEach { put("PROJECT" to it.id, it.name) }
            conversations.forEach { put("CONVERSATION" to it.id, it.title) }
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cada mudança, registrada", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Consulte as ações registradas nas suas tarefas, categorias, projetos e conversas.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Horários em ${zone.id}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (zoneResult.isFailure) Text("Fuso horário inválido. Exibindo em UTC.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text("${ordered.size} ${if (ordered.size == 1) "atividade registrada" else "atividades registradas"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (ordered.isEmpty()) item {
            EmptyState("Nenhuma atividade ainda", "As ações realizadas no aplicativo aparecerão aqui, das mais recentes para as mais antigas.", Icons.Outlined.History)
        }
        grouped.forEach { (date, entries) ->
            item(key = "date:$date") {
                Text(date.format(activityDay), modifier = Modifier.padding(top = 10.dp, bottom = 2.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            items(entries, key = { "log:${it.id}" }) { log ->
                ActivityEntry(log, zone, entityNames[log.entityType to log.entityId])
            }
        }
    }
}

@Composable
private fun ActivityEntry(log: ActivityLogEntity, zone: ZoneId, entityName: String?) {
    val detail = localizedActivityDetail(log)
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
                Icon(activityIcon(log.action), null, Modifier.padding(10.dp).size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(activityActionLabel(log), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                // Historical details are authoritative, including names of entities that were deleted.
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium)
                if (!entityName.isNullOrBlank() && !detail.contains(entityName, ignoreCase = true)) {
                    Text(entityName, style = MaterialTheme.typography.bodyMedium)
                }
                Text(activityEntityLabel(log.entityType), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    Instant.ofEpochMilli(log.createdAt).atZone(zone).format(activityDateTime),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
