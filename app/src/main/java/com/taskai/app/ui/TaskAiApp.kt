package com.taskai.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskai.app.data.TaskEntity
import kotlinx.coroutines.delay
import java.time.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TaskAiApp(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var route by rememberSaveable { mutableStateOf("home") }
    var filter by rememberSaveable { mutableStateOf("ALL") }
    var editTask by remember { mutableStateOf<TaskEntity?>(null) }
    var isNew by remember { mutableStateOf(false) }
    val snackbars = remember { SnackbarHostState() }
    var now by remember { mutableStateOf(ZonedDateTime.now(ZoneId.of(settings.zoneId))) }
    LaunchedEffect(settings.zoneId) { while (true) { now = ZonedDateTime.now(ZoneId.of(settings.zoneId)); delay(30_000) } }
    LaunchedEffect(notice) {
        notice?.let {
            snackbars.showSnackbar(it)
            if (vm.notice.value == it) vm.notice.value = null
        }
    }
    val tabs = listOf("home" to "Início", "tasks" to "Tarefas", "chat" to "Assistente", "calendar" to "Calendário", "more" to "Mais")
    val icons = listOf(Icons.Outlined.SpaceDashboard, Icons.Outlined.Checklist, Icons.Outlined.AutoAwesome, Icons.Outlined.CalendarMonth, Icons.Outlined.GridView)
    val subpage = route !in tabs.map { it.first }
    BackHandler(route != "home") { route = if (subpage) "more" else "home" }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            if (route != "home") TopAppBar(
                title = { Text(when (route) { "tasks" -> "Suas tarefas"; "chat" -> "Assistente IA"; "calendar" -> "Calendário"; "more" -> "Seu espaço"; "settings" -> "Configurações"; "organize" -> "Categorias e projetos"; "logs" -> "Histórico de atividades"; else -> "Backup e restauração" }) },
                navigationIcon = { if (subpage) IconButton(onClick = { route = "more" }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Voltar") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                tabs.forEachIndexed { i, (key, title) ->
                    NavigationBarItem(selected = route == key || (key == "more" && subpage), onClick = { route = key },
                        icon = { Icon(icons[i], null) }, label = { Text(title, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer))
                }
            }
        },
        floatingActionButton = {
            if (route in listOf("home", "tasks", "calendar")) FloatingActionButton(onClick = {
                isNew = true; editTask = TaskEntity(title = "", reminderMinutes = settings.defaultReminderMinutes)
            }, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) { Icon(Icons.Outlined.Add, "Criar tarefa") }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.fillMaxSize()) {
                when (route) {
                    "home" -> DashboardScreen(tasks, categories, settings.userName, now, onChat = { route = "chat" }, onFilter = { filter = it; route = "tasks" }, onEdit = { isNew = false; editTask = it }, onToggle = vm::toggleTask)
                    "tasks" -> TasksScreen(tasks, categories, projects, now.toLocalDate(), filter, { filter = it }, { isNew = false; editTask = it }, vm::toggleTask)
                    "calendar" -> CalendarScreen(tasks, categories, now.toLocalDate(), { isNew = false; editTask = it }, vm::toggleTask)
                    "chat" -> ChatScreen(vm, onSettings = { route = "settings" })
                    "more" -> MoreScreen { route = it }
                    "settings" -> SettingsScreen(vm)
                    "organize" -> OrganizeScreen(vm)
                    "logs" -> ActivityScreen(vm, settings.zoneId)
                    "backup" -> BackupScreen(vm)
                }
            }
        }
    }
    editTask?.let { task ->
        TaskEditor(task, isNew, categories, projects, busy,
            onDismiss = { editTask = null },
            onSave = { vm.saveTask(it, isNew) { editTask = null } },
            onDelete = { vm.deleteTask(task) { editTask = null } })
    }
}
