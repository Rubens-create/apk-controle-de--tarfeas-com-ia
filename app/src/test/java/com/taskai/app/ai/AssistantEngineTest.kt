package com.taskai.app.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.taskai.app.data.AppDatabase
import com.taskai.app.data.TaskRepository
import com.taskai.app.infra.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Scripted AiProvider is a test fixture only; every mutation uses the real in-memory Room DB. */
@RunWith(RobolectricTestRunner::class)
@Config(application=android.app.Application::class, sdk=[35], manifest=Config.NONE)
class AssistantEngineTest {
    private lateinit var db:AppDatabase
    private lateinit var repo:TaskRepository
    @Before fun setup() {db=AppDatabase.createInMemory(ApplicationProvider.getApplicationContext<Context>());repo=TaskRepository(db)}
    @After fun close(){db.close()}
    private fun call(name:String,args:String)=ToolCall("fixture-call",name,JsonParser.parseString(args).asJsonObject)
    @Test fun ambiguousResolutionRoutesCannotAuthorizeAnArbitraryMutation() = runBlocking {
        val a=repo.createTask(com.taskai.app.data.TaskEntity(title="Reunião",dueDate="2026-11-17"))
        val b=repo.createTask(com.taskai.app.data.TaskEntity(title="Reunião",dueDate="2026-11-18"))
        val resolutions=listOf(
            listOf(call("list_tasks","{}")),
            listOf(call("search_tasks","""{"query":"Reunião"}""")),
            listOf(ToolCall("get-a","get_task",com.google.gson.JsonObject().apply {addProperty("id",a.id)}),ToolCall("get-b","get_task",com.google.gson.JsonObject().apply {addProperty("id",b.id)}))
        )
        for(resolution in resolutions) {
            val c=repo.createConversation();var turns=0
            val fixture=object:AiProvider {override suspend fun reply(settings:AppSettings,messages:List<AiMessage>,tools:JsonArray):AiReply {
                turns++;return if(turns==1) AiReply("",resolution) else AiReply("Done",listOf(ToolCall("chosen","complete_task",com.google.gson.JsonObject().apply {addProperty("id",a.id)})))
            }}
            // Safety cannot depend on recognizing a Portuguese mutation verb in the user's wording.
            val out=AssistantEngine(repo,{AppSettings()},fixture).send(c.id,"Reunião pronta")
            assertTrue("${resolution.map {it.name}} must ask for user selection",out.text.contains("ID"))
            assertTrue(out.text.contains(a.id));assertTrue(out.text.contains(b.id))
            assertTrue("No arbitrary model selection may write",repo.listTasks().all {it.status=="PENDING"})
            assertFalse(out.pendingConfirmation)
        }
    }
    @Test fun taskRenderingShowsActualDetailsAndExactTruncationMetadata() = runBlocking {
        val c=repo.createConversation()
        repeat(101) {repo.createTask(com.taskai.app.data.TaskEntity(title="Tarefa $it",description="Descrição real",dueDate="2026-11-18",dueTime="14:35",priority="URGENTE",recurrenceRule="FREQ=WEEKLY;BYDAY=WE"))}
        var calls=0
        val fixture=object:AiProvider {override suspend fun reply(settings:AppSettings,messages:List<AiMessage>,tools:JsonArray):AiReply {
            calls++;return if(calls==1) AiReply("",listOf(call("list_tasks","{}"))) else AiReply("Todas as 999 tarefas foram concluídas")
        }}
        val out=AssistantEngine(repo,{AppSettings()},fixture).send(c.id,"Mostre as tarefas com detalhes")
        listOf("2026-11-18","14:35","URGENTE","PENDING","Descrição real","FREQ=WEEKLY;BYDAY=WE","Retornados: 100","Total: 101","has_more: true","Resultado limitado; refine a busca para obter outros registros.").forEach {assertTrue("Missing actual result field: $it",out.text.contains(it))}
        assertFalse(out.text.contains("999 tarefas"));assertTrue(repo.listTasks().all {it.status=="PENDING"})
    }
    @Test fun geminiOriginalPartsReplayThroughRoomAfterEngineRecreation() = runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val parts=JsonParser.parseString("""[{"text":"private thought fixture","thought":true,"thoughtSignature":"thought-signature"},{"functionCall":{"name":"create_task","args":{"title":"Café"}},"thoughtSignature":"call-signature"},{"text":"Public fixture text","thoughtSignature":"text-signature"}]""").asJsonArray
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"candidates":[{"content":{"role":"model","parts":$parts}}]}"""))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"OK"}]}}]}"""))
            server.start(java.net.InetAddress.getByName("127.0.0.1"),0)
            val config=AppSettings(provider="GEMINI",model="gemini-fixture",apiKey="fixture-secret",endpoint=server.url("/v1beta").newBuilder().host("127.0.0.1").build().toString())
            val c=repo.createConversation()
            val first=AssistantEngine(repo,{config},HttpAiProvider()).send(c.id,"Crie Café")
            assertTrue(first.text.contains("Tarefa criada"));assertEquals(1,repo.listTasks().size)
            assertFalse(first.text.contains("private thought"));assertFalse(first.text.contains("signature"));assertFalse(first.text.contains("fixture-secret"))
            AssistantEngine(repo,{config},HttpAiProvider()).send(c.id,"Qual foi a tarefa?")
            assertNotNull(server.takeRequest(5,java.util.concurrent.TimeUnit.SECONDS))
            val request=server.takeRequest(5,java.util.concurrent.TimeUnit.SECONDS)
            assertNotNull("Recreated engine must issue an HTTP replay request",request)
            val replay=JsonParser.parseString(request!!.body.readUtf8()).asJsonObject["contents"].asJsonArray
            val original=replay.first {it.asJsonObject["role"].asString=="model"}.asJsonObject["parts"]
            assertEquals("All native parts and signatures must stay on their original ordered parts",parts,original)
            assertTrue(replay.any {it.asJsonObject["parts"].asJsonArray.any {p->p.asJsonObject.has("functionResponse")}})
            assertTrue(repo.getMessages(c.id).filter {it.role=="tool"}.any {it.content.contains("call-signature")})
            assertTrue(repo.getMessages(c.id).filter {it.role=="assistant"}.none {it.content.contains("private thought") || it.content.contains("signature") || it.content.contains("fixture-secret")})
        }
    }
    @Test fun replaysPersistedProtocolHistoryWithLocalDateTimeZone() = runBlocking {
        val conversation=repo.createConversation()
        val config=AppSettings(zoneId="Pacific/Kiritimati",userName="Ana")
        var calls=0
        val fixture=object:AiProvider {
            override suspend fun reply(settings:AppSettings,messages:List<AiMessage>,tools:JsonArray):AiReply {
                calls++
                if(calls==1) return AiReply("",listOf(call("create_task","""{"title":"Comprar café"}""")))
                val sys=messages.first {it.role=="system"}.content
                assertTrue(sys.contains(java.time.LocalDate.now(java.time.ZoneId.of(config.zoneId)).toString()))
                assertTrue(sys.contains(config.zoneId));assertTrue(sys.contains("Ana"));assertTrue(sys.contains("horário"))
                assertTrue(messages.any {it.role=="user" && it.content=="Crie Comprar café"})
                assertTrue(messages.any {it.role=="assistant" && it.toolCalls.any {c->c.name=="create_task"}})
                assertTrue(messages.any {it.role=="tool" && it.toolCallId=="fixture-call" && it.content.contains("Comprar café")})
                return AiReply("Contexto disponível.")
            }
        }
        AssistantEngine(repo,{config},fixture).send(conversation.id,"Crie Comprar café")
        AssistantEngine(repo,{config},fixture).send(conversation.id,"Qual foi a última tarefa?")
        assertEquals(2,calls)
    }
    @Test fun uiConfirmationReplaysFrozenIdsExactlyOnceWithoutCallingModelAgain() = runBlocking {
        val conversation=repo.createConversation()
        val target=repo.createTask(com.taskai.app.data.TaskEntity(title="Excluir A"))
        val other=repo.createTask(com.taskai.app.data.TaskEntity(title="Preservar B"))
        val args=com.google.gson.JsonObject().apply {add("ids",JsonArray().apply {add(target.id)})}
        var providerCalls=0
        val fixture=object:AiProvider { override suspend fun reply(settings:AppSettings,messages:List<AiMessage>,tools:JsonArray):AiReply {
            providerCalls++;return AiReply("Já excluí!",listOf(ToolCall("delete","delete_task",args)))
        } }
        val engine=AssistantEngine(repo,{AppSettings()},fixture)
        val pending=engine.send(conversation.id,"Exclua a tarefa A")
        assertTrue(pending.pendingConfirmation);assertTrue(pending.text.contains(target.title));assertTrue(pending.text.contains(target.id));assertFalse(pending.text.contains("Já excluí"))
        args.add("ids",JsonArray().apply {add(other.id)})
        val confirmed=engine.confirm(conversation.id)
        assertFalse(confirmed.pendingConfirmation);assertTrue(confirmed.text.contains(target.id))
        assertNull(repo.getTask(target.id));assertNotNull(repo.getTask(other.id));assertEquals(1,providerCalls)
        val repeated=engine.confirm(conversation.id)
        assertTrue(repeated.text.contains("Nenhuma"));assertNotNull(repo.getTask(other.id))
    }
    @Test fun noToolReceiptMeansNoMutationClaimEvenIfProviderSaysDone() = runBlocking {
        val conversation=repo.createConversation()
        val fixture=object:AiProvider {override suspend fun reply(settings:AppSettings,messages:List<AiMessage>,tools:JsonArray)=AiReply("Pronto, tarefa criada com sucesso!")}
        val outcome=AssistantEngine(repo,{AppSettings()},fixture).send(conversation.id,"Crie Comprar remédio")
        assertTrue(outcome.text.contains("Nenhuma alteração"));assertFalse(outcome.text.contains("criada com sucesso"));assertTrue(repo.listTasks().isEmpty())
    }
    @Test fun cancelAndChangedSelectionCannotReplayOldDestructiveActions() = runBlocking {
        val c=repo.createConversation();val task=repo.createTask(com.taskai.app.data.TaskEntity(title="Original"))
        val fixture=object:AiProvider {override suspend fun reply(settings:AppSettings,messages:List<AiMessage>,tools:JsonArray)=AiReply("",listOf(ToolCall("d","delete_task",com.google.gson.JsonObject().apply {add("ids",JsonArray().apply {add(task.id)})})))}
        val engine=AssistantEngine(repo,{AppSettings()},fixture)
        engine.send(c.id,"Exclua Original");engine.cancelPending(c.id);engine.confirm(c.id);assertNotNull(repo.getTask(task.id))
        engine.send(c.id,"Exclua Original");repo.updateTask(task.copy(title="Renomeada"))
        assertTrue(engine.confirm(c.id).text.contains("mudaram"));assertNotNull(repo.getTask(task.id))
    }
    @Test fun executesSearchThenUpdateUsingActualToolContext() = runBlocking {
        val c=repo.createConversation();val task=repo.createTask(com.taskai.app.data.TaskEntity(title="Dentista"))
        var turns=0
        val fixture=object:AiProvider {override suspend fun reply(settings:AppSettings,messages:List<AiMessage>,tools:JsonArray):AiReply {
            turns++
            if(turns==1)return AiReply("",listOf(call("search_tasks","""{"query":"Dentista"}""")))
            val actual=JsonParser.parseString(messages.last {it.role=="tool"}.content).asJsonObject["data"].asJsonArray.single().asJsonObject["id"].asString
            return AiReply("Tarefa apagada",listOf(ToolCall("update","update_task",com.google.gson.JsonObject().apply {addProperty("id",actual);addProperty("priority","ALTA")})))
        }}
        val outcome=AssistantEngine(repo,{AppSettings()},fixture).send(c.id,"Mude a prioridade do Dentista para alta")
        assertEquals(2,turns);assertEquals("ALTA",repo.getTask(task.id)?.priority);assertTrue(outcome.text.contains(task.id));assertFalse(outcome.text.contains("apagada"));assertFalse(outcome.text.contains("search_tasks"))
    }
    @Test fun repeatedToolCallsAreBoundedAndNeverMutate() = runBlocking {
        val c=repo.createConversation();var turns=0
        val fixture=object:AiProvider {override suspend fun reply(settings:AppSettings,messages:List<AiMessage>,tools:JsonArray):AiReply {turns++;return AiReply("",listOf(ToolCall("call-$turns","list_tasks",com.google.gson.JsonObject())))}}
        val out=AssistantEngine(repo,{AppSettings()},fixture).send(c.id,"Mostre tarefas")
        assertTrue(turns in 2..4);assertTrue(out.text.contains("Limite"));assertTrue(repo.listTasks().isEmpty())
    }
    @Test fun vaguePeriodsRequireExplicitTimeBeforeAnyProviderOrMutation() = runBlocking {
        val c=repo.createConversation();var calls=0
        val fixture=object:AiProvider {override suspend fun reply(settings:AppSettings,messages:List<AiMessage>,tools:JsonArray):AiReply {calls++;return AiReply("",listOf(call("create_task","""{"title":"Reunião","due_date":"2026-11-17","due_time":"09:00"}""")))}}
        val out=AssistantEngine(repo,{AppSettings()},fixture).send(c.id,"Agende reunião amanhã de manhã")
        assertTrue(out.text.contains("horário exato"));assertEquals(0,calls);assertTrue(repo.listTasks().isEmpty())
    }
    @Test fun ambiguousSearchMustAskForExactIdInsteadOfLettingModelChoose() = runBlocking {
        val c=repo.createConversation()
        val a=repo.createTask(com.taskai.app.data.TaskEntity(title="Reunião",dueDate="2026-11-17"));val b=repo.createTask(com.taskai.app.data.TaskEntity(title="Reunião",dueDate="2026-11-18"))
        var calls=0
        val fixture=object:AiProvider {override suspend fun reply(settings:AppSettings,messages:List<AiMessage>,tools:JsonArray):AiReply {
            calls++;return if(calls==1) AiReply("",listOf(call("search_tasks","""{"query":"Reunião"}"""))) else AiReply("",listOf(ToolCall("u","complete_task",com.google.gson.JsonObject().apply {addProperty("id",a.id)})))
        }}
        val out=AssistantEngine(repo,{AppSettings()},fixture).send(c.id,"Conclua Reunião")
        assertTrue(out.text.contains("ID"));assertTrue(out.text.contains(a.id));assertTrue(out.text.contains(b.id));assertEquals(1,calls);assertTrue(repo.listTasks().all {it.status=="PENDING"})
    }
    @Test fun providerFailureIsPersistedWithoutLeakingSecretOrClaimingSuccess() = runBlocking {
        val c=repo.createConversation()
        val fixture=object:AiProvider {override suspend fun reply(settings:AppSettings,messages:List<AiMessage>,tools:JsonArray):AiReply {throw java.io.IOException("fixture-secret endpoint body")}}
        val out=AssistantEngine(repo,{AppSettings()},fixture).send(c.id,"Crie lembrete")
        assertTrue(out.text.contains("Não foi possível"));assertFalse(out.text.contains("fixture-secret"));assertEquals(out.text,repo.getMessages(c.id).last().content)
    }
    @Test fun ambiguousSearchAndMutationInSameBatchCannotBypassSelection() = runBlocking {
        val c=repo.createConversation();val a=repo.createTask(com.taskai.app.data.TaskEntity(title="Reunião",dueDate="2026-11-17"));repo.createTask(com.taskai.app.data.TaskEntity(title="Reunião",dueDate="2026-11-18"))
        val fixture=object:AiProvider {override suspend fun reply(settings:AppSettings,messages:List<AiMessage>,tools:JsonArray)=AiReply("",listOf(call("search_tasks","""{"query":"Reunião"}"""),ToolCall("update","complete_task",com.google.gson.JsonObject().apply {addProperty("id",a.id)})))}
        AssistantEngine(repo,{AppSettings()},fixture).send(c.id,"Conclua Reunião")
        assertTrue(repo.listTasks().all {it.status=="PENDING"})
    }
    @Test fun invalidToolBatchCannotPartiallyMutateOrReuseCallIds() = runBlocking {
        val first=call("create_task","""{"title":"Válida"}""")
        val batches=listOf(listOf(first,first.copy(arguments=JsonParser.parseString("""{"title":"Outra"}""").asJsonObject)),listOf(first,ToolCall("invalid","create_task",JsonParser.parseString("""{"title":"Inválida","confirmed":true}""").asJsonObject)))
        for(batch in batches) {
            val c=repo.createConversation()
            val fixture=object:AiProvider {override suspend fun reply(settings:AppSettings,messages:List<AiMessage>,tools:JsonArray)=AiReply("Tudo criado",batch)}
            val out=AssistantEngine(repo,{AppSettings()},fixture).send(c.id,"Crie duas tarefas")
            assertTrue(out.text.contains("Nenhuma alteração"));assertTrue(repo.listTasks().isEmpty())
        }
    }
    @Test fun summaryIsDeterministicPortugueseRatherThanInventedModelCount() = runBlocking {
        val c=repo.createConversation();repo.createTask(com.taskai.app.data.TaskEntity(title="Única"))
        var turns=0
        val fixture=object:AiProvider {override suspend fun reply(settings:AppSettings,messages:List<AiMessage>,tools:JsonArray):AiReply {turns++;return if(turns==1) AiReply("",listOf(call("summary","{}"))) else AiReply("Há 1000 tarefas concluídas.")}}
        val out=AssistantEngine(repo,{AppSettings()},fixture).send(c.id,"Mostre o resumo")
        assertTrue(out.text.contains("Total: 1"));assertTrue(out.text.contains("Pendentes: 1"));assertFalse(out.text.contains("1000"))
    }
    @Test fun contextIsBoundedByWholeTurnsAndKeepsRecentMessages() = runBlocking {
        val c=repo.createConversation()
        repeat(105) {repo.addMessage(c.id,"user","Pergunta $it");repo.addMessage(c.id,"assistant","Resposta $it")}
        val fixture=object:AiProvider {override suspend fun reply(settings:AppSettings,messages:List<AiMessage>,tools:JsonArray):AiReply {
            assertTrue(messages.size<=81);assertEquals("system",messages.first().role);assertEquals("user",messages[1].role)
            assertTrue(messages.any {it.content=="Mensagem recente"});assertFalse(messages.any {it.content=="Pergunta 0"})
            return AiReply("Contexto recente disponível.")
        }}
        AssistantEngine(repo,{AppSettings()},fixture).send(c.id,"Mensagem recente")
        Unit
    }
    @Test fun unrecognizedSuccessWordingAlsoRequiresActualReceipt() = runBlocking {
        val c=repo.createConversation()
        val fixture=object:AiProvider {override suspend fun reply(settings:AppSettings,messages:List<AiMessage>,tools:JsonArray)=AiReply("Everything is on your calendar.")}
        val out=AssistantEngine(repo,{AppSettings()},fixture).send(c.id,"Tenho dentista amanhã às 14h")
        assertTrue(out.text.contains("Nenhuma alteração"));assertFalse(out.text.contains("Everything"));assertTrue(repo.listTasks().isEmpty())
    }
    @Test fun interruptedToolHistoryIsNotReplayedAsUnmatchedProviderCalls() = runBlocking {
        val c=repo.createConversation();repo.addMessage(c.id,"user","Pedido interrompido")
        repo.addMessage(c.id,"tool","""{"_taskai_protocol":1,"kind":"calls","calls":[{"id":"orphan","name":"list_tasks","arguments":{}}]}""")
        val fixture=object:AiProvider {override suspend fun reply(settings:AppSettings,messages:List<AiMessage>,tools:JsonArray):AiReply {assertFalse(messages.any {it.toolCalls.any {call->call.id=="orphan"}});return AiReply("Consulte novamente.")}}
        AssistantEngine(repo,{AppSettings()},fixture).send(c.id,"Mostre tarefas")
        assertTrue(repo.listTasks().isEmpty())
    }
    @Test fun successfulMutationUsesPersistedReceiptNotModelSuccessText() = runBlocking {
        val conversation=repo.createConversation()
        val fixture=object:AiProvider {
            override suspend fun reply(settings:AppSettings,messages:List<AiMessage>,tools:JsonArray)=AiReply("Excluí todas as tarefas!",listOf(call("create_task","""{"title":"Comprar pão"}""")))
        }
        val outcome=AssistantEngine(repo,{AppSettings()},fixture).send(conversation.id,"Crie Comprar pão")
        val saved=repo.listTasks().single()
        assertTrue(outcome.text.contains(saved.id));assertTrue(outcome.text.contains("Comprar pão"))
        assertFalse(outcome.text.contains("Excluí"));assertFalse(outcome.pendingConfirmation)
        val history=repo.getMessages(conversation.id)
        assertTrue(history.any {it.role=="tool" && it.content.contains(saved.id)})
        assertEquals(outcome.text,history.last().content)
    }
}
