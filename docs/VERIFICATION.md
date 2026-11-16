# Estado da verificação

A integração Android está em validação. Este documento distingue execução real de validações ainda pendentes e será atualizado com os resultados do build e dos testes de dispositivo.

## Confirmado pelo executor principal

- Java 17 executado no ambiente local.
- Gradle Wrapper 8.11.1 gerado; checksum da distribuição fixado a partir do endpoint oficial.
- SDK Manager confirmou `platforms;android-35`, `build-tools;35.0.0`, `platform-tools`, `emulator` e imagem Android 29 x86_64 instalados.
- Leitura limitada de backup validada com Kotlin/JUnit: 3 testes aprovados, após falha RED por implementação ausente.
- Receivers de alarme e restauração registrados no manifesto, não exportados.
- Revisão de código confirmou que backup e repositório usam NFKC, normalização de whitespace Unicode e lowercase ROOT para duplicidades.
- Repositório de destino acessível e permissão de push confirmada. Isso não significa que o código já foi publicado.

## Testes isolados relatados pelos módulos

Os autores dos módulos executaram fontes reais e Room/Robolectric em JVMs isoladas: dados (24 testes), IA (37 testes) e infraestrutura (21 testes). Estes relatos são evidência auxiliar, não substituem a suíte Gradle integrada do executor principal.

## Aceitação integrada

| Critério | Situação atual |
|---|---|
| Compilação integrada / lint | Em execução |
| Instalação e abertura do APK | Pendente |
| Criar, editar, excluir e concluir manualmente | Testes de dispositivo escritos; execução pendente |
| Persistência ao recriar a Activity | Teste escrito; execução pendente |
| Keystore real no Android | Teste escrito; execução pendente |
| Alarme publicando notificação real | Teste escrito; execução pendente |
| Backup transacional e round-trip | Coberto isoladamente; suíte integrada pendente |
| Protocolos de provedores e ferramentas | Testes locais com MockWebServer; suíte integrada pendente |
| API autenticada real | Não executada. Requer chave fornecida diretamente no aplicativo |
| GitHub Actions executando e publicando APK | Pendente |
| Release estável assinada | Pendente de configuração dos secrets de assinatura e tag |

## Limites da evidência

- Fixtures de transporte e chaves fictícias de criptografia existem somente nos testes, não há modo de IA simulada no aplicativo.
- Testes de protocolo HTTP não comprovam créditos, disponibilidade de modelos ou autorização numa conta real.
- Recriar a Activity não é o mesmo que forçar a parada do processo. A validação completa deve tratar ambos separadamente.
- Entrega de notificações depende das permissões e das restrições de bateria do Android. Forçar parada suspende execução até o usuário abrir o aplicativo novamente.
- Um APK debug instalável não é uma Release de produção nem aprovação para Play Store.
