package com.example.domain.recurrence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RecurrenceCalculatorTest {
    private val zone = TimeZone.getTimeZone("Asia/Tehran")

    private fun date(year: Int, month: Int, day: Int, hour: Int = 9): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, 0, 0)
        }.timeInMillis

    private fun parts(timestamp: Long): List<Int> =
        Calendar.getInstance(zone).apply { timeInMillis = timestamp }.let {
            listOf(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1,
                it.get(Calendar.DAY_OF_MONTH), it.get(Calendar.HOUR_OF_DAY))
        }

    @Test fun dailyKeepsLocalTime() {
        val next = RecurrenceCalculator.nextOccurrence(
            date(2026, 11, 27, 8),
            RecurrenceRule(RecurrenceRule.Frequency.DAILY), zone
        )!!
        assertEquals(listOf(2026, 11, 28, 8), parts(next))
    }

    @Test fun weeklyWednesdayUsesIranianWeekdayNumbering() {
        // 2026-11-25 is Wednesday; Iranian Wednesday = 4
        val next = RecurrenceCalculator.nextOccurrence(
            date(2026, 11, 25),
            RecurrenceRule(RecurrenceRule.Frequency.WEEKLY, weekdays = setOf(4)), zone
        )!!
        assertEquals(listOf(2026, 12, 2, 9), parts(next))
    }

    @Test fun workdaysSkipFriday() {
        // 2026-11-26 Thursday -> next occurrence is Saturday
        val next = RecurrenceCalculator.nextOccurrence(
            date(2026, 11, 26),
            RecurrenceRule(RecurrenceRule.Frequency.WORKDAYS), zone
        )!!
        assertEquals(listOf(2026, 11, 28, 9), parts(next))
    }

    @Test fun monthlyClampsToLastDayOfShortMonth() {
        val next = RecurrenceCalculator.nextOccurrence(
            date(2026, 1, 31),
            RecurrenceRule(RecurrenceRule.Frequency.MONTHLY, monthDay = 31), zone
        )!!
        assertEquals(listOf(2026, 2, 28, 9), parts(next))
    }

    @Test fun yearlyClampsLeapDayInNonLeapYear() {
        val next = RecurrenceCalculator.nextOccurrence(
            date(2024, 2, 29),
            RecurrenceRule(RecurrenceRule.Frequency.YEARLY, month = 2, monthDay = 29), zone
        )!!
        assertEquals(listOf(2025, 2, 28, 9), parts(next))
    }

    @Test fun endDateStopsRecurrence() {
        val start = date(2026, 11, 27)
        val rule = RecurrenceRule(
            RecurrenceRule.Frequency.DAILY,
            endAtMillis = date(2026, 11, 27, 23)
        )
        assertNull(RecurrenceCalculator.nextOccurrence(start, rule, zone))
    }

    @Test fun customWeekdaysAreRespected() {
        val next = RecurrenceCalculator.nextOccurrence(
            date(2026, 11, 27), // Friday
            RecurrenceRule(RecurrenceRule.Frequency.CUSTOM, weekdays = setOf(1, 4)), zone
        )!!
        assertTrue(parts(next)[2] > 27 || parts(next)[1] > 11)
        assertEquals(1, iranianDay(next))
    }

    private fun iranianDay(timestamp: Long): Int {
        val day = Calendar.getInstance(zone).apply { timeInMillis = timestamp }
            .get(Calendar.DAY_OF_WEEK)
        return if (day == Calendar.SATURDAY) 1 else day + 1
    }
}
