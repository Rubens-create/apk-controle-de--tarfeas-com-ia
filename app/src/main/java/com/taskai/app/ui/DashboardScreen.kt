package com.taskai.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taskai.app.data.*
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable fun DashboardScreen(tasks: List<TaskEntity>, categories: List<CategoryEntity>, userName: String, now: ZonedDateTime, onChat: () -> Unit, onFilter: (String) -> Unit, onEdit: (TaskEntity) -> Unit, onToggle: (TaskEntity) -> Unit) {
    val today = now.toLocalDate()
    val summary = DashboardSummary.from(tasks, today)
    val greeting = when (now.hour) { in 5..11 -> "Bom dia"; in 12..17 -> "Boa tarde"; else -> "Boa noite" }
    val upcoming = tasks.filter { it.status == "PENDING" }.sortedWith(compareBy<TaskEntity> { it.dueDate ?: "9999" }.thenBy { it.dueTime ?: "23:59" }).take(5)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("TASK AI", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(greeting + if (userName.isBlank()) "!" else ", ${userName.trim()}!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    Text(today.format(DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", Locale.forLanguageTag("pt-BR"))), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) { Icon(Icons.Outlined.DoneAll, null, Modifier.padding(14.dp), tint = MaterialTheme.colorScheme.primary) }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Um passo de cada vez.", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(if (summary.todayTotal == 0) "Seu dia está aberto a novas possibilidades. Adicione sua primeira tarefa de hoje." else "${summary.todayCompleted} de ${summary.todayTotal} tarefas de hoje concluídas.", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { summary.progress }, modifier = Modifier.fillMaxWidth().height(7.dp), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surface.copy(alpha = .6f))
                    Text("PROGRESSO DO DIA", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Pendentes", summary.pending, Icons.Outlined.Checklist, Modifier.weight(1f)) { onFilter("PENDING") }
                StatTile("Atrasadas", summary.overdue, Icons.Outlined.Schedule, Modifier.weight(1f)) { onFilter("OVERDUE") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Concluídas", summary.completed, Icons.Outlined.TaskAlt, Modifier.weight(1f)) { onFilter("COMPLETED") }
                StatTile("Prioritárias", summary.priority, Icons.Outlined.Flag, Modifier.weight(1f)) { onFilter("PRIORITY") }
            }
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onChat), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) { Text("Vamos organizar seu dia?", style = MaterialTheme.typography.titleSmall); Text("Converse com seu assistente", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Abrir assistente", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item { SectionTitle("Próximas tarefas", "Ver todas") { onFilter("ALL") } }
        if (upcoming.isEmpty()) item { EmptyState("Tudo em ordem por aqui", "Toque em + para adicionar uma tarefa. Seus dados ficam salvos neste dispositivo.") }
        items(upcoming, key = { it.id }) { TaskRow(it, categories, today, onEdit, onToggle) }
    }
}

@Composable private fun StatTile(title: String, count: Int, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    OutlinedCard(modifier.clickable(onClick = onClick), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(count.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold); Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp)) }
            Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
