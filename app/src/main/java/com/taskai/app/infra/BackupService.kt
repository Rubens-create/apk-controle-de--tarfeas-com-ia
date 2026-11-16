package com.taskai.app.infra

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.taskai.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.StringReader
import java.util.Locale

/** Versioned logical snapshot, deliberately excluding preferences and Android Keystore material. */
class BackupService internal constructor(private val db: AppDatabase, private val maxRows: Int) {
    constructor(db: AppDatabase) : this(db, MAX_ROWS)
    init { require(maxRows in 1..MAX_ROWS) }
    private val gson = GsonBuilder().serializeNulls().create()

    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        val root = db.withTransaction {
            val dao = db.dao()
            JsonObject().apply {
                addProperty("version", 1)
                addProperty("exportedAt", System.currentTimeMillis())
                add("categories", gson.toJsonTree(dao.getCategories()))
                add("projects", gson.toJsonTree(dao.getProjects()))
                add("tasks", gson.toJsonTree(dao.getTasks()))
                add("conversations", gson.toJsonTree(dao.getConversations()))
                add("messages", gson.toJsonTree(dao.getAllMessages()))
                add("logs", gson.toJsonTree(dao.getLogs()))
            }
        }
        val json = gson.toJson(root)
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Backup excede 10 MiB." }
        // Never hand the user a snapshot that this very importer would reject.
        validated(json)
        json
    }

    suspend fun inspect(json: String): String = withContext(Dispatchers.Default) {
        val root = validated(json)
        "${root.getAsJsonArray("tasks").size()} tarefas, ${root.getAsJsonArray("categories").size()} categorias, " +
            "${root.getAsJsonArray("projects").size()} projetos, ${root.getAsJsonArray("conversations").size()} conversas, " +
            "${root.getAsJsonArray("messages").size()} mensagens e ${root.getAsJsonArray("logs").size()} registros. " +
            "A restauração substituirá todos os dados. O arquivo contém informações pessoais; guarde-o com segurança."
    }

    suspend fun restore(json: String, confirmation: String) {
        require(confirmation == "RESTAURAR") { "Digite RESTAURAR para confirmar." }
        // No writes, including deletes, occur until structural and domain validation have completed.
        val root = withContext(Dispatchers.Default) { validated(json) }
        withContext(Dispatchers.IO) {
            db.withTransaction {
                val sql = db.openHelper.writableDatabase
                // All table/column identifiers come from this immutable application schema; values bind.
                for (spec in SPECS.asReversed()) sql.delete(spec.table, null, null)
                for (spec in SPECS) for (element in root.getAsJsonArray(spec.jsonName)) {
                    val row = element.asJsonObject
                    val values = ContentValues()
                    for ((name, kind) in spec.fields) {
                        val value = row.get(name)
                        when {
                            value.isJsonNull -> values.putNull(name)
                            kind.trimEnd('?') == "long" -> values.put(name, value.asLong)
                            kind.trimEnd('?') == "int" -> values.put(name, value.asInt)
                            kind == "boolean" -> values.put(name, if (value.asBoolean) 1 else 0)
                            else -> values.put(name, value.asString)
                        }
                    }
                    check(sql.insert(spec.table, SQLiteDatabase.CONFLICT_ABORT, values) != -1L) { "Falha ao restaurar dados." }
                }
            }
        }
    }

    private fun validated(json: String): JsonObject {
        require(json.length <= MAX_BYTES && json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Backup excede 10 MiB." }
        try {
            val reader = JsonReader(StringReader(json)).apply { strictness = Strictness.STRICT }
            val root = reader.use {
                val value = readStrict(it, 0)
                require(it.peek() == JsonToken.END_DOCUMENT)
                require(value.isJsonObject)
                value.asJsonObject
            }
            require(root.keySet() == SPECS.map { it.jsonName }.toSet() + setOf("version", "exportedAt"))
            require(integer(root.get("version")) == 1L) { "Versão de backup não suportada." }
            require(integer(root.get("exportedAt")) >= 0)
            var total = 0
            for (spec in SPECS) {
                val array = root.get(spec.jsonName)
                require(array.isJsonArray && array.asJsonArray.size() <= maxRows)
                total += array.asJsonArray.size()
                require(total <= maxRows)
                val ids = HashSet<String>()
                for (element in array.asJsonArray) {
                    require(element.isJsonObject)
                    val row = element.asJsonObject
                    require(row.keySet() == spec.fields.keys)
                    for ((name, kind) in spec.fields) {
                        val value = row.get(name)
                        if (value.isJsonNull) { require(kind.endsWith('?')); continue }
                        when (kind.trimEnd('?')) {
                            "long" -> require(integer(value) >= 0)
                            "int" -> require(integer(value) in 0..Int.MAX_VALUE.toLong())
                            "boolean" -> require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
                            "string" -> require(value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.length <= 100000)
                        }
                    }
                    require(row.get("id").asString.isNotBlank() && row.get("id").asString.length <= 200 && ids.add(row.get("id").asString))
                }
            }
            val categories = root.getAsJsonArray("categories").map { gson.fromJson(it, CategoryEntity::class.java) }
            val projects = root.getAsJsonArray("projects").map { gson.fromJson(it, ProjectEntity::class.java) }
            val tasks = root.getAsJsonArray("tasks").map { gson.fromJson(it, TaskEntity::class.java) }
            val chats = root.getAsJsonArray("conversations").map { gson.fromJson(it, ConversationEntity::class.java) }
            val messages = root.getAsJsonArray("messages").map { gson.fromJson(it, MessageEntity::class.java) }
            val logs = root.getAsJsonArray("logs").map { gson.fromJson(it, ActivityLogEntity::class.java) }
            fun label(text: String, max: Int = 200) { require(text.isNotBlank() && text.length <= max) }
            fun color(text: String) { require(Regex("#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?").matches(text)) }
            fun normalized(text: String): String = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
                .replace(Regex("[\\p{javaWhitespace}\\p{Z}]+"), " ").trim().lowercase(Locale.ROOT)
            require(categories.map { normalized(it.name) }.distinct().size == categories.size)
            require(projects.map { normalized(it.name) }.distinct().size == projects.size)
            categories.forEach { label(it.name); color(it.color); label(it.icon, 100) }
            projects.forEach { label(it.name); color(it.color); require(it.description.length <= 20000) }
            chats.forEach { label(it.title, 500) }
            val categoryIds = categories.map { it.id }.toHashSet()
            val projectIds = projects.map { it.id }.toHashSet()
            val chatIds = chats.map { it.id }.toHashSet()
            val pendingKeys = HashSet<List<String?>>()
            tasks.forEach {
                TaskRules.validate(it)
                require(it.categoryId == null || it.categoryId in categoryIds)
                require(it.projectId == null || it.projectId in projectIds)
                if (it.status == "PENDING") require(pendingKeys.add(listOf(normalized(it.title), it.dueDate, it.dueTime))) { "Tarefas pendentes duplicadas." }
            }
            messages.forEach {
                require(it.conversationId in chatIds && it.role in setOf("system", "user", "assistant", "tool"))
                require(it.content.isNotBlank() && it.content.length <= 100000)
            }
            logs.forEach { label(it.action, 100); label(it.entityType, 100); label(it.entityId); require(it.details.length <= 20000) }
            return root
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Backup inválido: verifique versão, campos e relacionamentos.")
        } catch (_: Exception) {
            // Never echo arbitrary file content or library parser messages to the UI/logs.
            throw IllegalArgumentException("Arquivo de backup inválido.")
        }
    }

    private fun integer(value: JsonElement): Long {
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber && Regex("-?(0|[1-9][0-9]*)").matches(value.asString))
        return value.asString.toLong()
    }

    private fun readStrict(reader: JsonReader, depth: Int): JsonElement {
        require(depth <= 8)
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(!has(name) && size() < 64)
                    add(name, readStrict(reader, depth + 1))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                reader.beginArray()
                while (reader.hasNext()) { require(size() < maxRows); add(readStrict(reader, depth + 1)) }
                reader.endArray()
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> JsonPrimitive(java.math.BigDecimal(reader.nextString()))
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
            else -> throw IllegalArgumentException("JSON inválido.")
        }
    }

    private data class Spec(val jsonName: String, val table: String, val fields: Map<String, String>)
    companion object {
        private const val MAX_BYTES = 10 * 1024 * 1024
        private const val MAX_ROWS = 50000
        private fun spec(json: String, table: String, strings: String, longs: String = "createdAt", nullableStrings: String = "", ints: String = "", booleans: String = "", nullableLongs: String = ""): Spec {
            val fields = linkedMapOf<String, String>()
            for ((names, type) in listOf(strings to "string", longs to "long", nullableStrings to "string?", ints to "int", booleans to "boolean", nullableLongs to "long?")) {
                names.split(' ').filter { it.isNotBlank() }.forEach { fields[it] = type }
            }
            return Spec(json, table, fields)
        }
        private val SPECS = listOf(
            spec("categories", "categories", "id name color icon"),
            spec("projects", "projects", "id name description color"),
            spec("tasks", "tasks", "id title description priority status", "createdAt updatedAt", "dueDate dueTime categoryId projectId recurrenceRule", "reminderMinutes", "reminderEnabled", "reminderDatetime completedAt"),
            spec("conversations", "conversations", "id title", "createdAt updatedAt"),
            spec("messages", "messages", "id conversationId role content"),
            spec("logs", "activity_logs", "id action entityType entityId details")
        )
    }
}
