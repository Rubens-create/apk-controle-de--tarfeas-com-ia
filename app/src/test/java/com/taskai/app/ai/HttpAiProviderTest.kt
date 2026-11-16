package com.taskai.app.ai

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.taskai.app.infra.AppSettings
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** All response bodies in this class are explicit protocol fixtures, not live API results. */
class HttpAiProviderTest {
    @Test fun compatibleFixtureSupportsOpenAiTemperatureRangeAndConnectionTest() = runBlocking {
        MockWebServer().use {server ->
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"OK"}}]}"""));server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
            val text=HttpAiProvider().testConnection(AppSettings(provider="COMPATIBLE",temperature=1.5,apiKey="fixture-secret",endpoint=server.url("/custom/v1").newBuilder().host("127.0.0.1").build().toString()))
            assertTrue(text.contains("validada"));assertEquals("/custom/v1/chat/completions",server.takeRequest().path)
        }
    }

    @Test fun errorsAndRedirectFixturesNeverEchoResponseBodyOrSecret() = runBlocking {
        for(code in listOf(401,429,500,302)) MockWebServer().use {server ->
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Location","https://attacker.invalid/").setBody("fixture-secret raw provider body"));server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
            try {HttpAiProvider().testConnection(AppSettings(apiKey="fixture-secret",endpoint=server.url("/v1").newBuilder().host("127.0.0.1").build().toString()));fail("HTTP $code")}
            catch(e:AiTransportException) {assertFalse(e.message.orEmpty().contains("fixture-secret"));assertFalse(e.message.orEmpty().contains("raw provider"));assertEquals(1,server.requestCount)}
        }
    }
    @Test fun cleartextRemoteEndpointIsRejectedWithoutNetwork() = runBlocking {
        try {HttpAiProvider().testConnection(AppSettings(apiKey="fixture-secret",endpoint="http://example.invalid/v1"));fail("HTTPS required")}
        catch(e:IllegalArgumentException) {assertTrue(e.message.orEmpty().contains("HTTPS"))}
    }
    @Test fun invalidCredentialHeaderCannotLeakCredentialInException() = runBlocking {
        try {HttpAiProvider().testConnection(AppSettings(apiKey="fixture-secret\n"));fail("Control characters must fail")}
        catch(e:IllegalArgumentException) {assertFalse(e.message.orEmpty().contains("fixture-secret"))}
    }

    @Test fun geminiFixtureTranslatesNullableSchemaAndFunctionResponses() = runBlocking {
        MockWebServer().use {server ->
            server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"OK"}]}}]}"""));server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
            val settings=AppSettings(provider="GEMINI",model="gemini-2.5-flash",apiKey="fixture-secret",endpoint=server.url("/v1beta").newBuilder().host("127.0.0.1").build().toString())
            HttpAiProvider().reply(settings,listOf(AiMessage("user","Listar"),AiMessage("assistant","",listOf(ToolCall("x","list_tasks",com.google.gson.JsonObject()))),AiMessage("tool","""{"ok":true,"data":[]}""",toolCallId="x")),ToolCatalog.definitions)
            val body=JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
            val declaration=body["tools"].asJsonArray[0].asJsonObject["functionDeclarations"].asJsonArray.first {it.asJsonObject["name"].asString=="create_task"}.asJsonObject
            val schema=declaration["parameters"].asJsonObject
            assertFalse(schema.has("additionalProperties"))
            val date=schema["properties"].asJsonObject["due_date"].asJsonObject
            assertEquals("STRING",date["type"].asString);assertTrue(date["nullable"].asBoolean)
            val summary=body["tools"].asJsonArray[0].asJsonObject["functionDeclarations"].asJsonArray.first {it.asJsonObject["name"].asString=="summary"}.asJsonObject
            assertFalse(summary.has("parameters"))
            assertEquals("list_tasks",body["contents"].asJsonArray[2].asJsonObject["parts"].asJsonArray[0].asJsonObject["functionResponse"].asJsonObject["name"].asString)
        }
    }
    @Test fun unknownProviderFailsBeforeSendingCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"OK"}}]}"""));server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
            try { HttpAiProvider().reply(AppSettings(provider="UNKNOWN",apiKey="fixture-secret",endpoint=server.url("/v1").newBuilder().host("127.0.0.1").build().toString()),listOf(AiMessage("user","Oi")),JsonArray());fail("Unknown provider") }
            catch(_:IllegalArgumentException) {assertEquals(0,server.requestCount)}
        }
    }

    @Test fun rejectsNonJsonToolArgumentsWithoutLeakingProviderErrors() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"tool_calls":[{"id":"bad","function":{"name":"list_tasks","arguments":"{filter:'ALL'}"}}]}}]}"""))
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            try {
                HttpAiProvider().reply(AppSettings(apiKey="fixture-secret", endpoint=server.url("/v1").newBuilder().host("127.0.0.1").build().toString()), listOf(AiMessage("user","listar")), JsonArray())
                fail("Non-JSON arguments must be rejected")
            } catch (e: AiTransportException) { assertFalse(e.message.orEmpty().contains("fixture-secret")) }
        }
    }

    @Test fun geminiProtocolFixtureMapsFunctionCalling() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"functionCall":{"name":"list_tasks","args":{"filter":"TODAY"}}}]}}]}"""))
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val reply = HttpAiProvider().reply(AppSettings(provider = "GEMINI", model = "gemini-2.5-flash", apiKey = "fixture-secret", endpoint = server.url("/v1beta").newBuilder().host("127.0.0.1").build().toString()), listOf(AiMessage("system", "Data local"), AiMessage("user", "Hoje?")), JsonArray())
            assertEquals("list_tasks", reply.calls.single().name)
            assertTrue(reply.calls.single().id.isNotBlank())
            val request = server.takeRequest()
            assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", request.path)
            assertEquals("fixture-secret", request.getHeader("x-goog-api-key"))
            val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertEquals("Data local", body["systemInstruction"].asJsonObject["parts"].asJsonArray[0].asJsonObject["text"].asString)
            assertEquals("user", body["contents"].asJsonArray[0].asJsonObject["role"].asString)
        }
    }

    @Test fun anthropicProtocolFixtureMapsToolUseAndToolResult() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"Consultando."},{"type":"tool_use","id":"use-7","name":"list_tasks","input":{"filter":"ALL"}}]}"""))
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val settings = AppSettings(provider = "ANTHROPIC", model = "claude-sonnet-4-5", apiKey = "fixture-secret", endpoint = server.url("/v1").newBuilder().host("127.0.0.1").build().toString())
            val reply = HttpAiProvider().reply(settings, listOf(AiMessage("system", "Contexto"), AiMessage("assistant", "", listOf(ToolCall("previous", "list_tasks", com.google.gson.JsonObject()))), AiMessage("tool", "{\"ok\":true}", toolCallId = "previous")), JsonArray())
            assertEquals("use-7", reply.calls.single().id)
            val request = server.takeRequest()
            assertEquals("/v1/messages", request.path)
            assertEquals("fixture-secret", request.getHeader("x-api-key"))
            assertEquals("2023-06-01", request.getHeader("anthropic-version"))
            val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertEquals("Contexto", body["system"].asString)
            assertEquals("tool_result", body["messages"].asJsonArray[1].asJsonObject["content"].asJsonArray[0].asJsonObject["type"].asString)
        }
    }

    @Test fun openAiProtocolFixtureSendsToolsAndReadsArguments() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"list_tasks","arguments":"{\"filter\":\"TODAY\"}"}}]}}]}"""))
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val reply = HttpAiProvider().reply(AppSettings(apiKey = "fixture-secret", endpoint = server.url("/v1").newBuilder().host("127.0.0.1").build().toString()), listOf(AiMessage("user", "Hoje?")), JsonArray())
            assertEquals("list_tasks", reply.calls.single().name)
            assertEquals("TODAY", reply.calls.single().arguments["filter"].asString)
            val request = server.takeRequest()
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer fixture-secret", request.getHeader("Authorization"))
            val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertEquals("user", body["messages"].asJsonArray[0].asJsonObject["role"].asString)
            assertFalse(body["stream"].asBoolean)
        }
    }
}
