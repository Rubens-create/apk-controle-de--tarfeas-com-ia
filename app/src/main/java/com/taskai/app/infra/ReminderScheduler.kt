package com.taskai.app.infra

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.taskai.app.data.AppDatabase
import com.taskai.app.data.TaskEntity
import com.taskai.app.data.TaskRepository
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Alarm identity is a URI, never a hash code (different task IDs may have equal hashes). */
class ReminderScheduler(context: Context) {
    private val context = context.applicationContext
    private val alarms = this.context.getSystemService(AlarmManager::class.java)
    private val preferences = this.context.getSharedPreferences("taskai.reminders", Context.MODE_PRIVATE)

    fun start() {
        Notifications.channels(context)
        scheduleWorkers(SettingsStore(context).settings.value)
    }

    @Synchronized fun sync(tasks: List<TaskEntity>, settings: AppSettings) {
        val zone = ZoneId.of(settings.zoneId)
        val now = System.currentTimeMillis()
        val wanted = if (settings.notificationsEnabled) tasks.mapNotNull { task ->
            ReminderPlan.triggerAt(task, zone)?.takeIf { it > now }?.let { task.id to it }
        }.toMap() else emptyMap()
        val previous = preferences.getStringSet("alarmIds", emptySet()).orEmpty().toSet()
        for (id in previous - wanted.keys) alarms.cancel(alarmIntent(id))
        // Persist identities BEFORE scheduling: interrupted sync is recoverable by the next sync/boot.
        check(preferences.edit().putStringSet("alarmIds", wanted.keys.toSet()).commit())
        for ((id, trigger) in wanted) {
            val operation = alarmIntent(id, trigger)
            try {
                if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, operation)
                } else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, operation)
            } catch (_: SecurityException) {
                // The grant may be revoked after canScheduleExactAlarms returned true.
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, operation)
            }
        }
        scheduleWorkers(settings)
    }

    private fun alarmIntent(id: String, trigger: Long = 0): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, TaskAlarmReceiver::class.java)
            .setAction(ACTION_TASK).setData(Uri.Builder().scheme("taskai").authority("reminder").appendPath(id).build())
            .putExtra("taskId", id).putExtra("triggerAt", trigger), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun scheduleWorkers(settings: AppSettings) {
        val work = WorkManager.getInstance(context)
        if (!settings.notificationsEnabled) {
            work.cancelUniqueWork("taskai.daily")
            work.cancelUniqueWork("taskai.overdue")
            Notifications.cancelSummaries(context)
            preferences.edit().remove("workerConfiguration").apply()
            return
        }
        val configuration = "${settings.zoneId}:${settings.dailySummaryHour}:${settings.dailySummaryEnabled}"
        val changed = preferences.getString("workerConfiguration", null) != configuration
        val policy = if (changed) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP
        // A worker can start immediately. Publish its configuration before it can re-enter sync.
        check(preferences.edit().putString("workerConfiguration", configuration).commit())
        work.enqueueUniquePeriodicWork("taskai.overdue", policy,
            PeriodicWorkRequestBuilder<OverdueSummaryWorker>(1, TimeUnit.HOURS).build())
        if (settings.dailySummaryEnabled) {
            val delay = Duration.between(Instant.now(), ReminderPlan.nextSummary(Instant.now(), ZoneId.of(settings.zoneId), settings.dailySummaryHour)).toMillis().coerceAtLeast(0)
            // WorkManager is deliberately inexact. Recheck local clock every 15 min after first due run,
            // rather than using a 24h interval, which drifts at daylight-saving transitions.
            work.enqueueUniquePeriodicWork("taskai.daily", policy,
                PeriodicWorkRequestBuilder<DailySummaryWorker>(15, TimeUnit.MINUTES)
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS).build())
        } else {
            work.cancelUniqueWork("taskai.daily")
            context.getSystemService(NotificationManager::class.java).cancel("daily", 1)
        }
    }

    companion object { internal const val ACTION_TASK = "com.taskai.app.TASK_REMINDER" }
}

/** Non-exported: alarms carry no task text, and workers fetch the current record before notifying. */
class TaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_TASK) return
        val id = intent.getStringExtra("taskId")?.takeIf { it.isNotBlank() && it.length <= 200 } ?: return
        WorkManager.getInstance(context).enqueueUniqueWork("taskai.reminder.$id", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<TaskReminderWorker>().setInputData(workDataOf("taskId" to id, "triggerAt" to intent.getLongExtra("triggerAt", -1))).build())
    }
}

