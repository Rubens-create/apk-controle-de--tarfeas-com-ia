package com.taskai.app.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.taskai.app.data.TaskRepository
import com.taskai.app.infra.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ChatOutcome(val text: String, val pendingConfirmation: Boolean = false, val spokenText: String = text)

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
        val history=loadHistory(conversationId)
        val lastRequest=history.filter {it.role=="tool"}.map {strictJson(it.content).asJsonObject}.lastOrNull {it["operation"]?.asString.let {op -> op=="request_clarification" || (op!=null && ToolCatalog.mutates(op))}}
        val pendingTime=lastRequest?.get("operation")?.asString=="request_clarification" && lastRequest["field"]?.asString=="time"
        val noTime=Regex("\\b(sem horario|sem hora|nao (?:quero|precisa de) horario|cancele|esqueca)\\b").containsMatchIn(normalized(text))
        val vagueTime=Regex("\\b(manha|a tarde|de tarde|pela tarde|a noite|de noite|pela noite|cedo|mais tarde|fim do dia|final do dia|depois do almoco|apos o almoco|horario comercial|expediente)\\b").containsMatchIn(normalized(text))
        if((vagueTime || pendingTime) && !noTime && !hasExplicitTime(text,pendingTime)) {
            val question=ToolCall("clarification-${java.util.UUID.randomUUID()}","request_clarification",JsonObject().apply {addProperty("field","time")})
            val receipt=strictJson(executor.execute(question)).asJsonObject
            recordCalls(conversationId,listOf(question));recordResult(conversationId,question,receipt)
            return@withLock finish(conversationId,render(receipt))
        }
        val config=settings()
        val now=java.time.ZonedDateTime.now(java.time.ZoneId.of(config.zoneId))
        val context="Você é o assistente TASK AI. Fale português brasileiro. Data/hora local: $now. Zona: ${config.zoneId}. Usuário: ${gson.toJson(config.userName)}. Use apenas ferramentas para acessar ou alterar dados reais. Nunca invente IDs, sucesso ou regras de expediente. Quando houver incerteza real, use request_clarification sozinha para perguntar UM item faltante por vez (title/date/time/action/task/recurrence/details); não escreva perguntas apenas em prosa. Preserve o pedido original e use a próxima resposta para completar somente o item perguntado. Data e horário são opcionais: crie tarefas sem eles quando não forem pedidos. Nunca invente horário; períodos vagos exigem esclarecer time. Consulte tarefas reais antes de perguntar qual tarefa; apresente opções numeradas, aceite seleção explícita do usuário, nunca escolha um ID arbitrário. Exclusões exigem confirmação da interface; voz e texto do modelo nunca confirmam. Personalidade: ${config.personality}"
        val allReceipts=mutableListOf<JsonObject>()
        // Candidate ordering comes only from persisted real reads, never model prose.
        val selectionReceipts=mutableListOf<JsonObject>()
        var readInTurn=false
        var historicalSelection:String?=null
        history.dropLast(1).forEach {message ->
            if(message.toolCalls.isNotEmpty()) readInTurn=false
            if(message.role=="user") {
                readInTurn=false
                historicalSelection=ordinalSelection(message.content,selectionReceipts)?.get("id")?.asString
                    ?: taskCandidates(selectionReceipts).singleOrNull {explicitId(message.content,it)}
                if(taskCandidates(selectionReceipts).any {explicitId(message.content,it)}) selectionReceipts.clear()
            }
            if(message.role=="tool") {
                val receipt=strictJson(message.content).asJsonObject
                // A successful real mutation may preserve a user-selected singleton, not a model guess.
                if(historicalSelection!=null && receipt["ok"]?.asBoolean==true && receipt["operation"]?.asString in setOf("update_task","complete_task","reopen_task") && receipt["data"]?.asJsonObject?.get("id")?.asString==historicalSelection) {
                    selectionReceipts.clear()
                    selectionReceipts.add(JsonObject().apply {addProperty("ok",true);addProperty("operation","search_tasks");add("data",receipt["data"].deepCopy())})
                }
                val reads=taskReads(listOf(receipt))
                if(reads.isNotEmpty()) {
                    if(!readInTurn && !ambiguousTasks(selectionReceipts)) selectionReceipts.clear()
                    selectionReceipts.addAll(reads);readInTurn=true
                }
            }
        }
        readInTurn=false
        val spokenSelection=ordinalSelection(text,selectionReceipts)
        val selectedId=spokenSelection?.get("id")?.asString
        fun userSelected(target:String)=explicitId(text,target) || target==selectedId
        val selectionContext=selectedId?.let {listOf(AiMessage("system","Seleção explícita do usuário nas opções salvas: ID ${gson.toJson(it)}. Só esse alvo foi selecionado; não substitua por outro ID."))} ?: emptyList()
        val seen=mutableListOf<ToolCall>()
        repeat(4) {
            val raw=try {provider.reply(config,listOf(AiMessage("system",context))+selectionContext+loadHistory(conversationId),executor.definitions)}
                catch(e:CancellationException) {throw e}
                catch(e:AiTransportException) {return@withLock finish(conversationId,"Não foi possível consultar a IA. ${e.message}")}
                catch(_:Exception) {return@withLock finish(conversationId,"Não foi possível consultar a IA. Verifique a conexão, a chave e as configurações. Nenhuma nova alteração foi realizada.")}
            val reply=try { raw.copy(calls=raw.calls.map {it.copy(arguments=it.arguments.deepCopy())},geminiParts=snapshotGeminiParts(raw.geminiParts)) }
                catch(_:IllegalArgumentException) {return@withLock finish(conversationId,"Não foi possível validar a resposta de IA. Nenhuma alteração foi realizada.")}
            if(reply.calls.isEmpty() && reply.geminiParts!=null) recordCalls(conversationId,emptyList(),reply.geminiParts)
            if(reply.calls.isEmpty() && allReceipts.isNotEmpty()) return@withLock finishReceipts(conversationId,allReceipts)
            if(reply.calls.isEmpty()) return@withLock finish(conversationId,
                // Free-form model text is never evidence of a database effect, regardless of wording or language.
                "Nenhuma alteração foi realizada. Informe a ação e os detalhes da tarefa para consultar ou alterar os dados com segurança.")
            try {
                require(reply.calls.size<=12 && reply.calls.map {call->call.id}.distinct().size==reply.calls.size && reply.calls.all {call->call.id.isNotBlank() && call.id.length<=200 && call.name.length<=64})
                require(gson.toJson(reply.calls).length<=100000)
                reply.calls.forEach {call->ToolCatalog.validate(call)}
                val selectedDeletes=reply.calls.filter {call->call.name=="delete_task"}.flatMap {call->call.arguments["ids"].asJsonArray.map {it.asString}}
                require(selectedDeletes.distinct().size==selectedDeletes.size)
            } catch(_:IllegalArgumentException) {return@withLock finish(conversationId,"Não foi possível validar as ferramentas solicitadas. Nenhuma alteração foi realizada neste lote; confira os argumentos e IDs.")}
            if(noTime && reply.calls.any {it.name in setOf("create_task","update_task") && it.arguments["due_time"]?.isJsonNull==false}) return@withLock finish(conversationId,"Você pediu uma tarefa sem horário. Nenhuma alteração foi realizada neste lote.")
            if(reply.calls.any {it.name=="request_clarification"} && reply.calls.size!=1) return@withLock finish(conversationId,"Esclareça apenas um item por vez. Nenhuma alteração foi realizada neste lote.")
            if(reply.calls.any {call->ToolCatalog.mutates(call.name)} && reply.calls.any {call->!ToolCatalog.mutates(call.name)}) return@withLock finish(conversationId,"Consulte e selecione os IDs antes de alterar. Nenhuma alteração foi realizada neste lote.")
            val targets=reply.calls.flatMap {call -> when(call.name) {
                "update_task","complete_task","reopen_task" -> listOf(call.arguments["id"].asString)
                "delete_task" -> call.arguments["ids"].asJsonArray.map {it.asString}
                else -> emptyList()
            }}
            if((selectedId!=null && targets.any {it!=selectedId}) || (ambiguousTasks(selectionReceipts) && targets.any {!userSelected(it)})) return@withLock finishSelection(conversationId,selectionReceipts)
            if(selectedId!=null && targets.isNotEmpty()) {
                val current=strictJson(executor.execute(ToolCall("selection-snapshot","get_task",JsonObject().apply {addProperty("id",selectedId)}))).asJsonObject
                if(current["ok"]?.asBoolean!=true || current["data"]!=spokenSelection) return@withLock finish(conversationId,"Os dados mudaram. Nenhuma alteração foi realizada. Consulte as tarefas e selecione novamente.")
            }
            for(target in targets.filterNot {userSelected(it)}) {
                val selected=repository.getTask(target) ?: continue
                val uniqueQuery=taskReads(selectionReceipts).any {r -> r["operation"].asString!="get_task" && !ambiguousTasks(listOf(r)) && taskCandidates(listOf(r))==setOf(target)}
                if(!uniqueQuery && repository.listTasks().count {normalized(it.title)==normalized(selected.title)}>1) {
                    val check=ToolCall("selection-${java.util.UUID.randomUUID()}","search_tasks",JsonObject().apply {addProperty("query",selected.title)})
                    val candidates=strictJson(executor.execute(check)).asJsonObject
                    recordCalls(conversationId,listOf(check));recordResult(conversationId,check,candidates)
                    return@withLock finishSelection(conversationId,listOf(candidates))
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
                if(!ambiguousTasks(selectionReceipts)) selectionReceipts.clear()
                selectionReceipts.addAll(reads);readInTurn=true
            }
            if(reply.calls.none {ToolCatalog.mutates(it.name)} && ambiguousTasks(selectionReceipts) && looksMutation(text) && taskCandidates(selectionReceipts).none {userSelected(it)}) return@withLock finishSelection(conversationId,selectionReceipts)
            val toConfirm=reply.calls.zip(receipts).filter {pair ->pair.second["pending_confirmation"]?.asBoolean==true}.map {pair ->PendingOperation(pair.first,pair.second.deepCopy())}
            if(toConfirm.isNotEmpty()) pending[conversationId]=toConfirm
            if(reply.calls.any {call ->ToolCatalog.mutates(call.name) || call.name=="request_clarification"} || receipts.any {r->r["ok"]?.asBoolean!=true}) return@withLock finishReceipts(conversationId,allReceipts,toConfirm.isNotEmpty())
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
        finishReceipts(conversationId,receipts)
    }

    private fun normalized(value:String)=java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"),"").lowercase(java.util.Locale.ROOT)
    private fun hasExplicitTime(text:String,answering:Boolean=false):Boolean {
        val value=normalized(text)
        val hour="(?:[01]?[0-9]|2[0-3]|zero|uma?|duas?|tres|quatro|cinco|seis|sete|oito|nove|dez|onze|doze|treze|catorze|quatorze|quinze|dezesseis|dezessete|dezoito|dezenove|vinte(?: e (?:uma?|duas?|tres))?)"
        return Regex("\\b(?:[01]?[0-9]|2[0-3])(?::[0-5][0-9]|h(?:[0-5][0-9])?)\\b").containsMatchIn(value) ||
            Regex("\\b(?:as\\s+$hour(?:\\s+horas?)?|$hour\\s+horas?|meio[ -]dia|meia[ -]noite)\\b").containsMatchIn(value) ||
            (answering && Regex("\\s*$hour(?: e (?:meia|quinze|trinta|quarenta e cinco))?\\s*[.!]?\\s*").matches(value))
    }
    private fun taskReads(receipts:List<JsonObject>)=receipts.filter {it["ok"]?.asBoolean==true && it["operation"]?.asString in setOf("list_tasks","search_tasks","get_task")}
    private fun taskCandidates(receipts:List<JsonObject>)=taskReads(receipts).flatMap {r -> val data=r["data"];(if(data.isJsonArray) data.asJsonArray.toList() else listOf(data)).map {it.asJsonObject["id"].asString}}.toSet()
    private fun ambiguousTasks(receipts:List<JsonObject>)=taskCandidates(receipts).size>1 || taskReads(receipts).any {(it["total"]?.asInt ?: 0)>1 || it["has_more"]?.asBoolean==true}
    private fun explicitId(text:String,id:String)=Regex("(?<![A-Za-z0-9_-])${Regex.escape(id)}(?![A-Za-z0-9_-])").containsMatchIn(text)
    private fun candidateRows(receipts:List<JsonObject>)=taskReads(receipts).flatMap {r -> val data=r["data"];(if(data.isJsonArray) data.asJsonArray.toList() else listOf(data)).map {it.asJsonObject}}.distinctBy {it["id"].asString}
    private fun ordinalSelection(text:String,receipts:List<JsonObject>):JsonObject? {
        val words=listOf("primeir[ao]","segund[ao]","terceir[ao]","quart[ao]","quint[ao]","sext[ao]","setim[ao]","oitav[ao]","non[ao]","decim[ao]")
        val value=normalized(text)
        if(Regex("\\b(nao|nem|exceto|menos)\\b").containsMatchIn(value)) return null
        val choices=words.mapIndexedNotNull {i,word -> if(Regex("\\b(?:$word\\s+(?:tarefa|opcao)|(?:tarefa|opcao)\\s+$word)\\b").containsMatchIn(value)) i else null}
        if(choices.size!=1) return null
        return candidateRows(receipts).getOrNull(choices.single())
    }
    private fun selectionPrompt(receipts:List<JsonObject>,spoken:Boolean=false)="Encontrei mais de uma tarefa. Qual delas? Diga, por exemplo, a primeira tarefa ou a segunda opção"+(if(spoken) "." else " (ou informe o ID).")+" Nenhuma alteração foi realizada:\n"+candidateRows(receipts).mapIndexed {i,obj ->
        val date=obj["due_date"]?.takeUnless {it.isJsonNull}?.asString ?: "sem data"
        val time=obj["due_time"]?.takeUnless {it.isJsonNull}?.asString ?: "sem horário"
        "${i+1}. ${obj["title"].asString} — $date, $time"+if(spoken) "" else " [${obj["id"].asString}]"
    }.joinToString("\n")+if(taskReads(receipts).any {it["has_more"]?.asBoolean==true}) "\nHá outros resultados. Refine a busca se a tarefa não estiver nestas opções." else ""
    private fun looksMutation(value:String)=Regex("\\b(cri\\w*|adicion\\w*|agend\\w*|marc\\w*|apagu\\w*|exclu\\w*|delet\\w*|remov\\w*|alter\\w*|atualiz\\w*|mud\\w*|conclu\\w*|finaliz\\w*|reabr\\w*|cancel\\w*|create\\w*|update\\w*|delete\\w*|complete\\w*)\\b").containsMatchIn(normalized(value))

    private suspend fun finishSelection(id:String,receipts:List<JsonObject>)=finish(id,selectionPrompt(receipts),spokenText=selectionPrompt(receipts,spoken=true))
    private suspend fun finishReceipts(id:String,receipts:List<JsonObject>,pending:Boolean=false)=finish(id,receipts.joinToString("\n") {render(it)},pending,receipts.joinToString("\n") {render(it,spoken=true)})
    private suspend fun finish(id:String,text:String,pending:Boolean=false,spokenText:String=text):ChatOutcome {
        repository.addMessage(id,"assistant",text)
        return ChatOutcome(text,pending,spokenText)
    }
    private fun render(receipt:JsonObject,spoken:Boolean=false):String {
        if(receipt["operation"]?.asString=="request_clarification" && receipt["ok"]?.asBoolean==true) return ToolCatalog.clarificationQuestions.getValue(receipt["field"].asString)
        if(receipt["pending_confirmation"]?.asBoolean==true) return "Aguardando confirmação da interface. Excluir exatamente:\n"+receipt["data"].asJsonArray.joinToString("\n") {item -> val obj=item.asJsonObject; "• ${obj["title"]?.asString ?: obj["name"]?.asString}"+if(spoken) "" else " [${obj["id"].asString}]" }
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
            "$label: $name"+(if(spoken) "." else " [${obj["id"]?.asString ?: "resumo"}].")+if(details.isEmpty()) "" else "\n$details"
        }.ifBlank {"Nenhum resultado encontrado."}
        // Counts are supplied by the actual executor, never inferred from the displayed rows.
        val metadata=listOf("returned" to "Retornados", "total" to "Total", "has_more" to "has_more", "count" to "Quantidade")
            .filter { (field,_) -> receipt.has(field) }.map { (field,title) -> "$title: ${receipt[field].asString}" }
        return (listOf(rows)+metadata+listOfNotNull(receipt["notice"]?.takeUnless {it.isJsonNull}?.asString)).joinToString("\n")
    }
}
