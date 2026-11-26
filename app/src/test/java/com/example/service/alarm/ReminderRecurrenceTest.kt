package com.example.service.alarm

import com.example.data.model.ReminderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ReminderRecurrenceTest {
    private val zone = TimeZone.getTimeZone("Asia/Tehran")

    private fun timestamp(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun reminder(timestamp: Long, repeat: String, days: String?) = ReminderEntity(
        title = "ورزش",
        triggerTimestamp = timestamp,
        jalaliDateText = "۱۴۰۵/۰۸/۰۷",
        timeText = "۰۸:۰۰",
        repeatType = repeat,
        recurrenceDaysOfWeek = days
    )

    @Test
    fun weeklyWednesdaySchedulesTheFollowingWednesday() {
        val first = timestamp(2026, Calendar.OCTOBER + 1, 28, 8, 0)
        val next = ReminderRecurrence.next(reminder(first, "WEEKLY", "WED"), zone)
        assertNotNull(next)
        val expected = timestamp(2026, Calendar.NOVEMBER + 1, 4, 8, 0)
        assertEquals(expected, next!!.triggerTimestamp)
    }

    @Test
    fun customEveryDayExceptFridaySkipsFridayAndChoosesSaturday() {
        val thursday = timestamp(2026, Calendar.OCTOBER + 1, 29, 7, 0)
        val next = ReminderRecurrence.next(
            reminder(thursday, "CUSTOM", "SAT,SUN,MON,TUE,WED,THU"),
            zone
        )
        assertNotNull(next)
        val expectedSaturday = timestamp(2026, Calendar.OCTOBER + 1, 31, 7, 0)
        assertEquals(expectedSaturday, next!!.triggerTimestamp)
    }
}
