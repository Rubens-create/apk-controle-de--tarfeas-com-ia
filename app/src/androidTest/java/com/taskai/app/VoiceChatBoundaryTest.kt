package com.taskai.app

import android.Manifest
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taskai.app.infra.AppSettings
import com.taskai.app.ui.*
import com.taskai.app.voice.VoiceController
import com.taskai.app.voice.VoiceUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Controlled Android-service boundary callbacks. These tests do not claim acoustic recognition. */
@RunWith(AndroidJUnit4::class)
class VoiceChatBoundaryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as TaskAiApplication

    @Before fun resetDisposableInstallation() {
        runBlocking(Dispatchers.IO) { app.database.clearAllTables() }
        app.settingsStore.save(AppSettings())
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            compose.activity.packageName, Manifest.permission.RECORD_AUDIO
        )
        compose.waitForIdle()
    }

    private fun mount(vm: AppViewModel): SpeechBoundarySpy {
        lateinit var spy: SpeechBoundarySpy
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalVoiceControllerFactory provides { _, state, recognized ->
                    SpeechBoundarySpy(state, recognized).also { spy = it }
                }) {
                    TaskAiTheme("LIGHT") { ChatScreen(vm, onSettings = {}) }
                }
            }
        }
        compose.waitForIdle()
        return spy
    }

    @Test fun finalSpeechIsSubmittedWithoutFakingAWriteWhenNoApiKeyExists() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val spy = mount(vm)
        compose.onNodeWithTag("spoken_replies_toggle").assertIsOff()
        compose.onNodeWithContentDescription("Falar tarefa").performClick()
        compose.onNodeWithText("Ouvindo sua tarefa...").assertIsDisplayed()
        val phrase = "Revisar o servidor amanhã às nove horas"
        compose.runOnIdle { spy.deliverFinal(phrase) }
        compose.waitUntil(10_000) { vm.notice.value == "Configure a chave de API em Mais > Configurações antes de usar a IA." }
        compose.onNodeWithText(phrase).assertExists()
        assertTrue(spy.spoken.isEmpty())
        assertTrue(runBlocking(Dispatchers.IO) { app.repository.listTasks().isEmpty() })
    }

    @Test fun voiceSwitchReadsOnlyNewRepliesAndTurningItOffStopsPlayback() {
        val vm = ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val conversation = runBlocking(Dispatchers.IO) {
            app.repository.createConversation("Histórico de teste").also {
                app.repository.addMessage(it.id, "assistant", "Esta mensagem antiga não deve ser lida automaticamente.")
            }
        }
        compose.runOnIdle { vm.selectConversation(conversation.id) }
        val spy = mount(vm)
        compose.onNodeWithTag("spoken_replies_toggle").performClick()
        compose.waitUntil(10_000) { app.settingsStore.settings.value.spokenRepliesEnabled }
        compose.waitForIdle()
        assertTrue("Opening history/enabling audio must not speak old messages", spy.spoken.isEmpty())
        compose.onNodeWithText("Mensagem para o assistente").performTextInput("Quais tarefas tenho?")
        compose.onNodeWithContentDescription("Enviar mensagem").performClick()
        compose.waitUntil(10_000) { spy.spoken.size == 1 }
        assertEquals("Configure a chave de API em Mais > Configurações antes de usar a IA.", spy.spoken.single())
        val beforeStop = spy.stops.get()
        compose.onNodeWithTag("spoken_replies_toggle").performClick()
        compose.waitUntil(10_000) { !app.settingsStore.settings.value.spokenRepliesEnabled }
        compose.waitUntil(10_000) { spy.stops.get() > beforeStop }
        compose.onNodeWithText("Mensagem para o assistente").performTextReplacement("E amanhã?")
        compose.onNodeWithContentDescription("Enviar mensagem").performClick()
        compose.waitForIdle()
        assertEquals(1, spy.spoken.size)
    }

    private class SpeechBoundarySpy(
        val onState: (VoiceUiState) -> Unit,
        val onRecognized: (String) -> Unit
    ) : VoiceController {
        val spoken = CopyOnWriteArrayList<String>()
        val stops = AtomicInteger()
        private var enabled = false
        override fun startListening() { onState(VoiceUiState(listening = true)) }
        override fun finishListening() { onState(VoiceUiState(processing = true)) }
        override fun cancelListening() { onState(VoiceUiState()) }
        override fun setSpokenRepliesEnabled(enabled: Boolean) { this.enabled = enabled; if (!enabled) stopSpeaking() }
        override fun speak(text: String) { if (enabled) spoken.add(text) }
        override fun stopSpeaking() { stops.incrementAndGet() }
        override fun close() { enabled = false }
        fun deliverFinal(text: String) { onState(VoiceUiState()); onRecognized(text) }
    }
}
