package com.taskai.app.infra

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.taskai.app.data.AppDatabase
import com.taskai.app.data.TaskEntity
import com.taskai.app.data.TaskRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class ReminderWorkersTest {
    private lateinit var context: Context
    private lateinit var repository: TaskRepository
    private lateinit var db: AppDatabase
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("taskai.settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("taskai.reminders", Context.MODE_PRIVATE).edit().clear().commit()
        db = AppDatabase.createInMemory(context)
        // Workers intentionally use the production singleton API; install a disposable real Room DB.
        AppDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, db)
        repository = TaskRepository(db)
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(java.util.concurrent.Executor { }).build())
    }
    @After fun closeWork() {
        androidx.work.WorkManager.getInstance(context).cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
        db.close()
        AppDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    @Test fun `task worker rereads current task and ignores stale alarms`() = runBlocking {
        val trigger = System.currentTimeMillis() - 1000
        val task = repository.createTask(TaskEntity(id = "task", title = "Consulta real", dueDate = "2030-01-02", dueTime = "10:00", reminderEnabled = true, reminderDatetime = trigger))
        fun worker(at: Long) = TestListenableWorkerBuilder<TaskReminderWorker>(context)
            .setInputData(Data.Builder().putString("taskId", task.id).putLong("triggerAt", at).build()).build()
        val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))
        worker(trigger - 1).doWork()
        assertTrue(notifications.allNotifications.isEmpty())
        worker(trigger).doWork()
        assertEquals(1, notifications.allNotifications.size)
        context.getSystemService(NotificationManager::class.java).cancelAll()
        repository.updateTask(task.copy(status = "COMPLETED", completedAt = System.currentTimeMillis()))
        worker(trigger).doWork()
        assertTrue(notifications.allNotifications.isEmpty())
    }

    @Test fun `daily summary uses configured zone and respects opt out`() = runBlocking {
        val zone = ZoneId.of("Pacific/Kiritimati")
        SettingsStore(context).save(AppSettings(zoneId = zone.id, dailySummaryHour = 0))
        repository.createTask(TaskEntity(id = "today", title = "Dia configurado", dueDate = LocalDate.now(zone).toString()))
        repository.createTask(TaskEntity(id = "tomorrow", title = "Não incluir", dueDate = LocalDate.now(zone).plusDays(1).toString()))
        val worker = TestListenableWorkerBuilder<DailySummaryWorker>(context).build()
        worker.doWork()
        val manager = context.getSystemService(NotificationManager::class.java)
        val notifications = shadowOf(manager).allNotifications
        assertEquals(1, notifications.size)
        assertTrue(notifications.single().extras.getCharSequence("android.text").toString().contains("Dia configurado"))
        assertFalse(notifications.single().extras.getCharSequence("android.text").toString().contains("Não incluir"))
        manager.cancelAll()
        worker.doWork()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        SettingsStore(context).save(AppSettings(dailySummaryEnabled = false, dailySummaryHour = 0))
        worker.doWork()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test fun `overdue summary excludes completed tasks and global disable suppresses output`() = runBlocking {
        SettingsStore(context).save(AppSettings(zoneId = "UTC"))
        repository.createTask(TaskEntity(id = "late", title = "Atrasada", dueDate = "2000-01-01"))
        repository.createTask(TaskEntity(id = "completed", title = "Concluída", dueDate = "2000-01-01", status = "COMPLETED", completedAt = 1))
        val worker = TestListenableWorkerBuilder<OverdueSummaryWorker>(context).build()
        worker.doWork()
        val manager = context.getSystemService(NotificationManager::class.java)
        assertTrue(shadowOf(manager).allNotifications.single().extras.getCharSequence("android.text").toString().contains("1 tarefa(s): Atrasada"))
        manager.cancelAll()
        SettingsStore(context).save(AppSettings(notificationsEnabled = false))
        worker.doWork()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test @Config(sdk = [33]) fun `Android 13 notification denial is never bypassed`() {
        shadowOf(context as android.app.Application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(Notifications.allowed(context))
        assertFalse(Notifications.show(context, "test", "Título", "Texto"))
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }
}