/** System-only lifecycle events are accepted; never run DB work on the receiver main thread. */
class ReminderRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED,
                "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) return
        WorkManager.getInstance(context).enqueueUniqueWork("taskai.restore-reminders", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RestoreRemindersWorker>().build())
    }
}

class RestoreRemindersWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val settings = SettingsStore(applicationContext).settings.value
        val scheduler = ReminderScheduler(applicationContext)
        scheduler.start()
        scheduler.sync(TaskRepository(AppDatabase.get(applicationContext)).listTasks(), settings)
        Result.success()
    } catch (_: Exception) { Result.retry() }
}

class TaskReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val settings = SettingsStore(applicationContext).settings.value
        if (!settings.notificationsEnabled || !Notifications.allowed(applicationContext)) return Result.success()
        val id = inputData.getString("taskId") ?: return Result.success()
        val task = TaskRepository(AppDatabase.get(applicationContext)).getTask(id) ?: return Result.success()
        val trigger = ReminderPlan.triggerAt(task, ZoneId.of(settings.zoneId)) ?: return Result.success()
        // Ignore stale pending work after editing, rescheduling or changing timezone.
        if (trigger != inputData.getLong("triggerAt", -1) || trigger > System.currentTimeMillis()) return Result.success()
        val preferences = applicationContext.getSharedPreferences("taskai.reminders", Context.MODE_PRIVATE)
        val token = "$id:$trigger"
        if (preferences.getString("lastTask.$id", null) == token) return Result.success()
        if (Notifications.show(applicationContext, "task.$id", "Lembrete de tarefa", task.title)) {
            preferences.edit().putString("lastTask.$id", token).commit()
        }
        return Result.success()
    }
}

class DailySummaryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = summaries(applicationContext, daily = true)
}

class OverdueSummaryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        // This also repairs alarms if the process was terminated halfway through a previous sync.
        val settings = SettingsStore(applicationContext).settings.value
        val tasks = TaskRepository(AppDatabase.get(applicationContext)).listTasks()
        ReminderScheduler(applicationContext).sync(tasks, settings)
        return summaries(applicationContext, daily = false, tasks = tasks)
    }
}

private suspend fun summaries(context: Context, daily: Boolean, tasks: List<TaskEntity>? = null): ListenableWorker.Result {
    val settings = SettingsStore(context).settings.value
    if (!settings.notificationsEnabled || (daily && !settings.dailySummaryEnabled) || !Notifications.allowed(context)) return ListenableWorker.Result.success()
    val local = Instant.now().atZone(ZoneId.of(settings.zoneId))
    if (daily && local.hour < settings.dailySummaryHour) return ListenableWorker.Result.success()
    val preferences = context.getSharedPreferences("taskai.reminders", Context.MODE_PRIVATE)
    val kind = if (daily) "daily" else "overdue"
    val day = "${settings.zoneId}:${local.toLocalDate()}"
    if (preferences.getString("lastSummary.$kind", null) == day) return ListenableWorker.Result.success()
    val pending = (tasks ?: TaskRepository(AppDatabase.get(context)).listTasks()).filter { it.status == "PENDING" }
    val selected = pending.filter { task ->
        val date = task.dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@filter false
        if (daily) date == local.toLocalDate() else date < local.toLocalDate() ||
            (date == local.toLocalDate() && task.dueTime?.let { runCatching { LocalTime.parse(it) < local.toLocalTime() }.getOrDefault(false) } == true)
    }
    if (selected.isNotEmpty() && Notifications.show(context, kind,
            if (daily) "Seu resumo diário" else "Tarefas atrasadas", "${selected.size} tarefa(s): " + selected.take(3).joinToString { it.title })) {
        preferences.edit().putString("lastSummary.$kind", day).commit()
    }
    return ListenableWorker.Result.success()
}

internal object Notifications {
    private const val CHANNEL = "taskai.reminders"
    fun channels(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Tarefas e resumos", NotificationManager.IMPORTANCE_DEFAULT))
    }
    fun allowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun show(context: Context, tag: String, title: String, text: String): Boolean {
        if (!allowed(context)) return false
        channels(context)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text.take(1000)).setStyle(NotificationCompat.BigTextStyle().bigText(text.take(4000)))
            .setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (launch != null) builder.setContentIntent(launch)
        return try { context.getSystemService(NotificationManager::class.java).notify(tag, 1, builder.build()); true }
        catch (_: SecurityException) { false }
    }
    fun cancelSummaries(context: Context) {
        context.getSystemService(NotificationManager::class.java).apply { cancel("daily", 1); cancel("overdue", 1) }
    }
}
