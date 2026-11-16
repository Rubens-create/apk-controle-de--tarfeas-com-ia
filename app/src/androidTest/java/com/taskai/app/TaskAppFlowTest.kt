package com.taskai.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskAppFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @org.junit.Before fun cleanTestInstallation() {
        val app = compose.activity.application as TaskAiApplication
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { app.database.clearAllTables() }
        app.settingsStore.save(com.taskai.app.infra.AppSettings())
        compose.waitForIdle()
    }

    @Test fun manualTaskSurvivesRecreationAndCanBeCompleted() {
        compose.onNodeWithContentDescription("Criar tarefa").performClick()
        compose.onNodeWithText("Título da tarefa").performTextInput("Verificar persistência offline")
        compose.onNodeWithText("Salvar tarefa").performClick()
        compose.onNodeWithText("Tarefas", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Verificar persistência offline").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Verificar persistência offline").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Concluir Verificar persistência offline").performClick()
        compose.onNodeWithText("Concluídas").performScrollTo().performClick()
        compose.onNodeWithText("Verificar persistência offline").assertIsDisplayed()
    }

    @Test fun manualTaskCanBeEditedAndDeletedOnlyAfterConfirmation() {
        compose.onNodeWithText("Tarefas", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Criar tarefa").performClick()
        compose.onNodeWithText("Título da tarefa").performTextInput("Tarefa inicial de teste")
        compose.onNodeWithText("Salvar tarefa").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Tarefa inicial de teste").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Tarefa inicial de teste").performClick()
        compose.onNodeWithText("Título da tarefa").performTextReplacement("Tarefa editada de teste")
        compose.onNodeWithText("Salvar tarefa").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Tarefa editada de teste").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Tarefa editada de teste").performClick()
        compose.onNodeWithContentDescription("Excluir tarefa").performClick()
        val app = compose.activity.application as TaskAiApplication
        org.junit.Assert.assertEquals(1, kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { app.repository.listTasks().size })
        compose.onNodeWithText("Excluir", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { app.repository.listTasks().isEmpty() } }
    }

    @Test fun priorityDashboardOpensActualPrioritiesAcrossDates() {
        val app = compose.activity.application as TaskAiApplication
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            app.repository.createTask(com.taskai.app.data.TaskEntity(title = "Urgente amanhã", priority = "URGENTE", dueDate = java.time.LocalDate.now().plusDays(1).toString()))
            app.repository.createTask(com.taskai.app.data.TaskEntity(title = "Baixa hoje", priority = "BAIXA", dueDate = java.time.LocalDate.now().toString()))
        }
        compose.onNodeWithText("Prioritárias").performClick()
        compose.onNodeWithText("Urgente amanhã").assertIsDisplayed()
        compose.onNodeWithText("Baixa hoje").assertDoesNotExist()
    }

    @Test fun settingsCanBeSavedAndReopened() {
        compose.onNodeWithText("Mais", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Configurações").performClick()
        compose.onNodeWithText("Seu nome").performTextInput("Rubens de teste")
        compose.onNodeWithText("Salvar configurações").performScrollTo().performClick()
        compose.waitUntil(10_000) { (compose.activity.application as TaskAiApplication).settingsStore.settings.value.userName == "Rubens de teste" }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Rubens de teste").assertExists()
    }

    @Test fun categoriesAreStoredAndListed() {
        compose.onNodeWithText("Mais", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Categorias e projetos").performClick()
        compose.onNodeWithContentDescription("Adicionar categoria").performClick()
        compose.onNodeWithText("Nome").performTextInput("Casa de teste")
        compose.onNodeWithText("Salvar").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Casa de teste").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Casa de teste").assertIsDisplayed()
    }

    @Test fun backupExplainsCredentialExclusion() {
        compose.onNodeWithText("Mais", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Backup e restauração").performClick()
        compose.onNodeWithText("A chave de API não faz parte do backup.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Exportar backup").assertIsEnabled()
        compose.onNodeWithText("Importar backup").assertIsEnabled()
    }

    @Test fun assistantWithoutKeyDoesNotPretendToExecute() {
        compose.onNodeWithText("Assistente", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Mensagem para o assistente").performTextInput("Crie uma tarefa para amanhã")
        compose.onNodeWithContentDescription("Enviar mensagem").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Configure a chave de API em Mais > Configurações antes de usar a IA.").fetchSemanticsNodes().isNotEmpty() }
        val app = compose.activity.application as TaskAiApplication
        org.junit.Assert.assertTrue(kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { app.repository.listTasks().isEmpty() })
    }
}
