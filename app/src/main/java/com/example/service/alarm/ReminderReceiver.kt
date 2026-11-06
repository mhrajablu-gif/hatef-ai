package com.example.service.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.db.AppDatabase
import com.example.data.engine.PersianDateEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "hatef_reminders_channel"
        const val CHANNEL_NAME = "یادآوری‌های یادت"
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
                showNotification(context, reminderId, title, desc, time, soundUriStr)
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
                            val jDate = PersianDateEngine.timestampToJalali(newTrigger)
                            val timeStr = PersianDateEngine.formatPersianTime(jDate.hour, jDate.minute)
                            val updated = reminder.copy(
                                triggerTimestamp = newTrigger,
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

    private fun showNotification(
        context: Context,
        reminderId: Long,
        title: String,
        description: String,
        time: String,
        soundUriStr: String?
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val effectiveSoundUriStr = if (!soundUriStr.isNullOrBlank()) {
            soundUriStr
        } else {
            AlarmSoundPreferences.getSelectedSoundUri(context)
        }

        val soundUri = if (!effectiveSoundUriStr.isNullOrBlank()) {
            Uri.parse(effectiveSoundUriStr)
        } else {
            AlarmSoundPreferences.getResolvedSoundUri(context)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()

            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                this.description = "اعلان هشدارهای زمان‌بندی شده دستیار یادت"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
                setSound(soundUri, audioAttributes)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Full Screen Alarm Activity Intent
        val fullScreenIntent = Intent(context, com.example.ui.alarm.FullScreenAlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION
            putExtra(AlarmScheduler.EXTRA_REMINDER_ID, reminderId)
            putExtra(AlarmScheduler.EXTRA_REMINDER_TITLE, title)
            putExtra(AlarmScheduler.EXTRA_REMINDER_DESC, description)
            putExtra(AlarmScheduler.EXTRA_REMINDER_TIME, time)
            putExtra(AlarmScheduler.EXTRA_REMINDER_SOUND_URI, effectiveSoundUriStr)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            (reminderId + 400000).toInt(),
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Try direct launch for instant full-screen wake-up
        try {
            context.startActivity(fullScreenIntent)
        } catch (_: Exception) {}

        // Tap to open app
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_NAV_REMINDER_ID", reminderId)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            reminderId.toInt(),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Done
        val doneIntent = Intent(context, ReminderReceiver::class.java).apply {
            action = AlarmScheduler.ACTION_COMPLETE_REMINDER
            putExtra(AlarmScheduler.EXTRA_REMINDER_ID, reminderId)
        }
        val donePendingIntent = PendingIntent.getBroadcast(
            context,
            (reminderId + 200000).toInt(),
            doneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action: Snooze 15m
        val snoozeIntent = Intent(context, ReminderReceiver::class.java).apply {
            action = AlarmScheduler.ACTION_SNOOZE_REMINDER
            putExtra(AlarmScheduler.EXTRA_REMINDER_ID, reminderId)
        }
        val snoozePendingIntent = PendingIntent.getBroadcast(
            context,
            (reminderId + 300000).toInt(),
            snoozeIntent,
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
