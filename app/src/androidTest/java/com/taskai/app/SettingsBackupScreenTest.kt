package com.taskai.app

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskai.app.infra.AppSettings
import com.taskai.app.ui.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsBackupScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun resetDisposableInstallation() {
        val app = compose.activity.application as TaskAiApplication
        runBlocking(Dispatchers.IO) { app.database.clearAllTables() }
        app.settingsStore.save(AppSettings())
        compose.waitForIdle()
    }

    private fun openSettings() {
        compose.onNodeWithText("Mais", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Configurações").performClick()
    }

    @Test fun invalidZoneAndNumericDraftCannotBeSaved() {
        openSettings()
        compose.onNodeWithText("Fuso horário").performScrollTo().performTextReplacement("Invalid/Zone")
        compose.onNodeWithText("Salvar configurações").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Fuso horário inválido.").assertExists()
        compose.onNodeWithText("Fuso horário").performScrollTo().performTextReplacement("America/Sao_Paulo")
        compose.onNodeWithText("Hora do resumo (0–23)").performScrollTo().performTextReplacement("24")
        compose.onNodeWithText("Salvar configurações").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Hora do resumo (0–23)").performScrollTo().performTextReplacement("8")
        compose.onNodeWithText("Salvar configurações").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Português (Brasil) · pt-BR").assertExists()
        compose.onNodeWithText("English").assertDoesNotExist()
    }

    @Test fun unsavedCredentialIsMaskedAndNotRestoredByActivityRecreation() {
        openSettings()
        compose.onNodeWithText("Chave de API").performScrollTo().performTextInput("ui-fixture-not-a-real-key")
        compose.onNode(hasSetTextAction() and hasText("Chave de API")).assert(
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password)
        )
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Chave de API").performScrollTo().assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString(""))
        )
        compose.onNodeWithText("Testar conexão").performScrollTo().assertIsNotEnabled()
    }

    @Test fun changingProviderResetsEndpointModelAndDraftCredential() {
        openSettings()
        compose.onNodeWithText("Chave de API").performScrollTo().performTextInput("ui-fixture-not-a-real-key")
        compose.onNodeWithText("OpenAI").performScrollTo().performClick()
        compose.onNodeWithText("Anthropic").performClick()
        compose.onNodeWithText("Endpoint HTTPS").assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                androidx.compose.ui.text.AnnotatedString("https://api.anthropic.com/v1"))
        )
        compose.onNodeWithText("Modelo").assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                androidx.compose.ui.text.AnnotatedString("claude-sonnet-4-6"))
        )
        compose.onNodeWithText("Chave de API").assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString(""))
        )
        compose.onNodeWithText("Testar conexão").performScrollTo().assertIsNotEnabled()
    }

    @Test fun restoreNeedsExactTypedConfirmationAndCancelDiscardsPreview() {
        val app = compose.activity.application as TaskAiApplication
        val file = File(app.cacheDir, "settings-backup-ui-test.json")
        try {
            file.writeText(runBlocking(Dispatchers.IO) { app.backup.exportJson() })
            compose.onNodeWithText("Mais", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Backup e restauração").performClick()
            compose.runOnUiThread {
                ViewModelProvider(compose.activity)[AppViewModel::class.java].inspectBackup(Uri.fromFile(file))
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Confirmar restauração").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Confirmar restauração").assertIsNotEnabled()
            compose.onNodeWithText("Digite RESTAURAR").performTextInput("restaurar")
            compose.onNodeWithText("Confirmar restauração").assertIsNotEnabled()
            compose.onNodeWithText("Digite RESTAURAR").performTextReplacement("RESTAURAR")
            compose.onNodeWithText("Confirmar restauração").assertIsEnabled()
            compose.onNodeWithText("Cancelar").performClick()
            compose.onNodeWithText("Confirmar restauração").assertDoesNotExist()
            compose.runOnIdle {
                check(ViewModelProvider(compose.activity)[AppViewModel::class.java].restorePreview.value == null)
            }
        } finally { file.delete() }
    }
}
