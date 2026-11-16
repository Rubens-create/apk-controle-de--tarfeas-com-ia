# TASK AI - integration contracts

Package `com.taskai.app`. Kotlin 2.1.20, Compose BOM 2025.04.01, AGP 8.9.2, SDK 35, min 26, Java 17. Room 2.7.1 + KSP 2.1.20-1.0.32; Gson 2.12.1; OkHttp 4.12.0; WorkManager 2.10.1; coroutines 1.10.1. JVM tests JUnit4, Robolectric 4.14.1, coroutine-test 1.10.1, mockwebserver 4.12.0. No real user data seeded.

## data/ (domain agent owns)

Room entities public data classes, named arguments stable:
- TaskEntity(id:String=UUID, title:String, description:String="", dueDate:String?=null ISO yyyy-MM-dd, dueTime:String?=null HH:mm, priority:String="MEDIA" enum BAIXA/MEDIA/ALTA/URGENTE, status:String="PENDING" enum PENDING/COMPLETED, categoryId:String?=null, projectId:String?=null, recurrenceRule:String?=null RRULE subset FREQ=DAILY|WEEKLY|MONTHLY|YEARLY;INTERVAL=n;BYDAY=MO,TU;BYMONTHDAY=10, reminderEnabled:Boolean=false, reminderMinutes:Int=15, reminderDatetime:Long?=null epoch millis, createdAt:Long=now, updatedAt:Long=now, completedAt:Long?=null).
- CategoryEntity(id:String=UUID,name:String,color:String="#F282AF",icon:String="label",createdAt:Long=now)
- ProjectEntity(id:String=UUID,name:String,description:String="",color:String="#F282AF",createdAt:Long=now)
- ConversationEntity(id:String=UUID,title:String="Nova conversa",createdAt:Long=now,updatedAt:Long=now)
- MessageEntity(id:String=UUID,conversationId:String,role:String,content:String,createdAt:Long=now)
- ActivityLogEntity(id:String=UUID,action:String,entityType:String,entityId:String,details:String,createdAt:Long=now)

AppDatabase.get(context):AppDatabase singleton, AppDatabase.createInMemory(context) tests. DAO access as db.dao():AppDao. Database exports schema.
TaskRepository(db:AppDatabase, now:()->Long={System.currentTimeMillis()}): public flows `tasks:Flow<List<TaskEntity>>`, `categories`, `projects`, `logs`, `conversations`; `messages(conversationId):Flow<List<MessageEntity>>`.
Suspend methods:
- createTask(task):TaskEntity (validate and deduplicate same normalized title/date/time among pending; error if duplicate)
- updateTask(task):TaskEntity (must exist)
- getTask(id):TaskEntity?; listTasks():List<TaskEntity>
- deleteTasks(ids:List<String>):Int (all or nothing, must exist; confirmation enforced by callers)
- completeTask(id):TaskEntity (idempotent; recurrence inserts next pending occurrence only once in same transaction)
- reopenTask(id):TaskEntity
- saveCategory(category):CategoryEntity; deleteCategory(id) (SET NULL references)
- saveProject(project):ProjectEntity; deleteProject(id)
- createConversation(title:String="Nova conversa"):ConversationEntity
- addMessage(conversationId:String,role:String,content:String):MessageEntity
- getMessages(conversationId:String):List<MessageEntity>
- clearConversations()
Use transaction for writes plus activity log; no SQL exposed to AI. Foreign keys for all relationships.
Pure `TaskRules.validate(task)` throws IllegalArgumentException; `Recurrence.nextDate(date:LocalDate,rule:String):LocalDate`; `TaskFilters.apply(tasks:List<TaskEntity>,filter:String,today:LocalDate):List<TaskEntity>` filter ALL/PENDING/PRIORITY/TODAY/TOMORROW/UPCOMING/WEEK/OVERDUE/COMPLETED/UNDATED. Recurrence validation strict and rejects unsupported rule parts.

## infra/ (infrastructure agent owns)

