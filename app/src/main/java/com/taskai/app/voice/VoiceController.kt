package com.taskai.app.voice

/** Capture/processing describe Android transcription, not the AI request. */
data class VoiceUiState(
    val listening: Boolean = false,
    val processing: Boolean = false,
    val speaking: Boolean = false,
    val partialText: String = "",
    val error: String? = null
)

interface VoiceController {
    fun startListening()
    fun finishListening()
    fun cancelListening()
    fun setSpokenRepliesEnabled(enabled: Boolean)
    fun speak(text: String)
    fun stopSpeaking()
    fun close()
}
