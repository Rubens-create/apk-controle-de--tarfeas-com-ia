package com.taskai.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.taskai.app.data.*
import java.time.*
import java.util.Locale

@Composable fun TaskEditor(task: TaskEntity, isNew: Boolean, categories: List<CategoryEntity>, projects: List<ProjectEntity>, busy: Boolean, onDismiss: () -> Unit, onSave: (TaskEntity) -> Unit, onDelete: () -> Unit) {
    var title by remember(task.id) { mutableStateOf(task.title) }
    var description by remember(task.id) { mutableStateOf(task.description) }
    var date by remember(task.id) { mutableStateOf(task.dueDate) }
    var time by remember(task.id) { mutableStateOf(task.dueTime) }
    var priority by remember(task.id) { mutableStateOf(task.priority) }
    var category by remember(task.id) { mutableStateOf(task.categoryId ?: "") }
    var project by remember(task.id) { mutableStateOf(task.projectId ?: "") }
    var rule by remember(task.id) { mutableStateOf(task.recurrenceRule ?: "") }
    var reminder by remember(task.id) { mutableStateOf(task.reminderEnabled) }
    var minutes by remember(task.id) { mutableStateOf(task.reminderMinutes.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val rules = listOf("" to "Não se repete", "FREQ=DAILY" to "Diariamente", "FREQ=WEEKLY" to "Semanalmente", "FREQ=MONTHLY" to "Mensalmente", "FREQ=YEARLY" to "Anualmente", "CUSTOM" to "Dias ou intervalo personalizado")
    var customRule by remember(task.id) { mutableStateOf(rule.isNotBlank() && rules.none { it.first == rule }) }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).heightIn(max = 740.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (isNew) "Nova tarefa" else "Editar tarefa", style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss, enabled = !busy) { Icon(Icons.Outlined.Close, "Fechar editor") }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("Título da tarefa") }, modifier = Modifier.fillMaxWidth(), singleLine = true, isError = title.isBlank() && error != null)
                    OutlinedTextField(description, { description = it }, label = { Text("Descrição e observações") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = {
                            val initial = date?.let(LocalDate::parse) ?: LocalDate.now()
                            DatePickerDialog(context, { _, year, month, day -> date = LocalDate.of(year, month + 1, day).toString() }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
                        }, modifier = Modifier.weight(1f)) { Text(date?.let { LocalDate.parse(it).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) } ?: "Definir data") }
                        OutlinedButton(onClick = {
                            val initial = time?.let(LocalTime::parse) ?: LocalTime.of(9, 0)
                            TimePickerDialog(context, { _, hour, minute -> time = String.format(Locale.ROOT, "%02d:%02d", hour, minute) }, initial.hour, initial.minute, true).show()
                        }, enabled = date != null, modifier = Modifier.weight(1f)) { Text(time ?: "Definir horário") }
                    }
                    if (date != null) Row {
                        TextButton(onClick = { date = null; time = null; reminder = false; rule = ""; customRule = false }) { Text("Remover data") }
                        if (time != null) TextButton(onClick = { time = null; reminder = false }) { Text("Remover horário") }
                    }
                    ChoiceField("Prioridade", priority, listOf("BAIXA", "MEDIA", "ALTA", "URGENTE").map { it to priorityLabel(it) }, { priority = it })
                    ChoiceField("Categoria", category, listOf("" to "Sem categoria") + categories.map { it.id to it.name }, { category = it })
                    ChoiceField("Projeto", project, listOf("" to "Sem projeto") + projects.map { it.id to it.name }, { project = it })
                    ChoiceField("Recorrência", if (customRule) "CUSTOM" else rule, rules, {
                        customRule = it == "CUSTOM"; rule = if (customRule) "FREQ=WEEKLY;BYDAY=MO,WE,FR" else it
                    })
                    if (customRule) OutlinedTextField(rule, { rule = it.uppercase(Locale.ROOT) }, Modifier.fillMaxWidth(), label = { Text("Regra RRULE") }, supportingText = { Text("Ex.: FREQ=DAILY;INTERVAL=3 ou FREQ=MONTHLY;BYMONTHDAY=10. MO, TU, WE, TH, FR, SA, SU são os dias da semana.") })
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Lembrete"); Text(if (date == null || time == null) "Defina uma data e um horário" else "Aviso antes do prazo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Switch(reminder, { reminder = it }, enabled = date != null && time != null)
                    }
                    if (reminder) OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Antecedência em minutos") }, singleLine = true, supportingText = { Text("0 avisa no horário da tarefa.") })
                    Text(if (task.status == "COMPLETED") "Status: concluída" else "Status: pendente", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!isNew) IconButton(onClick = { deleting = true }, enabled = !busy) { Icon(Icons.Outlined.DeleteOutline, "Excluir tarefa", tint = MaterialTheme.colorScheme.error) }
                    Button(onClick = {
                        try {
                            val value = task.copy(title = title.trim(), description = description.trim(), dueDate = date, dueTime = time, priority = priority,
                                categoryId = category.ifBlank { null }, projectId = project.ifBlank { null }, recurrenceRule = rule.ifBlank { null }, reminderEnabled = reminder,
                                reminderMinutes = minutes.toIntOrNull() ?: throw IllegalArgumentException("Informe uma antecedência válida."), reminderDatetime = null)
                            TaskRules.validate(value); error = null; onSave(value)
                        } catch (e: Exception) { error = e.message ?: "Verifique os dados da tarefa." }
                    }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Salvar tarefa") }
                }
            }
        }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Excluir tarefa?") }, text = { Text("${task.title} será removida deste dispositivo. Esta ação não pode ser desfeita.") }, confirmButton = { TextButton(onClick = { deleting = false; onDelete() }, enabled = !busy) { Text("Excluir", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancelar") } })
}
