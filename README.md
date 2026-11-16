# Task AI

Aplicativo Android nativo para organizar tarefas offline e operar o banco local por um assistente com ferramentas controladas. Interface em português brasileiro, Kotlin, Jetpack Compose, Room e WorkManager.

[![Android build](https://github.com/Rubens-create/apk-controle-de--tarfeas-com-ia/actions/workflows/android-build.yml/badge.svg)](https://github.com/Rubens-create/apk-controle-de--tarfeas-com-ia/actions/workflows/android-build.yml)

## Entrega validada

[Execução aprovada no Actions](https://github.com/Rubens-create/apk-controle-de--tarfeas-com-ia/actions/runs/43918479590): **99 testes JVM e 18 testes Android aprovados**, lint e APK compilado. [Baixar APK dessa execução](https://github.com/Rubens-create/apk-controle-de--tarfeas-com-ia/actions/runs/43918479590/artifacts/14254400142). A assinatura, a instalação local e a persistência após encerrar o processo também foram verificadas. Detalhes e limites: [docs/VERIFICATION.md](docs/VERIFICATION.md).

## Requisitos

- Android 8.0 (API 26) ou superior.
- Java 17, Android SDK 35 e Build Tools 35.0.0 para compilar.
- Internet e uma API Key própria somente para o assistente. O CRUD manual não depende da internet.
- Provedores: OpenAI, endpoints compatíveis com OpenAI, Anthropic e Google Gemini.

## Compilar

```bash
# Linux/macOS/Git Bash, com JAVA_HOME e ANDROID_HOME configurados
bash ./gradlew testDebugUnitTest lintDebug assembleDebug
# Windows cmd
# gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. O APK de desenvolvimento usa o identificador `com.taskai.app.debug`, permitindo coexistir com a versão de produção.

**Assinatura de testes:** runners diferentes podem gerar chaves debug diferentes. Exporte um backup antes de trocar de APK; pode ser necessário desinstalar uma versão com assinatura diferente. Para atualizar sem remover os dados, distribua Releases assinadas sempre com o mesmo keystore privado.

O script opcional `python scripts/provision-local.py` baixa um JDK 17 e a distribuição Gradle em `.tooling/`, ignorada pelo Git. O SDK Android exige aceitar as licenças oficiais; então instale `platform-tools`, `platforms;android-35` e `build-tools;35.0.0` com `sdkmanager`. `source scripts/env.sh` configura esses caminhos locais no Git Bash. O wrapper é o caminho oficial e reproduzível em CI.

## Instalar e começar

1. Baixe o APK e autorize a instalação de fontes desconhecidas para o aplicativo que abrir o arquivo.
2. Instale e abra o Task AI.
3. Crie tarefas pelo botão de adicionar. Sem dados de demonstração pré-carregados.
4. Em Mais > Configurações, informe nome, fuso e preferências.
5. Escolha o provedor, modelo e endpoint. Cole a API Key **na tela do aplicativo**, nunca no código ou em uma conversa.
6. Salve e use Testar conexão. A cobrança e disponibilidade pertencem ao provedor escolhido.
7. No Assistente, inicie uma conversa. Consultas e ações passam por ferramentas que validam e acessam o Room. Exclusões exigem confirmação na interface.
8. Para lembretes, permita notificações no Android. Alarmes exatos são opcionais; sem essa autorização o sistema pode atrasar a entrega para economizar bateria.

## Modelos e endpoints

| Provedor | Endpoint base | Exemplo de modelo configurável |
|---|---|---|
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |
| Anthropic | `https://api.anthropic.com/v1` | `claude-sonnet-4-6` |
| Gemini | `https://generativelanguage.googleapis.com/v1beta` | `gemini-3.8-flash` |
| Compatível | URL HTTPS da API OpenAI-compatible | Informe o identificador do provedor |

A disponibilidade dos exemplos depende da sua conta. Não há chaves no APK. A chave inserida é cifrada com AES-GCM usando uma chave não exportável do Android Keystore. Backups e transferência automática Android estão desabilitados para impedir a cópia de credenciais cifradas sem sua chave. O backup explícito do aplicativo não contém configurações nem credenciais.

## Organização

- `data/`: entidades Room, DAO, repositório transacional, validações, filtros e recorrência.
- `ai/`: contratos de provedores, HTTP real, schemas de ferramentas, validação e orquestração de conversas.
- `infra/`: Keystore/configurações, agendamento Android e backup lógico.
- `ui/`: telas Compose e apresentação.
- `docs/CONTRACTS.md`: contratos entre camadas.
- `docs/ARCHITECTURE.md`: decisões e limites de segurança.
- `docs/VERIFICATION.md`: resultados executados e validações ainda dependentes de ambiente externo.

## Recorrência

Regras aceitas seguem um subconjunto validado de RRULE: `FREQ=DAILY`, `FREQ=WEEKLY`, `FREQ=MONTHLY`, `FREQ=YEARLY`, `INTERVAL=n`, `BYDAY=MO,WE,FR`, `BYMONTHDAY=10`. A conclusão de uma ocorrência cria a próxima tarefa pendente na mesma transação. Reabrir uma ocorrência não deve recriar a próxima novamente. Lembretes usam o fuso configurado; datas sem horário não recebem horários inventados pelo assistente.

## Backup e restauração

Em Mais > Backup, exporte um JSON pelo seletor de documentos do Android. O arquivo contém dados pessoais, tarefas, categorias, projetos, conversas e histórico; proteja-o como um documento privado. A chave de IA não é exportada. Na importação, o app apresenta a inspeção do arquivo e exige digitar `RESTAURAR`. A operação substitui os dados locais em uma transação, após validação de versão, tamanho, campos e relacionamentos. Exporte uma cópia atual antes de substituir. Configurações e credenciais atuais são mantidas.

## Testes

```bash
bash ./gradlew testDebugUnitTest
bash ./gradlew lintDebug
# Com emulador ou aparelho conectado (adb devices)
bash ./gradlew connectedDebugAndroidTest
```

Testes JVM verificam regras, Room real via Robolectric, persistência, segurança de ferramentas, protocolos HTTP com servidor local de teste e falhas de rede. As respostas HTTP nos testes são fixtures explícitas, não um modo simulado do aplicativo. Testes de dispositivo exercitam a criação manual, recriação da Activity e a ausência de chave no assistente. Veja `docs/VERIFICATION.md` para não confundir testes locais com chamadas a um provedor pago ou execução no GitHub.

Em um **emulador descartável**, `python scripts/device-verify.py` instala os APKs já compilados e executa a instrumentação sem manter um JVM Gradle na memória. `python scripts/process-persistence-smoke.py` cria uma tarefa pela UI e verifica sua permanência após `am force-stop`. Esses testes limpam os dados do pacote debug; não os use em uma instalação com dados que deseja preservar.

## GitHub Actions e versões

O workflow `.github/workflows/android-build.yml` executa em push na `main`, pull requests e execução manual. Roda testes JVM, lint, compila um APK debug instalável, envia o APK como artefato e executa testes de interface em emulador Linux. Na aba **Actions**, abra a execução e baixe `TaskAI-debug-<sha>`. O artefato aparece após o build; o resultado final da execução também depende dos testes de dispositivo.

Tags `v*` também executam a validação e, se ela passar, produzem APK e Android App Bundle de produção assinados, publicados em GitHub Releases. Antes da primeira versão estável, configure em Settings > Secrets and variables > Actions:

- `ANDROID_KEYSTORE_BASE64`: keystore de produção codificado em Base64.
- `ANDROID_KEYSTORE_PASSWORD`: senha do keystore.
- `ANDROID_KEY_ALIAS`: alias da chave.
- `ANDROID_KEY_PASSWORD`: senha da chave.

Não há senha padrão nem fallback para assinatura debug nas versões estáveis. Sem esses secrets o job de Release falha explicitamente. Guarde a chave de produção e suas senhas fora do Git; atualizações Android exigem a mesma assinatura. No Windows, use `keytool.exe` do JDK para gerar a chave de produção localmente e a interface do GitHub para cadastrar secrets, sem expor senhas no chat.

```bash
# Após configurar origin e autenticar na sua conta:
git push -u origin main
# Depois de validar e configurar assinatura:
git tag v0.1.0
git push origin v0.1.0
```

O `versionName` vem da tag e o `versionCode` vem do número da execução. Para builds fora do CI, defina `VERSION_NAME`, `VERSION_CODE` e as variáveis `ANDROID_KEYSTORE_*` usadas em `app/build.gradle.kts`. Nunca use versionCode menor ou igual ao de um lançamento já distribuído.

## Publicação futura na Play Store

O build de produção gera `.aab`. Antes da publicação é necessário definir identificador definitivo, identidade visual final, política de privacidade, ficha de segurança dos dados (o texto do chat é enviado ao provedor escolhido), classificação de conteúdo, assinatura/Play App Signing, testes em dispositivos e revisar o target SDK exigido pela loja no momento do envio. Gerar um bundle não significa que o aplicativo já foi aprovado pela Google Play.
