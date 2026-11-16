package com.taskai.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taskai.app.data.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable fun CalendarScreen(tasks: List<TaskEntity>, categories: List<CategoryEntity>, today: LocalDate, onEdit: (TaskEntity) -> Unit, onToggle: (TaskEntity) -> Unit) {
    var monthString by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var selectedString by rememberSaveable { mutableStateOf(today.toString()) }
    val month = YearMonth.parse(monthString)
    val selected = LocalDate.parse(selectedString)
    val dayTasks = tasks.filter { it.dueDate == selectedString }.sortedBy { it.dueTime ?: "23:59" }
    val offset = month.atDay(1).dayOfWeek.value - 1
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            OutlinedCard(colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        IconButton(onClick = { monthString = month.minusMonths(1).toString() }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Mês anterior") }
                        Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.forLanguageTag("pt-BR"))), style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { monthString = month.plusMonths(1).toString() }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Próximo mês") }
                    }
                    Row { listOf("S", "T", "Q", "Q", "S", "S", "D").forEach { Text(it, Modifier.weight(1f).wrapContentWidth(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    repeat((offset + month.lengthOfMonth() + 6) / 7) { week ->
                        Row(Modifier.fillMaxWidth()) {
                            repeat(7) { column ->
                                val day = week * 7 + column - offset + 1
                                if (day !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).height(46.dp))
                                else {
                                    val date = month.atDay(day)
                                    val chosen = date == selected
                                    Column(Modifier.weight(1f).height(46.dp).clickable { selectedString = date.toString() }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        Box(Modifier.size(32.dp).background(if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, CircleShape), contentAlignment = Alignment.Center) {
                                            Text(day.toString(), color = if (chosen) MaterialTheme.colorScheme.onPrimary else if (date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal)
                                        }
                                        if (tasks.any { it.dueDate == date.toString() }) Box(Modifier.size(4.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                                    }
                                }
                            }
                        }
                    }
                    TextButton(onClick = { monthString = YearMonth.from(today).toString(); selectedString = today.toString() }, modifier = Modifier.align(Alignment.End)) { Text("Ir para hoje") }
                }
            }
        }
        item { SectionTitle(selected.format(DateTimeFormatter.ofPattern("d 'de' MMMM", Locale.forLanguageTag("pt-BR")))) }
        if (dayTasks.isEmpty()) item { EmptyState("Dia livre", "Nenhuma tarefa agendada para a data selecionada.") }
        items(dayTasks, key = { it.id }) { TaskRow(it, categories, today, onEdit, onToggle) }
    }
}
