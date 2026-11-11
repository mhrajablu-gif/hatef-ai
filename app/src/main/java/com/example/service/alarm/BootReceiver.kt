package com.example.service.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED ||
            action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON") {

            Log.d("BootReceiver", "Device reboot detected. Rescheduling all future active reminders.")
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                        alarmManager?.canScheduleExactAlarms() != true
                    ) {
                        Log.w("BootReceiver", "Exact alarm access is not granted; reminders remain saved but are not scheduled.")
                        return@launch
                    }
                    val db = AppDatabase.getDatabase(context)
                    val activeReminders = db.reminderDao().getActiveRemindersList(System.currentTimeMillis())
                    for (reminder in activeReminders) {
                        AlarmScheduler.scheduleReminder(context, reminder)
                    }
                    Log.d("BootReceiver", "Successfully restored ${activeReminders.size} reminders.")
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Failed to restore reminders after reboot", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
