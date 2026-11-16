package com.taskai.app.infra

import com.taskai.app.data.TaskEntity
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ReminderPlanTest {
    @Test fun `computes reminder from task local date in selected zone`() {
        val task = TaskEntity(id = "a", title = "Consulta", dueDate = "2030-01-02", dueTime = "10:00", reminderEnabled = true, reminderMinutes = 30)
        assertEquals(Instant.parse("2030-01-02T12:30:00Z").toEpochMilli(), ReminderPlan.triggerAt(task, ZoneId.of("America/Sao_Paulo")))
        assertNull(ReminderPlan.triggerAt(task.copy(reminderEnabled = false), ZoneId.of("UTC")))
        assertNull(ReminderPlan.triggerAt(task.copy(status = "COMPLETED"), ZoneId.of("UTC")))
    }

    @Test fun `daily summary delay follows wall clock across DST`() {
        val now = Instant.parse("2030-03-09T14:00:00Z")
        val next = ReminderPlan.nextSummary(now, ZoneId.of("America/New_York"), 8)
        assertEquals(Instant.parse("2030-03-10T12:00:00Z"), next)
    }

    @Test fun `explicit reminder timestamp wins over local due time`() {
        val task = TaskEntity(title = "Alarme", reminderEnabled = true, reminderDatetime = 12345)
        assertEquals(12345L, ReminderPlan.triggerAt(task, ZoneId.of("UTC")))
    }
}
