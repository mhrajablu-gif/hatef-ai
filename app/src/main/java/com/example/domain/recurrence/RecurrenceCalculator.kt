package com.example.domain.recurrence

import java.util.Calendar
import java.util.TimeZone

/**
 * Pure calculation helper (no Android APIs). All calendar operations use the
 * supplied timezone, preserving the local hour/minute across DST transitions.
 */
object RecurrenceCalculator {

    /**
     * Returns the next occurrence strictly after [previousOccurrenceMillis].
     * Returns null for a non-recurring rule or when the recurrence has ended.
     */
    fun nextOccurrence(
        previousOccurrenceMillis: Long,
        rule: RecurrenceRule,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long? {
        if (rule.frequency == RecurrenceRule.Frequency.NONE) return null

        val previous = calendar(previousOccurrenceMillis, timeZone)
        val candidate = when (rule.frequency) {
            RecurrenceRule.Frequency.NONE -> return null
            RecurrenceRule.Frequency.DAILY ->
                addDays(previous, rule.interval)
            RecurrenceRule.Frequency.WEEKLY,
            RecurrenceRule.Frequency.CUSTOM,
            RecurrenceRule.Frequency.WORKDAYS ->
                nextWeekday(previous, rule)
            RecurrenceRule.Frequency.MONTHLY ->
                nextMonthly(previous, rule)
            RecurrenceRule.Frequency.YEARLY ->
                nextYearly(previous, rule)
        } ?: return null

        val result = candidate.timeInMillis
        if (result <= previousOccurrenceMillis) return null
        if (rule.endAtMillis != null && result > rule.endAtMillis) return null
        return result
    }

    private fun nextWeekday(previous: Calendar, rule: RecurrenceRule): Calendar? {
        val allowed = when (rule.frequency) {
            RecurrenceRule.Frequency.WORKDAYS -> (1..6).toSet() // Saturday through Thursday
            else -> rule.weekdays
        }
        val originWeek = iranianWeekIndex(previous)
        val candidate = previous.clone() as Calendar
        // Search at most two full recurrence cycles plus one day.
        val limit = 7 * (rule.interval + 2)
        repeat(limit) {
            candidate.add(Calendar.DAY_OF_YEAR, 1)
            val day = iranianWeekday(candidate)
            val weekDistance = iranianWeekIndex(candidate) - originWeek
            if (day in allowed && weekDistance >= 0 && weekDistance % rule.interval == 0) {
                return candidate
            }
        }
        return null
    }

    private fun nextMonthly(previous: Calendar, rule: RecurrenceRule): Calendar {
        val result = previous.clone() as Calendar
        result.add(Calendar.MONTH, rule.interval)
        val wantedDay = rule.monthDay ?: previous.get(Calendar.DAY_OF_MONTH)
        result.set(Calendar.DAY_OF_MONTH, minOf(wantedDay, result.getActualMaximum(Calendar.DAY_OF_MONTH)))
        return result
    }

    private fun nextYearly(previous: Calendar, rule: RecurrenceRule): Calendar {
        val result = previous.clone() as Calendar
        result.add(Calendar.YEAR, rule.interval)
        val wantedMonth = (rule.month ?: (previous.get(Calendar.MONTH) + 1)) - 1
        val wantedDay = rule.monthDay ?: previous.get(Calendar.DAY_OF_MONTH)
        result.set(Calendar.MONTH, wantedMonth)
        result.set(Calendar.DAY_OF_MONTH, 1)
        result.set(Calendar.DAY_OF_MONTH, minOf(wantedDay, result.getActualMaximum(Calendar.DAY_OF_MONTH)))
        return result
    }

    private fun addDays(previous: Calendar, days: Int): Calendar =
        (previous.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, days) }

    private fun calendar(timestamp: Long, zone: TimeZone): Calendar =
        Calendar.getInstance(zone).apply {
            timeInMillis = timestamp
            isLenient = false
        }

    private fun iranianWeekday(calendar: Calendar): Int {
        val day = calendar.get(Calendar.DAY_OF_WEEK) // Sunday=1 ... Saturday=7
        return if (day == Calendar.SATURDAY) 1 else day + 1
    }

    /** Stable Gregorian day number; avoids DST-length weeks and epoch bucket drift. */
    private fun iranianWeekIndex(calendar: Calendar): Long {
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val a = (14 - month) / 12
        val y = year + 4800 - a
        val m = month + 12 * a - 3
        val julianDay = day + (153 * m + 2) / 5 + 365L * y +
            y / 4 - y / 100 + y / 400 - 32045
        return Math.floorDiv(julianDay - (iranianWeekday(calendar) - 1), 7L)
    }
}
