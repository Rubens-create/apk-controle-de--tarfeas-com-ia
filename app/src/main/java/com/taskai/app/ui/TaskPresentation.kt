package com.taskai.app.ui

import com.taskai.app.data.TaskEntity
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class DashboardSummary(
    val pending: Int, val overdue: Int, val completed: Int, val priority: Int,
    val todayCompleted: Int, val todayTotal: Int
) {
    val progress: Float get() = if (todayTotal == 0) 0f else todayCompleted.toFloat() / todayTotal
    companion object {
        fun from(tasks: List<TaskEntity>, today: LocalDate): DashboardSummary {
            val day = today.toString()
            return DashboardSummary(
                tasks.count { it.status == "PENDING" },
                tasks.count { it.status == "PENDING" && it.dueDate != null && it.dueDate < day },
                tasks.count { it.status == "COMPLETED" },
                tasks.count { it.status == "PENDING" && it.priority in listOf("ALTA", "URGENTE") },
                tasks.count { it.dueDate == day && it.status == "COMPLETED" },
                tasks.count { it.dueDate == day }
            )
        }
    }
}

fun taskDateLabel(task: TaskEntity, today: LocalDate): String {
    val date = task.dueDate?.let { LocalDate.parse(it) }
    val label = when (date) {
        null -> "Sem data"
        today -> "Hoje"
        today.plusDays(1) -> "Amanhã"
        else -> date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
    }
    return label + (task.dueTime?.let { " · $it" } ?: "")
}

fun priorityLabel(priority: String): String = when (priority) {
    "BAIXA" -> "Baixa"
    "ALTA" -> "Alta"
    "URGENTE" -> "Urgente"
    else -> "Média"
}

val taskFilterLabels = linkedMapOf(
    "ALL" to "Todas", "PENDING" to "Pendentes", "PRIORITY" to "Prioritárias", "TODAY" to "Hoje", "TOMORROW" to "Amanhã", "UPCOMING" to "Próximos dias",
    "WEEK" to "Esta semana", "OVERDUE" to "Atrasadas", "COMPLETED" to "Concluídas", "UNDATED" to "Sem data"
)