`AppSettings(userName:String="",theme:String="AUTO",language:String="pt-BR",zoneId:String=ZoneId.systemDefault().id,notificationsEnabled:Boolean=true,dailySummaryEnabled:Boolean=true,dailySummaryHour:Int=8,defaultReminderMinutes:Int=15,provider:String="OPENAI",model:String="gpt-4o-mini",endpoint:String="https://api.openai.com/v1",personality:String="Seja objetivo, gentil e fale português brasileiro.",temperature:Double=0.3,apiKey:String="")`.
`SettingsStore(context)` public `settings:StateFlow<AppSettings>`, `fun save(settings:AppSettings)` persists all, key via Android Keystore AES/GCM. Never log keys. No secret in backup. Model/endpoint configurable. HTTPS validation.
`ReminderScheduler(context)` `fun start()` persistent periodic + daily workers; `fun sync(tasks:List<TaskEntity>,settings:AppSettings)` schedules/cancels task alarms, follows zone, uses setExactAndAllowWhileIdle if granted else inexact fallback, durable receivers after boot/time/zone changes. All tasks with reminderEnabled; summary/overdue workers. Worker uses db/repo/settings and does not depend on Application graph. Notifications use android.R.drawable.ic_dialog_info if custom icon absent.
`BackupService(db:AppDatabase)` `suspend fun exportJson():String`, `suspend fun inspect(json:String):String` summary, `suspend fun restore(json:String,confirmation:String)` requires literal RESTAURAR, validates size/version/references before transaction; replace all user data including chat/logs, excluding credentials and settings. Must coordinate all database writes via Room transaction. No SQL built from input.

## ai/ (AI agent owns)

`ToolCall(id:String,name:String,arguments:JsonObject)`; `AiReply(text:String,calls:List<ToolCall>=emptyList(),geminiParts:JsonArray?=null)`; `AiMessage(role:String,content:String,toolCalls:List<ToolCall>=emptyList(),toolCallId:String?=null,geminiParts:JsonArray?=null)`. Native Gemini parts, including signatures and order, are persisted for protocol replay only, bounded and never displayed as chat text.
`interface AiProvider { suspend fun reply(settings:AppSettings,messages:List<AiMessage>,tools:JsonArray):AiReply }`
`HttpAiProvider` implements OpenAI compatible, Anthropic, Gemini protocols, picks settings.provider OPENAI/COMPATIBLE/ANTHROPIC/GEMINI; no credentials hardcoded. Constructor optional OkHttpClient for tests. HTTPS except localhost in test; timeouts, bounds, no secret errors. test connection via `suspend fun testConnection(settings):String`.
`ToolExecutor(repository:TaskRepository,zone:()->ZoneId={ZoneId.systemDefault()})` has `val definitions:JsonArray`, `suspend fun execute(call:ToolCall,confirmed:Boolean=false):String` JSON actual results or errors. Requires explicit IDs for updates/deletes; selection via search; delete_task ALWAYS requires confirmation unless confirmed parameter true, never trust model-supplied confirmation field. Handle category delete confirmation too. Unknown params rejected. All task schema fields use snake_case (due_date etc). list_projects/create_project/update_project/delete_project also supported.
`AssistantEngine(repository:TaskRepository,settings:()->AppSettings,provider:AiProvider=HttpAiProvider())` has `suspend fun send(conversationId:String,text:String):ChatOutcome`; `suspend fun confirm(conversationId:String):ChatOutcome`; `fun cancelPending(conversationId:String)`.
`ChatOutcome(text:String,pendingConfirmation:Boolean=false)` persisted assistant/user/tool receipts. Executor creates pending for destructive operations; engine stores in-memory pending, renders exact task names/ids to user (not blindly trust model). Confirm executes stored operations exactly once, not another model-chosen call. Prevent mutation claims without successful tool receipt: render deterministic operation receipts from repository results for mutating requests; failure cannot be claimed successful by model text. Provider replay history context supports prior tasks. Bound tool turns. Local current date/time/zone sent as system context; vague periods request explicit times, no invented workday rules.

## UI + build (parent owns)

Application/ViewModel wires Room, settings, scheduler, assistant. Compose screens dashboard, task list/calendar, task editor dialog, chat/history/confirmation, categories/projects, logs, settings with connection test & key/config, backup SAF export/import with preview/typed confirmation. New tasks default no date/no reminder, no fake data. State changes use repository and observe flows. Error surfaced to user. Notifications permission requested explicitly and exact-alarm permission settings link only if needed. Files never shared between owners except coordination via this document. No commits/push without parent. Each agent uses strict TDD and owns corresponding app/src/test/... package. Build scripts, manifest and app resources owned parent. Instrumentation tests may be added by agents in their package.
