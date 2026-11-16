package com.taskai.app.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, application = android.app.Application::class)
class TaskRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: TaskRepository
    private var clock = 1800000000000L

    @Before fun setUp() {
        db = AppDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        repository = TaskRepository(db) { clock }
    }
    @After fun tearDown() { db.close() }

    @Test fun categoryAndProjectNamesAndColorsMatchTheBackupFormat() = runBlocking {
        val category = repository.saveCategory(CategoryEntity(name = "C".repeat(200), color = "#80F282AF"))
        val project = repository.saveProject(ProjectEntity(name = "P".repeat(200), color = "#FF123456"))
        assertEquals("#80F282AF", repository.categories.first().single().color)
        assertEquals(project, repository.projects.first().single())
        expectFailure { repository.saveCategory(category.copy(name = "C".repeat(201))) }
        expectFailure { repository.saveProject(project.copy(name = "P".repeat(201))) }
    }

    @Test fun rejectsMessagesThatCannotRoundTripThroughTheBackupFormat() = runBlocking {
        val conversation = repository.createConversation()
        val count = repository.logs.first().size
        expectFailure { repository.addMessage(conversation.id, "user", "x".repeat(100001)) }
        assertTrue(repository.getMessages(conversation.id).isEmpty())
        assertEquals(count, repository.logs.first().size)
    }

    @Test fun databaseForeignKeysRejectOrphansEvenWhenBypassingTheRepository() = runBlocking {
        try {
            db.dao().insertTask(TaskEntity(title = "Órfã", categoryId = "ausente"))
            fail("O banco deve rejeitar uma categoria inexistente.")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        try {
            db.dao().insertTask(TaskEntity(title = "Órfã", projectId = "ausente"))
            fail("O banco deve rejeitar um projeto inexistente.")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        try {
            db.dao().insertMessage(MessageEntity(conversationId = "ausente", role = "user", content = "Oi"))
            fail("O banco deve rejeitar uma conversa inexistente.")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertTrue(repository.listTasks().isEmpty())
        assertTrue(db.dao().getAllMessages().isEmpty())
    }

    @Test fun logInsertionFailureRollsBackTheTaskWrite() = runBlocking {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_audit BEFORE INSERT ON activity_logs BEGIN SELECT RAISE(ABORT, 'audit unavailable'); END")
        }
        try {
            repository.createTask(TaskEntity(title = "Não pode ficar parcialmente salva"))
            fail("A transação deve falhar se o registro de atividade não for salvo.")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertTrue(repository.listTasks().isEmpty())
        assertTrue(repository.logs.first().isEmpty())
    }

    @Test fun duplicatesUseUnicodeWhitespaceAndCaseNormalizationWithoutPartialLogs() = runBlocking {
        val original = repository.createTask(TaskEntity(title = "CAFÉ   da manhã", dueDate = "2026-11-18", dueTime = "09:00"))
        expectFailure { repository.createTask(TaskEntity(title = " cafe\u0301\tDA\u00a0MANHÃ ", dueDate = "2026-11-18", dueTime = "09:00")) }
        assertEquals(1, repository.listTasks().size)
        assertEquals(1, repository.logs.first().size)
        repository.createTask(TaskEntity(title = original.title, dueDate = original.dueDate, dueTime = "10:00"))
        val other = repository.createTask(TaskEntity(title = "Outra", dueDate = original.dueDate, dueTime = original.dueTime))
        expectFailure { repository.updateTask(other.copy(title = original.title)) }
        assertEquals("Outra", repository.getTask(other.id)?.title)
        expectFailure { repository.createTask(original.copy(title = "Mesmo ID")) }
    }

    @Test fun rejectsInvalidAndMissingWritesAndDeletesAllOrNothing() = runBlocking {
        val task = repository.createTask(TaskEntity(title = "Preservada"))
        val baseline = repository.logs.first().size
        expectFailure { repository.createTask(TaskEntity(title = " ")) }
        expectFailure { repository.createTask(TaskEntity(title = "Inválida", categoryId = "ausente")) }
        expectFailure { repository.createTask(TaskEntity(title = "Inválida", projectId = "ausente")) }
        expectFailure { repository.updateTask(task.copy(id = "ausente")) }
        expectFailure { repository.deleteTasks(listOf(task.id, "ausente")) }
        expectFailure { repository.deleteTasks(listOf(task.id, task.id)) }
        expectFailure { repository.deleteTasks(listOf(" ")) }
        assertEquals(listOf(task), repository.listTasks())
        assertEquals(baseline, repository.logs.first().size)
        assertEquals(0, repository.deleteTasks(emptyList()))
        assertEquals(baseline, repository.logs.first().size)
    }

    @Test fun completionAndReopeningAreIdempotentAndRecurrenceNeverSpawnsTwice() = runBlocking {
        val original = repository.createTask(TaskEntity(title = "Medicação", dueDate = "2026-11-18", dueTime = "09:00", recurrenceRule = "FREQ=DAILY", reminderEnabled = true, reminderDatetime = clock))
        val completed = repository.completeTask(original.id)
        assertEquals("COMPLETED", completed.status)
        assertEquals(clock, completed.completedAt)
        val next = repository.listTasks().single { it.status == "PENDING" }
        assertEquals("2026-11-19", next.dueDate)
        assertEquals("09:00", next.dueTime)
        assertEquals(original.recurrenceRule, next.recurrenceRule)
        assertTrue(next.reminderEnabled)
        assertNull(next.reminderDatetime)
        val logCount = repository.logs.first().size
        assertEquals(completed, repository.completeTask(original.id))
        assertEquals(logCount, repository.logs.first().size)
        val reopened = repository.reopenTask(original.id)
        assertEquals("PENDING", reopened.status)
        assertNull(reopened.completedAt)
        val reopenedLogs = repository.logs.first().size
        assertEquals(reopened, repository.reopenTask(original.id))
        assertEquals(reopenedLogs, repository.logs.first().size)
        repository.deleteTasks(listOf(next.id))
        repository.completeTask(original.id)
        assertEquals(1, repository.listTasks().size)
        expectFailure { repository.completeTask("ausente") }
        expectFailure { repository.reopenTask("ausente") }
    }

    @Test fun concurrentCompletionsProduceExactlyOneNextOccurrence() = runBlocking {
        val task = repository.createTask(TaskEntity(title = "Exercício", dueDate = "2026-11-18", recurrenceRule = "FREQ=WEEKLY;BYDAY=MO,WE"))
        kotlinx.coroutines.coroutineScope {
            (1..8).map { async(kotlinx.coroutines.Dispatchers.Default) { repository.completeTask(task.id) } }.forEach { it.await() }
        }
        assertEquals(2, repository.listTasks().size)
        assertEquals(1, repository.listTasks().count { it.status == "PENDING" })
    }

    @Test fun updateStatusUsesTheSameCompletionAndRecurrenceRules() = runBlocking {
        val task = repository.createTask(TaskEntity(title = "Água", dueDate = "2026-11-18", recurrenceRule = "FREQ=DAILY"))
        val saved = repository.updateTask(task.copy(status = "COMPLETED"))
        assertEquals(clock, saved.completedAt)
        assertEquals(2, repository.listTasks().size)
        repository.updateTask(saved.copy(status = "PENDING"))
        assertNull(repository.getTask(task.id)?.completedAt)
        repository.updateTask(repository.getTask(task.id)!!.copy(status = "COMPLETED"))
        assertEquals(2, repository.listTasks().size)
    }

    @Test fun categoryAndProjectRelationshipsSurviveEditsAndDetachOnDelete() = runBlocking {
        val category = repository.saveCategory(CategoryEntity(name = "  Saúde  ", icon = "favorite"))
        val project = repository.saveProject(ProjectEntity(name = "  Pessoal  ", description = "Metas"))
        assertEquals("Saúde", category.name)
        assertEquals("Pessoal", project.name)
        val task = repository.createTask(TaskEntity(title = "Consulta", categoryId = category.id, projectId = project.id))
        clock += 1000
        val editedCategory = repository.saveCategory(category.copy(name = "Bem-estar", color = "#123456"))
        val editedProject = repository.saveProject(project.copy(name = "Vida", color = "#ABCDEF"))
        assertEquals(listOf(editedCategory), repository.categories.first())
        assertEquals(listOf(editedProject), repository.projects.first())
        assertEquals(task, repository.getTask(task.id))
        repository.deleteCategory(category.id)
        assertNull(repository.getTask(task.id)?.categoryId)
        assertEquals(project.id, repository.getTask(task.id)?.projectId)
        repository.deleteProject(project.id)
        assertNull(repository.getTask(task.id)?.projectId)
        assertTrue(repository.categories.first().isEmpty())
        assertTrue(repository.projects.first().isEmpty())
        expectFailure { repository.deleteCategory(category.id) }
        expectFailure { repository.deleteProject(project.id) }
    }

    @Test fun validatesCategoryAndProjectFieldsAndNames() = runBlocking {
        val cat = repository.saveCategory(CategoryEntity(name = "Saúde"))
        expectFailure { repository.saveCategory(CategoryEntity(name = " SAÚDE ")) }
        expectFailure { repository.saveCategory(cat.copy(name = " ")) }
        expectFailure { repository.saveCategory(cat.copy(color = "pink")) }
        expectFailure { repository.saveCategory(cat.copy(icon = "")) }
        val project = repository.saveProject(ProjectEntity(name = "Trabalho"))
        expectFailure { repository.saveProject(ProjectEntity(name = " trabalho ")) }
        expectFailure { repository.saveProject(project.copy(name = " ")) }
        expectFailure { repository.saveProject(project.copy(color = "#fff")) }
        expectFailure { repository.saveProject(project.copy(description = "x".repeat(20001))) }
        assertEquals(listOf(cat), repository.categories.first())
        assertEquals(listOf(project), repository.projects.first())
    }

    @Test fun conversationsPersistMessagesInInsertionOrderAndCascadeClear() = runBlocking {
        val conversation = repository.createConversation("  Planejamento  ")
        assertEquals("Planejamento", conversation.title)
        assertTrue(repository.getMessages(conversation.id).isEmpty())
        val first = repository.addMessage(conversation.id, "user", "Olá")
        val second = repository.addMessage(conversation.id, "assistant", "Olá! Como posso ajudar?")
        val third = repository.addMessage(conversation.id, "tool", "{\"ok\":true}")
        assertEquals(listOf(first, second, third), repository.getMessages(conversation.id))
        assertEquals(listOf(first, second, third), repository.messages(conversation.id).first())
        assertEquals(clock, repository.conversations.first().single().updatedAt)
        expectFailure { repository.createConversation(" ") }
        expectFailure { repository.addMessage("ausente", "user", "Oi") }
        expectFailure { repository.addMessage(conversation.id, "admin", "Oi") }
        expectFailure { repository.addMessage(conversation.id, "user", " ") }
        expectFailure { repository.getMessages("ausente") }
        assertTrue(repository.logs.first().any { it.entityType == "CONVERSATION" })
        assertFalse(repository.logs.first().any { it.details.contains("Como posso ajudar?") })
        repository.clearConversations()
        assertTrue(repository.conversations.first().isEmpty())
        assertTrue(db.dao().getAllMessages().isEmpty())
        assertTrue(repository.logs.first().any { it.action == "CLEAR" })
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try { block(); fail("Esperava uma validação em português.") }
        catch (error: IllegalArgumentException) { assertTrue(error.message?.isNotBlank() == true) }
    }

    @Test fun emptyDatabaseAndRealCrudArePersistedAndObserved() = runBlocking {
        assertTrue(repository.tasks.first().isEmpty())
        assertTrue(repository.categories.first().isEmpty())
        assertTrue(repository.projects.first().isEmpty())
        assertTrue(repository.logs.first().isEmpty())
        assertTrue(repository.conversations.first().isEmpty())
        val created = repository.createTask(TaskEntity(title = "  Comprar pão  ", description = "Integral"))
        assertEquals("Comprar pão", created.title)
        assertEquals(clock, created.createdAt)
        assertEquals(created, repository.getTask(created.id))
        assertEquals(listOf(created), repository.tasks.first())
        clock += 1000
        val updated = repository.updateTask(created.copy(title = "Comprar café"))
        assertEquals(clock, updated.updatedAt)
        assertEquals(created.createdAt, updated.createdAt)
        assertEquals(listOf(updated), repository.listTasks())
        assertEquals(2, repository.logs.first().size)
        assertEquals(1, repository.deleteTasks(listOf(created.id)))
        assertNull(repository.getTask(created.id))
        assertTrue(repository.listTasks().isEmpty())
        assertEquals(3, repository.logs.first().size)
    }
}
