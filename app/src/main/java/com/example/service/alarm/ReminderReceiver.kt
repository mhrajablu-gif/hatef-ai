package com.example.service.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.db.AppDatabase
import com.example.data.engine.PersianDateEngine
import com.example.data.model.ReminderEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone

class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "hatef_reminders_channel"
        const val CHANNEL_NAME = "یادآوری‌های یادت"
        private val TEHRAN = TimeZone.getTimeZone("Asia/Tehran")
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val reminderId = intent.getLongExtra(AlarmScheduler.EXTRA_REMINDER_ID, -1L)

        when (action) {
            AlarmScheduler.ACTION_TRIGGER_REMINDER -> {
                val title = intent.getStringExtra(AlarmScheduler.EXTRA_REMINDER_TITLE) ?: "یادآوری کار"
                val desc = intent.getStringExtra(AlarmScheduler.EXTRA_REMINDER_DESC) ?: ""
                val time = intent.getStringExtra(AlarmScheduler.EXTRA_REMINDER_TIME) ?: ""
                val soundUriStr = intent.getStringExtra(AlarmScheduler.EXTRA_REMINDER_SOUND_URI)
                val isAdvance = intent.getBooleanExtra(AlarmScheduler.EXTRA_IS_ADVANCE, false)
                showNotification(context, reminderId, title, desc, time, soundUriStr)
                // Only the actual due alarm advances a recurring series; advance alerts do not.
                if (!isAdvance && reminderId > 0L) {
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            scheduleNextOccurrence(context, reminderId)
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }

            AlarmScheduler.ACTION_COMPLETE_REMINDER -> {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(reminderId.toInt())
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val db = AppDatabase.getDatabase(context)
                        db.reminderDao().setCompleted(reminderId, true, System.currentTimeMillis())
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            AlarmScheduler.ACTION_SNOOZE_REMINDER -> {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(reminderId.toInt())
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val db = AppDatabase.getDatabase(context)
                        val reminder = db.reminderDao().getReminderById(reminderId)
                        if (reminder != null) {
                            val newTrigger = System.currentTimeMillis() + 15 * 60 * 1000L
                            val jDate = PersianDateEngine.timestampToJalali(newTrigger, TEHRAN)
                            val timeStr = PersianDateEngine.formatPersianTime(jDate.hour, jDate.minute)
                            val updated = reminder.copy(
                                triggerTimestamp = newTrigger,
                                jalaliDateText = jDate.formatPersianNumeric(),
                                timeText = timeStr,
                                isCompleted = false
                            )
                            db.reminderDao().update(updated)
                            AlarmScheduler.scheduleReminder(context, updated)
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    /**
     * Creates and schedules the next occurrence as a separate row. Keeping each occurrence
     * separate preserves completion history and prevents marking the whole series complete.
     */
    private suspend fun scheduleNextOccurrence(context: Context, reminderId: Long) {
        val db = AppDatabase.getDatabase(context)
        val current = db.reminderDao().getReminderById(reminderId) ?: return
        if (current.isCompleted || current.repeatType.equals("NONE", ignoreCase = true)) return

        val nextTimestamp = calculateNextOccurrence(current) ?: return
        if (current.recurrenceEndDate != null && nextTimestamp > current.recurrenceEndDate) return

        val nextJalali = PersianDateEngine.timestampToJalali(nextTimestamp, TEHRAN)
        val next = current.copy(
            id = 0L,
            triggerTimestamp = nextTimestamp,
            jalaliDateText = nextJalali.formatPersianNumeric(),
            timeText = PersianDateEngine.formatPersianTime(nextJalali.hour, nextJalali.minute),
            isCompleted = false,
            completedAt = null,
            createdAt = System.currentTimeMillis()
        )
        val newId = db.reminderDao().insert(next)
        AlarmScheduler.scheduleReminder(context, next.copy(id = newId))
    }

    private fun calculateNextOccurrence(reminder: ReminderEntity): Long? {
        val calendar = Calendar.getInstance(TEHRAN).apply {
            timeInMillis = reminder.triggerTimestamp
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        when (reminder.repeatType.uppercase()) {
            "DAILY" -> calendar.add(Calendar.DAY_OF_YEAR, 1)
            "WEEKLY" -> {
                val days = reminder.recurrenceDaysOfWeek
                    ?.split(",")?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() }
                if (days.isNullOrEmpty()) {
                    calendar.add(Calendar.DAY_OF_YEAR, 7)
                } else {
                    val names = mapOf(
                        Calendar.SATURDAY to "SAT", Calendar.SUNDAY to "SUN",
                        Calendar.MONDAY to "MON", Calendar.TUESDAY to "TUE",
                        Calendar.WEDNESDAY to "WED", Calendar.THURSDAY to "THU",
                        Calendar.FRIDAY to "FRI"
                    )
                    var found = false
                    repeat(7) {
                        if (!found) {
                            calendar.add(Calendar.DAY_OF_YEAR, 1)
                            if (names[calendar.get(Calendar.DAY_OF_WEEK)] in days) found = true
                        }
                    }
                    if (!found) return null
                }
            }
            "MONTHLY" -> calendar.add(Calendar.MONTH, 1)
            "YEARLY" -> calendar.add(Calendar.YEAR, 1)
            "WORKDAYS" -> {
                do {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                } while (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY)
            }
            else -> return null
        }
        return calendar.timeInMillis
    }

    private fun showNotification(
        context: Context,
        reminderId: Long,
        title: String,
        description: String,
        time: String,
        soundUriStr: String?
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val effectiveSoundUriStr = if (!soundUriStr.isNullOrBlank()) soundUriStr else AlarmSoundPreferences.getSelectedSoundUri(context)
        val soundUri = if (!effectiveSoundUriStr.isNullOrBlank()) Uri.parse(effectiveSoundUriStr) else AlarmSoundPreferences.getResolvedSoundUri(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "اعلان هشدارهای زمان‌بندی شده دستیار یادت"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
                setSound(soundUri, audioAttributes)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }

        val fullScreenIntent = Intent(context, com.example.ui.alarm.FullScreenAlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION
            putExtra(AlarmScheduler.EXTRA_REMINDER_ID, reminderId)
            putExtra(AlarmScheduler.EXTRA_REMINDER_TITLE, title)
            putExtra(AlarmScheduler.EXTRA_REMINDER_DESC, description)
            putExtra(AlarmScheduler.EXTRA_REMINDER_TIME, time)
            putExtra(AlarmScheduler.EXTRA_REMINDER_SOUND_URI, effectiveSoundUriStr)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, (reminderId + 400000).toInt(), fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            context.startActivity(fullScreenIntent)
        } catch (_: Exception) { }

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_NAV_REMINDER_ID", reminderId)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context, reminderId.toInt(), contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val doneIntent = Intent(context, ReminderReceiver::class.java).apply {
            action = AlarmScheduler.ACTION_COMPLETE_REMINDER
            putExtra(AlarmScheduler.EXTRA_REMINDER_ID, reminderId)
        }
        val donePendingIntent = PendingIntent.getBroadcast(
            context, (reminderId + 200000).toInt(), doneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozeIntent = Intent(context, ReminderReceiver::class.java).apply {
            action = AlarmScheduler.ACTION_SNOOZE_REMINDER
            putExtra(AlarmScheduler.EXTRA_REMINDER_ID, reminderId)
        }
        val snoozePendingIntent = PendingIntent.getBroadcast(
            context, (reminderId + 300000).toInt(), snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fullBody = if (time.isNotBlank()) "⏰ ساعت $time | $description" else description
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(fullBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(fullBody))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setSound(soundUri)
            .setVibrate(longArrayOf(0, 400, 200, 400))
            .setContentIntent(contentPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(0, "✅ انجام شد", donePendingIntent)
            .addAction(0, "⏱ ۱۵ دقیقه بعد", snoozePendingIntent)
        notificationManager.notify(reminderId.toInt(), builder.build())
    }
}
