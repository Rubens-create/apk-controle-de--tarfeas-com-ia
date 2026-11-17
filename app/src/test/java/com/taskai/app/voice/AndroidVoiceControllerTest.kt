package com.taskai.app.voice

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSpeechRecognizer
import org.robolectric.shadows.ShadowTextToSpeech

/** Service callback tests, NOT acoustic recognition or real audio playback. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidVoiceControllerTest {
    private lateinit var app: Application
    private var state = VoiceUiState()
    private val recognized = mutableListOf<String>()
    private lateinit var controller: AndroidVoiceController

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        controller = AndroidVoiceController(app, { state = it }, recognized::add)
    }

    @org.junit.After fun release() {
        controller.close()
        idle()
    }

    @Suppress("DEPRECATION")
    private fun service() {
        shadowOf(app.packageManager).addResolveInfoForIntent(
            Intent(RecognitionService.SERVICE_INTERFACE),
            ResolveInfo().apply {
                serviceInfo = ServiceInfo().apply {
                    packageName = "test.recognizer"
                    name = "test.recognizer.Service"
                    applicationInfo = ApplicationInfo().apply { packageName = "test.recognizer" }
                }
            }
        )
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun words(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    @Test fun `permission denial reports localized error without creating recognizer`() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        controller.startListening()
        idle()
        assertEquals(VoiceFailure.PERMISSION.message, state.error)
        assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        controller.close()
    }

    @Test fun `missing Android recognition service never fabricates text`() {
        controller.startListening()
        idle()
        assertEquals(VoiceFailure.UNAVAILABLE.message, state.error)
        assertTrue(recognized.isEmpty())
        controller.close()
    }

    @Test fun `standard service receives Brazilian Portuguese and partials stay local`() {
        service()
        controller.startListening()
        idle()
        val recognizer = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        val speech = shadowOf(recognizer)
        assertEquals("pt-BR", speech.lastRecognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue(speech.lastRecognizerIntent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
        speech.triggerOnPartialResults(words("prévia"))
        idle()
        assertEquals("prévia", state.partialText)
        assertTrue(recognized.isEmpty())
        speech.triggerOnEndOfSpeech()
        idle()
        assertTrue(state.processing)
        speech.triggerOnResults(words("criar tarefa"))
        idle()
        assertEquals(listOf("criar tarefa"), recognized)
        assertTrue(speech.isDestroyed)
        controller.close()
    }

    @Test fun `on device service works even without a standard service`() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        controller.startListening()
        idle()
        val speech = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        assertEquals("pt-BR", speech.lastRecognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        controller.cancelListening()
        idle()
        assertTrue(speech.isDestroyed)
        assertTrue(recognized.isEmpty())
        controller.close()
    }

    @Test
    @Config(shadows = [ListenerFailureTtsShadow::class])
    fun `broken TTS listener registration reports an error instead of crashing callback thread`() {
        controller.setSpokenRepliesEnabled(true)
        controller.speak("não reproduzir")
        val output = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        ShadowTextToSpeech.addLanguageAvailability(Locale.forLanguageTag("pt-BR"))
        output.onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        assertEquals(VoiceFailure.TTS_UNAVAILABLE.message, state.error)
        assertTrue(output.isShutdown)
        assertTrue(output.spokenTextList.isEmpty())
    }

    @Test fun `close before queued init callback never speaks and shuts down TTS`() {
        controller.setSpokenRepliesEnabled(true)
        controller.speak("não reproduzir")
        val output = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        ShadowTextToSpeech.addLanguageAvailability(Locale.forLanguageTag("pt-BR"))
        output.onInitListener.onInit(TextToSpeech.SUCCESS)
        controller.close()
        idle()
        assertTrue(output.spokenTextList.isEmpty())
        assertTrue(output.isShutdown)
    }

    @Test fun `TTS initialization and missing pt BR data fail without playback`() {
        controller.setSpokenRepliesEnabled(true)
        controller.speak("primeira")
        val failed = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        failed.onInitListener.onInit(TextToSpeech.ERROR)
        idle()
        assertEquals(VoiceFailure.TTS_UNAVAILABLE.message, state.error)
        assertTrue(failed.isShutdown)
        assertTrue(failed.spokenTextList.isEmpty())
        controller.speak("segunda")
        val missingLanguage = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        missingLanguage.onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        assertEquals(VoiceFailure.TTS_LANGUAGE.message, state.error)
        assertTrue(missingLanguage.isShutdown)
        assertTrue(missingLanguage.spokenTextList.isEmpty())
        controller.close()
    }

    @Test fun `Android error codes map to bounded localized messages`() {
        val expected = mapOf(
            SpeechRecognizer.ERROR_NETWORK to VoiceFailure.NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT to VoiceFailure.NETWORK,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT to VoiceFailure.NO_SPEECH,
            SpeechRecognizer.ERROR_NO_MATCH to VoiceFailure.NO_SPEECH,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY to VoiceFailure.BUSY,
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS to VoiceFailure.BUSY,
            SpeechRecognizer.ERROR_AUDIO to VoiceFailure.AUDIO,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS to VoiceFailure.PERMISSION,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED to VoiceFailure.LANGUAGE,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE to VoiceFailure.LANGUAGE,
            SpeechRecognizer.ERROR_SERVER to VoiceFailure.RECOGNITION,
            -999 to VoiceFailure.RECOGNITION
        )
        expected.forEach { (code, failure) -> assertEquals(failure, androidRecognitionFailure(code)) }
        service()
        controller.startListening()
        idle()
        val speech = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        speech.triggerOnError(SpeechRecognizer.ERROR_NETWORK)
        idle()
        assertEquals(VoiceFailure.NETWORK.message, state.error)
        assertTrue(speech.isDestroyed)
        assertTrue(recognized.isEmpty())
        controller.close()
    }

    @Test fun `background calls are dispatched to main and close invalidates queued work`() {
        service()
        var callbackThread: Thread? = null
        controller.close()
        controller = AndroidVoiceController(app, { state = it; callbackThread = Thread.currentThread() }, recognized::add)
        Thread { controller.startListening() }.apply { start(); join() }
        idle()
        assertSame(Looper.getMainLooper().thread, callbackThread)
        val speech = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        Thread { controller.close(); controller.startListening() }.apply { start(); join() }
        idle()
        assertTrue(speech.isDestroyed)
        assertTrue(recognized.isEmpty())
    }

    @Test fun `long spoken replies are queued in Android sized chunks without losing text`() {
        controller.setSpokenRepliesEnabled(true)
        val text = "a".repeat(TextToSpeech.getMaxSpeechInputLength() + 1)
        controller.speak(text)
        val output = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        ShadowTextToSpeech.addLanguageAvailability(Locale.forLanguageTag("pt-BR"))
        output.onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        assertEquals(text, output.spokenTextList.joinToString(""))
        assertEquals(2, output.spokenTextList.size)
        assertEquals(TextToSpeech.QUEUE_ADD, output.queueMode)
        controller.close()
    }

    @Test fun `TTS is lazy switch gated and initialized in Brazilian Portuguese`() {
        controller.speak("histórico")
        idle()
        assertNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
        controller.setSpokenRepliesEnabled(true)
        controller.speak("antiga")
        controller.speak("nova")
        idle()
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
        val output = shadowOf(tts)
        ShadowTextToSpeech.addLanguageAvailability(Locale.forLanguageTag("pt-BR"))
        output.onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        assertEquals(Locale.forLanguageTag("pt-BR"), output.currentLanguage)
        assertEquals(listOf("nova"), output.spokenTextList)
        controller.setSpokenRepliesEnabled(false)
        idle()
        assertTrue(output.isStopped)
        controller.speak("proibida")
        idle()
        assertEquals(listOf("nova"), output.spokenTextList)
        controller.close()
        idle()
        assertTrue(output.isShutdown)
    }
}

@org.robolectric.annotation.Implements(TextToSpeech::class)
class ListenerFailureTtsShadow : ShadowTextToSpeech() {
    @org.robolectric.annotation.Implementation
    fun setOnUtteranceProgressListener(listener: android.speech.tts.UtteranceProgressListener?): Int {
        throw IllegalStateException("private engine internals")
    }
}
