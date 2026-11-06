package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.db.AppDatabase
import com.example.data.engine.PersianDateEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

class TodayTasksWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateWidgetViews(context, appWidgetManager, appWidgetIds)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_UPDATE_ALL_WIDGETS || intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, TodayTasksWidgetReceiver::class.java)
            )
            if (appWidgetIds.isNotEmpty()) {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        updateWidgetViews(context, appWidgetManager, appWidgetIds)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_UPDATE_ALL_WIDGETS = "com.example.ACTION_UPDATE_ALL_WIDGETS"

        fun updateAllWidgets(context: Context) {
            try {
                val intent = Intent(context, TodayTasksWidgetReceiver::class.java).apply {
                    action = ACTION_UPDATE_ALL_WIDGETS
                }
                context.sendBroadcast(intent)
            } catch (e: Exception) {
                // Ignore if context is null
            }
        }

        suspend fun updateWidgetViews(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray
        ) {
            val db = AppDatabase.getDatabase(context)
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val start = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val end = cal.timeInMillis

            val todayList = db.reminderDao().getRemindersBetweenList(start, end)
            val uncompletedList = todayList.filter { !it.isCompleted }
            val countText = PersianDateEngine.toPersianDigits("${uncompletedList.size} کار")

            val itemsSummary = if (todayList.isEmpty()) {
                "امروز هیچ کاری برنامه‌ریزی نشده است."
            } else {
                todayList.take(3).joinToString("\n") { task ->
                    val statusMark = if (task.isCompleted) "✓ " else "• "
                    "$statusMark${task.title} (ساعت ${task.timeText})"
                }
            }

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                1,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            for (appWidgetId in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_today_tasks).apply {
                    setTextViewText(R.id.widget_today_count, countText)
                    setTextViewText(R.id.widget_today_items, itemsSummary)
                    setOnClickPendingIntent(R.id.widget_today_root, pendingIntent)
                }
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }
}
