package com.taskai.app.infra

import android.content.Context

import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import com.taskai.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class BackupServiceTest {
    private lateinit var db: AppDatabase
    private lateinit var service: BackupService
    @Before fun setup() {
        db = AppDatabase.createInMemory(ApplicationProvider.getApplicationContext<Context>())
        service = BackupService(db)
    }
    @After fun close() { db.close() }

    @Test fun `round trip restores every entity and excludes configuration`() = runBlocking {
        val repository = TaskRepository(db)
        val category = repository.saveCategory(CategoryEntity(id = "cat", name = "Saúde"))
        val project = repository.saveProject(ProjectEntity(id = "project", name = "Casa"))
        repository.createTask(TaskEntity(id = "task", title = "Consulta", categoryId = category.id, projectId = project.id))
        val chat = repository.createConversation("Pessoal")
        repository.addMessage(chat.id, "user", "Minha mensagem")
        val backup = service.exportJson()
        assertFalse(backup.contains("apiKey"))
        assertFalse(backup.contains("endpoint"))
        assertTrue(service.inspect(backup).contains("1 tarefas"))
        repository.deleteTasks(listOf("task"))
        repository.clearConversations()
        service.restore(backup, "RESTAURAR")
        assertEquals(JsonParser.parseString(backup).asJsonObject.apply { remove("exportedAt") }, JsonParser.parseString(service.exportJson()).asJsonObject.apply { remove("exportedAt") })
    }

    @Test fun `validation and exact confirmation leave current graph untouched`() = runBlocking {
        val repository = TaskRepository(db)
        repository.createTask(TaskEntity(id = "existing", title = "Não apagar"))
        val baseline = service.exportJson()
        val invalid = JsonParser.parseString(baseline).asJsonObject.apply {
            getAsJsonArray("tasks")[0].asJsonObject.addProperty("categoryId", "missing")
        }.toString()
        suspend fun rejected(block: suspend () -> Unit) {
            try { block(); fail("Expected validation failure") } catch (_: IllegalArgumentException) { }
            assertNotNull(repository.getTask("existing"))
        }
        rejected { service.restore(baseline, " restaurar ") }
        rejected { service.restore(invalid, "RESTAURAR") }
        rejected { service.restore(baseline.replace("\"version\":1", "\"version\":999"), "RESTAURAR") }
        rejected { service.restore("{broken", "RESTAURAR") }
        rejected { service.restore(baseline.dropLast(1) + ",\"settings\":{\"apiKey\":\"unsafe\"}}", "RESTAURAR") }
    }

    @Test fun `restore enforces repository Unicode normalized uniqueness`() = runBlocking {
        val repository = TaskRepository(db)
        repository.createTask(TaskEntity(id = "task", title = "Original"))
        repository.saveCategory(CategoryEntity(id = "category", name = "A"))
        repository.saveProject(ProjectEntity(id = "project", name = "A"))
        val original = JsonParser.parseString(service.exportJson()).asJsonObject
        val invalid = listOf(
            original.deepCopy().apply { getAsJsonArray("tasks").add(getAsJsonArray("tasks")[0].deepCopy().asJsonObject.apply { addProperty("id", "other"); addProperty("title", "Ｏｒｉｇｉｎａｌ") }) },
            original.deepCopy().apply { getAsJsonArray("categories").add(getAsJsonArray("categories")[0].deepCopy().asJsonObject.apply { addProperty("id", "other"); addProperty("name", "Ａ") }) },
            original.deepCopy().apply { getAsJsonArray("projects").add(getAsJsonArray("projects")[0].deepCopy().asJsonObject.apply { addProperty("id", "other"); addProperty("name", "Ａ") }) }
        )
        invalid.forEach { payload ->
            try { service.restore(payload.toString(), "RESTAURAR"); fail("Normalized duplicate accepted") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(1, repository.listTasks().size)
    }

    @Test fun `database failure after deletion rolls every table back`() = runBlocking {
        val repository = TaskRepository(db)
        repository.createTask(TaskEntity(id = "task", title = "Preservada"))
        val before = JsonParser.parseString(service.exportJson()).asJsonObject.apply { remove("exportedAt") }
        val incoming = before.deepCopy().apply {
            addProperty("exportedAt", 1)
            getAsJsonArray("tasks")[0].asJsonObject.addProperty("title", "FAIL_INSERT")
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_restore BEFORE INSERT ON tasks WHEN NEW.title = 'FAIL_INSERT' BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        }
        try { service.restore(incoming.toString(), "RESTAURAR"); fail("Expected database failure") } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(before, JsonParser.parseString(service.exportJson()).asJsonObject.apply { remove("exportedAt") })
    }

    @Test fun `duplicate JSON names trailing input and excessive size are rejected`() = runBlocking {
        val original = service.exportJson()
        val invalid = listOf(original.dropLast(1) + ",\"version\":1}", original + " {}", " ".repeat(10 * 1024 * 1024 + 1))
        invalid.forEach { text ->
            try { service.inspect(text); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun `export refuses graphs that exceed its own restore row cap`() = runBlocking {
        val repository = TaskRepository(db)
        repository.createConversation("Primeira conversa")
        repository.createConversation("Segunda conversa")
        // Two conversations plus their real activity logs exceed this injected shared cap.
        val limited = BackupService(db, maxRows = 3)
        try {
            limited.exportJson()
            fail("Export must reject a graph that cannot be restored by the same app.")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun `duplicate ids bad enums and explicit null fields are rejected before restore`() = runBlocking {
        val repository = TaskRepository(db)
        repository.createTask(TaskEntity(id = "task", title = "Original"))
        val original = JsonParser.parseString(service.exportJson()).asJsonObject
        val invalid = listOf(
            original.deepCopy().apply { getAsJsonArray("tasks").add(getAsJsonArray("tasks")[0].deepCopy()) },
            original.deepCopy().apply { getAsJsonArray("tasks")[0].asJsonObject.addProperty("status", "INVALID") },
            original.deepCopy().apply { getAsJsonArray("tasks")[0].asJsonObject.add("title", com.google.gson.JsonNull.INSTANCE) },
            original.deepCopy().apply { getAsJsonArray("tasks")[0].asJsonObject.addProperty("reminderEnabled", "false") }
        )
        invalid.forEach { json ->
            try { service.restore(json.toString(), "RESTAURAR"); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
        }
        assertEquals("Original", repository.getTask("task")?.title)
    }
}
