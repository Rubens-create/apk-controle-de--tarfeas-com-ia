package com.taskai.app

import android.app.Application
import com.taskai.app.ai.AssistantEngine
import com.taskai.app.data.AppDatabase
import com.taskai.app.data.TaskRepository
import com.taskai.app.infra.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

class TaskAiApplication : Application() {
    val database by lazy { AppDatabase.get(this) }
    val repository by lazy { TaskRepository(database) }
    val settingsStore by lazy { SettingsStore(this) }
    val assistant by lazy { AssistantEngine(repository, { settingsStore.settings.value }) }
    val backup by lazy { BackupService(database) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        val scheduler = ReminderScheduler(this)
        scheduler.start()
        scope.launch {
            combine(repository.tasks, settingsStore.settings) { tasks, settings -> tasks to settings }
                .collect { (tasks, settings) -> scheduler.sync(tasks, settings) }
        }
    }
}
