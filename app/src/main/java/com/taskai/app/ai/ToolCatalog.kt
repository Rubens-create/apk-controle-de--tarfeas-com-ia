package com.taskai.app.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal object ToolCatalog {
    private data class Field(val type: String, val nullable: Boolean = false, val choices: List<String> = emptyList(), val max: Int = 10000)
    private data class Spec(val description: String, val fields: Map<String, Field>, val required: Set<String> = emptySet())
    private val str = Field("string")
    private val id = Field("string", max = 200)
    private val taskFields = linkedMapOf(
        "title" to Field("string", max=300), "description" to str,
        "due_date" to Field("string", true, max=10), "due_time" to Field("string", true, max=5),
        "priority" to Field("string", choices=listOf("BAIXA","MEDIA","ALTA","URGENTE")),
        "category_id" to id.copy(nullable=true), "project_id" to id.copy(nullable=true),
        "recurrence_rule" to Field("string", true, max=500), "reminder_enabled" to Field("boolean"),
        "reminder_minutes" to Field("integer"), "reminder_datetime" to Field("integer", true)
    )
    private val filter = Field("string", choices=listOf("ALL","TODAY","TOMORROW","UPCOMING","WEEK","OVERDUE","COMPLETED","UNDATED"))
    private val category = linkedMapOf("name" to Field("string", max=200), "color" to Field("string", max=7), "icon" to Field("string", max=100))
    private val project = linkedMapOf("name" to Field("string", max=200), "description" to str, "color" to Field("string", max=7))
    private val specs = linkedMapOf(
        "list_tasks" to Spec("Lista tarefas reais por filtro e IDs; nunca assume horários.", mapOf("filter" to filter)),
        "search_tasks" to Spec("Pesquisa título/descrição. Vários resultados exigem esclarecer o ID antes de alterar.", mapOf("query" to Field("string",max=300)),setOf("query")),
        "get_task" to Spec("Consulta tarefa por ID exato.", mapOf("id" to id),setOf("id")),
        "create_task" to Spec("Cria tarefa sem data/horário/lembrete se não informados. Períodos vagos exigem esclarecer o horário.",taskFields,setOf("title")),
        "update_task" to Spec("Altera somente campos fornecidos de tarefa com ID explícito.",mapOf("id" to id)+taskFields,setOf("id")),
        "complete_task" to Spec("Conclui tarefa por ID; gera próxima recorrência configurada.",mapOf("id" to id),setOf("id")),
        "reopen_task" to Spec("Reabre tarefa por ID explícito.",mapOf("id" to id),setOf("id")),
        "delete_task" to Spec("Solicita confirmação da interface para excluir exatamente estes IDs. Não aceita confirmação do modelo.",mapOf("ids" to Field("array")),setOf("ids")),
        "summary" to Spec("Resumo calculado das tarefas reais para a data local.",emptyMap()),
        "list_categories" to Spec("Lista categorias reais e IDs.",emptyMap()),
        "create_category" to Spec("Cria categoria.",category,setOf("name")),
        "update_category" to Spec("Atualiza categoria por ID.",mapOf("id" to id)+category,setOf("id")),
        "delete_category" to Spec("Solicita confirmação da interface para excluir categoria por ID.",mapOf("id" to id),setOf("id")),
        "list_projects" to Spec("Lista projetos reais e IDs.",emptyMap()),
        "create_project" to Spec("Cria projeto.",project,setOf("name")),
        "update_project" to Spec("Atualiza projeto por ID.",mapOf("id" to id)+project,setOf("id")),
        "delete_project" to Spec("Solicita confirmação da interface para excluir projeto por ID.",mapOf("id" to id),setOf("id"))
    )
    val destructive = setOf("delete_task","delete_category","delete_project")
    fun mutates(name: String) = name.startsWith("create_") || name.startsWith("update_") || name in destructive || name in setOf("complete_task","reopen_task")
    val definitions: JsonArray get() = JsonArray().apply { specs.forEach { (name,spec) -> add(JsonObject().apply {
        addProperty("type","function")
        add("function",JsonObject().apply {
            addProperty("name",name); addProperty("description",spec.description)
            add("parameters",JsonObject().apply {
                addProperty("type","object"); addProperty("additionalProperties",false)
                add("properties",JsonObject().apply { spec.fields.forEach { (key,field) -> add(key,JsonObject().apply {
                    if (field.nullable) add("type",JsonArray().apply { add(field.type); add("null") }) else addProperty("type",field.type)
                    if (field.choices.isNotEmpty()) add("enum",JsonArray().apply { field.choices.forEach { add(it) } })
                    if (field.type=="array") { add("items",JsonObject().apply { addProperty("type","string") });addProperty("minItems",1);addProperty("maxItems",100) }
                }) } })
                add("required",JsonArray().apply { spec.required.forEach { add(it) } })
            })
        })
    }) } }

    fun validate(call: ToolCall) {
        val spec = specs[call.name] ?: throw IllegalArgumentException("Ferramenta desconhecida.")
        require(call.arguments.toString().length <= 32000) { "Argumentos excedem o limite." }
        val unknown = call.arguments.keySet() - spec.fields.keys
        require(unknown.isEmpty()) { "Parâmetros desconhecidos: ${unknown.joinToString()}." }
        require(call.arguments.keySet().containsAll(spec.required)) { "Informe os campos obrigatórios: ${spec.required.joinToString()}." }
        call.arguments.entrySet().forEach { (key,value) ->
            val f = spec.fields.getValue(key)
            if (value.isJsonNull) { require(f.nullable) { "$key não pode ser nulo." }; return@forEach }
            val valid = when (f.type) {
                "string" -> value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.length <= f.max && (key == "description" || value.asString.isNotBlank()) && (f.choices.isEmpty() || value.asString in f.choices)
                "boolean" -> value.isJsonPrimitive && value.asJsonPrimitive.isBoolean
                "integer" -> value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.toString().matches(Regex("-?(0|[1-9][0-9]*)")) && value.asString.toLongOrNull() != null
                "array" -> value.isJsonArray && value.asJsonArray.size() in 1..100 && value.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotBlank() && it.asString.length <= 200 } && value.asJsonArray.map { it.asString }.distinct().size == value.asJsonArray.size()
                else -> false
            }
            require(valid) { "Argumento inválido: $key." }
        }
        if (call.name.startsWith("update_")) require(call.arguments.size() > 1) { "Informe ao menos um campo para atualizar." }
    }
}
