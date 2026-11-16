package com.taskai.app.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

object TaskFilters {
    /** ALL includes history. Date views contain only pending tasks; WEEK is Monday through Sunday. */
    fun apply(tasks: List<TaskEntity>, filter: String, today: LocalDate): List<TaskEntity> {
        require(filter in setOf("ALL", "TODAY", "TOMORROW", "UPCOMING", "WEEK", "OVERDUE", "COMPLETED", "UNDATED")) { "Filtro de tarefas inválido." }
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return tasks.filter { task ->
            val date = task.dueDate?.let(TaskRules::parseDate)
            when (filter) {
                "ALL" -> true
                "COMPLETED" -> task.status == "COMPLETED"
                else -> task.status == "PENDING" && when (filter) {
                    "TODAY" -> date == today
                    "TOMORROW" -> date == today.plusDays(1)
                    "UPCOMING" -> date != null && date > today
                    "WEEK" -> date != null && date >= weekStart && date <= weekStart.plusDays(6)
                    "OVERDUE" -> date != null && date < today
                    "UNDATED" -> date == null
                    else -> false
                }
            }
        }
    }
}
