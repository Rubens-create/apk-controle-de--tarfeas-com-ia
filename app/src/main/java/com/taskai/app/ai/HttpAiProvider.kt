package com.taskai.app.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.taskai.app.infra.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit

class AiTransportException(message: String) : IOException(message)

class HttpAiProvider(client: OkHttpClient = OkHttpClient()) : AiProvider {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS).build()

    override suspend fun reply(settings: AppSettings, messages: List<AiMessage>, tools: JsonArray): AiReply = withContext(Dispatchers.IO) {
        require(settings.provider in setOf("OPENAI","COMPATIBLE","ANTHROPIC","GEMINI")) { "Provedor de IA inválido." }
        require(settings.apiKey.isNotBlank()) { "Configure a chave de API nas configurações." }
        require(settings.model.isNotBlank()) { "Configure o modelo de IA." }
        val maxTemperature=if(settings.provider=="ANTHROPIC") 1.0 else 2.0
        require(settings.temperature.isFinite() && settings.temperature in 0.0..maxTemperature) { "Temperatura inválida." }
        val base = settings.endpoint.trimEnd('/').toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Endpoint inválido.")
        val jvmTest = !System.getProperty("java.vm.name").orEmpty().contains("Dalvik", ignoreCase = true)
        require(base.isHttps || (jvmTest && base.host in setOf("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1"))) { "O endpoint deve usar HTTPS." }
        require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) { "Endpoint inválido." }
        require(messages.size <= 200 && messages.sumOf { it.content.length.toLong() + it.toolCalls.toString().length + (it.geminiParts?.toString()?.length ?: 0) } <= 250_000) { "Conversa muito longa. Inicie uma nova conversa." }
        if (settings.provider == "GEMINI") return@withContext gemini(settings, messages, tools, base.toString().trimEnd('/'))
        if (settings.provider == "ANTHROPIC") return@withContext anthropic(settings, messages, tools, base.toString().trimEnd('/'))
        val body = JsonObject().apply {
            addProperty("model", settings.model)
            addProperty("temperature", settings.temperature)
            addProperty("stream", false)
            add("messages", JsonArray().apply {
                messages.forEach { m -> add(JsonObject().apply {
                    addProperty("role", m.role); addProperty("content", m.content)
                    m.toolCallId?.let { addProperty("tool_call_id", it) }
                    if (m.toolCalls.isNotEmpty()) add("tool_calls", JsonArray().apply {
                        m.toolCalls.forEach { call -> add(JsonObject().apply {
                            addProperty("id", call.id); addProperty("type", "function")
                            add("function", JsonObject().apply { addProperty("name", call.name); addProperty("arguments", call.arguments.toString()) })
                        }) }
                    })
                }) }
            })
            if (!tools.isEmpty) add("tools", tools.deepCopy())
        }
        val request = Request.Builder().url(base.toString().trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val json = requestJson(request)
        try {
            val message = json.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message")
            val text = message["content"]?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            val calls = message.getAsJsonArray("tool_calls")?.map { element ->
                val call = element.asJsonObject
                val function = call.getAsJsonObject("function")
                ToolCall(call["id"].asString, function["name"].asString, strictJson(function["arguments"].asString).asJsonObject)
            }.orEmpty()
            if (calls.size > 16 || calls.any { it.id.isBlank() || it.name.isBlank() } || calls.map { it.id }.distinct().size != calls.size) throw IllegalArgumentException()
            AiReply(text, calls)
        } catch (_: RuntimeException) { throw AiTransportException("Resposta de IA inválida. Nenhuma ação foi confirmada.") }
    }

    private fun geminiSchema(schema:JsonObject):JsonObject = schema.deepCopy().apply {
        remove("additionalProperties")
        get("type")?.let { type ->
            if(type.isJsonArray) {addProperty("nullable",type.asJsonArray.any {it.asString=="null"});addProperty("type",type.asJsonArray.first {it.asString!="null"}.asString.uppercase(java.util.Locale.ROOT))}
            else addProperty("type",type.asString.uppercase(java.util.Locale.ROOT))
        }
        getAsJsonObject("properties")?.let {properties -> properties.entrySet().toList().forEach {(key,value)->properties.add(key,geminiSchema(value.asJsonObject))}}
        getAsJsonObject("items")?.let {add("items",geminiSchema(it))}
    }

    private fun gemini(settings: AppSettings, messages: List<AiMessage>, tools: JsonArray, base: String): AiReply {
        require(settings.model.matches(Regex("[A-Za-z0-9._-]+"))) { "Modelo Gemini inválido." }
        val names = messages.flatMap { it.toolCalls }.associate { it.id to it.name }
        val body = JsonObject().apply {
            add("systemInstruction", JsonObject().apply { add("parts", JsonArray().apply { add(JsonObject().apply {
                addProperty("text", messages.filter { it.role == "system" }.joinToString("\n") { it.content })
            }) }) })
            add("contents", JsonArray().apply { messages.filter { it.role != "system" }.forEach { m -> add(JsonObject().apply {
                addProperty("role", if (m.role == "assistant") "model" else "user")
                add("parts", if (m.role == "assistant" && m.geminiParts != null) snapshotGeminiParts(m.geminiParts) else JsonArray().apply {
                    if (m.role == "tool") add(JsonObject().apply { add("functionResponse", JsonObject().apply {
                        addProperty("name", names[m.toolCallId] ?: throw IllegalArgumentException("Resultado de ferramenta sem chamada."))
                        add("response", JsonParser.parseString(m.content).asJsonObject)
                    }) }) else {
                        if (m.content.isNotBlank()) add(JsonObject().apply { addProperty("text", m.content) })
                        m.toolCalls.forEach { c -> add(JsonObject().apply { add("functionCall", JsonObject().apply {
                            addProperty("name", c.name); add("args", c.arguments)
                        }) }) }
                    }
                })
            }) } })
            add("generationConfig", JsonObject().apply { addProperty("temperature", settings.temperature); addProperty("maxOutputTokens", 4096) })
            if (!tools.isEmpty) add("tools", JsonArray().apply { add(JsonObject().apply {
                add("functionDeclarations", JsonArray().apply { tools.forEach { tool ->
                    add(tool.asJsonObject["function"].asJsonObject.deepCopy().apply {
                        val parameters=getAsJsonObject("parameters")
                        if(parameters.getAsJsonObject("properties").size()==0) remove("parameters") else add("parameters",geminiSchema(parameters))
                    })
                } })
            }) })
        }
        val response = requestJson(Request.Builder().url("$base/models/${settings.model}:generateContent")
            .header("x-goog-api-key", settings.apiKey).post(body.toString().toRequestBody("application/json".toMediaType())).build())
        return try {
            val parts = snapshotGeminiParts(response.getAsJsonArray("candidates")[0].asJsonObject.getAsJsonObject("content").getAsJsonArray("parts")) ?: throw IllegalArgumentException()
            val text = parts.filter { it.asJsonObject.has("text") && it.asJsonObject["thought"]?.asBoolean != true }.joinToString("\n") { it.asJsonObject["text"].asString }
            val calls = parts.filter { it.asJsonObject.has("functionCall") }.map { p ->
                val f = p.asJsonObject.getAsJsonObject("functionCall")
                ToolCall(java.util.UUID.randomUUID().toString(), f["name"].asString, f.getAsJsonObject("args"))
            }
            require(calls.size <= 16 && calls.none { it.name.isBlank() })
            AiReply(text, calls, geminiParts = parts)
        } catch (_: RuntimeException) { throw AiTransportException("Resposta de IA inválida. Nenhuma ação foi confirmada.") }
    }

    private fun anthropic(settings: AppSettings, messages: List<AiMessage>, tools: JsonArray, base: String): AiReply {
        val body = JsonObject().apply {
            addProperty("model", settings.model); addProperty("max_tokens", 4096); addProperty("temperature", settings.temperature)
            addProperty("system", messages.filter { it.role == "system" }.joinToString("\n") { it.content })
            add("messages", JsonArray().apply { messages.filter { it.role != "system" }.forEach { m ->
                add(JsonObject().apply {
                    addProperty("role", if (m.role == "assistant") "assistant" else "user")
                    add("content", JsonArray().apply {
                        if (m.role == "tool") add(JsonObject().apply {
                            addProperty("type", "tool_result"); addProperty("tool_use_id", m.toolCallId); addProperty("content", m.content)
                        }) else {
                            if (m.content.isNotBlank()) add(JsonObject().apply { addProperty("type", "text"); addProperty("text", m.content) })
                            m.toolCalls.forEach { call -> add(JsonObject().apply {
                                addProperty("type", "tool_use"); addProperty("id", call.id); addProperty("name", call.name); add("input", call.arguments)
                            }) }
                        }
                    })
                })
            } })
            if (!tools.isEmpty) add("tools", JsonArray().apply { tools.forEach { t ->
                val f = t.asJsonObject.getAsJsonObject("function")
                add(JsonObject().apply { add("name", f["name"]); add("description", f["description"]); add("input_schema", f["parameters"]) })
            } })
        }
        val response = requestJson(Request.Builder().url("$base/messages").header("x-api-key", settings.apiKey)
            .header("anthropic-version", "2023-06-01").post(body.toString().toRequestBody("application/json".toMediaType())).build())
        return try {
            val parts = response.getAsJsonArray("content")
            val text = parts.filter { it.asJsonObject["type"].asString == "text" }.joinToString("\n") { it.asJsonObject["text"].asString }
            val calls = parts.filter { it.asJsonObject["type"].asString == "tool_use" }.map { p ->
                p.asJsonObject.let { ToolCall(it["id"].asString, it["name"].asString, it.getAsJsonObject("input")) }
            }
            require(calls.size <= 16 && calls.map { it.id }.distinct().size == calls.size && calls.none { it.id.isBlank() || it.name.isBlank() })
            AiReply(text, calls)
        } catch (_: RuntimeException) { throw AiTransportException("Resposta de IA inválida. Nenhuma ação foi confirmada.") }
    }

    private fun requestJson(request: Request): JsonObject {
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw AiTransportException(when (response.code) {
                    401, 403 -> "Acesso à IA negado. Verifique a chave e as permissões."
                    429 -> "Limite da IA atingido. Tente novamente mais tarde."
                    else -> "Serviço de IA indisponível (HTTP ${response.code})."
                })
                val source = response.body?.source() ?: throw AiTransportException("Resposta de IA vazia.")
                if (source.request(1_048_577) && source.buffer.size > 1_048_576) throw AiTransportException("Resposta de IA excedeu o limite.")
                return try { strictJson(source.readUtf8()).asJsonObject }
                catch (_: RuntimeException) { throw AiTransportException("Resposta de IA inválida.") }
            }
        } catch (e: AiTransportException) { throw e }
        catch (_: IOException) { throw AiTransportException("Não foi possível conectar à IA. Verifique a conexão e o endpoint.") }
    }

    suspend fun testConnection(settings: AppSettings): String {
        reply(settings, listOf(AiMessage("user", "Responda apenas OK. Não execute ferramentas.")), JsonArray())
        return "Conexão com a IA validada."
    }
}
