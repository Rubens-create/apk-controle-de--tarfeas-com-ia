# Voz e esclarecimentos no Task AI

## Fluxo

O microfone do chat recebe uma fala em português brasileiro. Resultados parciais são apenas uma prévia local. Somente o resultado final inicia o mesmo fluxo de IA e ferramentas usado pelo texto. Enquanto houver gravação/transcrição, o usuário pode cancelar sem enviar nada. O texto reconhecido é mostrado na conversa; não armazenamos um arquivo de áudio no banco.

Quando faltarem informações, a assistente deve perguntar e manter o contexto para a próxima resposta, por voz ou texto. Não deve inventar horários ou escolher uma tarefa arbitrariamente. Exclusões continuam exigindo a confirmação explícita da interface.

O switch **Respostas por voz**, no topo do chat, é independente do microfone e começa desligado. Quando ligado, somente respostas novas são lidas. Desligar, sair do chat, colocar o app em segundo plano ou iniciar uma nova captura interrompe a reprodução. Abrir histórico não dispara leitura automática.

## Serviços Android e privacidade

- SpeechRecognizer, com permissão RECORD_AUDIO solicitada somente após o toque no microfone.
- Preferir reconhecimento no dispositivo quando disponível. O serviço padrão pode exigir internet e processar áudio fora do dispositivo; informar isso na interface.
- TextToSpeech do Android para respostas em pt-BR; nenhum áudio deve ser reproduzido com o switch desligado.
- Serviço de reconhecimento ou voz ausente deve produzir uma mensagem clara, nunca uma transcrição/resposta fictícia.
- A suíte de testes pode controlar callbacks nas fronteiras Android; isso não substitui reconhecimento acústico em telefone com serviços de voz instalados.

## Contratos entre camadas

Pacote `com.taskai.app.voice`:

```kotlin
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
class AndroidVoiceController(
    context: android.content.Context,
    onState: (VoiceUiState) -> Unit,
    onRecognized: (String) -> Unit
) : VoiceController
```

A UI solicita permissão antes de `startListening`. O controlador ignora callbacks atrasados após cancelamento/fechamento e não chama onRecognized por resultados parciais. `speak` respeita o estado interno do switch. A implementação usa apenas a thread principal para APIs Android.

`AppSettings.spokenRepliesEnabled: Boolean = false` persiste a preferência. `ChatOutcome(text, pendingConfirmation = false, spokenText = text)` contém a resposta verificada e sua versão falável, sem IDs técnicos. A UI recebe eventos efêmeros de respostas novas do ViewModel, nunca infere reprodução a partir do carregamento de mensagens antigas.
