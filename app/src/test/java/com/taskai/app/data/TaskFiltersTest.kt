package com.taskai.app.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TaskFiltersTest {
    private val today = LocalDate.of(2026, 11, 18) // Wednesday
    private val tasks = listOf(
        TaskEntity(id = "past", title = "Atrasada", dueDate = "2026-11-17"),
        TaskEntity(id = "today", title = "Hoje", dueDate = "2026-11-18"),
        TaskEntity(id = "tomorrow", title = "Amanhã", dueDate = "2026-11-19"),
        TaskEntity(id = "sunday", title = "Domingo", dueDate = "2026-11-22"),
        TaskEntity(id = "monday", title = "Segunda", dueDate = "2026-11-23"),
        TaskEntity(id = "future", title = "Futuro", dueDate = "2027-01-01"),
        TaskEntity(id = "undated", title = "Sem data"),
        TaskEntity(id = "done", title = "Concluída", dueDate = "2026-11-18", status = "COMPLETED", completedAt = 1)
    )

    @Test fun appliesAllSupportedFiltersWithoutIncludingCompletedTasksInPendingViews() {
        val expected = mapOf(
            "ALL" to tasks.map { it.id },
            "TODAY" to listOf("today"), "TOMORROW" to listOf("tomorrow"),
            "UPCOMING" to listOf("tomorrow", "sunday", "monday", "future"),
            "WEEK" to listOf("past", "today", "tomorrow", "sunday"),
            "OVERDUE" to listOf("past"), "COMPLETED" to listOf("done"), "UNDATED" to listOf("undated")
        )
        expected.forEach { (filter, ids) -> assertEquals(filter, ids, TaskFilters.apply(tasks, filter, today).map { it.id }) }
        assertEquals(8, tasks.size)
    }

    @Test fun rejectsUnknownFiltersRatherThanReturningAllTasks() {
        assertThrows(IllegalArgumentException::class.java) { TaskFilters.apply(tasks, "DELETE", today) }
    }
}
