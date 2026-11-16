package com.taskai.app.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import com.taskai.app.data.AppDatabase
import com.taskai.app.data.TaskRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(application=android.app.Application::class, sdk=[35], manifest=Config.NONE)
class ToolExecutorTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: TaskRepository
    @Before fun setup() { db = AppDatabase.createInMemory(ApplicationProvider.getApplicationContext<Context>()); repo = TaskRepository(db) }
    @After fun close() { db.close() }
    private fun call(name:String, args:String) = ToolCall("fixture-call",name,JsonParser.parseString(args).asJsonObject)
    @Test fun taskCrudUsesActualIdsAndPreservesUnspecifiedFields() = runBlocking {
        val executor = ToolExecutor(repo)
        val created = JsonParser.parseString(executor.execute(call("create_task","""{"title":"Consulta","due_date":"2026-11-17","due_time":"14:30","priority":"ALTA","reminder_enabled":true,"reminder_minutes":30}"""))).asJsonObject
        val id = created["data"].asJsonObject["id"].asString
        assertEquals("2026-11-17",repo.getTask(id)?.dueDate)
        val updateArgs = com.google.gson.JsonObject().apply { addProperty("id",id);addProperty("description","Documentos") }
        assertTrue(JsonParser.parseString(executor.execute(ToolCall("u","update_task",updateArgs))).asJsonObject["ok"].asBoolean)
        assertEquals("14:30",repo.getTask(id)?.dueTime)
        assertEquals("Documentos",repo.getTask(id)?.description)
        assertTrue(JsonParser.parseString(executor.execute(ToolCall("c","complete_task",com.google.gson.JsonObject().apply {addProperty("id",id)}))).asJsonObject["ok"].asBoolean)
        assertEquals("COMPLETED",repo.getTask(id)?.status)
        executor.execute(ToolCall("r","reopen_task",com.google.gson.JsonObject().apply {addProperty("id",id)}))
        assertEquals("PENDING",repo.getTask(id)?.status)
    }
    @Test fun destructiveTaskNeedsUiConfirmationAndExactIds() = runBlocking {
        val target = repo.createTask(com.taskai.app.data.TaskEntity(title="Excluir"))
        val other = repo.createTask(com.taskai.app.data.TaskEntity(title="Preservar"))
        val executor = ToolExecutor(repo)
        val op = ToolCall("d","delete_task",com.google.gson.JsonObject().apply {add("ids",com.google.gson.JsonArray().apply {add(target.id)})})
        val pending = JsonParser.parseString(executor.execute(op)).asJsonObject
        assertTrue(pending["pending_confirmation"].asBoolean)
        assertFalse(pending["ok"].asBoolean)
        assertEquals(target.id,pending["data"].asJsonArray.single().asJsonObject["id"].asString)
        assertNotNull(repo.getTask(target.id))
        assertTrue(JsonParser.parseString(executor.execute(op,confirmed=true)).asJsonObject["ok"].asBoolean)
        assertNull(repo.getTask(target.id))
        assertNotNull(repo.getTask(other.id))
    }
    @Test fun categoryAndProjectCrudRequiresExactUiConfirmedDeletes() = runBlocking {
        val executor=ToolExecutor(repo)
        listOf("category","project").forEach { type ->
            val created=JsonParser.parseString(executor.execute(call("create_$type","""{"name":"Pessoal"}"""))).asJsonObject
            assertTrue(created["ok"].asBoolean)
            val id=created["data"].asJsonObject["id"].asString
            val args=com.google.gson.JsonObject().apply {addProperty("id",id);addProperty("name","Trabalho")}
            assertTrue(JsonParser.parseString(executor.execute(ToolCall("u","update_$type",args))).asJsonObject["ok"].asBoolean)
            val plural=if(type=="category") "categories" else "projects"
            val listed=JsonParser.parseString(executor.execute(call("list_$plural","{}"))).asJsonObject
            assertEquals("Trabalho",listed["data"].asJsonArray.single().asJsonObject["name"].asString)
            val deletion=ToolCall("d","delete_$type",com.google.gson.JsonObject().apply {addProperty("id",id)})
            assertTrue(JsonParser.parseString(executor.execute(deletion)).asJsonObject["pending_confirmation"].asBoolean)
            assertTrue(JsonParser.parseString(executor.execute(deletion,true)).asJsonObject["ok"].asBoolean)
            assertEquals(0,JsonParser.parseString(executor.execute(call("list_$plural","{}"))).asJsonObject["data"].asJsonArray.size())
        }
    }
    @Test fun searchesAmbiguousMatchesWithoutSelectingAndSummarizesRealDates() = runBlocking {
        val executor=ToolExecutor(repo) { ZoneId.of("Pacific/Kiritimati") }
        val today=java.time.LocalDate.now(ZoneId.of("Pacific/Kiritimati"))
        val first=repo.createTask(com.taskai.app.data.TaskEntity(title="Reunião A",dueDate=today.toString()))
        repo.createTask(com.taskai.app.data.TaskEntity(title="Reunião B",dueDate=today.minusDays(1).toString()))
        val search=JsonParser.parseString(executor.execute(call("search_tasks","""{"query":"Reunião"}"""))).asJsonObject
        assertEquals(2,search["data"].asJsonArray.size())
        val listed=JsonParser.parseString(executor.execute(call("list_tasks","""{"filter":"TODAY"}"""))).asJsonObject
        assertEquals(first.id,listed["data"].asJsonArray.single().asJsonObject["id"].asString)
        val one=JsonParser.parseString(executor.execute(ToolCall("g","get_task",com.google.gson.JsonObject().apply {addProperty("id",first.id)}))).asJsonObject
        assertEquals(first.id,one["data"].asJsonObject["id"].asString)
        val summary=JsonParser.parseString(executor.execute(call("summary","{}"))).asJsonObject["data"].asJsonObject
        assertEquals(2,summary["total"].asInt);assertEquals(1,summary["today"].asInt);assertEquals(1,summary["overdue"].asInt)
        listOf("""{"title":"Inválida","due_date":"2026-02-30"}""", """{"title":"Inválida","due_time":"de manhã"}""", """{"title":"Inválida","sql":"DELETE FROM tasks"}""").forEach { args ->
            assertFalse(JsonParser.parseString(executor.execute(call("create_task",args))).asJsonObject["ok"].asBoolean)
        }
        assertEquals(2,repo.listTasks().size)
    }
    @Test fun storageFailureReturnsSafeJsonInsteadOfThrowingSqlDetails() = runBlocking {
        // Explicit corrupted-database fixture; no SQL capability exists in the production tool catalog.
        db.openHelper.writableDatabase.execSQL("DROP TABLE tasks")
        val result=JsonParser.parseString(ToolExecutor(repo).execute(call("list_tasks","{}"))).asJsonObject
        assertFalse(result["ok"].asBoolean);assertFalse(result["error"].asString.contains("SELECT",ignoreCase=true))
    }
    @Test fun largeReadResultsAreBoundedWithAccurateTotalsAndDeletePreviewIsCompact() = runBlocking {
        repeat(7) {repo.createTask(com.taskai.app.data.TaskEntity(title="Grande $it",description="x".repeat(19000)))}
        val executor=ToolExecutor(repo)
        val read=executor.execute(call("list_tasks","{}"));assertTrue(read.length<=120000)
        val json=JsonParser.parseString(read).asJsonObject
        assertEquals(7,json["total"].asInt);assertTrue(json["has_more"].asBoolean);assertEquals(json["data"].asJsonArray.size(),json["returned"].asInt)
        val deletion=ToolCall("d","delete_task",com.google.gson.JsonObject().apply {add("ids",com.google.gson.JsonArray().apply {repo.listTasks().forEach {add(it.id)}})})
        assertTrue(executor.execute(deletion).length<20000)
        assertEquals(7,repo.listTasks().size)
    }
    @Test fun createsRealTaskAndReturnsPersistedSnakeCaseReceipt() = runBlocking {
        val executor = ToolExecutor(repo) { ZoneId.of("America/Sao_Paulo") }
        val result = JsonParser.parseString(executor.execute(call("create_task","""{"title":"Consultar dentista"}"""))).asJsonObject
        assertTrue(result["ok"].asBoolean)
        val actual = repo.listTasks().single()
        assertEquals(actual.id,result["data"].asJsonObject["id"].asString)
        assertNull(actual.dueDate)
        assertFalse(actual.reminderEnabled)
        assertTrue(result["data"].asJsonObject.has("due_date"))
    }
}
