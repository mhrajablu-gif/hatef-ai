package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val description: String = "",
    val triggerTimestamp: Long, // Epoch millis in UTC
    val jalaliDateText: String, // e.g. "۱۴۰۵/۰۵/۱۴"
    val timeText: String,       // e.g. "۱۹:۰۰"
    val repeatType: String = "NONE", // NONE, DAILY, WEEKLY, MONTHLY, YEARLY, WORKDAYS, CUSTOM
    val recurrenceEndDate: Long? = null, // Epoch millis until which recurring reminders continue
    val recurrenceDaysOfWeek: String? = null, // e.g. "SAT,SUN,MON,TUE,WED" or "EVEN_DAYS"
    val advanceAlertMinutes: Int = 0, // e.g. 10, 15 or 30 mins before
    val customSoundUri: String? = null,
    val imageUri: String? = null,
    val category: String = "عمومی",
    val priority: Int = 0, // 0: عادی, 1: مهم, 2: فوری
    val isCompleted: Boolean = false,
    val completedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val isOverdue: Boolean
        get() = !isCompleted && triggerTimestamp < System.currentTimeMillis()
}
