package com.taskai.app.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.taskai.app.infra.AppSettings

data class ToolCall(val id: String, val name: String, val arguments: JsonObject)
data class AiReply(val text: String, val calls: List<ToolCall> = emptyList(), val geminiParts: JsonArray? = null)
/** Native parts are protocol-only; never render them as conversation text. */
data class AiMessage(val role: String, val content: String, val toolCalls: List<ToolCall> = emptyList(), val toolCallId: String? = null, val geminiParts: JsonArray? = null)

internal fun snapshotGeminiParts(parts: JsonArray?): JsonArray? = parts?.let {
    require(it.size() <= 128 && it.toString().length <= 100_000) { "Conteúdo nativo de IA excedeu o limite." }
    strictJson(it.toString()).asJsonArray.also { copy -> require(copy.all { part -> part.isJsonObject }) { "Conteúdo nativo de IA inválido." } }
}
interface AiProvider {
    suspend fun reply(settings: AppSettings, messages: List<AiMessage>, tools: JsonArray): AiReply
}
