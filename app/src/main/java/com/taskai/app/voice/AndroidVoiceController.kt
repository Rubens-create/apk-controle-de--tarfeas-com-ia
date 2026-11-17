package com.taskai.app.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/** Android service adapter. No service is created until capture or an enabled new reply. */
class AndroidVoiceController(
    context: Context,
    onState: (VoiceUiState) -> Unit,
    onRecognized: (String) -> Unit
) : VoiceController {
    private val main = VoiceMainThread()
    private val closing = AtomicBoolean(false)
    private val engine = VoiceEngine(
        AndroidRecognitionPort(context.applicationContext, main),
        AndroidSpeechOutputPort(context.applicationContext, main),
        { if (!closing.get()) onState(it) },
        { if (!closing.get()) onRecognized(it) }
    )

    private fun run(action: () -> Unit) = main.run { if (!closing.get()) action() }
    override fun startListening() = run(engine::startListening)
    override fun finishListening() = run(engine::finishListening)
    override fun cancelListening() = run(engine::cancelListening)
    override fun setSpokenRepliesEnabled(enabled: Boolean) = run { engine.setSpokenRepliesEnabled(enabled) }
    override fun speak(text: String) = run { engine.speak(text) }
    override fun stopSpeaking() = run(engine::stopSpeaking)
    override fun close() {
        if (closing.compareAndSet(false, true)) main.run(engine::close)
    }
}

internal class VoiceMainThread {
    private val handler = Handler(Looper.getMainLooper())
    fun run(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
    }
    /** Always enqueue constructor callbacks, even when a service calls back synchronously. */
    fun post(action: () -> Unit) { handler.post { action() } }
}
