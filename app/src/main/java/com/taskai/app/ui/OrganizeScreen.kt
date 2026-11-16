package com.taskai.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskai.app.data.CategoryEntity
import com.taskai.app.data.ProjectEntity
import java.text.Normalizer
import java.util.Locale

private data class CategoryIconChoice(val key: String, val label: String, val image: ImageVector)
private val categoryIconChoices = listOf(
    CategoryIconChoice("label", "Etiqueta", Icons.Outlined.Label),
    CategoryIconChoice("home", "Casa", Icons.Outlined.Home),
    CategoryIconChoice("work", "Trabalho", Icons.Outlined.WorkOutline),
    CategoryIconChoice("school", "Estudos", Icons.Outlined.School),
    CategoryIconChoice("fitness_center", "Saúde", Icons.Outlined.FitnessCenter),
    CategoryIconChoice("shopping_cart", "Compras", Icons.Outlined.ShoppingCart),
    CategoryIconChoice("restaurant", "Alimentação", Icons.Outlined.Restaurant),
    CategoryIconChoice("folder", "Pasta", Icons.Outlined.FolderOpen)
)
private val organizePalette = listOf(
    "Rosa" to "#F282AF", "Violeta" to "#8B6FC0", "Azul" to "#427EB7", "Turquesa" to "#298B93",
    "Verde" to "#3A806B", "Amarelo" to "#BC882B", "Laranja" to "#CD7041", "Vermelho" to "#BC4560"
)
private val organizeHexPattern = Regex("#[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?")
private data class OrganizeDraft(val name: String, val description: String, val color: String, val icon: String)
private data class OrganizeDeletion(val id: String, val name: String, val category: Boolean)

