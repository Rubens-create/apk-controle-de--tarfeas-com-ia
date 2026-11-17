package com.taskai.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

internal class AndroidSpeechOutputPort(
    private val context: Context,
    private val main: VoiceMainThread
) : SpeechOutputPort {
    private var tts: TextToSpeech? = null
    private var instanceGeneration = 0L
    private var utteranceGeneration = 0L
    private var playback: PlaybackEvents? = null
    private var firstUtterance: String? = null
    private var lastUtterance: String? = null

    override fun initialize(onReady: (VoiceFailure?) -> Unit) {
        close()
        val token = ++instanceGeneration
        tts = TextToSpeech(context) { status ->
            main.post {
                if (token != instanceGeneration) return@post
                val service = tts ?: return@post
                var failure = try {
                    when {
                        status != TextToSpeech.SUCCESS -> VoiceFailure.TTS_UNAVAILABLE
                        service.isLanguageAvailable(BRAZILIAN_PORTUGUESE) < TextToSpeech.LANG_COUNTRY_AVAILABLE -> VoiceFailure.TTS_LANGUAGE
                        service.setLanguage(BRAZILIAN_PORTUGUESE) < TextToSpeech.LANG_COUNTRY_AVAILABLE -> VoiceFailure.TTS_LANGUAGE
                        else -> null
                    }
                } catch (_: RuntimeException) { VoiceFailure.TTS_UNAVAILABLE }
                if (failure == null) try {
                    val result = service.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = main.run {
                            if (token == instanceGeneration && utteranceId == firstUtterance) playback?.onStart()
                        }
                        override fun onDone(utteranceId: String?) = main.run {
                            if (token == instanceGeneration && utteranceId == lastUtterance) {
                                val events = playback
                                clearPlayback()
                                events?.onDone()
                            }
                        }
                        @Deprecated("Android compatibility callback")
                        override fun onError(utteranceId: String?) = playbackError(token, utteranceId)
                        override fun onError(utteranceId: String?, errorCode: Int) = playbackError(token, utteranceId)
                        override fun onStop(utteranceId: String?, interrupted: Boolean) = main.run {
                            if (token == instanceGeneration && belongsToPlayback(utteranceId)) {
                                val events = playback
                                clearPlayback()
                                events?.onDone()
                            }
                        }
                    })
                    if (result == TextToSpeech.ERROR) failure = VoiceFailure.TTS_UNAVAILABLE
                } catch (_: RuntimeException) { failure = VoiceFailure.TTS_UNAVAILABLE }
                if (failure != null) {
                    try { close() } catch (_: RuntimeException) { /* Still notify the caller. */ }
                }
                onReady(failure)
            }
        }
    }

    override fun speak(text: String, events: PlaybackEvents) {
        val service = tts ?: throw VoiceServiceException(VoiceFailure.TTS_UNAVAILABLE)
        stop()
        val token = ++utteranceGeneration
        val parts = text.chunked(TextToSpeech.getMaxSpeechInputLength().coerceAtLeast(1))
        if (parts.isEmpty()) return
        playback = events
        firstUtterance = "$token:0"
        lastUtterance = "$token:${parts.lastIndex}"
        parts.forEachIndexed { index, part ->
            val result = service.speak(part, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "$token:$index")
            if (result == TextToSpeech.ERROR) {
                stop()
                throw VoiceServiceException(VoiceFailure.TTS_ERROR)
            }
        }
    }

    private fun playbackError(token: Long, id: String?) = main.run {
        if (token == instanceGeneration && belongsToPlayback(id)) {
            val events = playback
            clearPlayback()
            events?.onError()
        }
    }

    private fun belongsToPlayback(id: String?): Boolean =
        id != null && playback != null && id.substringBefore(':') == firstUtterance?.substringBefore(':')

    private fun clearPlayback() {
        playback = null
        firstUtterance = null
        lastUtterance = null
    }

    override fun stop() {
        clearPlayback()
        tts?.stop()
    }

    override fun close() {
        instanceGeneration++
        clearPlayback()
        val service = tts
        tts = null
        try { service?.stop() } finally { service?.shutdown() }
    }

    private companion object {
        val BRAZILIAN_PORTUGUESE: Locale = Locale.forLanguageTag("pt-BR")
    }
}
