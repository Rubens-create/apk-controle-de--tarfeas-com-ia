package com.taskai.app.infra

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.taskai.app.data.TaskEntity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class ReminderSchedulerTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("taskai.reminders", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("taskai.settings", Context.MODE_PRIVATE).edit().clear().commit()
        // Scheduler tests inspect requests, not asynchronous worker execution against a different DB.
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(java.util.concurrent.Executor { }).build())
    }
    @After fun closeWork() {
        WorkManager.getInstance(context).cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }
    private fun taskAlarms() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.filter {
        shadowOf(it.operation).savedIntent.action == ReminderScheduler.ACTION_TASK
    }

    @Test fun `sync schedules durable alarm and cancels it when task is disabled`() {
        val alarm = shadowOf(context.getSystemService(AlarmManager::class.java))
        val settings = AppSettings(zoneId = "UTC")
        val task = TaskEntity(id = "Aa", title = "Consulta", dueDate = "2099-01-02", dueTime = "10:00", reminderEnabled = true)
        val scheduler = ReminderScheduler(context)
        scheduler.sync(listOf(task), settings)
        val scheduled = taskAlarms().single()
        assertEquals(Instant.parse("2099-01-02T09:45:00Z").toEpochMilli(), scheduled.triggerAtTime)
        scheduler.sync(listOf(task.copy(reminderEnabled = false)), settings)
        assertTrue(taskAlarms().isEmpty())
    }

    @Test fun `hash collisions do not overwrite task alarms and global opt out cancels all`() {
        val alarm = shadowOf(context.getSystemService(AlarmManager::class.java))
        val task = TaskEntity(id = "Aa", title = "Um", dueDate = "2099-01-02", dueTime = "10:00", reminderEnabled = true)
        assertEquals("Aa".hashCode(), "BB".hashCode())
        ReminderScheduler(context).sync(listOf(task, task.copy(id = "BB")), AppSettings(zoneId = "UTC"))
        assertEquals(2, taskAlarms().size)
        ReminderScheduler(context).sync(emptyList(), AppSettings(notificationsEnabled = false))
        assertTrue(taskAlarms().isEmpty())
    }

    @Test @Config(sdk = [33]) fun `exact alarm denial falls back without losing reminder`() {
        val task = TaskEntity(id = "fallback", title = "Permissão opcional", dueDate = "2099-01-02", dueTime = "10:00", reminderEnabled = true)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val scheduler = ReminderScheduler(context)
        scheduler.sync(listOf(task), AppSettings(zoneId = "UTC"))
        assertEquals(org.robolectric.shadows.ShadowAlarmManager.WINDOW_HEURISTIC, taskAlarms().single().windowLengthMs)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        scheduler.sync(listOf(task), AppSettings(zoneId = "UTC"))
        assertEquals(org.robolectric.shadows.ShadowAlarmManager.WINDOW_EXACT, taskAlarms().single().windowLengthMs)
    }

    @Test fun `boot time and timezone broadcasts durably enqueue resynchronization`() {
        val receiver = ReminderRestoreReceiver()
        val manager = WorkManager.getInstance(context)
        receiver.onReceive(context, android.content.Intent("unexpected.action"))
        assertTrue(manager.getWorkInfosForUniqueWork("taskai.restore-reminders").get().isEmpty())
        for (action in listOf(android.content.Intent.ACTION_BOOT_COMPLETED, android.content.Intent.ACTION_TIME_CHANGED, android.content.Intent.ACTION_TIMEZONE_CHANGED)) {
            receiver.onReceive(context, android.content.Intent(action))
            assertEquals(1, manager.getWorkInfosForUniqueWork("taskai.restore-reminders").get().count { !it.state.isFinished })
        }
        TaskAlarmReceiver().onReceive(context, android.content.Intent(ReminderScheduler.ACTION_TASK).putExtra("taskId", "abc").putExtra("triggerAt", 1L))
        assertEquals(1, manager.getWorkInfosForUniqueWork("taskai.reminder.abc").get().count { !it.state.isFinished })
    }

    @Test fun `start enqueues unique periodic summary workers`() {
        val scheduler = ReminderScheduler(context)
        scheduler.start()
        scheduler.start()
        val manager = WorkManager.getInstance(context)
        assertEquals(1, manager.getWorkInfosForUniqueWork("taskai.overdue").get().count { it.state != WorkInfo.State.CANCELLED })
        assertEquals(1, manager.getWorkInfosForUniqueWork("taskai.daily").get().count { it.state != WorkInfo.State.CANCELLED })
    }
}
