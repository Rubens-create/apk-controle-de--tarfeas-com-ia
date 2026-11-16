# Decisões arquiteturais

## Android nativo e offline-first

Kotlin/Compose reduz a distância entre as APIs de permissões, alarmes e o aplicativo. Room é a fonte de verdade para tarefas, categorias, projetos, conversas e auditoria. A interface observa fluxos, sem armazenar listas paralelas. Chamadas HTTP não são necessárias para o CRUD manual. Não existe backend obrigatório nem conta própria.

Pacote inicial `com.taskai.app`, minSdk 26, compile/target 35, Java 17. As versões das dependências são fixadas no Gradle e o wrapper fixa Gradle 8.11.1. Target 35 não é uma declaração de conformidade com futuros requisitos da Play Store.

## Camadas e extensibilidade

`ui -> ViewModel -> TaskRepository -> Room`. A IA recebe apenas as ferramentas públicas, não uma conexão SQL. Provedores implementam um contrato independente, com conversão explícita dos protocolos OpenAI, Anthropic e Gemini. Configurações e lembretes são serviços Android separados. Uma futura sincronização deve consumir IDs estáveis e transações do repositório, não substituir o banco por estado em memória.

## Ferramentas e confiança

O modelo é entrada não confiável. Nomes de ferramentas são uma allowlist e os argumentos são validados antes de qualquer operação. Atualizações e exclusões recebem IDs retornados por consultas; uma referência ambígua requer consulta ou esclarecimento. Confirmações são decisões da interface, nunca um booleano aceito do modelo. Operações destrutivas ficam pendentes com alvos explícitos, e o botão de confirmação executa exatamente o pedido armazenado, uma única vez. Cancelar descarta a operação.

Respostas de sucesso para mutações são ancoradas nos resultados reais retornados pelo repositório. Erros de rede ou falhas das ferramentas não podem virar recibos de sucesso. O histórico local guarda mensagens e resultados das ferramentas para contexto e rastreabilidade. Há limite de rodadas de chamadas para impedir loops ilimitados e custos descontrolados.

## Data, horário e recorrência

Datas civis e horários são guardados como ISO local, com o fuso explicitamente configurado usado no agendamento. Instantes de auditoria são epoch millis. O prompt fornece data/hora/fuso atuais em cada solicitação. Expressões vagas como fim do expediente não definem um horário universal; o assistente deve perguntar. Recorrência usa subconjunto de RRULE validado, não código arbitrário. Concluir preserva a ocorrência original e cria a próxima na mesma transação, de maneira idempotente.

## Segurança e privacidade

A chave de API é cifrada por AES-GCM com chave do Android Keystore, não vai para o Git, logs ou backup explícito. O Android pode tornar a chave indisponível após reinstalação/alteração de segurança; nesse caso deve ser inserida novamente. Backups automáticos e transferência do aplicativo são excluídos para evitar migração incorreta das credenciais.

A API recebe o conteúdo da conversa e os resultados de ferramentas necessários para responder. As tarefas são dados pessoais; o usuário deve escolher um provedor em que confia. HTTPS é obrigatório em produção. Sem telemetria, anúncios ou segredos embutidos. O backup lógico não é criptografado; o usuário precisa armazená-lo em local privado. Restauração exige confirmação textual e validação de todo o grafo antes de alterar o banco.

## Lembretes Android

Alarmes por tarefa com identificador estável, canal de notificação e intents explícitas. Permissão POST_NOTIFICATIONS no Android 13+. Alarmes exatos somente se autorizados; caso contrário, fallback inexato sujeito a Doze. WorkManager cobre verificação persistente de atrasos e resumo diário. Boot, mudança de horário e fuso reconstroem o agendamento. Forçar parada pelo Android impede execução até abrir o app novamente; fabricantes podem impor restrições extras de bateria. Nenhum aplicativo pode garantir entrega pontual em todas essas condições.

## Distribuição e assinatura

Build debug é instalável para testes, mas não é uma versão estável de produção. Release exige keystore próprio e secrets explícitos. Não gerar ou publicar uma chave de produção sem o dono guardar uma cópia. Actions deve validar testes/lint e disponibilizar relatórios além do APK. Uma Release é autorizada por tag `v*` e depende das verificações e assinatura. O App Bundle prepara o formato de publicação, não substitui a revisão de loja.
