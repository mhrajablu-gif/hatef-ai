package com.example.service.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.model.ReminderEntity

object AlarmScheduler {
    private const val TAG = "AlarmScheduler"

    const val ACTION_TRIGGER_REMINDER = "com.example.ACTION_TRIGGER_REMINDER"
    const val ACTION_COMPLETE_REMINDER = "com.example.ACTION_COMPLETE_REMINDER"
    const val ACTION_SNOOZE_REMINDER = "com.example.ACTION_SNOOZE_REMINDER"

    const val EXTRA_REMINDER_ID = "extra_reminder_id"
    const val EXTRA_REMINDER_TITLE = "extra_reminder_title"
    const val EXTRA_REMINDER_DESC = "extra_reminder_desc"
    const val EXTRA_REMINDER_TIME = "extra_reminder_time"
    const val EXTRA_REMINDER_SOUND_URI = "extra_reminder_sound_uri"
    const val EXTRA_IS_ADVANCE = "extra_is_advance"

    fun scheduleReminder(context: Context, reminder: ReminderEntity) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val now = System.currentTimeMillis()

        // Schedule primary alarm
        if (reminder.triggerTimestamp > now) {
            val intent = Intent(context, ReminderReceiver::class.java).apply {
                action = ACTION_TRIGGER_REMINDER
                putExtra(EXTRA_REMINDER_ID, reminder.id)
                putExtra(EXTRA_REMINDER_TITLE, reminder.title)
                putExtra(EXTRA_REMINDER_DESC, reminder.description)
                putExtra(EXTRA_REMINDER_TIME, reminder.timeText)
                putExtra(EXTRA_REMINDER_SOUND_URI, reminder.customSoundUri)
                putExtra(EXTRA_IS_ADVANCE, false)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                reminder.id.toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            setExactAlarm(alarmManager, reminder.triggerTimestamp, pendingIntent)
            Log.d(TAG, "Scheduled reminder ${reminder.id} at ${reminder.triggerTimestamp}")
        }

        // Schedule advance warning alert if configured
        if (reminder.advanceAlertMinutes > 0) {
            val advanceMillis = reminder.triggerTimestamp - (reminder.advanceAlertMinutes * 60 * 1000L)
            if (advanceMillis > now) {
                val advanceIntent = Intent(context, ReminderReceiver::class.java).apply {
                    action = ACTION_TRIGGER_REMINDER
                    putExtra(EXTRA_REMINDER_ID, reminder.id)
                    putExtra(EXTRA_REMINDER_TITLE, "یادآوری زودهنگام: ${reminder.title}")
                    putExtra(EXTRA_REMINDER_DESC, "${reminder.advanceAlertMinutes} دقیقه تا موعد: ${reminder.description}")
                    putExtra(EXTRA_REMINDER_TIME, reminder.timeText)
                    putExtra(EXTRA_REMINDER_SOUND_URI, reminder.customSoundUri)
                    putExtra(EXTRA_IS_ADVANCE, true)
                }

                val advancePendingIntent = PendingIntent.getBroadcast(
                    context,
                    (reminder.id + 100000).toInt(),
                    advanceIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                setExactAlarm(alarmManager, advanceMillis, advancePendingIntent)
            }
        }
    }

    fun cancelReminder(context: Context, reminderId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_TRIGGER_REMINDER
        }

        // Cancel main alarm
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminderId.toInt(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }

        // Cancel advance alarm
        val advancePendingIntent = PendingIntent.getBroadcast(
            context,
            (reminderId + 100000).toInt(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (advancePendingIntent != null) {
            alarmManager.cancel(advancePendingIntent)
            advancePendingIntent.cancel()
        }
    }

    private fun setExactAlarm(alarmManager: AlarmManager, triggerAtMillis: Long, pendingIntent: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot schedule exact alarm without permission", e)
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }
}
