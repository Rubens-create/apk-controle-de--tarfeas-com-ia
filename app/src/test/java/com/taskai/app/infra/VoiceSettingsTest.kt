package com.taskai.app.infra

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class VoiceSettingsTest {
    private lateinit var context: Context
    @Before fun reset() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("taskai.settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun voiceRepliesAreOffByDefault() {
        assertFalse(AppSettings().spokenRepliesEnabled)
        assertFalse(SettingsStore(context).settings.value.spokenRepliesEnabled)
    }

    @Test fun changingVoicePreferencePersistsWithoutLosingConfigurationOrSecret() {
        val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = SecretCipher { key }
        val store = SettingsStore(context, cipher)
        store.save(AppSettings(userName = "Teste de voz", zoneId = "America/Sao_Paulo", apiKey = "voice-test-fixture-only"))
        store.setSpokenRepliesEnabled(true)
        val reloaded = SettingsStore(context, cipher)
        assertTrue(reloaded.settings.value.spokenRepliesEnabled)
        assertEquals("Teste de voz", reloaded.settings.value.userName)
        assertEquals("America/Sao_Paulo", reloaded.settings.value.zoneId)
        assertEquals("voice-test-fixture-only", reloaded.settings.value.apiKey)
        assertFalse(context.getSharedPreferences("taskai.settings", Context.MODE_PRIVATE).all.values.joinToString().contains("voice-test-fixture-only"))
        reloaded.setSpokenRepliesEnabled(false)
        assertFalse(SettingsStore(context, cipher).settings.value.spokenRepliesEnabled)
    }

    @Test fun olderConfigurationWithoutVoiceFieldRemainsSilent() {
        val json = JsonParser.parseString(Gson().toJson(AppSettings())).asJsonObject.apply { remove("spokenRepliesEnabled") }
        context.getSharedPreferences("taskai.settings", Context.MODE_PRIVATE).edit().putString("configuration", json.toString()).commit()
        assertFalse(SettingsStore(context).settings.value.spokenRepliesEnabled)
    }
}
