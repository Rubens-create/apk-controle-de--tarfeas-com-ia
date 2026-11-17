package com.taskai.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.taskai.app.TaskAiApplication
import com.taskai.app.ai.HttpAiProvider
import com.taskai.app.data.*
import com.taskai.app.infra.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class SpokenReplyEvent(val conversationId: String?, val text: String)

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TaskAiApplication
    private val repo = app.repository
    val settings = app.settingsStore.settings
    val tasks = repo.tasks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val categories = repo.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val projects = repo.projects.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val logs = repo.logs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val conversations = repo.conversations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _conversation = MutableStateFlow<String?>(null)
    val conversation = _conversation.asStateFlow()
    val messages = _conversation.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.messages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val notice = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val chatting = MutableStateFlow(false)
    val pendingConfirmation = MutableStateFlow(false)
    val voicePreferenceSaving = MutableStateFlow(false)
    private val _spokenReplies = MutableSharedFlow<SpokenReplyEvent>(extraBufferCapacity = 1)
    val spokenReplies = _spokenReplies.asSharedFlow()
    val restorePreview = MutableStateFlow<String?>(null)
    private var importedJson: String? = null

    init { viewModelScope.launch { val old = repo.conversations.first().firstOrNull(); if (_conversation.value == null) _conversation.value = old?.id } }

    private fun action(block: suspend () -> Unit) = viewModelScope.launch {
        busy.value = true
        try { block() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { notice.value = e.message ?: "Não foi possível concluir a operação." }
        finally { busy.value = false }
    }

    fun saveTask(task: TaskEntity, isNew: Boolean, onSaved: () -> Unit) = action {
        if (isNew) repo.createTask(task) else repo.updateTask(task)
        notice.value = "Tarefa salva."; onSaved()
    }
    fun toggleTask(task: TaskEntity) = action {
        if (task.status == "COMPLETED") repo.reopenTask(task.id) else repo.completeTask(task.id)
    }
    fun deleteTask(task: TaskEntity, onDeleted: () -> Unit) = action {
        repo.deleteTasks(listOf(task.id)); notice.value = "Tarefa excluída."; onDeleted()
    }
    fun saveCategory(value: CategoryEntity, done: () -> Unit) = action { repo.saveCategory(value); done() }
    fun deleteCategory(id: String) = action { repo.deleteCategory(id) }
    fun saveProject(value: ProjectEntity, done: () -> Unit) = action { repo.saveProject(value); done() }
    fun deleteProject(id: String) = action { repo.deleteProject(id) }
    fun saveSettings(value: AppSettings) = action { app.settingsStore.save(value); notice.value = "Configurações salvas." }
    fun testConnection(value: AppSettings) = action { notice.value = HttpAiProvider().testConnection(value) }

    fun setSpokenRepliesEnabled(enabled: Boolean) {
        if (voicePreferenceSaving.value) return
        voicePreferenceSaving.value = true
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { app.settingsStore.setSpokenRepliesEnabled(enabled) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice.value = "Não foi possível salvar a preferência de voz." }
            finally { voicePreferenceSaving.value = false }
        }
    }

    private suspend fun announce(id: String?, text: String) {
        if (settings.value.spokenRepliesEnabled && text.isNotBlank()) {
            _spokenReplies.emit(SpokenReplyEvent(id, text))
        }
    }

    fun selectConversation(id: String) { if (chatting.value) return; _conversation.value?.let(app.assistant::cancelPending); _conversation.value = id; pendingConfirmation.value = false }
    fun newConversation() = action {
        if (!chatting.value) { _conversation.value?.let(app.assistant::cancelPending); _conversation.value = repo.createConversation().id; pendingConfirmation.value = false }
    }
    fun send(text: String) {
        if (text.isBlank() || chatting.value || pendingConfirmation.value) return
        viewModelScope.launch {
            chatting.value = true
            try {
                if (settings.value.apiKey.isBlank()) throw IllegalArgumentException("Configure a chave de API em Mais > Configurações antes de usar a IA.")
                val id = _conversation.value ?: repo.createConversation(text.take(60)).id.also { _conversation.value = it }
                val result = app.assistant.send(id, text)
                pendingConfirmation.value = result.pendingConfirmation
                announce(id, result.spokenText)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = e.message ?: "Falha ao consultar a IA. Nenhuma ação foi presumida."
                notice.value = message
                announce(_conversation.value, message)
            }
            finally { chatting.value = false }
        }
    }
    fun confirmAction() {
        val id = _conversation.value ?: return
        if (chatting.value) return
        viewModelScope.launch {
            chatting.value = true
            try {
                val result = app.assistant.confirm(id)
                pendingConfirmation.value = result.pendingConfirmation
                announce(id, result.spokenText)
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { pendingConfirmation.value = false; notice.value = e.message ?: "Falha na confirmação." }
            finally { chatting.value = false }
        }
    }
    fun cancelAction() { _conversation.value?.let(app.assistant::cancelPending); pendingConfirmation.value = false; notice.value = "Operação cancelada." }
    fun clearHistory() = action {
        check(!chatting.value) { "Aguarde o assistente terminar." }
        _conversation.value?.let(app.assistant::cancelPending)
        repo.clearConversations(); _conversation.value = null; pendingConfirmation.value = false
        notice.value = "Histórico de conversas removido. As tarefas foram mantidas."
    }
    fun exportBackup(uri: Uri) = action {
        withContext(Dispatchers.IO) {
            val json = app.backup.exportJson()
            app.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(json) }
                ?: error("Não foi possível abrir o destino do backup.")
        }
        notice.value = "Backup exportado. Guarde o arquivo em um local privado."
    }
    fun inspectBackup(uri: Uri) = action {
        withContext(Dispatchers.IO) {
            val bytes = app.contentResolver.openInputStream(uri)?.use { BackupInput.read(it, 10 * 1024 * 1024) }
                ?: error("Não foi possível abrir o arquivo.")
            require(bytes.size <= 10 * 1024 * 1024) { "O backup excede 10 MB." }
            val json = bytes.toString(Charsets.UTF_8)
            val preview = app.backup.inspect(json)
            importedJson = json; restorePreview.value = preview
        }
    }
    fun cancelRestore() { importedJson = null; restorePreview.value = null }
    fun restore(confirmation: String) = action {
        check(!chatting.value) { "Aguarde o assistente terminar antes de restaurar." }
        val json = importedJson ?: error("Selecione um backup.")
        app.backup.restore(json, confirmation)
        _conversation.value?.let(app.assistant::cancelPending); _conversation.value = null; pendingConfirmation.value = false
        cancelRestore(); notice.value = "Backup restaurado."
    }
}
