package com.taskai.app.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RecurrenceTest {
    private fun next(date: String, rule: String) = Recurrence.nextDate(LocalDate.parse(date), rule).toString()

    @Test fun selectsWeekdaysInIntervalWeeksStartingOnMonday() {
        assertEquals("2026-11-18", next("2026-11-16", "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE"))
        assertEquals("2026-11-30", next("2026-11-18", "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE"))
        assertEquals("2026-11-23", next("2026-11-22", "FREQ=WEEKLY;BYDAY=MO"))
    }

    @Test fun skipsNonexistentCalendarDaysInsteadOfClampingThem() {
        assertEquals("2026-03-31", next("2026-01-31", "FREQ=MONTHLY"))
        assertEquals("2028-02-29", next("2024-02-29", "FREQ=YEARLY"))
        assertEquals("2026-12-10", next("2026-11-16", "FREQ=MONTHLY;BYMONTHDAY=10"))
        assertEquals("2026-11-20", next("2026-11-16", "FREQ=MONTHLY;BYMONTHDAY=10,20"))
        assertEquals("2026-11-18", next("2026-11-16", "FREQ=DAILY;BYDAY=WE"))
    }

    @Test fun rejectsMalformedUnsupportedDuplicateOrImpossibleRules() {
        listOf("", " ", "DAILY", "RRULE:FREQ=DAILY", "FREQ=daily", "FREQ=HOURLY",
            "FREQ=DAILY;", ";FREQ=DAILY", "FREQ=DAILY;;INTERVAL=1", "FREQ=DAILY;COUNT=2",
            "FREQ=DAILY;INTERVAL=0", "FREQ=DAILY;INTERVAL=-1", "FREQ=DAILY;INTERVAL=1.5",
            "FREQ=DAILY;INTERVAL=01", "FREQ=DAILY;INTERVAL=2147483648", "FREQ=DAILY;INTERVAL=",
            "FREQ=DAILY;FREQ=WEEKLY", "FREQ=DAILY=BAD", "INTERVAL=2", "FREQ=WEEKLY;BYDAY=",
            "FREQ=WEEKLY;BYDAY=MO,MO", "FREQ=WEEKLY;BYDAY=XX", "FREQ=WEEKLY;BYDAY=1MO",
            "FREQ=MONTHLY;BYMONTHDAY=0", "FREQ=MONTHLY;BYMONTHDAY=32", "FREQ=MONTHLY;BYMONTHDAY=1,1",
            "FREQ=WEEKLY;BYMONTHDAY=3", "FREQ=MONTHLY;BYMONTHDAY=-1",
            "FREQ=DAILY; INTERVAL=2", "FREQ=DAILY;INTERVAL=+2").forEach { rule ->
            assertThrows("Deveria rejeitar $rule", IllegalArgumentException::class.java) { next("2026-11-16", rule) }
        }
    }

    @Test fun validatesRecurrenceWhenSavingATask() {
        assertThrows(IllegalArgumentException::class.java) {
            TaskRules.validate(TaskEntity(title = "Consulta", recurrenceRule = "FREQ=DAILY"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskRules.validate(TaskEntity(title = "Consulta", dueDate = "2026-11-16", recurrenceRule = "FREQ=DAILY;COUNT=2"))
        }
        TaskRules.validate(TaskEntity(title = "Consulta", dueDate = "2026-11-16", recurrenceRule = "FREQ=DAILY"))
    }

    @Test fun rejectsDatesThatCannotBePersistedInTheCanonicalDateFormat() {
        assertThrows(IllegalArgumentException::class.java) { next("9999-12-31", "FREQ=DAILY") }
        assertThrows(IllegalArgumentException::class.java) {
            TaskRules.validate(TaskEntity(title = "Limite", dueDate = "9999-12-31", recurrenceRule = "FREQ=YEARLY"))
        }
    }

    @Test fun advancesByAnIntervalWithoutChangingTheOriginalDate() {
        assertEquals("2026-11-19", next("2026-11-16", "FREQ=DAILY;INTERVAL=3"))
        assertEquals("2026-11-30", next("2026-11-16", "FREQ=WEEKLY;INTERVAL=2"))
        assertEquals("2027-01-16", next("2026-11-16", "FREQ=MONTHLY;INTERVAL=2"))
        assertEquals("2028-11-16", next("2026-11-16", "FREQ=YEARLY;INTERVAL=2"))
    }
}
