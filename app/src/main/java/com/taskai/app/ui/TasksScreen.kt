package com.taskai.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.taskai.app.data.*
import java.time.LocalDate

@Composable fun TasksScreen(tasks: List<TaskEntity>, categories: List<CategoryEntity>, projects: List<ProjectEntity>, today: LocalDate, filter: String, onFilter: (String) -> Unit, onEdit: (TaskEntity) -> Unit, onToggle: (TaskEntity) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var priority by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var project by rememberSaveable { mutableStateOf("") }
    val filtered = TaskFilters.apply(tasks, filter, today).filter {
        (search.isBlank() || it.title.contains(search, true) || it.description.contains(search, true)) &&
        (priority.isBlank() || it.priority == priority) && (category.isBlank() || it.categoryId == category) && (project.isBlank() || it.projectId == project)
    }.sortedWith(compareBy<TaskEntity> { it.status == "COMPLETED" }.thenBy { it.dueDate ?: "9999" }.thenBy { it.dueTime ?: "23:59" })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), placeholder = { Text("Buscar nas suas tarefas") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true) }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                taskFilterLabels.forEach { (id, label) -> FilterChip(selected = filter == id, onClick = { onFilter(id) }, label = { Text(label) }) }
            }
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ChoiceField("Prioridade", priority, listOf("" to "Todas") + listOf("BAIXA", "MEDIA", "ALTA", "URGENTE").map { it to priorityLabel(it) }, { priority = it }, Modifier.weight(1f))
            ChoiceField("Categoria", category, listOf("" to "Todas") + categories.map { it.id to it.name }, { category = it }, Modifier.weight(1f))
        } }
        if (projects.isNotEmpty()) item { ChoiceField("Projeto", project, listOf("" to "Todos") + projects.map { it.id to it.name }, { project = it }) }
        item { Text("${filtered.size} tarefa(s)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (filtered.isEmpty()) item { EmptyState("Nenhuma tarefa neste filtro", "Crie uma tarefa ou escolha outro período.") }
        items(filtered, key = { it.id }) { TaskRow(it, categories, today, onEdit, onToggle) }
    }
}
