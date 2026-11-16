package com.taskai.app.data

import java.time.LocalDate
import java.time.format.DateTimeParseException

object TaskRules {
    fun validate(task: TaskEntity) {
        require(task.id.isNotBlank() && task.id.length <= 200) { "Identificador da tarefa inválido." }
        require(task.title.isNotBlank() && task.title.length <= 500) { "O título deve conter de 1 a 500 caracteres." }
        require(task.description.length <= 20000) { "A descrição deve ter até 20000 caracteres." }
        task.dueDate?.let { parseDate(it) }
        task.dueTime?.let {
            require(task.dueDate != null) { "Informe a data antes do horário." }
            require(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(it)) { "Horário inválido. Use HH:mm." }
        }
        task.recurrenceRule?.let {
            require(task.dueDate != null) { "Uma tarefa recorrente exige uma data." }
            Recurrence.nextDate(parseDate(task.dueDate), it)
        }
        require(task.priority in setOf("BAIXA", "MEDIA", "ALTA", "URGENTE")) { "Prioridade inválida." }
        require(task.status in setOf("PENDING", "COMPLETED")) { "Status inválido." }
        require(task.categoryId == null || task.categoryId.isNotBlank()) { "Categoria inválida." }
        require(task.projectId == null || task.projectId.isNotBlank()) { "Projeto inválido." }
        require(task.reminderMinutes in 0..525600) { "O lembrete deve estar entre 0 e 525600 minutos." }
        require(!task.reminderEnabled || (task.dueDate != null && task.dueTime != null)) { "O lembrete exige data e horário." }
        require(task.reminderDatetime == null || task.reminderDatetime >= 0) { "Data do lembrete inválida." }
        require(task.createdAt >= 0 && task.updatedAt >= 0) { "Data de criação ou atualização inválida." }
        require(task.completedAt == null || task.completedAt >= 0) { "Data de conclusão inválida." }
        require((task.status == "COMPLETED") == (task.completedAt != null)) { "A data de conclusão deve corresponder ao status da tarefa." }
    }

    internal fun parseDate(value: String): LocalDate {
        require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) { "Data inválida. Use AAAA-MM-DD." }
        return try { LocalDate.parse(value) } catch (_: DateTimeParseException) {
            throw IllegalArgumentException("Data inválida. Use uma data existente no formato AAAA-MM-DD.")
        }
    }
}
