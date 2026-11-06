package com.example.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.ReminderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {

    @Query("SELECT * FROM reminders ORDER BY triggerTimestamp ASC")
    fun getAllReminders(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getReminderById(id: Long): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE isCompleted = 0 AND triggerTimestamp >= :now ORDER BY triggerTimestamp ASC")
    fun getUpcomingReminders(now: Long): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE isCompleted = 0 AND triggerTimestamp < :now ORDER BY triggerTimestamp ASC")
    fun getOverdueReminders(now: Long): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE triggerTimestamp BETWEEN :start AND :end ORDER BY triggerTimestamp ASC")
    fun getRemindersBetween(start: Long, end: Long): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE isCompleted = 0 AND triggerTimestamp > :now")
    suspend fun getActiveRemindersList(now: Long): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE triggerTimestamp BETWEEN :start AND :end ORDER BY triggerTimestamp ASC")
    suspend fun getRemindersBetweenList(start: Long, end: Long): List<ReminderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reminder: ReminderEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(reminders: List<ReminderEntity>): List<Long>

    @Update
    suspend fun update(reminder: ReminderEntity)

    @Delete
    suspend fun delete(reminder: ReminderEntity)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM reminders")
    suspend fun deleteAll()

    @Query("DELETE FROM reminders WHERE triggerTimestamp BETWEEN :start AND :end")
    suspend fun deleteBetween(start: Long, end: Long)

    @Query("UPDATE reminders SET isCompleted = :completed, completedAt = :completedAt WHERE id = :id")
    suspend fun setCompleted(id: Long, completed: Boolean, completedAt: Long?)

    @Query("UPDATE reminders SET triggerTimestamp = :newTimestamp, jalaliDateText = :jalaliDate, timeText = :timeText, isCompleted = 0 WHERE id = :id")
    suspend fun reschedule(id: Long, newTimestamp: Long, jalaliDate: String, timeText: String)
}
