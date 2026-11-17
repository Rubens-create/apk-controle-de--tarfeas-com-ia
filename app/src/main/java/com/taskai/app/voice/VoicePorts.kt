package com.taskai.app.voice

/** Android boundaries; implementations and callbacks are serialized on the main thread. */
internal interface RecognitionPort {
    fun start(events: RecognitionEvents): RecognitionSession
}

internal interface RecognitionSession {
    fun finish()
    /** Cancels outstanding capture and releases its service. */
    fun close()
}

internal interface RecognitionEvents {
    fun onPartial(text: String)
    fun onEndOfSpeech()
    fun onFinal(text: String)
    fun onError(failure: VoiceFailure)
}

internal interface SpeechOutputPort {
    fun initialize(onReady: (VoiceFailure?) -> Unit)
    fun speak(text: String, events: PlaybackEvents)
    fun stop()
    fun close()
}

internal interface PlaybackEvents {
    fun onStart()
    fun onDone()
    fun onError()
}

internal enum class VoiceFailure(val message: String) {
    UNAVAILABLE("Não há serviço de reconhecimento de voz disponível neste dispositivo."),
    PERMISSION("Permita o acesso ao microfone para usar comandos de voz."),
    NO_SPEECH("Não consegui entender a fala. Toque no microfone e tente novamente."),
    TOO_LONG("O comando de voz excede o limite de 10.000 caracteres. Tente uma frase menor."),
    NETWORK("Não foi possível reconhecer a fala. Verifique a conexão e tente novamente."),
    BUSY("O reconhecimento de voz está ocupado. Aguarde e tente novamente."),
    AUDIO("Não foi possível acessar o microfone. Tente novamente."),
    LANGUAGE("O reconhecimento em português do Brasil não está disponível neste serviço."),
    RECOGNITION("Não foi possível reconhecer a fala. Tente novamente."),
    TTS_UNAVAILABLE("Não há serviço de leitura por voz disponível neste dispositivo."),
    TTS_LANGUAGE("A voz em português do Brasil não está disponível. Instale os dados de voz nas configurações do Android."),
    TTS_ERROR("Não foi possível reproduzir a resposta por voz.")
}

internal class VoiceServiceException(val failure: VoiceFailure) : RuntimeException()

internal fun recognitionFailure(error: RuntimeException): VoiceFailure = when (error) {
    is SecurityException -> VoiceFailure.PERMISSION
    is VoiceServiceException -> error.failure
    else -> VoiceFailure.RECOGNITION
}
