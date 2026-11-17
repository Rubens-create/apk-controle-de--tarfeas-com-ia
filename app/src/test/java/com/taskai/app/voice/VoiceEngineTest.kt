package com.taskai.app.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceEngineTest {
    @Test fun `partial is preview only and final is delivered once`() {
        val h = Harness()
        h.engine.startListening()
        assertTrue(h.state.listening)
        h.input.listeners.single().onPartial("criar tarefa")
        assertEquals("criar tarefa", h.state.partialText)
        assertTrue(h.recognized.isEmpty())
        h.input.listeners.single().onFinal("  criar tarefa amanhã  ")
        h.input.listeners.single().onFinal("duplicado")
        assertEquals(listOf("criar tarefa amanhã"), h.recognized)
        assertEquals(VoiceUiState(), h.state)
        assertEquals(1, h.input.sessions.single().closes)
    }

    @Test fun `cancel and restart invalidate every callback of the previous capture`() {
        val h = Harness()
        h.engine.startListening()
        val old = h.input.listeners.single()
        h.engine.cancelListening()
        old.onPartial("tardio")
        old.onFinal("não enviar")
        old.onError(VoiceFailure.UNAVAILABLE)
        assertEquals(VoiceUiState(), h.state)
        assertTrue(h.recognized.isEmpty())
        assertEquals(1, h.input.sessions.single().closes)

        h.engine.startListening()
        h.engine.startListening()
        h.input.listeners[1].onFinal("captura anterior")
        assertTrue(h.state.listening)
        assertEquals(1, h.input.sessions[1].closes)
        h.input.listeners.last().onFinal("atual")
        assertEquals(listOf("atual"), h.recognized)
    }

    @Test fun `close is idempotent and disables future capture and late callbacks`() {
        val h = Harness()
        h.engine.startListening()
        val old = h.input.listeners.single()
        h.engine.close()
        h.engine.close()
        h.engine.startListening()
        old.onPartial("ignorar")
        old.onEndOfSpeech()
        old.onFinal("ignorar")
        old.onError(VoiceFailure.UNAVAILABLE)
        assertTrue(h.recognized.isEmpty())
        assertEquals(VoiceUiState(), h.state)
        assertEquals(1, h.input.sessions.single().closes)
        assertEquals(1, h.output.closes)
    }

    @Test fun `finish requests final once and never submits partial`() {
        val h = Harness()
        h.engine.startListening()
        val events = h.input.listeners.single()
        events.onPartial("prévia")
        h.engine.finishListening()
        h.engine.finishListening()
        assertEquals(1, h.input.sessions.single().finishes)
        assertFalse(h.state.listening)
        assertTrue(h.state.processing)
        assertTrue(h.recognized.isEmpty())
        events.onFinal("final")
        assertEquals(listOf("final"), h.recognized)
    }

    @Test fun `end of speech waits for final rather than sending preview`() {
        val h = Harness()
        h.engine.startListening()
        h.input.listeners.single().onEndOfSpeech()
        assertEquals(VoiceUiState(processing = true), h.state)
        assertTrue(h.recognized.isEmpty())
    }

    @Test fun `blank and oversized finals are rejected and maximum valid final is accepted`() {
        listOf("", " \n\t", "x".repeat(10_001)).forEach { text ->
            val h = Harness()
            h.engine.startListening()
            h.input.listeners.single().onPartial("p".repeat(10_001))
            assertTrue(h.state.partialText.length <= 10_000)
            h.input.listeners.single().onFinal(text)
            assertTrue(h.recognized.isEmpty())
            assertNotNull(h.state.error)
            assertFalse(h.state.processing)
            assertFalse(h.state.listening)
        }
        val h = Harness()
        h.engine.startListening()
        h.input.listeners.single().onFinal("x".repeat(10_000))
        assertEquals(10_000, h.recognized.single().length)
    }

    @Test fun `recognition failures clear preview and release capture without submission`() {
        val h = Harness()
        h.engine.startListening()
        val events = h.input.listeners.single()
        events.onPartial("privado")
        events.onError(VoiceFailure.UNAVAILABLE)
        assertEquals(VoiceUiState(error = VoiceFailure.UNAVAILABLE.message), h.state)
        assertEquals(1, h.input.sessions.single().closes)
        events.onFinal("não enviar")
        assertTrue(h.recognized.isEmpty())
    }

    @Test fun `denied permission and service startup exceptions are safe localized errors`() {
        listOf(SecurityException("secret internal details"), IllegalStateException("secret internal details")).forEach { failure ->
            val h = Harness()
            h.input.startFailure = failure
            h.engine.startListening()
            assertNotNull(h.state.error)
            assertFalse(h.state.error!!.contains("secret"))
            assertFalse(h.state.listening)
            assertTrue(h.recognized.isEmpty())
        }
    }

    @Test fun `synchronous terminal callback releases newly returned session`() {
        val h = Harness()
        h.input.immediateFinal = "imediato"
        h.engine.startListening()
        assertEquals(listOf("imediato"), h.recognized)
        assertEquals(1, h.input.sessions.single().closes)
        h.engine.cancelListening()
        assertEquals(1, h.input.sessions.single().closes)
    }

    @Test fun `finish and cleanup exceptions do not leak service details or prevent close`() {
        val h = Harness()
        h.engine.startListening()
        h.input.sessions.single().failure = IllegalStateException("secret")
        h.engine.finishListening()
        assertNotNull(h.state.error)
        assertFalse(h.state.error!!.contains("secret"))
        h.engine.close()
        assertEquals(1, h.output.closes)
    }

    @Test fun `spoken output is disabled by default and only latest new reply waits for lazy init`() {
        val h = Harness()
        h.engine.speak("histórico")
        assertEquals(0, h.output.initializations)
        h.engine.setSpokenRepliesEnabled(true)
        assertEquals(0, h.output.initializations)
        h.engine.speak("primeira")
        h.engine.speak("mais recente")
        assertEquals(1, h.output.initializations)
        assertTrue(h.output.spoken.isEmpty())
        h.output.ready!!.invoke(null)
        assertEquals(listOf("mais recente"), h.output.spoken)
        h.output.events.single().onStart()
        assertTrue(h.state.speaking)
        h.output.events.single().onDone()
        assertFalse(h.state.speaking)
    }

    @Test fun `disable clears pending reply and stops current playback immediately`() {
        val h = Harness()
        h.engine.setSpokenRepliesEnabled(true)
        h.engine.speak("não ler")
        h.engine.setSpokenRepliesEnabled(false)
        assertEquals(1, h.output.stops)
        h.output.ready!!.invoke(null)
        assertTrue(h.output.spoken.isEmpty())
        h.engine.speak("continua desligado")
        h.engine.setSpokenRepliesEnabled(true)
        assertTrue(h.output.spoken.isEmpty())
        h.engine.speak("nova resposta")
        h.output.events.single().onStart()
        assertTrue(h.state.speaking)
        h.engine.setSpokenRepliesEnabled(false)
        assertEquals(2, h.output.stops)
        assertFalse(h.state.speaking)
        h.output.events.single().onStart()
        assertFalse(h.state.speaking)
    }

    @Test fun `starting capture stops playback and discards pending initialization reply`() {
        val h = Harness()
        h.engine.setSpokenRepliesEnabled(true)
        h.engine.speak("aguardando")
        h.engine.startListening()
        h.output.ready!!.invoke(null)
        assertTrue(h.output.spoken.isEmpty())
        assertEquals(1, h.output.stops)
        h.engine.speak("não interromper captura")
        assertTrue(h.output.spoken.isEmpty())
        h.input.listeners.single().onEndOfSpeech()
        h.engine.speak("não interromper transcrição")
        assertTrue(h.output.spoken.isEmpty())
        h.engine.cancelListening()
        h.engine.speak("nova resposta")
        val old = h.output.events.single()
        old.onStart()
        h.engine.startListening()
        assertFalse(h.state.speaking)
        old.onStart()
        old.onError()
        assertEquals(VoiceUiState(listening = true), h.state)
    }

    @Test fun `stop clears pending reply without disabling later new replies`() {
        val h = Harness()
        h.engine.setSpokenRepliesEnabled(true)
        h.engine.speak("descartar")
        h.engine.stopSpeaking()
        h.output.ready!!.invoke(null)
        assertTrue(h.output.spoken.isEmpty())
        h.engine.speak("nova")
        assertEquals(listOf("nova"), h.output.spoken)
    }

    @Test fun `close invalidates output initialization and playback callbacks`() {
        val h = Harness()
        h.engine.setSpokenRepliesEnabled(true)
        h.engine.speak("antes")
        h.output.ready!!.invoke(null)
        val events = h.output.events.single()
        events.onStart()
        h.engine.close()
        events.onStart()
        events.onError()
        events.onDone()
        h.output.ready!!.invoke(null)
        h.engine.setSpokenRepliesEnabled(true)
        h.engine.speak("depois")
        assertEquals(VoiceUiState(), h.state)
        assertEquals(listOf("antes"), h.output.spoken)
        assertEquals(1, h.output.closes)
        assertTrue(h.output.stops > 0)
    }

    @Test fun `output exceptions are localized and do not block stop or release`() {
        val h = Harness()
        h.engine.setSpokenRepliesEnabled(true)
        h.output.failure = IllegalStateException("secret")
        h.engine.speak("resposta")
        assertNotNull(h.state.error)
        assertFalse(h.state.error!!.contains("secret"))
        h.output.failure = null
        h.engine.speak("outra resposta")
        h.output.ready!!.invoke(null)
        h.output.events.single().onStart()
        h.output.failure = IllegalStateException("secret")
        h.engine.setSpokenRepliesEnabled(false)
        assertFalse(h.state.speaking)
        h.engine.close()
        assertEquals(1, h.output.closes)
    }

    @Test fun `initialization failure clears queued text and a new reply may retry`() {
        val h = Harness()
        h.engine.setSpokenRepliesEnabled(true)
        h.engine.speak("não repetir")
        h.output.ready!!.invoke(VoiceFailure.UNAVAILABLE)
        assertEquals(VoiceFailure.UNAVAILABLE.message, h.state.error)
        h.engine.speak("nova resposta")
        assertEquals(2, h.output.initializations)
        h.output.ready!!.invoke(null)
        assertEquals(listOf("nova resposta"), h.output.spoken)
    }

    private class Harness {
        val input = FakeInput()
        val output = FakeOutput()
        var state = VoiceUiState()
        val recognized = mutableListOf<String>()
        val engine = VoiceEngine(input, output, { state = it }, recognized::add)
    }

    private class FakeInput : RecognitionPort {
        val listeners = mutableListOf<RecognitionEvents>()
        val sessions = mutableListOf<FakeSession>()
        var startFailure: RuntimeException? = null
        var immediateFinal: String? = null
        override fun start(events: RecognitionEvents): RecognitionSession {
            startFailure?.let { throw it }
            listeners += events
            immediateFinal?.let(events::onFinal)
            return FakeSession().also { sessions += it }
        }
    }
    private class FakeSession : RecognitionSession {
        var finishes = 0
        var closes = 0
        var failure: RuntimeException? = null
        override fun finish() { finishes++; failure?.let { throw it } }
        override fun close() { closes++; failure?.let { throw it } }
    }
    private class FakeOutput : SpeechOutputPort {
        var closes = 0
        var initializations = 0
        var stops = 0
        var ready: ((VoiceFailure?) -> Unit)? = null
        val spoken = mutableListOf<String>()
        val events = mutableListOf<PlaybackEvents>()
        var failure: RuntimeException? = null
        override fun initialize(onReady: (VoiceFailure?) -> Unit) {
            initializations++
            failure?.let { throw it }
            ready = onReady
        }
        override fun speak(text: String, events: PlaybackEvents) {
            failure?.let { throw it }
            spoken += text
            this.events += events
        }
        override fun stop() { stops++; failure?.let { throw it } }
        override fun close() { closes++; failure?.let { throw it } }
    }
}
