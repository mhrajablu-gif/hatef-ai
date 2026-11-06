package com.example.data.repository

import com.example.data.db.ReminderDao
import com.example.data.model.ReminderEntity
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

data class TaskStats(
    val totalCount: Int,
    val completedCount: Int,
    val overdueCount: Int,
    val pendingCount: Int,
    val completionPercentage: Int
)

class ReminderRepository(private val reminderDao: ReminderDao) {

    val allReminders: Flow<List<ReminderEntity>> = reminderDao.getAllReminders()

    fun getUpcomingReminders(now: Long = System.currentTimeMillis()): Flow<List<ReminderEntity>> {
        return reminderDao.getUpcomingReminders(now)
    }

    fun getOverdueReminders(now: Long = System.currentTimeMillis()): Flow<List<ReminderEntity>> {
        return reminderDao.getOverdueReminders(now)
    }

    fun getRemindersForDay(timestampMillis: Long): Flow<List<ReminderEntity>> {
        val cal = Calendar.getInstance().apply {
            timeInMillis = timestampMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val endOfDay = cal.timeInMillis - 1
        return reminderDao.getRemindersBetween(startOfDay, endOfDay)
    }

    suspend fun getActiveReminders(now: Long = System.currentTimeMillis()): List<ReminderEntity> {
        return reminderDao.getActiveRemindersList(now)
    }

    suspend fun getReminderById(id: Long): ReminderEntity? {
        return reminderDao.getReminderById(id)
    }

    suspend fun insert(reminder: ReminderEntity): Long {
        return reminderDao.insert(reminder)
    }

    suspend fun insertAll(reminders: List<ReminderEntity>): List<Long> {
        return reminderDao.insertAll(reminders)
    }

    suspend fun update(reminder: ReminderEntity) {
        reminderDao.update(reminder)
    }

    suspend fun delete(reminder: ReminderEntity) {
        reminderDao.delete(reminder)
    }

    suspend fun deleteById(id: Long) {
        reminderDao.deleteById(id)
    }

    suspend fun deleteAll() {
        reminderDao.deleteAll()
    }

    suspend fun deleteBetween(start: Long, end: Long) {
        reminderDao.deleteBetween(start, end)
    }

    suspend fun setCompleted(id: Long, completed: Boolean) {
        val completedAt = if (completed) System.currentTimeMillis() else null
        reminderDao.setCompleted(id, completed, completedAt)
    }

    suspend fun reschedule(id: Long, newTimestamp: Long, jalaliDate: String, timeText: String) {
        reminderDao.reschedule(id, newTimestamp, jalaliDate, timeText)
    }

    /**
     * Checks if there is an existing reminder within [thresholdMinutes] of the given [targetTimestamp]
     */
    suspend fun findConflictingReminder(
        targetTimestamp: Long,
        excludeId: Long? = null,
        thresholdMinutes: Int = 15
    ): ReminderEntity? {
        val delta = thresholdMinutes * 60 * 1000L
        val active = reminderDao.getActiveRemindersList(System.currentTimeMillis())
        return active.firstOrNull {
            it.id != excludeId && Math.abs(it.triggerTimestamp - targetTimestamp) < delta
        }
    }
}
