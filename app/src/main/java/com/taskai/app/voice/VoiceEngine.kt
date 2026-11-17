package com.taskai.app.voice

/** Deterministic state machine. No audio, Android objects, transcript logging or persistence. */
internal class VoiceEngine(
    private val input: RecognitionPort,
    private val output: SpeechOutputPort,
    private val onState: (VoiceUiState) -> Unit,
    private val onRecognized: (String) -> Unit
) : VoiceController {
    private var state = VoiceUiState()
    private var generation = 0L
    private var session: RecognitionSession? = null
    private var closed = false
    private var finishRequested = false
    private var spokenRepliesEnabled = false
    private var outputInitializing = false
    private var outputReady = false
    private var queuedReply: String? = null
    private var playbackGeneration = 0L

    private fun releaseCapture() {
        generation++
        val old = session
        session = null
        try { old?.close() } catch (_: RuntimeException) { /* Never expose service details. */ }
    }

    private fun failCapture(failure: VoiceFailure) {
        releaseCapture()
        update(state.copy(listening = false, processing = false, partialText = "", error = failure.message))
    }

    private fun update(next: VoiceUiState) {
        state = next
        onState(next)
    }

    override fun startListening() {
        if (closed) return
        stopSpeaking()
        if (closed) return
        releaseCapture()
        finishRequested = false
        val token = ++generation
        update(VoiceUiState(listening = true))
        if (closed || token != generation) return
        val events = object : RecognitionEvents {
            override fun onPartial(text: String) {
                if (token == generation) update(state.copy(partialText = text.take(10_000)))
            }
            override fun onEndOfSpeech() {
                if (token == generation) update(state.copy(listening = false, processing = true))
            }
            override fun onFinal(text: String) {
                if (token != generation) return
                val final = text.trim()
                when {
                    final.isBlank() -> failCapture(VoiceFailure.NO_SPEECH)
                    text.length > 10_000 -> failCapture(VoiceFailure.TOO_LONG)
                    else -> {
                        releaseCapture()
                        update(VoiceUiState())
                        if (!closed) onRecognized(final)
                    }
                }
            }
            override fun onError(failure: VoiceFailure) {
                if (token == generation) failCapture(failure)
            }
        }
        val created = try { input.start(events) } catch (error: RuntimeException) {
            if (token == generation) failCapture(recognitionFailure(error))
            return
        }
        if (token == generation && !closed) session = created
        else try { created.close() } catch (_: RuntimeException) { /* Already terminal. */ }
    }
    override fun finishListening() {
        if (closed || session == null || finishRequested) return
        finishRequested = true
        update(state.copy(listening = false, processing = true))
        try { session?.finish() } catch (error: RuntimeException) {
            failCapture(recognitionFailure(error))
        }
    }
    override fun cancelListening() {
        if (closed) return
        releaseCapture()
        update(state.copy(listening = false, processing = false, partialText = "", error = null))
    }
    override fun setSpokenRepliesEnabled(enabled: Boolean) {
        if (closed) return
        spokenRepliesEnabled = enabled
        if (!enabled) stopSpeaking()
    }

    override fun speak(text: String) {
        if (closed || !spokenRepliesEnabled || state.listening || state.processing || text.isBlank()) return
        queuedReply = text.trim()
        if (outputReady) {
            playQueuedReply()
        } else if (!outputInitializing) {
            outputInitializing = true
            try {
                output.initialize { failure ->
                    if (!closed) {
                        outputInitializing = false
                        outputReady = failure == null
                        if (failure == null) playQueuedReply()
                        else {
                            queuedReply = null
                            if (spokenRepliesEnabled) update(state.copy(error = failure.message))
                        }
                    }
                }
            } catch (error: RuntimeException) {
                failOutput((error as? VoiceServiceException)?.failure ?: VoiceFailure.TTS_ERROR)
            }
        }
    }

    private fun playQueuedReply() {
        if (closed || !spokenRepliesEnabled) return
        val reply = queuedReply ?: return
        queuedReply = null
        val token = ++playbackGeneration
        val events = object : PlaybackEvents {
            override fun onStart() {
                if (!closed && spokenRepliesEnabled && token == playbackGeneration) {
                    update(state.copy(speaking = true, error = null))
                }
            }
            override fun onDone() {
                if (!closed && token == playbackGeneration) {
                    playbackGeneration++
                    update(state.copy(speaking = false))
                }
            }
            override fun onError() {
                if (!closed && token == playbackGeneration) failOutput(VoiceFailure.TTS_ERROR)
            }
        }
        try { output.speak(reply, events) } catch (error: RuntimeException) {
            if (!closed && token == playbackGeneration) {
                failOutput((error as? VoiceServiceException)?.failure ?: VoiceFailure.TTS_ERROR)
            }
        }
    }

    private fun failOutput(failure: VoiceFailure) {
        playbackGeneration++
        outputInitializing = false
        outputReady = false
        queuedReply = null
        try { output.stop() } catch (_: RuntimeException) { /* Best effort. */ }
        if (!closed) update(state.copy(speaking = false, error = failure.message))
    }

    override fun stopSpeaking() {
        if (closed) return
        queuedReply = null
        playbackGeneration++
        try { output.stop() } catch (_: RuntimeException) { /* Best effort. */ }
        update(state.copy(speaking = false))
    }
    override fun close() {
        if (closed) return
        closed = true
        queuedReply = null
        spokenRepliesEnabled = false
        playbackGeneration++
        releaseCapture()
        try { output.stop() } catch (_: RuntimeException) { /* Still release the engine. */ }
        try { output.close() } catch (_: RuntimeException) { /* Never leak service details. */ }
        update(VoiceUiState())
    }
}
