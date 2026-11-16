package com.taskai.app.ui

import com.taskai.app.data.TaskEntity
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class TaskPresentationTest {
    private val today = LocalDate.of(2026, 11, 16)

    @Test fun `dashboard counts only real tasks and isolates daily progress`() {
        val tasks = listOf(
            TaskEntity(title = "Hoje", dueDate = "2026-11-16", priority = "ALTA"),
            TaskEntity(title = "Concluída", dueDate = "2026-11-16", status = "COMPLETED"),
            TaskEntity(title = "Ontem", dueDate = "2026-11-15"),
            TaskEntity(title = "Sem data"),
            TaskEntity(title = "Amanhã", dueDate = "2026-11-17", priority = "URGENTE")
        )
        val result = DashboardSummary.from(tasks, today)
        assertEquals(4, result.pending)
        assertEquals(1, result.overdue)
        assertEquals(1, result.completed)
        assertEquals(2, result.priority)
        assertEquals(1, result.todayCompleted)
        assertEquals(2, result.todayTotal)
        assertEquals(0.5f, result.progress, 0.001f)
    }

    @Test fun `empty dashboard has no synthetic records or invalid progress`() {
        val result = DashboardSummary.from(emptyList(), today)
        assertEquals(0, result.pending)
        assertEquals(0f, result.progress)
    }

    @Test fun `task labels retain dates time and status`() {
        val task = TaskEntity(title = "Revisar", dueDate = "2026-11-17", dueTime = "09:00")
        assertEquals("Amanhã · 09:00", taskDateLabel(task, today))
        assertEquals("Sem data", taskDateLabel(TaskEntity(title = "Livre"), today))
        assertEquals("Urgente", priorityLabel("URGENTE"))
    }
}
