package com.taskai.app.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.taskai.app.data.TaskRepository
import com.taskai.app.infra.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ChatOutcome(val text: String, val pendingConfirmation: Boolean = false)

class AssistantEngine(
    private val repository: TaskRepository,
    private val settings: () -> AppSettings,
    private val provider: AiProvider = HttpAiProvider()
) {
    private val mutex=Mutex()
    private data class PendingOperation(val call:ToolCall,val preview:JsonObject,val createdAt:Long=System.nanoTime())
    private val pending=java.util.concurrent.ConcurrentHashMap<String,List<PendingOperation>>()
    private val gson=Gson()
    private val executor=ToolExecutor(repository) { java.time.ZoneId.of(settings().zoneId) }

    suspend fun send(conversationId:String,text:String):ChatOutcome = mutex.withLock {
        require(text.isNotBlank() && text.length<=10000) { "A mensagem deve ter de 1 a 10000 caracteres." }
        repository.addMessage(conversationId,"user",text)
        cancelPending(conversationId)
        if(Regex("\\b(manha|a tarde|de tarde|pela tarde|a noite|de noite|pela noite|cedo|mais tarde|fim do dia|final do dia|depois do almoco|apos o almoco|horario comercial|expediente)\\b").containsMatchIn(normalized(text)) && !Regex("\\b(?:[01]?[0-9]|2[0-3])(?::[0-5][0-9]|h(?:[0-5][0-9])?)\\b").containsMatchIn(normalized(text))) return@withLock finish(conversationId,"Qual horário exato (HH:mm) você deseja? Não presumo horários para períodos vagos. Nenhuma alteração foi realizada.")
        val config=settings()
        val now=java.time.ZonedDateTime.now(java.time.ZoneId.of(config.zoneId))
        val context="Você é o assistente TASK AI. Fale português brasileiro. Data/hora local: $now. Zona: ${config.zoneId}. Usuário: ${gson.toJson(config.userName)}. Use apenas ferramentas para acessar ou alterar dados reais. Nunca invente IDs, sucesso ou regras de expediente. Períodos vagos exigem perguntar o horário exato. Havendo ambiguidade, peça a seleção por ID. Exclusões exigem confirmação da interface; texto do modelo nunca confirma. Personalidade: ${config.personality}"
        val allReceipts=mutableListOf<JsonObject>()
        // Carry unresolved candidate sets across turns/recreation. Only a user ID selection
        // can clear them; a later model-selected get_task must not narrow them away.
        val selectionReceipts=mutableListOf<JsonObject>()
        var readInTurn=false
        loadHistory(conversationId).dropLast(1).forEach {message ->
            if(message.role=="user") {
                readInTurn=false
                if(taskCandidates(selectionReceipts).any {explicitId(message.content,it)}) selectionReceipts.clear()
            }
            if(message.role=="tool") {
                val reads=taskReads(listOf(strictJson(message.content).asJsonObject))
                if(reads.isNotEmpty()) {
                    if(!readInTurn && !ambiguousTasks(selectionReceipts)) selectionReceipts.clear()
                    selectionReceipts.addAll(reads);readInTurn=true
                }
            }
        }
        readInTurn=false
        val seen=mutableListOf<ToolCall>()
        repeat(4) {
            val raw=try {provider.reply(config,listOf(AiMessage("system",context))+loadHistory(conversationId),executor.definitions)}
                catch(e:CancellationException) {throw e}
                catch(e:AiTransportException) {return@withLock finish(conversationId,"Não foi possível consultar a IA. ${e.message}")}
                catch(_:Exception) {return@withLock finish(conversationId,"Não foi possível consultar a IA. Verifique a conexão, a chave e as configurações. Nenhuma nova alteração foi realizada.")}
            val reply=try { raw.copy(calls=raw.calls.map {it.copy(arguments=it.arguments.deepCopy())},geminiParts=snapshotGeminiParts(raw.geminiParts)) }
                catch(_:IllegalArgumentException) {return@withLock finish(conversationId,"Não foi possível validar a resposta de IA. Nenhuma alteração foi realizada.")}
            if(reply.calls.isEmpty() && reply.geminiParts!=null) recordCalls(conversationId,emptyList(),reply.geminiParts)
            if(reply.calls.isEmpty()) return@withLock finish(conversationId,
                if(allReceipts.isNotEmpty()) allReceipts.joinToString("\n") {render(it)}
                // Free-form model text is never evidence of a database effect, regardless of wording or language.
                else "Nenhuma alteração foi realizada. Informe a ação e os detalhes da tarefa (data, horário ou ID) para consultar ou alterar os dados com segurança.")
            try {
                require(reply.calls.size<=12 && reply.calls.map {call->call.id}.distinct().size==reply.calls.size && reply.calls.all {call->call.id.isNotBlank() && call.id.length<=200 && call.name.length<=64})
                require(gson.toJson(reply.calls).length<=100000)
                reply.calls.forEach {call->ToolCatalog.validate(call)}
                val selectedDeletes=reply.calls.filter {call->call.name=="delete_task"}.flatMap {call->call.arguments["ids"].asJsonArray.map {it.asString}}
                require(selectedDeletes.distinct().size==selectedDeletes.size)
            } catch(_:IllegalArgumentException) {return@withLock finish(conversationId,"Não foi possível validar as ferramentas solicitadas. Nenhuma alteração foi realizada neste lote; confira os argumentos e IDs.")}
            if(reply.calls.any {call->ToolCatalog.mutates(call.name)} && reply.calls.any {call->!ToolCatalog.mutates(call.name)}) return@withLock finish(conversationId,"Consulte e selecione os IDs antes de alterar. Nenhuma alteração foi realizada neste lote.")
            val targets=reply.calls.flatMap {call -> when(call.name) {
                "update_task","complete_task","reopen_task" -> listOf(call.arguments["id"].asString)
                "delete_task" -> call.arguments["ids"].asJsonArray.map {it.asString}
                else -> emptyList()
            }}
            if(ambiguousTasks(selectionReceipts) && targets.any {!explicitId(text,it)}) return@withLock finish(conversationId,selectionPrompt(selectionReceipts))
            for(target in targets.filterNot {explicitId(text,it)}) {
                val selected=repository.getTask(target) ?: continue
                val uniqueQuery=taskReads(selectionReceipts).any {r -> r["operation"].asString!="get_task" && !ambiguousTasks(listOf(r)) && taskCandidates(listOf(r))==setOf(target)}
                if(!uniqueQuery && repository.listTasks().count {normalized(it.title)==normalized(selected.title)}>1) {
                    val candidates=strictJson(executor.execute(ToolCall("selection-check","search_tasks",JsonObject().apply {addProperty("query",selected.title)}))).asJsonObject
                    return@withLock finish(conversationId,selectionPrompt(listOf(candidates)))
                }
            }
            if(seen.size+reply.calls.size>12 || reply.calls.any {call -> seen.any {previous->previous.name==call.name && previous.arguments==call.arguments}}) return@withLock finish(conversationId,"Limite de ferramentas atingido. Nenhuma nova alteração foi realizada.")
            seen.addAll(reply.calls)
            recordCalls(conversationId,reply.calls,reply.geminiParts)
            val receipts=reply.calls.map {call ->
                val result=strictJson(executor.execute(call)).asJsonObject
                recordResult(conversationId,call,result)
                result
            }
            allReceipts.addAll(receipts)
            val reads=taskReads(receipts)
            if(reads.isNotEmpty()) {
                if(!readInTurn && !ambiguousTasks(selectionReceipts)) selectionReceipts.clear()
                selectionReceipts.addAll(reads);readInTurn=true
            }
            if(reply.calls.none {ToolCatalog.mutates(it.name)} && ambiguousTasks(selectionReceipts) && looksMutation(text) && taskCandidates(selectionReceipts).none {explicitId(text,it)}) return@withLock finish(conversationId,selectionPrompt(selectionReceipts))
            val toConfirm=reply.calls.zip(receipts).filter {pair ->pair.second["pending_confirmation"]?.asBoolean==true}.map {pair ->PendingOperation(pair.first,pair.second.deepCopy())}
            if(toConfirm.isNotEmpty()) pending[conversationId]=toConfirm
            if(reply.calls.any {call ->ToolCatalog.mutates(call.name)} || receipts.any {r->r["ok"]?.asBoolean!=true}) return@withLock finish(conversationId,allReceipts.joinToString("\n") {r->render(r)},toConfirm.isNotEmpty())
        }
        finish(conversationId,"Limite de ferramentas atingido. Nenhuma nova alteração foi realizada.")
    }

    private suspend fun recordCalls(id:String,calls:List<ToolCall>,geminiParts:com.google.gson.JsonArray?=null) {
        // The UI must omit these protocol-only tool rows from user/assistant display bubbles.
        repository.addMessage(id,"tool",JsonObject().apply {addProperty("_taskai_protocol",1);addProperty("kind","calls");add("calls",gson.toJsonTree(calls));geminiParts?.let {add("gemini_parts",it)}}.toString())
    }
    private suspend fun recordResult(id:String,call:ToolCall,result:JsonObject) {
        repository.addMessage(id,"tool",JsonObject().apply {addProperty("_taskai_protocol",1);addProperty("kind","result");addProperty("call_id",call.id);addProperty("name",call.name);add("result",result)}.toString())
    }
    private suspend fun loadHistory(conversationId:String):List<AiMessage> {
        val decoded=repository.getMessages(conversationId).mapNotNull {message ->
        if(message.role in setOf("user","assistant")) AiMessage(message.role,message.content) else if(message.role=="tool") {
            val envelope=strictJson(message.content).asJsonObject
            when(envelope["kind"]?.asString) {
                "calls" -> AiMessage("assistant","",envelope["calls"].asJsonArray.map {e->e.asJsonObject.let {ToolCall(it["id"].asString,it["name"].asString,it["arguments"].asJsonObject)}},geminiParts=snapshotGeminiParts(envelope.getAsJsonArray("gemini_parts")))
                "result" -> AiMessage("tool",envelope["result"].toString(),toolCallId=envelope["call_id"].asString)
                else -> null
            }
        } else null
        }
        val complete=mutableListOf<AiMessage>();var index=0
        while(index<decoded.size) {
            val message=decoded[index]
            if(message.toolCalls.isNotEmpty()) {
                val results=decoded.drop(index+1).take(message.toolCalls.size)
                val ids=message.toolCalls.map {it.id}.toSet()
                if(results.size==ids.size && results.all {it.role=="tool"} && results.map {it.toolCallId}.toSet()==ids) {
                    complete.add(message);complete.addAll(results);index+=results.size+1
                } else index++ // A crashed/cancelled turn is not evidence that its attempted effects succeeded.
            } else {if(message.role!="tool") complete.add(message);index++}
        }
        val turns=mutableListOf<MutableList<AiMessage>>()
        complete.forEach {message ->
            if(message.role=="user") turns.add(mutableListOf(message)) else turns.lastOrNull()?.add(message)
        }
        val selected=mutableListOf<List<AiMessage>>();var count=0;var chars=0
        for(turn in turns.asReversed()) {
            val cost=turn.sumOf {it.content.length+gson.toJson(it.toolCalls).length+(it.geminiParts?.toString()?.length ?: 0)}
            if(count+turn.size>80 || chars+cost>180000) break
            selected.add(turn);count+=turn.size;chars+=cost
        }
        return selected.asReversed().flatten()
    }

    fun cancelPending(conversationId:String) { pending.remove(conversationId) }

    suspend fun confirm(conversationId:String):ChatOutcome = mutex.withLock {
        val operations=pending.remove(conversationId) ?: return@withLock finish(conversationId,"Nenhuma confirmação pendente. Solicite a operação novamente.")
        if(operations.any {System.nanoTime()-it.createdAt>java.util.concurrent.TimeUnit.MINUTES.toNanos(5)}) return@withLock finish(conversationId,"A confirmação expirou. Nenhuma exclusão foi realizada.")
        for(op in operations) {
            val current=strictJson(executor.execute(op.call)).asJsonObject
            if(current["pending_confirmation"]?.asBoolean!=true || current["data"]!=op.preview["data"]) return@withLock finish(conversationId,"Os dados mudaram. Nenhuma exclusão foi realizada. Selecione e confirme novamente.")
        }
        repository.addMessage(conversationId,"user","Confirmação pela interface para os IDs exibidos.")
        val receipts=operations.map {op ->
            val call=op.call.copy(id="ui-${java.util.UUID.randomUUID()}",arguments=op.call.arguments.deepCopy())
            recordCalls(conversationId,listOf(call))
            val result=strictJson(executor.execute(call,confirmed=true)).asJsonObject
            recordResult(conversationId,call,result)
            result
        }
        finish(conversationId,receipts.joinToString("\n") {render(it)})
    }

    private fun normalized(value:String)=java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"),"").lowercase(java.util.Locale.ROOT)
    private fun taskReads(receipts:List<JsonObject>)=receipts.filter {it["ok"]?.asBoolean==true && it["operation"]?.asString in setOf("list_tasks","search_tasks","get_task")}
    private fun taskCandidates(receipts:List<JsonObject>)=taskReads(receipts).flatMap {r -> val data=r["data"];(if(data.isJsonArray) data.asJsonArray.toList() else listOf(data)).map {it.asJsonObject["id"].asString}}.toSet()
    private fun ambiguousTasks(receipts:List<JsonObject>)=taskCandidates(receipts).size>1 || taskReads(receipts).any {(it["total"]?.asInt ?: 0)>1 || it["has_more"]?.asBoolean==true}
    private fun explicitId(text:String,id:String)=Regex("(?<![A-Za-z0-9_-])${Regex.escape(id)}(?![A-Za-z0-9_-])").containsMatchIn(text)
    private fun selectionPrompt(receipts:List<JsonObject>)="Encontrei mais de uma tarefa. Selecione o ID exato antes de alterar. Nenhuma alteração foi realizada:\n"+taskReads(receipts).joinToString("\n") {render(it)}
    private fun looksMutation(value:String)=Regex("\\b(cri\\w*|adicion\\w*|agend\\w*|marc\\w*|apagu\\w*|exclu\\w*|delet\\w*|remov\\w*|alter\\w*|atualiz\\w*|mud\\w*|conclu\\w*|finaliz\\w*|reabr\\w*|cancel\\w*|create\\w*|update\\w*|delete\\w*|complete\\w*)\\b").containsMatchIn(normalized(value))

    private suspend fun finish(id:String,text:String,pending:Boolean=false):ChatOutcome {
        repository.addMessage(id,"assistant",text)
        return ChatOutcome(text,pending)
    }
    private fun render(receipt:JsonObject):String {
        if(receipt["pending_confirmation"]?.asBoolean==true) return "Aguardando confirmação da interface. Excluir exatamente:\n"+receipt["data"].asJsonArray.joinToString("\n") {item -> val obj=item.asJsonObject; "• ${obj["title"]?.asString ?: obj["name"]?.asString} [${obj["id"].asString}]" }
        if(receipt["ok"]?.asBoolean!=true) return "Operação não confirmada: ${receipt["error"]?.asString ?: "aguardando confirmação da interface"}."
        val operation=receipt["operation"].asString
        if(operation=="summary") {
            val data=receipt["data"].asJsonObject
            return "Resumo em ${data["date"].asString} (${data["zone"].asString})\nTotal: ${data["total"].asInt} • Pendentes: ${data["pending"].asInt} • Concluídas: ${data["completed"].asInt}\nHoje: ${data["today"].asInt} • Atrasadas: ${data["overdue"].asInt}"
        }
        val label=mapOf("list_tasks" to "Tarefa","search_tasks" to "Tarefa","get_task" to "Tarefa","list_categories" to "Categoria","list_projects" to "Projeto","create_task" to "Tarefa criada","update_task" to "Tarefa atualizada","complete_task" to "Tarefa concluída","reopen_task" to "Tarefa reaberta","delete_task" to "Tarefa excluída","create_category" to "Categoria criada","update_category" to "Categoria atualizada","delete_category" to "Categoria excluída","create_project" to "Projeto criado","update_project" to "Projeto atualizado","delete_project" to "Projeto excluído")[operation] ?: "Resultado de $operation"
        val data=receipt["data"]
        val objects=if(data.isJsonArray) data.asJsonArray.toList() else listOf(data)
        val rows=objects.joinToString("\n") { item ->
            val obj=item.asJsonObject
            val name=obj["title"]?.asString ?: obj["name"]?.asString ?: obj.toString()
            val details=listOf("due_date" to "Data", "due_time" to "Horário", "priority" to "Prioridade", "status" to "Status", "description" to "Descrição", "recurrence_rule" to "Recorrência")
                .filter { (field,_) -> obj.has(field) }
                .joinToString(" • ") { (field,title) -> "$title: ${obj[field].takeUnless {it.isJsonNull}?.asString?.takeIf {it.isNotBlank()} ?: "não definido"}" }
            "$label: $name [${obj["id"]?.asString ?: "resumo"}]."+if(details.isEmpty()) "" else "\n$details"
        }.ifBlank {"Nenhum resultado encontrado."}
        // Counts are supplied by the actual executor, never inferred from the displayed rows.
        val metadata=listOf("returned" to "Retornados", "total" to "Total", "has_more" to "has_more", "count" to "Quantidade")
            .filter { (field,_) -> receipt.has(field) }.map { (field,title) -> "$title: ${receipt[field].asString}" }
        return (listOf(rows)+metadata+listOfNotNull(receipt["notice"]?.takeUnless {it.isJsonNull}?.asString)).joinToString("\n")
    }
}
