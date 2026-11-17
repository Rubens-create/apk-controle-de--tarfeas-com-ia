package com.taskai.app.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

internal class AndroidRecognitionPort(
    private val context: Context,
    private val main: VoiceMainThread
) : RecognitionPort {
    override fun start(events: RecognitionEvents): RecognitionSession {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw VoiceServiceException(VoiceFailure.PERMISSION)
        }
        val recognizer = createRecognizer()
        val session = object : RecognitionSession {
            private var released = false
            override fun finish() { if (!released) recognizer.stopListening() }
            override fun close() {
                if (released) return
                released = true
                try { recognizer.cancel() } finally { recognizer.destroy() }
            }
        }
        try {
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onEndOfSpeech() = main.run(events::onEndOfSpeech)
                override fun onError(error: Int) = main.run { events.onError(androidRecognitionFailure(error)) }
                override fun onPartialResults(partialResults: Bundle?) {
                    val text = resultText(partialResults)
                    main.run { events.onPartial(text) }
                }
                override fun onResults(results: Bundle?) {
                    val text = resultText(results)
                    main.run { events.onFinal(text) }
                }
            })
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
        } catch (error: RuntimeException) {
            try { session.close() } catch (_: RuntimeException) { /* Still report the original failure. */ }
            throw error
        }
        return session
    }

    private fun createRecognizer(): SpeechRecognizer {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            try {
                return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } catch (_: UnsupportedOperationException) { /* Availability may change; try the standard service. */ }
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) throw VoiceServiceException(VoiceFailure.UNAVAILABLE)
        return SpeechRecognizer.createSpeechRecognizer(context)
    }

    private fun resultText(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }.orEmpty()
}

internal fun androidRecognitionFailure(code: Int): VoiceFailure = when (code) {
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceFailure.PERMISSION
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> VoiceFailure.NETWORK
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH -> VoiceFailure.NO_SPEECH
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> VoiceFailure.BUSY
    SpeechRecognizer.ERROR_AUDIO -> VoiceFailure.AUDIO
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> VoiceFailure.LANGUAGE
    else -> VoiceFailure.RECOGNITION
}
