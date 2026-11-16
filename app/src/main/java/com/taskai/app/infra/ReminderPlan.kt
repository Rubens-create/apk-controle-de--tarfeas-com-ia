package com.taskai.app.infra

import com.taskai.app.data.TaskEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

internal object ReminderPlan {
    fun triggerAt(task: TaskEntity, zone: ZoneId): Long? {
        if (!task.reminderEnabled || task.status != "PENDING") return null
        task.reminderDatetime?.let { return it }
        if (task.dueDate == null || task.dueTime == null) return null
        return try {
            LocalDate.parse(task.dueDate).atTime(LocalTime.parse(task.dueTime)).atZone(zone)
                .minusMinutes(task.reminderMinutes.toLong()).toInstant().toEpochMilli()
        } catch (_: Exception) { null }
    }

    fun nextSummary(now: Instant, zone: ZoneId, hour: Int): Instant {
        require(hour in 0..23)
        val local = now.atZone(zone)
        val candidate = local.toLocalDate().atTime(hour, 0).atZone(zone)
        return (if (candidate.toInstant() > now) candidate else local.toLocalDate().plusDays(1).atTime(hour, 0).atZone(zone)).toInstant()
    }
}
