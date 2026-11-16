package com.taskai.app.infra

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class SettingsStoreTest {
    private lateinit var context: Context

    @Before fun reset() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("taskai.settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `AES GCM persists only ciphertext and authenticates modifications`() {
        val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val secrets = SecretCipher { key }
        val store = SettingsStore(context, secrets)
        store.save(store.settings.value.copy(apiKey = "secret-example-never-persist-plain"))
        val prefs = context.getSharedPreferences("taskai.settings", Context.MODE_PRIVATE)
        assertFalse(prefs.all.values.joinToString().contains("secret-example-never-persist-plain"))
        assertEquals("secret-example-never-persist-plain", SettingsStore(context, secrets).settings.value.apiKey)
        assertFalse(store.settings.value.toString().contains("secret-example-never-persist-plain"))
        val first = secrets.encrypt("value")
        assertNotEquals(first, secrets.encrypt("value"))
        val raw = android.util.Base64.decode(first.substring(3), android.util.Base64.NO_WRAP)
        raw[raw.lastIndex] = (raw.last().toInt() xor 1).toByte()
        prefs.edit().putString("encryptedKey", "v1:" + android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)).commit()
        assertEquals("", SettingsStore(context, secrets).settings.value.apiKey)
    }

    @Test fun `invalid configuration never mutates state or preferences`() {
        val store = SettingsStore(context)
        val original = store.settings.value
        val invalid = listOf(
            original.copy(endpoint = "http://example.com/v1"),
            original.copy(endpoint = "https://key@example.com/v1"),
            original.copy(endpoint = "https://example.com/v1?key=secret"),
            original.copy(endpoint = "https://example.com/v1#fragment"),
            original.copy(zoneId = "not-a-zone"), original.copy(dailySummaryHour = 24),
            original.copy(temperature = Double.NaN), original.copy(defaultReminderMinutes = -1)
        )
        invalid.forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { store.save(value) }
            assertEquals(original, store.settings.value)
        }
        assertTrue(context.getSharedPreferences("taskai.settings", Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test fun `defaults match contract and persist nonsecret configuration`() {
        val store = SettingsStore(context)
        assertEquals("AUTO", store.settings.value.theme)
        assertEquals("pt-BR", store.settings.value.language)
        assertEquals("gpt-4o-mini", store.settings.value.model)
        assertEquals("https://api.openai.com/v1", store.settings.value.endpoint)
        assertTrue(store.settings.value.notificationsEnabled)
        assertEquals(8, store.settings.value.dailySummaryHour)
        store.save(store.settings.value.copy(userName = "Ana", zoneId = "America/Sao_Paulo", dailySummaryHour = 9))
        assertEquals("Ana", SettingsStore(context).settings.value.userName)
        assertEquals(9, SettingsStore(context).settings.value.dailySummaryHour)
    }
}
