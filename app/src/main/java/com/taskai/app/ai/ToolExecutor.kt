package com.taskai.app.ai

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.taskai.app.data.TaskEntity
import com.taskai.app.data.TaskRepository
import com.taskai.app.data.CategoryEntity
import com.taskai.app.data.ProjectEntity
import kotlinx.coroutines.flow.first
import java.time.ZoneId

class ToolExecutor(private val repository: TaskRepository, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    private val gson = GsonBuilder().serializeNulls().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
    val definitions: JsonArray get() = ToolCatalog.definitions
    private fun taskFrom(a: JsonObject, old: TaskEntity): TaskEntity = old.copy(
        title=a.string("title",old.title)!!, description=a.string("description",old.description)!!,
        dueDate=a.string("due_date",old.dueDate), dueTime=a.string("due_time",old.dueTime),
        priority=a.string("priority",old.priority)!!, categoryId=a.string("category_id",old.categoryId), projectId=a.string("project_id",old.projectId),
        recurrenceRule=a.string("recurrence_rule",old.recurrenceRule), reminderEnabled=a["reminder_enabled"]?.asBoolean ?: old.reminderEnabled,
        reminderMinutes=a["reminder_minutes"]?.asLong?.let { require(it in 0..525600) { "Antecedência inválida." }; it.toInt() } ?: old.reminderMinutes,
        reminderDatetime=if (a.has("reminder_datetime")) a["reminder_datetime"].takeUnless { it.isJsonNull }?.asLong else old.reminderDatetime
    )
    private fun JsonObject.string(key: String, default: String?): String? = if (has(key)) get(key).takeUnless { it.isJsonNull }?.asString else default

    private fun identity(value:Any):JsonObject {
        val full=gson.toJsonTree(value).asJsonObject
        return JsonObject().apply {
            listOf("id","title","name","due_date","due_time").forEach {key->full[key]?.let {add(key,it)}}
            val digest=java.security.MessageDigest.getInstance("SHA-256").digest(full.toString().toByteArray(Charsets.UTF_8))
            addProperty("snapshot",digest.joinToString("") {"%02x".format(it.toInt() and 255)})
        }
    }

    suspend fun execute(call: ToolCall, confirmed: Boolean = false): String {
        return try {
            ToolCatalog.validate(call)
            if (call.name == "delete_task") {
                val ids = call.arguments["ids"].asJsonArray.map { it.asString }
                val selected = ids.map { requireNotNull(repository.getTask(it)) { "Tarefa não encontrada. Selecione novamente." } }
                if (!confirmed) return JsonObject().apply {
                    addProperty("ok",false);addProperty("pending_confirmation",true);addProperty("operation",call.name);add("data",gson.toJsonTree(selected.map {identity(it)}))
                }.toString()
                val count = repository.deleteTasks(ids)
                check(ids.all { repository.getTask(it) == null })
                return JsonObject().apply { addProperty("ok",true);addProperty("operation",call.name);add("data",gson.toJsonTree(selected.map {identity(it)}));addProperty("count",count) }.toString()
            }
            val a = call.arguments
            if (call.name in setOf("delete_category","delete_project")) {
                val id=a["id"].asString
                val selected: Any = if (call.name=="delete_category") requireNotNull(repository.categories.first().find { it.id==id }) { "Categoria não encontrada." }
                    else requireNotNull(repository.projects.first().find { it.id==id }) { "Projeto não encontrado." }
                if (!confirmed) return JsonObject().apply { addProperty("ok",false);addProperty("pending_confirmation",true);addProperty("operation",call.name);add("data",gson.toJsonTree(listOf(identity(selected)))) }.toString()
                if (call.name=="delete_category") { repository.deleteCategory(id); check(repository.categories.first().none { it.id==id }) }
                else { repository.deleteProject(id); check(repository.projects.first().none { it.id==id }) }
                return JsonObject().apply { addProperty("ok",true);addProperty("operation",call.name);add("data",gson.toJsonTree(listOf(identity(selected))));addProperty("count",1) }.toString()
            }
            val result: Any = when (call.name) {
                "get_task" -> requireNotNull(repository.getTask(a["id"].asString)) { "Tarefa não encontrada." }
                "list_tasks" -> com.taskai.app.data.TaskFilters.apply(repository.listTasks(),a.string("filter","ALL")!!,java.time.LocalDate.now(zone()))
                "search_tasks" -> repository.listTasks().filter { it.title.contains(a["query"].asString,ignoreCase=true) || it.description.contains(a["query"].asString,ignoreCase=true) }
                "summary" -> {
                    val tasks=repository.listTasks();val today=java.time.LocalDate.now(zone())
                    mapOf("date" to today.toString(),"zone" to zone().id,"total" to tasks.size,"pending" to tasks.count {it.status=="PENDING"},"completed" to tasks.count {it.status=="COMPLETED"},"today" to com.taskai.app.data.TaskFilters.apply(tasks,"TODAY",today).size,"overdue" to com.taskai.app.data.TaskFilters.apply(tasks,"OVERDUE",today).size)
                }
                "list_categories" -> repository.categories.first()
                "list_projects" -> repository.projects.first()
                "create_category", "update_category" -> {
                    val old=if(call.name=="create_category") CategoryEntity(name=a["name"].asString) else requireNotNull(repository.categories.first().find { it.id==a["id"].asString }) { "Categoria não encontrada." }
                    val saved=repository.saveCategory(old.copy(name=a.string("name",old.name)!!,color=a.string("color",old.color)!!,icon=a.string("icon",old.icon)!!))
                    requireNotNull(repository.categories.first().find {it.id==saved.id})
                }
                "create_project", "update_project" -> {
                    val old=if(call.name=="create_project") ProjectEntity(name=a["name"].asString) else requireNotNull(repository.projects.first().find { it.id==a["id"].asString }) { "Projeto não encontrado." }
                    val saved=repository.saveProject(old.copy(name=a.string("name",old.name)!!,color=a.string("color",old.color)!!,description=a.string("description",old.description)!!))
                    requireNotNull(repository.projects.first().find {it.id==saved.id})
                }
                "create_task" -> repository.createTask(taskFrom(call.arguments, TaskEntity(title=call.arguments["title"].asString))).let { requireNotNull(repository.getTask(it.id)) }
                "update_task" -> repository.updateTask(taskFrom(call.arguments, requireNotNull(repository.getTask(call.arguments["id"].asString)) { "Tarefa não encontrada." })).let { requireNotNull(repository.getTask(it.id)) }
                "complete_task" -> repository.completeTask(call.arguments["id"].asString).let { requireNotNull(repository.getTask(it.id)) }
                "reopen_task" -> repository.reopenTask(call.arguments["id"].asString).let { requireNotNull(repository.getTask(it.id)) }
                else -> throw IllegalArgumentException("Ferramenta desconhecida.")
            }
            JsonObject().apply {
                addProperty("ok",true);addProperty("operation",call.name)
                if(result is List<*>) {
                    val data=JsonArray();var size=0
                    for(item in result) {val json=gson.toJsonTree(item);val length=json.toString().length;if(data.size()>=100 || size+length>100000) break;data.add(json);size+=length}
                    add("data",data);addProperty("total",result.size);addProperty("returned",data.size());addProperty("has_more",data.size()<result.size)
                    if(data.size()<result.size) addProperty("notice","Resultado limitado; refine a busca para obter outros registros.")
                } else add("data",gson.toJsonTree(result))
            }.toString()
        } catch(e:kotlinx.coroutines.CancellationException) { throw e }
        catch (e: IllegalArgumentException) {
            JsonObject().apply { addProperty("ok",false);addProperty("error",e.message ?: "Argumentos inválidos.") }.toString()
        } catch (_:Exception) {
            JsonObject().apply {addProperty("ok",false);addProperty("error","Falha de armazenamento. Não foi possível confirmar a operação; consulte os dados antes de tentar novamente.")}.toString()
        }
    }
}
