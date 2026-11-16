package com.taskai.app.data

import androidx.room.withTransaction
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Every mutation and its audit entry commit together. No demo/seed data is ever inserted. */
class TaskRepository(private val db: AppDatabase, private val now: () -> Long = { System.currentTimeMillis() }) {
    private val dao = db.dao()
    val tasks: Flow<List<TaskEntity>> = dao.observeTasks()
    val categories: Flow<List<CategoryEntity>> = dao.observeCategories()
    val projects: Flow<List<ProjectEntity>> = dao.observeProjects()
    val logs: Flow<List<ActivityLogEntity>> = dao.observeLogs()
    val conversations: Flow<List<ConversationEntity>> = dao.observeConversations()
    fun messages(conversationId: String): Flow<List<MessageEntity>> = dao.observeMessages(conversationId)

    suspend fun createTask(task: TaskEntity): TaskEntity = db.withTransaction {
        require(dao.getTask(task.id) == null) { "Já existe uma tarefa com este identificador." }
        val timestamp = now()
        val saved = task.copy(title = task.title.trim(), createdAt = timestamp, updatedAt = timestamp,
            completedAt = if (task.status == "COMPLETED") timestamp else task.completedAt)
        validateTask(saved)
        dao.insertTask(saved)
        log("CREATE", "TASK", saved.id, "Tarefa criada: ${saved.title}", timestamp)
        saved
    }

    suspend fun updateTask(task: TaskEntity): TaskEntity = db.withTransaction {
        val previous = taskOrThrow(task.id)
        val timestamp = now()
        val saved = task.copy(title = task.title.trim(), createdAt = previous.createdAt, updatedAt = timestamp,
            completedAt = if (task.status == "COMPLETED") previous.completedAt ?: timestamp else null)
        validateTask(saved)
        dao.updateTask(saved)
        log("UPDATE", "TASK", saved.id, "Tarefa atualizada: ${saved.title}", timestamp)
        if (previous.status != "COMPLETED" && saved.status == "COMPLETED") createNextOccurrence(saved, timestamp)
        saved
    }

    suspend fun getTask(id: String): TaskEntity? = dao.getTask(id)
    suspend fun listTasks(): List<TaskEntity> = dao.getTasks()

    suspend fun deleteTasks(ids: List<String>): Int = db.withTransaction {
        require(ids.size <= 10000) { "Selecione no máximo 10000 tarefas por operação." }
        require(ids.distinct().size == ids.size) { "A seleção contém tarefas repetidas." }
        require(ids.all(String::isNotBlank)) { "A seleção contém um identificador inválido." }
        val selected = ids.map { taskOrThrow(it) }
        val timestamp = now()
        // Stay below SQLite's bind-parameter limit; the enclosing transaction remains all-or-nothing.
        val count = ids.chunked(900).sumOf { dao.deleteTasks(it) }
        selected.forEach { log("DELETE", "TASK", it.id, "Tarefa excluída: ${it.title}", timestamp) }
        count
    }

    suspend fun completeTask(id: String): TaskEntity = db.withTransaction {
        val task = taskOrThrow(id)
        if (task.status == "COMPLETED") return@withTransaction task
        val timestamp = now()
        val completed = task.copy(status = "COMPLETED", updatedAt = timestamp, completedAt = timestamp)
        TaskRules.validate(completed)
        dao.updateTask(completed)
        log("COMPLETE", "TASK", id, "Tarefa concluída: ${task.title}", timestamp)
        createNextOccurrence(completed, timestamp)
        completed
    }

    suspend fun reopenTask(id: String): TaskEntity = db.withTransaction {
        val task = taskOrThrow(id)
        if (task.status == "PENDING") return@withTransaction task
        val reopened = task.copy(status = "PENDING", completedAt = null, updatedAt = now())
        validateTask(reopened)
        dao.updateTask(reopened)
        log("REOPEN", "TASK", id, "Tarefa reaberta: ${task.title}", reopened.updatedAt)
        reopened
    }

    suspend fun saveCategory(category: CategoryEntity): CategoryEntity = db.withTransaction {
        validateNamedEntity(category.id, category.name, category.color)
        require(category.icon.isNotBlank() && category.icon.length <= 100) { "Ícone de categoria inválido." }
        require(dao.getCategories().none { it.id != category.id && normalized(it.name) == normalized(category.name) }) { "Já existe uma categoria com este nome." }
        val previous = dao.getCategory(category.id)
        val timestamp = now()
        val saved = category.copy(name = category.name.trim(), createdAt = previous?.createdAt ?: timestamp)
        if (previous == null) dao.insertCategory(saved) else dao.updateCategory(saved)
        log(if (previous == null) "CREATE" else "UPDATE", "CATEGORY", saved.id, "Categoria salva: ${saved.name}", timestamp)
        saved
    }

    suspend fun deleteCategory(id: String) = db.withTransaction {
        val category = requireNotNull(dao.getCategory(id)) { "Categoria não encontrada." }
        dao.deleteCategory(id)
        log("DELETE", "CATEGORY", id, "Categoria excluída: ${category.name}. As tarefas foram preservadas.", now())
    }

