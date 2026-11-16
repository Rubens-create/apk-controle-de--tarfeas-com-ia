package com.taskai.app.ai

import com.google.gson.JsonElement

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

internal fun strictJson(value: String): JsonElement {
    require(value.length <= 1_048_576) { "JSON excedeu o limite." }
    try {
        JsonReader(StringReader(value)).use { reader ->
            reader.strictness = Strictness.STRICT
            fun read(depth:Int):JsonElement {
                require(depth<=64) { "JSON muito profundo." }
                return when(reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> com.google.gson.JsonObject().apply {
                        reader.beginObject()
                        while(reader.hasNext()) {val key=reader.nextName();require(!has(key)) {"Chave JSON duplicada."};add(key,read(depth+1))}
                        reader.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> com.google.gson.JsonArray().apply {reader.beginArray();while(reader.hasNext()) add(read(depth+1));reader.endArray()}
                    JsonToken.STRING -> com.google.gson.JsonPrimitive(reader.nextString())
                    JsonToken.NUMBER -> com.google.gson.JsonPrimitive(java.math.BigDecimal(reader.nextString()))
                    JsonToken.BOOLEAN -> com.google.gson.JsonPrimitive(reader.nextBoolean())
                    JsonToken.NULL -> {reader.nextNull();com.google.gson.JsonNull.INSTANCE}
                    else -> throw IllegalArgumentException("JSON inválido.")
                }
            }
            val result = read(0)
            require(reader.peek() == JsonToken.END_DOCUMENT) { "JSON inválido." }
            return result
        }
    } catch (_: Exception) { throw IllegalArgumentException("JSON inválido.") }
}
