package com.taskai.app.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskai.app.BuildConfig
import com.taskai.app.infra.AppSettings
import com.taskai.app.infra.SettingsStore

@Composable fun SettingsScreen(vm: AppViewModel) {
    val saved by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val chatting by vm.chatting.collectAsStateWithLifecycle()
    // Deliberately not saveable: neither the credential nor a draft containing it enters a Bundle.
    var draft by remember(saved) { mutableStateOf(saved.copy(language = "pt-BR")) }
    var hour by remember(saved) { mutableStateOf(saved.dailySummaryHour.toString()) }
    var minutes by remember(saved) { mutableStateOf(saved.defaultReminderMinutes.toString()) }
    var temperature by remember(saved) { mutableStateOf(saved.temperature.toString()) }
    var clearHistory by remember { mutableStateOf(false) }
    val candidate = draft.copy(dailySummaryHour = hour.toIntOrNull() ?: -1,
        defaultReminderMinutes = minutes.toIntOrNull() ?: -1,
        temperature = temperature.replace(',', '.').toDoubleOrNull() ?: Double.NaN)
    val validation = runCatching {
        SettingsStore.validate(candidate)
        require(candidate.provider != "ANTHROPIC" || candidate.temperature <= 1.0) { "Na Anthropic, use temperatura entre 0 e 1." }
    }.exceptionOrNull()?.message

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Ajuste o app à sua rotina. As alterações só são aplicadas ao salvar.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsSection("Seu perfil e aparência") {
            SettingsInput("Seu nome", draft.userName, { draft = draft.copy(userName = it) }, enabled = !busy)
            ChoiceField("Tema", draft.theme, listOf("AUTO" to "Acompanhar o sistema", "LIGHT" to "Claro", "DARK" to "Escuro"), { draft = draft.copy(theme = it) })
            Text("Idioma", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Português (Brasil) · pt-BR")
            SettingsInput("Fuso horário", draft.zoneId, { draft = draft.copy(zoneId = it) }, enabled = !busy)
            Text("Use um identificador válido, como America/Sao_Paulo. Datas e lembretes seguem esse fuso.", style = MaterialTheme.typography.bodySmall)
        }
        SettingsSection("Notificações e lembretes") {
            SettingsToggle("Receber notificações", draft.notificationsEnabled, !busy) { draft = draft.copy(notificationsEnabled = it) }
            NotificationAccess { vm.notice.value = it }
            SettingsToggle("Resumo diário", draft.dailySummaryEnabled, !busy && draft.notificationsEnabled) { draft = draft.copy(dailySummaryEnabled = it) }
            SettingsInput("Hora do resumo (0–23)", hour, { hour = it }, KeyboardType.Number, !busy)
            SettingsInput("Antecedência padrão (minutos)", minutes, { minutes = it }, KeyboardType.Number, !busy)
            Text("Antecedência de 0 a 525600 minutos. O padrão vale para novas tarefas; ative o lembrete em cada tarefa. Sem permissão para alarmes exatos, o Android pode atrasar os avisos.", style = MaterialTheme.typography.bodySmall)
        }
        SettingsSection("Inteligência artificial") {
            ChoiceField("Provedor de IA", draft.provider, listOf("OPENAI" to "OpenAI", "COMPATIBLE" to "Compatível com OpenAI", "ANTHROPIC" to "Anthropic", "GEMINI" to "Gemini"), { provider ->
                if (provider != draft.provider) {
                    val defaults = ProviderPresets.forProvider(provider)
                    draft = draft.copy(provider = provider, endpoint = defaults.first, model = defaults.second, apiKey = "")
                    temperature = candidate.temperature.takeIf { it.isFinite() }?.coerceIn(0.0, if (provider == "ANTHROPIC") 1.0 else 2.0)?.toString() ?: "0.3"
                }
            })
            Text("Ao trocar de provedor, a chave em edição é apagada. Confira o endpoint e o modelo; os valores sugeridos podem ser alterados conforme sua conta.", style = MaterialTheme.typography.bodySmall)
            if (draft.provider == "COMPATIBLE") Text("Informe o endpoint HTTPS e o identificador de modelo do seu serviço compatível com OpenAI.", style = MaterialTheme.typography.bodySmall)
            SettingsInput("Endpoint HTTPS", draft.endpoint, { draft = draft.copy(endpoint = it) }, KeyboardType.Uri, !busy)
            Text("Informe a URL base, sem credenciais, parâmetros ou fragmentos. Não inclua /chat/completions, /messages ou /models/…:generateContent.", style = MaterialTheme.typography.bodySmall)
            SettingsInput("Modelo", draft.model, { draft = draft.copy(model = it) }, enabled = !busy)
            OutlinedTextField(value = draft.apiKey, onValueChange = { draft = draft.copy(apiKey = it) },
                label = { Text("Chave de API") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                enabled = !busy, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
            Text("A chave fica criptografada com o Android Keystore ao salvar. A edição não é recuperada após recriar a tela. Deixe o campo vazio e salve para remover a chave.", style = MaterialTheme.typography.bodySmall)
            SettingsInput("Temperatura", temperature, { temperature = it }, KeyboardType.Decimal, !busy)
            Text(if (draft.provider == "ANTHROPIC") "De 0 a 1 na Anthropic; valores menores tornam as respostas mais objetivas." else "De 0 a 2; valores menores tornam as respostas mais objetivas.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(draft.personality, { draft = draft.copy(personality = it) }, label = { Text("Personalidade do assistente") },
                modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8, enabled = !busy)
            Text("O teste usa os valores em edição, envia uma mensagem real ao endpoint e pode consumir créditos. A chave é enviada somente ao endpoint configurado; confira se você confia nele.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { vm.testConnection(candidate) }, enabled = !busy && validation == null && candidate.apiKey.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Testar conexão") }
        }
        SettingsSection("Histórico de conversas") {
            Text("Apague as conversas e mensagens do assistente sem excluir suas tarefas, categorias ou projetos.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { clearHistory = true }, enabled = !busy && !chatting, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Limpar histórico de conversas") }
        }
        SettingsSection("Sobre e privacidade") {
            Text("Task AI · versão ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodyMedium)
            Text("Suas tarefas, conversas e preferências ficam neste dispositivo. Ao usar a IA, mensagens e dados de tarefas necessários ao pedido podem ser enviados ao provedor escolhido, sujeitos à política desse serviço. Não compartilhe informações sensíveis sem necessidade.", style = MaterialTheme.typography.bodySmall)
            Text("Backups são arquivos pessoais não criptografados. A chave de API não faz parte do backup. Ao desinstalar o app, a chave protegida pelo Keystore não pode ser recuperada pelo backup.", style = MaterialTheme.typography.bodySmall)
        }
        if (validation != null) Text(validation, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { vm.saveSettings(candidate) }, enabled = !busy && validation == null, modifier = Modifier.fillMaxWidth()) { Text("Salvar configurações") }
        Spacer(Modifier.height(12.dp))
    }
    if (clearHistory) AlertDialog(onDismissRequest = { clearHistory = false }, title = { Text("Limpar histórico de conversas?") },
        text = { Text("Todas as conversas e mensagens serão removidas deste dispositivo. Essa ação não pode ser desfeita. Suas tarefas serão mantidas.") },
        confirmButton = { TextButton(onClick = { clearHistory = false; vm.clearHistory() }, enabled = !busy && !chatting,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Excluir conversas") } },
        dismissButton = { TextButton(onClick = { clearHistory = false }) { Text("Cancelar") } })
}

@Composable private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable private fun SettingsInput(label: String, value: String, change: (String) -> Unit,
    keyboard: KeyboardType = KeyboardType.Text, enabled: Boolean = true) {
    OutlinedTextField(value, change, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false))
}

@Composable private fun SettingsToggle(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = change, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable private fun NotificationAccess(onError: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var notifications by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var exactAlarms by remember { mutableStateOf(exactAlarmsAllowed(context)) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!granted) onError("Permissão de notificações não concedida. Você pode autorizar nos ajustes do Android.")
    }
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
                exactAlarms = exactAlarmsAllowed(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Text(if (notifications) "Notificações autorizadas no Android." else "O Android está bloqueando notificações. Ativar a preferência acima não concede permissão.", style = MaterialTheme.typography.bodySmall)
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        OutlinedButton(onClick = { request.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.fillMaxWidth()) { Text("Permitir notificações") }
    }
    TextButton(onClick = {
        openSystemSettings(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName), onError)
    }) { Text("Abrir ajustes de notificações") }
    if (Build.VERSION.SDK_INT >= 31 && !exactAlarms) {
        Text("Alarmes exatos não autorizados. Os lembretes usarão o agendamento aproximado do Android.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            openSystemSettings(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")), onError)
        }, modifier = Modifier.fillMaxWidth()) { Text("Autorizar alarmes exatos") }
    } else Text("Alarmes exatos disponíveis no Android.", style = MaterialTheme.typography.bodySmall)
}

private fun exactAlarmsAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

private fun openSystemSettings(context: Context, intent: Intent, onError: (String) -> Unit) {
    try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: android.content.ActivityNotFoundException) { onError("Não foi possível abrir os ajustes. Acesse as permissões do Task AI nos ajustes do Android.") }
    catch (_: SecurityException) { onError("O Android bloqueou o acesso aos ajustes. Abra as permissões do app manualmente.") }
}