    suspend fun saveProject(project: ProjectEntity): ProjectEntity = db.withTransaction {
        validateNamedEntity(project.id, project.name, project.color)
        require(project.description.length <= 20000) { "A descrição do projeto deve ter até 20000 caracteres." }
        require(dao.getProjects().none { it.id != project.id && normalized(it.name) == normalized(project.name) }) { "Já existe um projeto com este nome." }
        val previous = dao.getProject(project.id)
        val timestamp = now()
        val saved = project.copy(name = project.name.trim(), createdAt = previous?.createdAt ?: timestamp)
        if (previous == null) dao.insertProject(saved) else dao.updateProject(saved)
        log(if (previous == null) "CREATE" else "UPDATE", "PROJECT", saved.id, "Projeto salvo: ${saved.name}", timestamp)
        saved
    }

    suspend fun deleteProject(id: String) = db.withTransaction {
        val project = requireNotNull(dao.getProject(id)) { "Projeto não encontrado." }
        dao.deleteProject(id)
        log("DELETE", "PROJECT", id, "Projeto excluído: ${project.name}. As tarefas foram preservadas.", now())
    }

    suspend fun createConversation(title: String = "Nova conversa"): ConversationEntity = db.withTransaction {
        require(title.isNotBlank() && title.length <= 500) { "O título da conversa deve conter de 1 a 500 caracteres." }
        val timestamp = now()
        val conversation = ConversationEntity(title = title.trim(), createdAt = timestamp, updatedAt = timestamp)
        dao.insertConversation(conversation)
        log("CREATE", "CONVERSATION", conversation.id, "Conversa criada.", timestamp)
        conversation
    }

    suspend fun addMessage(conversationId: String, role: String, content: String): MessageEntity = db.withTransaction {
        val conversation = requireNotNull(dao.getConversation(conversationId)) { "Conversa não encontrada." }
        require(role in setOf("user", "assistant", "tool", "system")) { "Papel da mensagem inválido." }
        require(content.isNotBlank() && content.length <= 100000) { "A mensagem deve conter de 1 a 100000 caracteres." }
        val timestamp = now()
        val message = MessageEntity(conversationId = conversationId, role = role, content = content, createdAt = timestamp)
        dao.insertMessage(message)
        dao.updateConversation(conversation.copy(updatedAt = timestamp))
        // Content is deliberately not copied into the audit log, which could expose private conversations twice.
        log("MESSAGE", "CONVERSATION", conversationId, "Mensagem registrada ($role).", timestamp)
        message
    }

    suspend fun getMessages(conversationId: String): List<MessageEntity> = db.withTransaction {
        requireNotNull(dao.getConversation(conversationId)) { "Conversa não encontrada." }
        dao.getMessages(conversationId)
    }

    suspend fun clearConversations() = db.withTransaction {
        val count = dao.getConversations().size
        if (count == 0) return@withTransaction
        dao.clearConversations()
        log("CLEAR", "CONVERSATION", "*", "$count conversa(s) e suas mensagens foram excluídas.", now())
    }

    private suspend fun taskOrThrow(id: String): TaskEntity = requireNotNull(dao.getTask(id)) { "Tarefa não encontrada." }

    private suspend fun validateTask(task: TaskEntity) {
        TaskRules.validate(task)
        task.categoryId?.let { requireNotNull(dao.getCategory(it)) { "Categoria não encontrada." } }
        task.projectId?.let { requireNotNull(dao.getProject(it)) { "Projeto não encontrado." } }
        require(findDuplicate(task) == null) { "Já existe uma tarefa pendente com o mesmo título, data e horário." }
    }

    private suspend fun findDuplicate(task: TaskEntity): TaskEntity? {
        if (task.status != "PENDING") return null
        val title = normalized(task.title)
        return dao.getTasks().firstOrNull {
            it.id != task.id && it.status == "PENDING" && normalized(it.title) == title && it.dueDate == task.dueDate && it.dueTime == task.dueTime
        }
    }

    private suspend fun createNextOccurrence(completed: TaskEntity, timestamp: Long) {
        val rule = completed.recurrenceRule ?: return
        if (dao.hasRecurrenceSuccessor(completed.id)) return
        val date = Recurrence.nextDate(TaskRules.parseDate(requireNotNull(completed.dueDate) { "A tarefa recorrente exige uma data." }), rule)
        val candidate = completed.copy(
            id = UUID.randomUUID().toString(), dueDate = date.toString(), status = "PENDING", completedAt = null,
            createdAt = timestamp, updatedAt = timestamp, reminderDatetime = null
        )
        val existing = findDuplicate(candidate)
        if (existing == null) {
            TaskRules.validate(candidate)
            dao.insertTask(candidate)
        }
        // This durable marker is exported with logs, preserving exactly-once behavior across restore/reopen/delete.
        log("RECURRENCE_CREATED", "TASK", completed.id, "Próxima ocorrência: ${existing?.id ?: candidate.id} em $date.", timestamp)
    }

    private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace(Regex("[\\p{javaWhitespace}\\p{Z}]+"), " ").trim().lowercase(Locale.ROOT)

    private fun validateNamedEntity(id: String, name: String, color: String) {
        require(id.isNotBlank() && id.length <= 200) { "Identificador inválido." }
        require(name.isNotBlank() && name.length <= 200) { "O nome deve conter de 1 a 200 caracteres." }
        require(Regex("#[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?").matches(color)) { "Cor inválida. Use #RRGGBB ou #AARRGGBB." }
    }

    private suspend fun log(action: String, entityType: String, entityId: String, details: String, timestamp: Long) {
        dao.insertLog(ActivityLogEntity(action = action, entityType = entityType, entityId = entityId, details = details, createdAt = timestamp))
    }
}
