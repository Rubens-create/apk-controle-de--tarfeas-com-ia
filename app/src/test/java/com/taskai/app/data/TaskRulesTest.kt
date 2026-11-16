package com.taskai.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TaskRulesTest {
    @Test fun acceptsAnUndatedTaskWithDefaultValues() {
        val task = TaskEntity(title = "Comprar pão")
        TaskRules.validate(task)
        assertEquals("PENDING", task.status)
    }

    @Test fun rejectsInvalidFieldsWithoutSilentlyCorrectingThem() {
        val valid = TaskEntity(title = "Consulta", dueDate = "2026-11-16", dueTime = "09:30")
        listOf(
            valid.copy(id = " "), valid.copy(title = "a".repeat(501)),
            valid.copy(description = "a".repeat(20001)),
            valid.copy(dueTime = "9:30"), valid.copy(dueTime = "24:00"),
            valid.copy(dueTime = "09:60"), valid.copy(dueDate = null),
            valid.copy(priority = "media"), valid.copy(status = "UNKNOWN"),
            valid.copy(categoryId = ""), valid.copy(projectId = " "),
            valid.copy(reminderMinutes = -1), valid.copy(reminderMinutes = 525601),
            valid.copy(reminderEnabled = true, dueDate = null, dueTime = null),
            valid.copy(reminderDatetime = -1), valid.copy(createdAt = -1),
            valid.copy(updatedAt = -1), valid.copy(completedAt = -1),
            valid.copy(status = "PENDING", completedAt = 1),
            valid.copy(status = "COMPLETED", completedAt = null)
        ).forEach { task ->
            val error = assertThrows("Deveria rejeitar: $task", IllegalArgumentException::class.java) { TaskRules.validate(task) }
            org.junit.Assert.assertTrue(error.message?.isNotBlank() == true)
        }
        TaskRules.validate(valid.copy(reminderEnabled = true))
        TaskRules.validate(valid.copy(status = "COMPLETED", completedAt = 1))
        TaskRules.validate(valid.copy(reminderMinutes = 0))
    }

    @Test fun rejectsBlankTitleAndInvalidCalendarDate() {
        listOf(
            TaskEntity(title = "  \n "),
            TaskEntity(title = "Consulta", dueDate = "2025-02-29"),
            TaskEntity(title = "Consulta", dueDate = "2026-2-03")
        ).forEach { task ->
            assertThrows(IllegalArgumentException::class.java) { TaskRules.validate(task) }
        }
    }
}
