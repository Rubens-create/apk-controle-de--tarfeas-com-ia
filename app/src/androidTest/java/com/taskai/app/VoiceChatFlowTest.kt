package com.taskai.app

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonParser
import com.taskai.app.infra.AppSettings
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceChatFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun resetDisposableSettings() {
        (compose.activity.application as TaskAiApplication).settingsStore.save(AppSettings())
        compose.waitForIdle()
    }

    @Test fun voiceReplySwitchIsOptionalPersistentAndIndependentOfMicrophone() {
        compose.onNodeWithText("Assistente", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("spoken_replies_toggle").assertIsOff()
        compose.onNodeWithContentDescription("Falar tarefa").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("spoken_replies_toggle").performClick()
        compose.waitUntil(10_000) {
            val json = compose.activity.getSharedPreferences("taskai.settings", Context.MODE_PRIVATE)
                .getString("configuration", "{}") ?: "{}"
            JsonParser.parseString(json).asJsonObject["spokenRepliesEnabled"]?.asBoolean == true
        }
        compose.onNodeWithTag("spoken_replies_toggle").assertIsOn()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("spoken_replies_toggle").assertIsOn()
        compose.onNodeWithContentDescription("Falar tarefa").assertIsEnabled()
        compose.onNodeWithTag("spoken_replies_toggle").performClick()
        compose.waitUntil(10_000) {
            val json = compose.activity.getSharedPreferences("taskai.settings", Context.MODE_PRIVATE)
                .getString("configuration", "{}") ?: "{}"
            JsonParser.parseString(json).asJsonObject["spokenRepliesEnabled"]?.asBoolean == false
        }
        compose.onNodeWithTag("spoken_replies_toggle").assertIsOff()
    }
}