private fun organizeNameKey(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .replace(Regex("[\\p{javaWhitespace}\\p{Z}]+"), " ").trim().lowercase(Locale.ROOT)

private fun organizeColor(value: String, fallback: Color): Color =
    if (organizeHexPattern.matches(value)) runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrDefault(fallback) else fallback

private fun categoryImage(key: String): ImageVector = categoryIconChoices.firstOrNull { it.key == key }?.image ?: Icons.Outlined.Label

@Composable
fun OrganizeScreen(vm: AppViewModel) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var categoryEditor by remember { mutableStateOf<CategoryEntity?>(null) }
    var projectEditor by remember { mutableStateOf<ProjectEntity?>(null) }
    var newEntry by remember { mutableStateOf(false) }
    var deletion by remember { mutableStateOf<OrganizeDeletion?>(null) }
    var deletionSubmitted by remember { mutableStateOf(false) }

    // The confirmation stays open until Room confirms removal, rather than claiming success on click.
    LaunchedEffect(categories, projects, deletion, deletionSubmitted) {
        deletion?.let { target ->
            if (deletionSubmitted && if (target.category) categories.none { it.id == target.id } else projects.none { it.id == target.id }) {
                deletion = null
                deletionSubmitted = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Crie categorias para o dia a dia e projetos para seus objetivos.",
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Categorias") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Projetos") })
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp, top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val count = if (tab == 0) categories.size else projects.size
            Text(
                if (tab == 0) "$count ${if (count == 1) "categoria" else "categorias"}" else "$count ${if (count == 1) "projeto" else "projetos"}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilledTonalButton(
                onClick = {
                    newEntry = true
                    if (tab == 0) categoryEditor = CategoryEntity(name = "") else projectEditor = ProjectEntity(name = "")
                },
                enabled = !busy,
                modifier = Modifier.semantics { contentDescription = if (tab == 0) "Adicionar categoria" else "Adicionar projeto" },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (tab == 0) "Nova categoria" else "Novo projeto")
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (tab == 0) {
                if (categories.isEmpty()) item {
                    EmptyState("Nenhuma categoria ainda", "Toque em Nova categoria para organizar suas tarefas por contexto.", Icons.Outlined.Label)
                }
                items(categories, key = { "category:${it.id}" }) { category ->
                    OrganizeRow(
                        name = category.name, detail = category.color, color = category.color, icon = categoryImage(category.icon), kind = "categoria", busy = busy,
                        onEdit = { newEntry = false; categoryEditor = category },
                        onDelete = { deletionSubmitted = false; deletion = OrganizeDeletion(category.id, category.name, true) }
                    )
                }
            } else {
                if (projects.isEmpty()) item {
                    EmptyState("Nenhum projeto ainda", "Toque em Novo projeto e reúna as tarefas de um objetivo.", Icons.Outlined.FolderOpen)
                }
                items(projects, key = { "project:${it.id}" }) { project ->
                    OrganizeRow(
                        name = project.name, detail = project.description.ifBlank { project.color }, color = project.color, icon = Icons.Outlined.FolderOpen, kind = "projeto", busy = busy,
                        onEdit = { newEntry = false; projectEditor = project },
                        onDelete = { deletionSubmitted = false; deletion = OrganizeDeletion(project.id, project.name, false) }
                    )
                }
            }
        }
    }

    categoryEditor?.let { category ->
        OrganizeEditor(
            editorKey = category.id, title = if (newEntry) "Nova categoria" else "Editar categoria",
            initialName = category.name, initialColor = category.color, initialDescription = null, initialIcon = category.icon,
            busy = busy, duplicateName = { name -> categories.any { it.id != category.id && organizeNameKey(it.name) == organizeNameKey(name) } },
            onDismiss = { categoryEditor = null },
            onSave = { value -> vm.saveCategory(category.copy(name = value.name, color = value.color, icon = value.icon)) { categoryEditor = null } }
        )
    }
    projectEditor?.let { project ->
        OrganizeEditor(
            editorKey = project.id, title = if (newEntry) "Novo projeto" else "Editar projeto",
            initialName = project.name, initialColor = project.color, initialDescription = project.description, initialIcon = null,
            busy = busy, duplicateName = { name -> projects.any { it.id != project.id && organizeNameKey(it.name) == organizeNameKey(name) } },
            onDismiss = { projectEditor = null },
            onSave = { value -> vm.saveProject(project.copy(name = value.name, description = value.description, color = value.color)) { projectEditor = null } }
        )
    }
    deletion?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!busy) { deletion = null; deletionSubmitted = false } },
            icon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(if (target.category) "Excluir categoria?" else "Excluir projeto?") },
            text = { Text("Excluir \"${target.name}\"? As tarefas serão mantidas e apenas o vínculo com ${if (target.category) "esta categoria" else "este projeto"} será removido. Esta ação não pode ser desfeita.") },
            confirmButton = {
                TextButton(onClick = {
                    deletionSubmitted = true
                    if (target.category) vm.deleteCategory(target.id) else vm.deleteProject(target.id)
                }, enabled = !busy) { Text("Excluir", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletion = null; deletionSubmitted = false }, enabled = !busy) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun OrganizeRow(name: String, detail: String, color: String, icon: ImageVector, kind: String, busy: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    val tint = organizeColor(color, MaterialTheme.colorScheme.primary)
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f).clickable(enabled = !busy, role = Role.Button, onClickLabel = "Editar $kind $name", onClick = onEdit),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(color = tint.copy(alpha = .14f), shape = RoundedCornerShape(12.dp)) {
                    Icon(icon, null, Modifier.padding(12.dp).size(22.dp), tint = tint)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onEdit, enabled = !busy) { Icon(Icons.Outlined.Edit, "Editar $kind $name", Modifier.size(20.dp)) }
            IconButton(onClick = onDelete, enabled = !busy) { Icon(Icons.Outlined.DeleteOutline, "Excluir $kind $name", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrganizeEditor(
    editorKey: String, title: String, initialName: String, initialColor: String, initialDescription: String?, initialIcon: String?,
    busy: Boolean, duplicateName: (String) -> Boolean, onDismiss: () -> Unit, onSave: (OrganizeDraft) -> Unit
) {
    var name by rememberSaveable(editorKey) { mutableStateOf(initialName) }
    var description by rememberSaveable(editorKey) { mutableStateOf(initialDescription.orEmpty()) }
    var color by rememberSaveable(editorKey) { mutableStateOf(initialColor) }
    var icon by rememberSaveable(editorKey) { mutableStateOf(initialIcon ?: "label") }
    var error by rememberSaveable(editorKey) { mutableStateOf<String?>(null) }
    val validColor = organizeHexPattern.matches(color.trim())
    val previewColor = organizeColor(color.trim(), MaterialTheme.colorScheme.primary)

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(.94f).systemBarsPadding().imePadding(), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, enabled = !busy) { Icon(Icons.Outlined.Close, "Fechar editor") }
                }
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it; error = null }, enabled = !busy,
                        label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        isError = error != null && (name.isBlank() || name.length > 200),
                        supportingText = { Text("Até 200 caracteres.") }
                    )
                    if (initialDescription != null) OutlinedTextField(
                        value = description, onValueChange = { description = it; error = null }, enabled = !busy,
                        label = { Text("Descrição") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5,
                        isError = description.length > 20000,
                        supportingText = { Text("Opcional. Até 20000 caracteres.") }
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Cor", style = MaterialTheme.typography.labelLarge)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().selectableGroup(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            organizePalette.forEach { (label, hex) ->
                                val selected = color.trim().equals(hex, ignoreCase = true)
                                val swatch = organizeColor(hex, MaterialTheme.colorScheme.primary)
                                Box(
                                    modifier = Modifier.size(48.dp).selectable(selected = selected, enabled = !busy, role = Role.RadioButton, onClick = { color = hex; error = null })
                                        .semantics { contentDescription = "Cor $label ($hex)" },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Surface(
                                        modifier = Modifier.size(34.dp), shape = CircleShape, color = swatch,
                                        border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant)
                                    ) {
                                        if (selected) Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Check, null, Modifier.size(18.dp), tint = if (swatch.luminance() > .5f) Color.Black else Color.White) }
                                    }
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = color, onValueChange = { color = it.uppercase(Locale.ROOT); error = null }, enabled = !busy,
                        label = { Text("Cor hexadecimal") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        isError = !validColor,
                        supportingText = { Text("Use #RRGGBB ou #AARRGGBB.") },
                        leadingIcon = { Surface(modifier = Modifier.size(22.dp), color = previewColor, shape = CircleShape, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {} }
                    )
                    if (initialIcon != null) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Ícone", style = MaterialTheme.typography.labelLarge)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().selectableGroup(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            categoryIconChoices.forEach { choice ->
                                val selected = icon == choice.key
                                Surface(
                                    modifier = Modifier.size(52.dp).selectable(selected = selected, enabled = !busy, role = Role.RadioButton, onClick = { icon = choice.key })
                                        .semantics { contentDescription = "Ícone ${choice.label}" },
                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(12.dp),
                                    border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                                ) {
                                    Box(contentAlignment = Alignment.Center) { Icon(choice.image, null, Modifier.size(24.dp), tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                        }
                        Text(
                            categoryIconChoices.firstOrNull { it.key == icon }?.label ?: "Ícone personalizado mantido",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar") }
                    Button(
                        onClick = {
                            error = when {
                                name.isBlank() || name.length > 200 -> "O nome deve conter de 1 a 200 caracteres."
                                !validColor -> "Cor inválida. Use #RRGGBB ou #AARRGGBB."
                                description.length > 20000 -> "A descrição deve ter até 20000 caracteres."
                                duplicateName(name) -> if (initialIcon != null) "Já existe uma categoria com este nome." else "Já existe um projeto com este nome."
                                else -> null
                            }
                            if (error == null) onSave(OrganizeDraft(name.trim(), description, color.trim(), icon))
                        }, enabled = !busy, modifier = Modifier.weight(1f)
                    ) {
                        if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)) }
                        Text("Salvar")
                    }
                }
            }
        }
    }
}
