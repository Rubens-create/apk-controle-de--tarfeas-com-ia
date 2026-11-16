# Verificação da entrega Android

## Execução aprovada

- Código do APK: `98c74b71fb725e33f65ca62cf8d8467ed72c54f9`.
- [GitHub Actions aprovado](https://github.com/Rubens-create/apk-controle-de--tarfeas-com-ia/actions/runs/43918479590), estado conferido na API: **completed / success**.
- [APK publicado como artefato](https://github.com/Rubens-create/apk-controle-de--tarfeas-com-ia/actions/runs/43918479590/artifacts/14254400142).
- Os contadores abaixo foram extraídos dos relatórios baixados dessa execução, não de relatos dos autores.

| Verificação | Resultado |
|---|---|
| Testes JVM integrados | **99 testes, 0 falhas, 0 ignorados** |
| Testes Android em emulador API 29 | **18 testes, 0 falhas, 0 ignorados** |
| Android lint debug | Aprovado |
| Compilação automática do APK | Aprovada |
| Assinatura do APK baixado | Verificada com `apksigner`, esquema v2 |
| Instalação e abertura local do mesmo APK | `Success` e Activity com `Status: ok` |
| Persistência após encerrar o processo | Criação pela UI, `am force-stop`, reabertura e consulta aprovados |

## APK entregue

- Arquivo: `TaskAI-0.1.0-debug.apk`; aplicativo: `com.taskai.app.debug`.
- Versão: `0.1.0-debug`, versionCode `1`; Android mínimo API 26, target/compile 35.
- Tamanho: `18741559` bytes.
- SHA-256: `da748496f8dfd73521519ef8a3e06032dbbafade195ee65742bef60373af9374`.

## Cobertura exercitada

CRUD real em Room, conclusão/reabertura, referências, concorrência, deduplicação Unicode, auditoria transacional, filtros e recorrência; protocolos OpenAI-compatible/Anthropic/Gemini e falhas HTTP; schemas, seleção ambígua e confirmação de exclusões; backup completo, validação, limites e rollback; configurações e criptografia; interface de tarefas, organização, histórico e backup.

Os testes de dispositivo comprovaram **Android Keystore real** e **publicação real de notificação por alarme**. O script `scripts/process-persistence-smoke.py`, restrito ao pacote debug em emulador descartável, confirmou persistência entre processos com o APK do CI. A instalação foi limpa depois; nenhum dado de demonstração acompanha o aplicativo.

## Limites explícitos

- **Nenhuma chamada autenticada a um provedor pago foi executada.** Insira sua chave diretamente em Mais > Configurações, escolha um modelo disponível na sua conta e use Testar conexão. Não envie a chave no chat nem a grave no Git.
- Fixtures HTTP existem somente nos testes; não há modo de IA simulada no APK. Testes de protocolo não comprovam créditos ou disponibilidade de um modelo na conta do usuário.
- Ambiente de teste Android: emulador Android 10/API 29. Não houve teste em telefone físico nem em todas as versões Android. APIs posteriores têm cobertura JVM/Robolectric, que não substitui testes físicos.
- Android 13+ exige permissão de notificações. Alarmes exatos dependem de autorização; o fallback é aproximado e sujeito a Doze e às restrições de bateria. Forçar parada suspende execução até abrir o app novamente.
- A interface está em português brasileiro; não há traduções completas para outros idiomas.
- **Release de produção não foi publicada.** O workflow de tag `v*` está configurado para APK/AAB assinados, mas exige os secrets do README. A etapa de Release é corretamente ignorada em pushes normais da `main`.
- A assinatura entregue é debug, não uma chave de produção nem aprovação para Play Store. Builds de runners diferentes podem ter assinaturas diferentes: exporte backup antes de trocar de build. Atualizações de produção devem manter o mesmo keystore privado.

Commits posteriores somente de documentação ou scripts de verificação não mudam o código do APK acima; cada push na `main` continua disparando o workflow.
