package com.example.service.alarm

import com.example.data.engine.PersianDateEngine
import com.example.data.model.ReminderEntity
import java.util.Calendar
import java.util.TimeZone

/**
 * Computes the next local occurrence for a recurring reminder.
 * Weekday tokens use Calendar-style English abbreviations: SAT,SUN,MON,TUE,WED,THU,FRI.
 */
object ReminderRecurrence {
    private val tehran = TimeZone.getTimeZone("Asia/Tehran")

    fun next(reminder: ReminderEntity, timeZone: TimeZone = tehran): ReminderEntity? {
        if (reminder.repeatType == "NONE") return null

        val current = Calendar.getInstance(timeZone).apply {
            timeInMillis = reminder.triggerTimestamp
        }
        val next = Calendar.getInstance(timeZone).apply {
            timeInMillis = reminder.triggerTimestamp
        }

        when (reminder.repeatType.uppercase()) {
            "DAILY" -> next.add(Calendar.DAY_OF_YEAR, 1)
            "WEEKLY", "CUSTOM", "WORKDAYS" -> {
                val allowed = when {
                    reminder.repeatType.equals("WORKDAYS", true) ->
                        setOf("SAT", "SUN", "MON", "TUE", "WED", "THU")
                    !reminder.recurrenceDaysOfWeek.isNullOrBlank() ->
                        reminder.recurrenceDaysOfWeek.split(",").map { it.trim().uppercase() }.filter { it.isNotBlank() }.toSet()
                    else -> setOf(dayToken(current.get(Calendar.DAY_OF_WEEK)))
                }
                var found = false
                repeat(8) {
                    if (!found) {
                        next.add(Calendar.DAY_OF_YEAR, 1)
                        if (dayToken(next.get(Calendar.DAY_OF_WEEK)) in allowed) found = true
                    }
                }
                if (!found) return null
            }
            "MONTHLY" -> {
                val desiredDay = current.get(Calendar.DAY_OF_MONTH)
                next.set(Calendar.DAY_OF_MONTH, 1)
                next.add(Calendar.MONTH, 1)
                val maxDay = next.getActualMaximum(Calendar.DAY_OF_MONTH)
                next.set(Calendar.DAY_OF_MONTH, minOf(desiredDay, maxDay))
            }
            "YEARLY" -> {
                val desiredMonth = current.get(Calendar.MONTH)
                val desiredDay = current.get(Calendar.DAY_OF_MONTH)
                next.add(Calendar.YEAR, 1)
                next.set(Calendar.MONTH, desiredMonth)
                next.set(Calendar.DAY_OF_MONTH, minOf(desiredDay, next.getActualMaximum(Calendar.DAY_OF_MONTH)))
            }
            else -> return null
        }

        val timestamp = next.timeInMillis
        if (reminder.recurrenceEndDate != null && timestamp > reminder.recurrenceEndDate) return null
        val jalali = PersianDateEngine.timestampToJalali(timestamp, timeZone)
        return reminder.copy(
            triggerTimestamp = timestamp,
            jalaliDateText = jalali.formatPersianNumeric(),
            timeText = PersianDateEngine.formatPersianTime(jalali.hour, jalali.minute),
            isCompleted = false,
            completedAt = null
        )
    }

    private fun dayToken(day: Int): String = when (day) {
        Calendar.SATURDAY -> "SAT"
        Calendar.SUNDAY -> "SUN"
        Calendar.MONDAY -> "MON"
        Calendar.TUESDAY -> "TUE"
        Calendar.WEDNESDAY -> "WED"
        Calendar.THURSDAY -> "THU"
        Calendar.FRIDAY -> "FRI"
        else -> ""
    }
}
