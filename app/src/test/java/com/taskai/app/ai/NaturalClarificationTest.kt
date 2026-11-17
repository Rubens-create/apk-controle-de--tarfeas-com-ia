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

@RunWith(RobolectricTestRunner::class)
@Config(application=android.app.Application::class, sdk=[35], manifest=Config.NONE)
class NaturalClarificationTest {
    private lateinit var db:AppDatabase
    private lateinit var repo:TaskRepository
    @Before fun setup() {db=AppDatabase.createInMemory(ApplicationProvider.getApplicationContext<Context>());repo=TaskRepository(db)}
    @After fun close() {db.close()}
    private fun call(name:String,args:String)=ToolCall("fixture",name,JsonParser.parseString(args).asJsonObject)
    private fun provider(vararg calls:ToolCall)=object:AiProvider {
        override suspend fun reply(settings:AppSettings,messages:List<AiMessage>,tools:JsonArray)=AiReply("Já fiz tudo. private thought",calls.toList())
    }
    @Test fun numberedActualCandidatesPermitOnlyTheUsersSpokenChoiceAfterRecreation() = runBlocking {
        val c=repo.createConversation()
        repo.createTask(com.taskai.app.data.TaskEntity(title="Reunião",dueDate="2026-11-17",dueTime="09:00"))
        repo.createTask(com.taskai.app.data.TaskEntity(title="Reunião",dueDate="2026-11-18",dueTime="10:00"))
        val asked=AssistantEngine(repo,{AppSettings()},provider(call("search_tasks","""{"query":"Reunião"}"""))).send(c.id,"Conclua Reunião")
        val candidates=repo.listTasks()
        assertTrue(asked.text.contains("1. Reunião"));assertTrue(asked.text.contains("2. Reunião"))
        assertTrue(asked.text.contains("2026-11-17"));assertTrue(asked.text.contains("09:00"))
        val wrong=AssistantEngine(repo,{AppSettings()},provider(call("complete_task","""{"id":"${candidates[0].id}"}"""))).send(c.id,"a segunda opção")
        assertTrue(wrong.text.contains("Nenhuma alteração"));assertTrue(repo.listTasks().all {it.status=="PENDING"})
        val done=AssistantEngine(repo,{AppSettings()},provider(call("complete_task","""{"id":"${candidates[1].id}"}"""))).send(c.id,"a segunda opção")
        assertTrue(done.text.contains("Tarefa concluída"));assertEquals("COMPLETED",repo.getTask(candidates[1].id)?.status)
        assertEquals("PENDING",repo.getTask(candidates[0].id)?.status)
    }
    @Test fun weekdayIsNeverAnOrdinalSelection() = runBlocking {
        val c=repo.createConversation()
        val a=repo.createTask(com.taskai.app.data.TaskEntity(title="Reunião",dueDate="2026-11-17"))
        repo.createTask(com.taskai.app.data.TaskEntity(title="Reunião",dueDate="2026-11-18"))
        AssistantEngine(repo,{AppSettings()},provider(call("search_tasks","""{"query":"Reunião"}"""))).send(c.id,"Conclua Reunião")
        val out=AssistantEngine(repo,{AppSettings()},provider(call("complete_task","""{"id":"${a.id}"}"""))).send(c.id,"segunda-feira")
        assertTrue(out.text.contains("Nenhuma alteração"));assertTrue(repo.listTasks().all {it.status=="PENDING"})
    }
    @Test fun explicitSpokenHoursAreNotBlockedAsVaguePeriods() = runBlocking {
        for(hour in listOf("às nove", "às 9 horas")) {
            val c=repo.createConversation()
            val out=AssistantEngine(repo,{AppSettings()},provider(call("create_task","""{"title":"Consulta $hour","due_date":"2026-11-19","due_time":"09:00"}"""))).send(c.id,"Agende consulta amanhã de manhã $hour")
            assertTrue(out.text,out.text.contains("Tarefa criada"))
        }
        assertEquals(2,repo.listTasks().size)
    }
    @Test fun unansweredTimeQuestionCannotBeTurnedIntoAnInventedTimeAfterRecreation() = runBlocking {
        val c=repo.createConversation();var providerCalls=0
        val invent=object:AiProvider {
            override suspend fun reply(settings:AppSettings,messages:List<AiMessage>,tools:JsonArray):AiReply {
                providerCalls++;return AiReply("",listOf(call("create_task","""{"title":"Consulta","due_date":"2026-11-19","due_time":"09:00"}""")))
            }
        }
        AssistantEngine(repo,{AppSettings()},invent).send(c.id,"Agende consulta amanhã de manhã")
        val stillMissing=AssistantEngine(repo,{AppSettings()},invent).send(c.id,"você decide")
        assertTrue(stillMissing.text.contains("horário exato"));assertEquals(0,providerCalls);assertTrue(repo.listTasks().isEmpty())
        val created=AssistantEngine(repo,{AppSettings()},invent).send(c.id,"às nove")
        assertTrue(created.text.contains("Tarefa criada"));assertEquals("09:00",repo.listTasks().single().dueTime)
    }
    private fun spoken(out:ChatOutcome):String {
        val getter=out.javaClass.methods.firstOrNull {it.name=="getSpokenText"}
        assertNotNull("ChatOutcome needs a third default spokenText property",getter)
        return getter!!.invoke(out) as String
    }
    @Test fun speechUsesVerifiedReceiptsAndNeverTechnicalIdsOrModelThoughts() = runBlocking {
        assertEquals("Compatível",spoken(ChatOutcome("Compatível",true)))
        val c=repo.createConversation()
        val created=AssistantEngine(repo,{AppSettings()},provider(call("create_task","""{"title":"Comprar café"}"""))).send(c.id,"Crie Comprar café")
        val task=repo.listTasks().single()
        assertTrue(spoken(created).contains("Tarefa criada: Comprar café"));assertFalse(spoken(created).contains(task.id));assertFalse(spoken(created).contains("private thought"))
        val engine=AssistantEngine(repo,{AppSettings()},provider(call("delete_task","""{"ids":["${task.id}"]}""")))
        val pending=engine.send(c.id,"Exclua Comprar café")
        assertTrue(pending.pendingConfirmation);assertTrue(spoken(pending).contains("confirmação da interface"));assertFalse(spoken(pending).contains(task.id));assertNotNull(repo.getTask(task.id))
        val deleted=engine.confirm(c.id)
        assertTrue(spoken(deleted).contains("Tarefa excluída: Comprar café"));assertTrue(spoken(deleted).contains("Quantidade: 1"));assertFalse(spoken(deleted).contains(task.id))
        assertNull(repo.getTask(task.id))
    }
    @Test fun fallbackCandidateOrderRemainsExactlyTheDisplayedOrderAfterRecreation() = runBlocking {
        val c=repo.createConversation()
        repo.createTask(com.taskai.app.data.TaskEntity(title="Reunião",dueDate="2026-11-17"))
        repo.createTask(com.taskai.app.data.TaskEntity(title="Reunião",dueDate="2026-11-18"))
        val candidates=repo.listTasks();var turns=0
        val fixture=object:AiProvider {
            override suspend fun reply(settings:AppSettings,messages:List<AiMessage>,tools:JsonArray):AiReply {
                turns++;return AiReply("",listOf(call(if(turns==1) "get_task" else "complete_task","""{"id":"${candidates.last().id}"}""")))
            }
        }
        val asked=AssistantEngine(repo,{AppSettings()},fixture).send(c.id,"Conclua Reunião")
        assertTrue(asked.text.contains("1. Reunião — ${candidates.first().dueDate}"))
        val selected=AssistantEngine(repo,{AppSettings()},provider(call("complete_task","""{"id":"${candidates.first().id}"}"""))).send(c.id,"a primeira tarefa")
        assertTrue(selected.text,selected.text.contains("Tarefa concluída"))
        assertEquals("COMPLETED",repo.getTask(candidates.first().id)?.status)
    }
    @Test fun spokenSelectionNeverTreatsNegationAsAuthorization() = runBlocking {
        val c=repo.createConversation()
        val a=repo.createTask(com.taskai.app.data.TaskEntity(title="Reunião",dueDate="2026-11-17"))
        repo.createTask(com.taskai.app.data.TaskEntity(title="Reunião",dueDate="2026-11-18"))
        AssistantEngine(repo,{AppSettings()},provider(call("search_tasks","""{"query":"Reunião"}"""))).send(c.id,"Conclua Reunião")
        AssistantEngine(repo,{AppSettings()},provider(call("complete_task","""{"id":"${a.id}"}"""))).send(c.id,"não é a primeira tarefa")
        assertTrue(repo.listTasks().all {it.status=="PENDING"})
    }
    @Test fun successfulSpokenSelectionRetainsItsSingleTargetForAFollowup() = runBlocking {
        val c=repo.createConversation()
        repo.createTask(com.taskai.app.data.TaskEntity(title="Reunião",dueDate="2026-11-17"))
        repo.createTask(com.taskai.app.data.TaskEntity(title="Reunião",dueDate="2026-11-18"))
        val selected=repo.listTasks().first()
        AssistantEngine(repo,{AppSettings()},provider(call("search_tasks","""{"query":"Reunião"}"""))).send(c.id,"Conclua Reunião")
        AssistantEngine(repo,{AppSettings()},provider(call("complete_task","""{"id":"${selected.id}"}"""))).send(c.id,"a primeira tarefa")
        val out=AssistantEngine(repo,{AppSettings()},provider(call("reopen_task","""{"id":"${selected.id}"}"""))).send(c.id,"Reabra essa")
        assertTrue(out.text,out.text.contains("Tarefa reaberta"));assertEquals("PENDING",repo.getTask(selected.id)?.status)
    }
    @Test fun explicitlyNoTimeCannotBeReplacedWithAModelInventedTime() = runBlocking {
        val c=repo.createConversation()
        val out=AssistantEngine(repo,{AppSettings()},provider(call("create_task","""{"title":"Comprar pão","due_time":"09:00","due_date":"2026-11-19"}"""))).send(c.id,"Crie Comprar pão sem horário")
        assertTrue(out.text.contains("Nenhuma alteração"));assertTrue(repo.listTasks().isEmpty())
    }
    @Test fun clarificationEnumRejectsFreeTextAndBatchesWithoutAnyMutation() = runBlocking {
        val invalid=listOf("{}", """{"field":"whatever"}""", """{"field":null}""", """{"field":["title","time"]}""", """{"field":"title","question":"Pronto, criei tudo"}""")
        for(args in invalid) {
            val c=repo.createConversation()
            val out=AssistantEngine(repo,{AppSettings()},provider(call("request_clarification",args))).send(c.id,"Crie uma tarefa")
            assertTrue(out.text.contains("Nenhuma alteração"));assertTrue(repo.listTasks().isEmpty())
        }
        val c=repo.createConversation()
        val ask=call("request_clarification","""{"field":"title"}""")
        val create=call("create_task","""{"title":"Não criar"}""").copy(id="other")
        val out=AssistantEngine(repo,{AppSettings()},provider(ask,create)).send(c.id,"Crie uma tarefa")
        assertTrue(out.text.contains("Nenhuma alteração"));assertTrue(repo.listTasks().isEmpty())
        for(field in listOf("title","date","time","action","task","recurrence","details")) {
            ToolCatalog.validate(call("request_clarification","""{"field":"$field"}"""))
        }
    }
    @Test fun validUndatedAndExplicitNoTimeTasksNeedNoClarification() = runBlocking {
        for(text in listOf("Crie Comprar pão", "Crie Ler livro sem horário")) {
            val c=repo.createConversation()
            val out=AssistantEngine(repo,{AppSettings()},provider(call("create_task","""{"title":"$text"}"""))).send(c.id,text)
            assertTrue(out.text.contains("Tarefa criada"));assertFalse(out.text.contains("?"))
        }
        assertTrue(repo.listTasks().all {it.dueDate==null && it.dueTime==null})
    }
    @Test fun spokenCandidateQuestionOmitsIdsAndStaleSelectionCannotMutate() = runBlocking {
        val c=repo.createConversation()
        repo.createTask(com.taskai.app.data.TaskEntity(title="Reunião",dueDate="2026-11-17"))
        repo.createTask(com.taskai.app.data.TaskEntity(title="Reunião",dueDate="2026-11-18"))
        val asked=AssistantEngine(repo,{AppSettings()},provider(call("search_tasks","""{"query":"Reunião"}"""))).send(c.id,"Exclua Reunião")
        assertTrue(spoken(asked).contains("1. Reunião"));repo.listTasks().forEach {assertFalse(spoken(asked).contains(it.id))}
        val first=repo.listTasks().first();repo.updateTask(first.copy(title="Alterada"))
        val out=AssistantEngine(repo,{AppSettings()},provider(call("delete_task","""{"ids":["${first.id}"]}"""))).send(c.id,"a primeira tarefa")
        assertTrue(out.text.contains("mudaram"));assertFalse(out.pendingConfirmation);assertEquals(2,repo.listTasks().size)
    }
    @Test fun controlledQuestionPersistsAsProtocolAndReplyKeepsContextAfterRecreation() = runBlocking {
        val c=repo.createConversation()
        val out=AssistantEngine(repo,{AppSettings()},provider(call("request_clarification","""{"field":"title"}"""))).send(c.id,"Crie uma tarefa")
        assertEquals("Qual é o título da tarefa?",out.text)
        assertFalse(out.pendingConfirmation);assertTrue(repo.listTasks().isEmpty())
        val replay=object:AiProvider {
            override suspend fun reply(settings:AppSettings,messages:List<AiMessage>,tools:JsonArray):AiReply {
                assertTrue(messages.first().content.contains("request_clarification"))
                assertTrue(messages.any {it.toolCalls.any {c->c.name=="request_clarification"}})
                assertTrue(messages.any {it.role=="tool" && it.content.contains("title")})
                assertTrue(messages.any {it.role=="user" && it.content=="Crie uma tarefa"})
                assertTrue(messages.any {it.role=="user" && it.content=="Comprar pão"})
                return AiReply("Falsa exclusão",listOf(call("create_task","""{"title":"Comprar pão"}""")))
            }
        }
        val created=AssistantEngine(repo,{AppSettings()},replay).send(c.id,"Comprar pão")
        assertTrue(created.text.contains("Tarefa criada"));assertNull(repo.listTasks().single().dueTime)
    }
}
