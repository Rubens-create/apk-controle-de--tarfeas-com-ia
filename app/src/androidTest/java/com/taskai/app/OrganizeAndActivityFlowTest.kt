package com.taskai.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskai.app.data.TaskEntity
import com.taskai.app.infra.AppSettings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the real application, repository and Room database on the test installation. */
@RunWith(AndroidJUnit4::class)
class OrganizeAndActivityFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as TaskAiApplication

    @Before fun cleanTestInstallation() {
        runBlocking(Dispatchers.IO) { app.database.clearAllTables() }
        app.settingsStore.save(AppSettings(zoneId = "UTC"))
        compose.waitForIdle()
    }

    private fun openOrganization() {
        compose.onNodeWithText("Mais", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Categorias e projetos").performClick()
    }

    private fun waitForSavedItem(name: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Salvar").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun moreDestinationsAreNavigable() {
        compose.onNodeWithText("Mais", useUnmergedTree = true).performClick()
        listOf("Configurações", "Categorias e projetos", "Histórico de atividades", "Backup e restauração").forEach { title ->
            compose.onNodeWithText(title).performScrollTo().performClick()
            compose.onAllNodesWithText(title).onFirst().assertExists()
            compose.onNodeWithContentDescription("Voltar").performClick()
        }
    }

    @Test fun categoryCustomizationPersistsAndConfirmedDeleteKeepsItsTask() {
        openOrganization()
        compose.onNodeWithContentDescription("Adicionar categoria").performClick()
        compose.onNodeWithText("Nome").performTextInput("Casa personalizada")
        compose.onNodeWithText("Cor hexadecimal").performScrollTo().performTextReplacement("#2367A8")
        compose.onNodeWithContentDescription("Ícone Casa").performScrollTo().performClick()
        compose.onNodeWithText("Salvar").performClick()
        waitForSavedItem("Casa personalizada")
        val category = runBlocking(Dispatchers.IO) { app.repository.categories.first().single() }
        assertEquals("#2367A8", category.color)
        assertEquals("home", category.icon)
        val task = runBlocking(Dispatchers.IO) {
            app.repository.createTask(TaskEntity(title = "Tarefa preservada", categoryId = category.id))
        }

        compose.onNodeWithContentDescription("Editar categoria Casa personalizada").performClick()
        compose.onNodeWithText("Nome").performTextReplacement("Casa atualizada")
        compose.onNodeWithText("Salvar").performClick()
        waitForSavedItem("Casa atualizada")
        compose.onNodeWithContentDescription("Excluir categoria Casa atualizada").performClick()
        compose.onNodeWithText("As tarefas serão mantidas", substring = true).assertExists()
        compose.onNodeWithText("Cancelar").performClick()
        compose.onNodeWithText("Casa atualizada").assertExists()
        compose.onNodeWithContentDescription("Excluir categoria Casa atualizada").performClick()
        compose.onNodeWithText("Excluir").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Casa atualizada").fetchSemanticsNodes().isEmpty() }
        val storedTask = runBlocking(Dispatchers.IO) { requireNotNull(app.repository.getTask(task.id)) }
        assertNull(storedTask.categoryId)
        assertEquals("Tarefa preservada", storedTask.title)
    }

    @Test fun projectDescriptionAndColorCanBeCreatedEditedAndDeleted() {
        openOrganization()
        compose.onNodeWithText("Projetos").performClick()
        compose.onNodeWithContentDescription("Adicionar projeto").performClick()
        compose.onNodeWithText("Nome").performTextInput("Projeto real de teste")
        compose.onNodeWithText("Descrição").performTextInput("Descrição persistida no Room")
        compose.onNodeWithText("Cor hexadecimal").performScrollTo().performTextReplacement("#3A806B")
        compose.onNodeWithText("Salvar").performClick()
        waitForSavedItem("Projeto real de teste")
        val project = runBlocking(Dispatchers.IO) { app.repository.projects.first().single() }
        assertEquals("Descrição persistida no Room", project.description)
        assertEquals("#3A806B", project.color)
        val task = runBlocking(Dispatchers.IO) {
            app.repository.createTask(TaskEntity(title = "Tarefa do projeto preservada", projectId = project.id))
        }
        compose.onNodeWithContentDescription("Editar projeto Projeto real de teste").performClick()
        compose.onNodeWithText("Descrição").performTextReplacement("Descrição revisada")
        compose.onNodeWithText("Salvar").performClick()
        waitForSavedItem("Descrição revisada")
        compose.onNodeWithContentDescription("Excluir projeto Projeto real de teste").performClick()
        compose.onNodeWithText("Excluir").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Projeto real de teste").fetchSemanticsNodes().isEmpty() }
        assertEquals(0, runBlocking(Dispatchers.IO) { app.repository.projects.first().size })
        assertNull(runBlocking(Dispatchers.IO) { requireNotNull(app.repository.getTask(task.id)).projectId })
    }

    @Test fun activityUsesRealLocalizedLogsAndTheConfiguredTimeZone() {
        openOrganization()
        compose.onNodeWithContentDescription("Adicionar categoria").performClick()
        compose.onNodeWithText("Nome").performTextInput("Categoria auditada")
        compose.onNodeWithText("Salvar").performClick()
        waitForSavedItem("Categoria auditada")
        val log = runBlocking(Dispatchers.IO) { app.repository.logs.first().single { it.entityType == "CATEGORY" } }
        val expectedTime = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm:ss", Locale.forLanguageTag("pt-BR"))
            .withZone(ZoneId.of("UTC")).format(Instant.ofEpochMilli(log.createdAt))
        compose.onNodeWithContentDescription("Voltar").performClick()
        compose.onNodeWithText("Histórico de atividades").performScrollTo().performClick()
        compose.onNodeWithText("Categoria criada").assertIsDisplayed()
        compose.onNodeWithText("Categoria salva: Categoria auditada").assertIsDisplayed()
        compose.onNodeWithText("Horários em UTC").assertIsDisplayed()
        compose.onNodeWithText(expectedTime).assertIsDisplayed()
    }
}
