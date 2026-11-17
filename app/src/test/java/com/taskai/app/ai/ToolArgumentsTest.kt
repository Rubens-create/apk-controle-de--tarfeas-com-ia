package com.taskai.app.ai

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ToolArgumentsTest {
    @Test fun strictJsonRejectsDuplicateKeysAndExcessiveNesting() {
        listOf("""{"ids":["a"],"ids":["b"]}""", "[".repeat(100)+"0"+"]".repeat(100)).forEach { value ->
            try {strictJson(value);fail("Ambiguous or deep JSON must fail") } catch(_:IllegalArgumentException){}
        }
    }

    @Test fun catalogDefinesOnlyValidatedCrudAndReadToolsWithSnakeCaseFields() {
        val valid = mapOf(
            "request_clarification" to """{"field":"title"}""",
            "create_task" to """{"title":"Reunião","due_date":"2026-11-17","due_time":"14:30","reminder_enabled":false}""",
            "update_task" to """{"id":"t","description":"Pauta"}""",
            "get_task" to """{"id":"t"}""", "complete_task" to """{"id":"t"}""", "reopen_task" to """{"id":"t"}""",
            "list_tasks" to "{}", "search_tasks" to """{"query":"reunião"}""", "summary" to "{}",
            "list_categories" to "{}", "create_category" to """{"name":"Casa"}""", "update_category" to """{"id":"c","name":"Pessoal"}""", "delete_category" to """{"id":"c"}""",
            "list_projects" to "{}", "create_project" to """{"name":"Viagem"}""", "update_project" to """{"id":"p","description":"Plano"}""", "delete_project" to """{"id":"p"}""", "delete_task" to """{"ids":["t"]}"""
        )
        assertEquals(valid.keys, ToolCatalog.definitions.map { it.asJsonObject["function"].asJsonObject["name"].asString }.toSet())
        valid.forEach { (name,args) -> ToolCatalog.validate(ToolCall("x", name, JsonParser.parseString(args).asJsonObject)) }
        listOf("""{"title":"x","reminder_enabled":"true"}""", """{"title":"x","dueDate":"2026-11-17"}""", """{"title":"x","reminder_minutes":1.5}""").forEach { args ->
            try { ToolCatalog.validate(ToolCall("x","create_task",JsonParser.parseString(args).asJsonObject)); fail(args) } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun requiresTypedExplicitIdsAndRejectsAmbiguousSelectors() {
        listOf("{}", """{"ids":[]}""", """{"ids":["x","x"]}""", """{"ids":[1]}""", """{"ids":[""]}""").forEach { json ->
            try { ToolCatalog.validate(ToolCall("a", "delete_task", JsonParser.parseString(json).asJsonObject)); fail(json) }
            catch (_: IllegalArgumentException) { }
        }
        try { ToolCatalog.validate(ToolCall("a", "update_task", JsonParser.parseString("""{"title":"Reunião"}""").asJsonObject)); fail("ID required") }
        catch (_: IllegalArgumentException) { }
        ToolCatalog.validate(ToolCall("a", "delete_task", JsonParser.parseString("""{"ids":["task-1"]}""").asJsonObject))
    }

    @Test fun modelCannotSupplyConfirmationOrUnknownFields() {
        val args = JsonParser.parseString("""{"ids":["task-1"],"confirmed":true}""").asJsonObject
        try { ToolCatalog.validate(ToolCall("a", "delete_task", args)); fail("Reject model confirmation") }
        catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("confirmed")) }
    }
}
