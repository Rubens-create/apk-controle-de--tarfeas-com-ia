package com.taskai.app

import android.app.NotificationManager
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskai.app.data.TaskEntity
import com.taskai.app.infra.AppSettings
import com.taskai.app.infra.ReminderScheduler
import com.taskai.app.infra.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZonedDateTime

/** Runs only in a disposable test installation. No paid API calls or real credentials. */
@RunWith(AndroidJUnit4::class)
class DeviceInfrastructureTest {
    private val app get() = ApplicationProvider.getApplicationContext<TaskAiApplication>()

    @Test fun encryptedKeyRoundTripsThroughRealAndroidKeystore() {
        val store = SettingsStore(app)
        val fixture = "taskai-not-a-provider-key-only-encryption-fixture"
        try {
            store.save(AppSettings(apiKey = fixture, userName = "Teste de criptografia"))
            assertEquals(fixture, SettingsStore(app).settings.value.apiKey)
            val preferences = app.getSharedPreferences("taskai.settings", 0)
            assertFalse(preferences.all.values.any { it.toString().contains(fixture) })
            assertTrue(preferences.getString("encryptedKey", "")!!.startsWith("v1:"))
        } finally { store.save(AppSettings()) }
    }

    @Test fun receiversAreRegisteredAndNotExported() {
        for (name in listOf("com.taskai.app.infra.TaskAlarmReceiver", "com.taskai.app.infra.ReminderRestoreReceiver")) {
            @Suppress("DEPRECATION")
            val receiver = app.packageManager.getReceiverInfo(ComponentName(app.packageName, name), 0)
            assertFalse(receiver.exported)
            assertTrue(receiver.enabled)
        }
    }

    @Test fun scheduledAlarmActuallyPostsTaskNotification() = runBlocking(Dispatchers.IO) {
        val manager = app.getSystemService(NotificationManager::class.java)
        // CI/device API 29 needs no runtime permission. API 33+ is verified by explicit permission tests.
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT < 33)
        app.settingsStore.save(AppSettings())
        val due = ZonedDateTime.now().plusMinutes(2)
        val task = app.repository.createTask(TaskEntity(
            title = "Teste de lembrete real no Android", dueDate = due.toLocalDate().toString(),
            dueTime = due.toLocalTime().withSecond(0).withNano(0).toString(),
            reminderEnabled = true, reminderMinutes = 0, reminderDatetime = System.currentTimeMillis() + 5000
        ))
        try {
            ReminderScheduler(app).sync(app.repository.listTasks(), app.settingsStore.settings.value)
            val limit = System.currentTimeMillis() + 45_000
            while (System.currentTimeMillis() < limit && manager.activeNotifications.none { it.tag == "task.${task.id}" }) {
                kotlinx.coroutines.delay(500)
            }
            val notification = manager.activeNotifications.singleOrNull { it.tag == "task.${task.id}" }
            assertNotNull("O alarme e o Worker devem publicar uma notificação real.", notification)
            assertEquals(task.title, notification!!.notification.extras.getCharSequence("android.text").toString())
        } finally {
            app.repository.deleteTasks(listOf(task.id))
            manager.cancel("task.${task.id}", 1)
        }
    }
}
