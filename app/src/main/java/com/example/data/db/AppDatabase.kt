package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.model.ReminderEntity

@Database(entities = [ReminderEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun reminderDao(): ReminderDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "hatef_reminder_database"
                )
                    // Never silently erase user reminders when a schema version changes.
                    // Add an explicit Room Migration before incrementing the database version.
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
