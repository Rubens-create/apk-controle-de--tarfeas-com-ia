package com.taskai.app.data

import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Strict date-only RFC 5545 subset. Monday starts the week; invalid calendar days are skipped. */
object Recurrence {
    private data class Rule(val frequency: String, val interval: Long, val days: Set<DayOfWeek>?, val monthDays: Set<Int>?)
    private val weekdays = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

    private fun parse(rule: String): Rule {
        require(rule.isNotEmpty() && rule.length <= 500) { "Regra de recorrência inválida." }
        val parts = linkedMapOf<String, String>()
        rule.split(';').forEach { part ->
            val pair = part.split('=')
            require(pair.size == 2 && pair[0] in setOf("FREQ", "INTERVAL", "BYDAY", "BYMONTHDAY") && pair[1].isNotEmpty()) {
                "A recorrência contém uma parte inválida ou não suportada."
            }
            require(parts.put(pair[0], pair[1]) == null) { "A recorrência contém uma parte repetida." }
        }
        val frequency = parts["FREQ"]
        require(frequency in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")) { "Frequência de recorrência inválida." }
        val interval = parts["INTERVAL"]?.let {
            require(Regex("[1-9][0-9]*").matches(it)) { "O intervalo deve ser um inteiro positivo." }
            it.toIntOrNull()?.also { n -> require(n > 0) { "Intervalo de recorrência inválido." } }
                ?: throw IllegalArgumentException("Intervalo de recorrência inválido.")
        } ?: 1
        val days = parts["BYDAY"]?.split(',')?.let { tokens ->
            require(tokens.distinct().size == tokens.size && tokens.all { it in weekdays }) { "Dias da semana inválidos na recorrência." }
            tokens.map { DayOfWeek.of(weekdays.indexOf(it) + 1) }.toSet()
        }
        val monthDays = parts["BYMONTHDAY"]?.split(',')?.let { tokens ->
            require(frequency != "WEEKLY") { "BYMONTHDAY não é permitido na recorrência semanal." }
            require(tokens.distinct().size == tokens.size && tokens.all { Regex("[1-9][0-9]?").matches(it) && it.toInt() in 1..31 }) {
                "Dias do mês inválidos na recorrência."
            }
            tokens.map(String::toInt).toSet()
        }
        return Rule(frequency!!, interval.toLong(), days, monthDays)
    }

    fun nextDate(date: LocalDate, rule: String): LocalDate {
        val parsed = parse(rule)
        try {
            var period = when (parsed.frequency) {
                "WEEKLY" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                "MONTHLY" -> date.withDayOfMonth(1)
                "YEARLY" -> date.withDayOfYear(1)
                else -> date
            }
            // Gregorian patterns repeat in 400 years; this also bounds impossible intersections.
            repeat(146097) {
                val length = when (parsed.frequency) {
                    "WEEKLY" -> 7
                    "MONTHLY" -> period.lengthOfMonth()
                    "YEARLY" -> period.lengthOfYear()
                    else -> 1
                }
                for (offset in 0 until length) {
                    val candidate = period.plusDays(offset.toLong())
                    if (candidate <= date) continue
                    if (parsed.days != null && candidate.dayOfWeek !in parsed.days) continue
                    if (parsed.monthDays != null && candidate.dayOfMonth !in parsed.monthDays) continue
                    when (parsed.frequency) {
                        "WEEKLY" -> if (parsed.days == null && candidate.dayOfWeek != date.dayOfWeek) continue
                        "MONTHLY" -> if (parsed.days == null && parsed.monthDays == null && candidate.dayOfMonth != date.dayOfMonth) continue
                        "YEARLY" -> if (parsed.days == null && parsed.monthDays == null && candidate.monthDay() != date.monthDay()) continue
                    }
                    require(candidate.year in 0..9999) { "A recorrência ultrapassa o limite do formato AAAA-MM-DD." }
                    return candidate
                }
                period = when (parsed.frequency) {
                    "DAILY" -> period.plusDays(parsed.interval)
                    "WEEKLY" -> period.plusWeeks(parsed.interval)
                    "MONTHLY" -> period.plusMonths(parsed.interval)
                    else -> period.plusYears(parsed.interval)
                }
            }
        } catch (_: DateTimeException) {
            throw IllegalArgumentException("A recorrência ultrapassa o intervalo de datas permitido.")
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("O intervalo de recorrência é muito grande.")
        }
        throw IllegalArgumentException("A recorrência não produz uma próxima data válida.")
    }

    private fun LocalDate.monthDay() = monthValue to dayOfMonth
}
