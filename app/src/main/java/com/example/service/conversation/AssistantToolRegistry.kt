package com.example.service.conversation

import android.content.Context
import com.example.data.engine.PersianDateEngine
import com.example.data.model.ReminderEntity
import com.example.data.repository.ReminderRepository
import com.example.service.ai.ParsedTask
import com.example.service.alarm.AlarmScheduler
import java.util.Calendar
import java.util.TimeZone

data class ToolResult(
    val success: Boolean,
    val message: String,
    val data: Any? = null,
    val errorCode: String? = null
)

/**
 * Enterprise Application Tools Registry separating AI dialogue understanding from real system execution.
 * Enforces real Room database operations, exact AlarmManager schedules, calendar validations,
 * disambiguation queries, and structured verifiable results.
 */
class AssistantToolRegistry(
    private val context: Context,
    private val repository: ReminderRepository,
    private val userPreferencesManager: com.example.data.preferences.UserPreferencesManager = com.example.data.preferences.UserPreferencesManager(context),
    private val themePreferences: com.example.ui.theme.ThemePreferences = com.example.ui.theme.ThemePreferences(context),
    private val defaultTimeZone: TimeZone = TimeZone.getTimeZone("Asia/Tehran")
) {

    /**
     * Tool 1: Create a new reminder / appointment in Room DB and schedule exact alarm.
     */
    suspend fun createReminder(task: ParsedTask): ToolResult {
        if (task.title.isBlank()) {
            return ToolResult(
                success = false,
                message = "عنوان یادآور نمی‌تواند خالی باشد.",
                errorCode = "MISSING_TITLE"
            )
        }

        if (task.triggerTimestamp <= 0) {
            return ToolResult(
                success = false,
                message = "زمان مشخص‌شده برای یادآور نامعتبر است.",
                errorCode = "INVALID_TIMESTAMP"
            )
        }

        if (isPastTime(task.triggerTimestamp)) {
            val j = PersianDateEngine.timestampToJalali(task.triggerTimestamp, defaultTimeZone)
            return ToolResult(
                success = false,
                message = "زمان مشخص‌شده (${j.formatPersianNumeric()} ساعت ${PersianDateEngine.formatPersianTime(j.hour, j.minute)}) در گذشته قرار دارد و امکان ثبت آن وجود ندارد.",
                errorCode = "PAST_TIMESTAMP"
            )
        }

        val j = PersianDateEngine.timestampToJalali(task.triggerTimestamp, defaultTimeZone)
        val finalDateText = if (task.jalaliDateText.isNotBlank()) task.jalaliDateText else j.formatPersianNumeric()
        val finalTimeText = if (task.timeText.isNotBlank()) task.timeText else PersianDateEngine.formatPersianTime(j.hour, j.minute)

        val advanceAlert = if (task.advanceAlertMinutes > 0) task.advanceAlertMinutes else userPreferencesManager.defaultAdvanceAlertMinutes.value

        val entity = ReminderEntity(
            title = task.title.trim(),
            description = task.description.trim(),
            triggerTimestamp = task.triggerTimestamp,
            jalaliDateText = finalDateText,
            timeText = finalTimeText,
            category = if (task.category.isNotBlank()) task.category else "عمومی",
            priority = task.priority,
            repeatType = task.repeatType,
            recurrenceEndDate = task.recurrenceEndDate,
            recurrenceDaysOfWeek = task.recurrenceDaysOfWeek,
            advanceAlertMinutes = advanceAlert
        )

        return try {
            val id = repository.insert(entity)
            val savedEntity = entity.copy(id = id)
            val scheduleResult = AlarmScheduler.scheduleReminder(context, savedEntity)

            // Check for close time conflicts
            val conflict = repository.findConflictingReminder(task.triggerTimestamp, excludeId = id)
            val conflictNote = if (conflict != null) {
                " (توجه: با «${conflict.title}» در ساعت ${conflict.timeText} هم‌پوشانی دارد)"
            } else ""

            val scheduleMessage = when (scheduleResult) {
                com.example.service.alarm.AlarmScheduleResult.SCHEDULED -> "و آلارم آن نیز زمان‌بندی شد"
                com.example.service.alarm.AlarmScheduleResult.PARTIALLY_SCHEDULED -> "اما فقط بخشی از هشدارهای آن زمان‌بندی شد"
                com.example.service.alarm.AlarmScheduleResult.ALREADY_DUE -> "اما زمان آن گذشته یا یادآور تکمیل‌شده است و آلارم فعالی ندارد"
                com.example.service.alarm.AlarmScheduleResult.EXACT_ALARM_PERMISSION_REQUIRED -> "اما مجوز هشدار دقیق فعال نیست و آلارم زمان‌بندی نشد"
                com.example.service.alarm.AlarmScheduleResult.ALARM_SERVICE_UNAVAILABLE,
                com.example.service.alarm.AlarmScheduleResult.FAILED -> "اما زمان‌بندی آلارم ناموفق بود"
            }
            ToolResult(
                success = true,
                message = "یادآور «${savedEntity.title}» برای $finalDateText ساعت $finalTimeText ثبت شد؛ $scheduleMessage$conflictNote.",
                data = savedEntity
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                message = "خطا در ثبت یادآور در پایگاه داده: ${e.localizedMessage}",
                errorCode = "DB_INSERT_ERROR"
            )
        }
    }

    /**
     * Tool 2: Retrieve reminders in a specific time range.
     */
    suspend fun getRemindersInRange(startMillis: Long, endMillis: Long): ToolResult {
        return try {
            val all = repository.getActiveReminders(0L)
            val inRange = all.filter { it.triggerTimestamp in startMillis..endMillis }
            ToolResult(
                success = true,
                message = "${PersianDateEngine.toPersianDigits(inRange.size)} یادآور در این بازه زمانی یافت شد.",
                data = inRange
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                message = "خطا در دریافت لیست یادآورها: ${e.localizedMessage}",
                errorCode = "DB_QUERY_ERROR"
            )
        }
    }

    /**
     * Tool 3: Search reminders by title keyword, Jalali date, or category.
     */
    suspend fun searchReminders(
        keyword: String? = null,
        jalaliDate: String? = null,
        category: String? = null,
        onlyActive: Boolean = true
    ): ToolResult {
        return try {
            val all = repository.getActiveReminders(if (onlyActive) System.currentTimeMillis() else 0L)
            val filtered = all.filter { item ->
                val matchesKeyword = keyword.isNullOrBlank() ||
                        item.title.contains(keyword, ignoreCase = true) ||
                        item.description.contains(keyword, ignoreCase = true)
                val matchesDate = jalaliDate.isNullOrBlank() || item.jalaliDateText.contains(jalaliDate)
                val matchesCat = category.isNullOrBlank() || item.category.equals(category, ignoreCase = true)
                matchesKeyword && matchesDate && matchesCat
            }
            ToolResult(
                success = true,
                message = "${PersianDateEngine.toPersianDigits(filtered.size)} یادآور مطابق با جستجو پیدا شد.",
                data = filtered
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                message = "خطا در جستجوی یادآورها: ${e.localizedMessage}",
                errorCode = "SEARCH_ERROR"
            )
        }
    }

    /**
     * Tool 4: Retrieve full details of a specific reminder by ID.
     */
    suspend fun getReminderDetails(id: Long): ToolResult {
        val rem = repository.getReminderById(id)
            ?: return ToolResult(
                success = false,
                message = "یادآور با شناسه $id یافت نشد.",
                errorCode = "NOT_FOUND"
            )
        return ToolResult(
            success = true,
            message = "جزئیات یادآور دریافت شد.",
            data = rem
        )
    }

    /**
     * Tool 5: Update fields of an existing reminder (title, description, category, priority, repeatType, advanceAlertMinutes).
     */
    suspend fun updateReminder(
        id: Long,
        newTitle: String? = null,
        newDescription: String? = null,
        newTimestamp: Long? = null,
        newJalaliDate: String? = null,
        newTimeText: String? = null,
        newCategory: String? = null,
        newPriority: Int? = null,
        newRepeatType: String? = null,
        newAdvanceAlertMinutes: Int? = null
    ): ToolResult {
        val existing = repository.getReminderById(id)
            ?: return ToolResult(
                success = false,
                message = "یادآور مورد نظر جهت ویرایش یافت نشد.",
                errorCode = "NOT_FOUND"
            )

        val updated = existing.copy(
            title = newTitle?.trim() ?: existing.title,
            description = newDescription?.trim() ?: existing.description,
            triggerTimestamp = newTimestamp ?: existing.triggerTimestamp,
            jalaliDateText = newJalaliDate ?: existing.jalaliDateText,
            timeText = newTimeText ?: existing.timeText,
            category = newCategory ?: existing.category,
            priority = newPriority ?: existing.priority,
            repeatType = newRepeatType ?: existing.repeatType,
            advanceAlertMinutes = newAdvanceAlertMinutes ?: existing.advanceAlertMinutes
        )

        return try {
            repository.update(updated)
            // Re-schedule alarm to apply any time or advance alert updates
            AlarmScheduler.cancelReminder(context, id)
            if (!updated.isCompleted && updated.triggerTimestamp > System.currentTimeMillis()) {
                AlarmScheduler.scheduleReminder(context, updated)
            }
            ToolResult(
                success = true,
                message = "یادآور «${updated.title}» با موفقیت ویرایش و اعلان آن به‌روزرسانی شد.",
                data = updated
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                message = "خطا در ویرایش یادآور: ${e.localizedMessage}",
                errorCode = "DB_UPDATE_ERROR"
            )
        }
    }

    /**
     * Tool 6: Reschedule a reminder to a new timestamp, or relative offset minutes.
     */
    suspend fun rescheduleReminder(
        id: Long,
        offsetMinutes: Int? = null,
        newTimestamp: Long? = null,
        newDateText: String? = null,
        newTimeText: String? = null
    ): ToolResult {
        val existing = repository.getReminderById(id)
            ?: return ToolResult(
                success = false,
                message = "یادآور با شناسه $id برای تغییر زمان یافت نشد.",
                errorCode = "NOT_FOUND"
            )

        val targetTimestamp = when {
            newTimestamp != null -> newTimestamp
            offsetMinutes != null -> existing.triggerTimestamp + (offsetMinutes * 60 * 1000L)
            else -> existing.triggerTimestamp
        }

        if (isPastTime(targetTimestamp)) {
            return ToolResult(
                success = false,
                message = "زمان جدید انتخاب‌شده در گذشته قرار دارد و قابل ثبت نیست.",
                errorCode = "PAST_TIMESTAMP"
            )
        }

        val j = PersianDateEngine.timestampToJalali(targetTimestamp, defaultTimeZone)
        val finalDate = newDateText ?: j.formatPersianNumeric()
        val finalTime = newTimeText ?: PersianDateEngine.formatPersianTime(j.hour, j.minute)

        return try {
            repository.reschedule(id, targetTimestamp, finalDate, finalTime)
            val updated = existing.copy(
                triggerTimestamp = targetTimestamp,
                jalaliDateText = finalDate,
                timeText = finalTime,
                isCompleted = false
            )
            // Reschedule alarm
            AlarmScheduler.cancelReminder(context, id)
            val scheduleResult = AlarmScheduler.scheduleReminder(context, updated)

            val desc = if (offsetMinutes != null) {
                if (offsetMinutes > 0) "$offsetMinutes دقیقه به تعویق افتاد" else "${-offsetMinutes} دقیقه جلو کشیده شد"
            } else {
                "به تاریخ $finalDate ساعت $finalTime منتقل شد"
            }

            val scheduleMessage = when (scheduleResult) {
                com.example.service.alarm.AlarmScheduleResult.SCHEDULED -> "و آلارم آن نیز تنظیم شد"
                com.example.service.alarm.AlarmScheduleResult.PARTIALLY_SCHEDULED -> "اما فقط بخشی از هشدارهای آن تنظیم شد"
                com.example.service.alarm.AlarmScheduleResult.ALREADY_DUE -> "اما زمان آن گذشته یا یادآور تکمیل‌شده است و آلارم فعالی ندارد"
                com.example.service.alarm.AlarmScheduleResult.EXACT_ALARM_PERMISSION_REQUIRED -> "اما مجوز هشدار دقیق فعال نیست و آلارم تنظیم نشد"
                com.example.service.alarm.AlarmScheduleResult.ALARM_SERVICE_UNAVAILABLE,
                com.example.service.alarm.AlarmScheduleResult.FAILED -> "اما تنظیم آلارم ناموفق بود"
            }
            ToolResult(
                success = true,
                message = "یادآور «${existing.title}» $desc $scheduleMessage.",
                data = updated
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                message = "خطا در تغییر زمان یادآور: ${e.localizedMessage}",
                errorCode = "DB_UPDATE_ERROR"
            )
        }
    }

    /**
     * Tool 7: Delete or cancel a reminder.
     * If isHardDelete = true: removes the record permanently from the DB and cancels alarms.
     * If isHardDelete = false: keeps the calendar event record but cancels the active alarm notification.
     */
    suspend fun deleteOrCancelReminder(id: Long, isHardDelete: Boolean = true): ToolResult {
        val existing = repository.getReminderById(id)
            ?: return ToolResult(
                success = false,
                message = "یادآور مورد نظر یافت نشد.",
                errorCode = "NOT_FOUND"
            )

        return try {
            if (isHardDelete) {
                repository.deleteById(id)
                AlarmScheduler.cancelReminder(context, id)
                ToolResult(
                    success = true,
                    message = "یادآور «${existing.title}» و تمام اعلان‌های وابسته به آن به طور کامل حذف شدند.",
                    data = existing
                )
            } else {
                // Cancel alarm only, but keep record
                AlarmScheduler.cancelReminder(context, id)
                val updated = existing.copy(
                    description = if (existing.description.isNotBlank()) "${existing.description} [اعلان لغو شد]" else "[اعلان لغو شد]"
                )
                repository.update(updated)
                ToolResult(
                    success = true,
                    message = "اعلان و آلارم یادآور «${existing.title}» لغو شد، اما رویداد آن در تقویم باقی ماند.",
                    data = updated
                )
            }
        } catch (e: Exception) {
            ToolResult(
                success = false,
                message = "خطا در پردازش حذف یا لغو یادآور: ${e.localizedMessage}",
                errorCode = "DB_DELETE_ERROR"
            )
        }
    }

    /**
     * Tool 8: Update advance alert minutes (e.g. alert 15, 30, or 60 minutes before).
     */
    suspend fun updateAdvanceAlert(id: Long, advanceAlertMinutes: Int): ToolResult {
        val existing = repository.getReminderById(id)
            ?: return ToolResult(
                success = false,
                message = "یادآور مورد نظر یافت نشد.",
                errorCode = "NOT_FOUND"
            )

        val updated = existing.copy(advanceAlertMinutes = advanceAlertMinutes)
        return try {
            repository.update(updated)
            AlarmScheduler.cancelReminder(context, id)
            val scheduleResult = if (!updated.isCompleted && updated.triggerTimestamp > System.currentTimeMillis()) {
                AlarmScheduler.scheduleReminder(context, updated)
            } else {
                com.example.service.alarm.AlarmScheduleResult.ALREADY_DUE
            }
            val alertDesc = when (scheduleResult) {
                com.example.service.alarm.AlarmScheduleResult.SCHEDULED -> if (advanceAlertMinutes > 0) "پیش‌اعلان $advanceAlertMinutes دقیقه قبل برای «${updated.title}» فعال و آلارم زمان‌بندی شد." else "پیش‌اعلان یادآور «${updated.title}» غیرفعال و آلارم اصلی زمان‌بندی شد."
                com.example.service.alarm.AlarmScheduleResult.PARTIALLY_SCHEDULED -> "تنظیمات پیش‌اعلان ذخیره شد، اما فقط بخشی از آلارم‌های «${updated.title}» زمان‌بندی شد."
                com.example.service.alarm.AlarmScheduleResult.EXACT_ALARM_PERMISSION_REQUIRED -> "تنظیمات ذخیره شد، اما مجوز هشدار دقیق فعال نیست و آلارم «${updated.title}» تنظیم نشد."
                com.example.service.alarm.AlarmScheduleResult.ALREADY_DUE -> "تنظیمات ذخیره شد؛ این یادآور تکمیل‌شده یا موعدگذشته است و آلارم فعالی ندارد."
                com.example.service.alarm.AlarmScheduleResult.ALARM_SERVICE_UNAVAILABLE,
                com.example.service.alarm.AlarmScheduleResult.FAILED -> "تنظیمات ذخیره شد، اما زمان‌بندی آلارم «${updated.title}» ناموفق بود."
            }
            ToolResult(
                success = true,
                message = alertDesc,
                data = updated
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                message = "خطا در تنظیم پیش‌اعلان: ${e.localizedMessage}",
                errorCode = "DB_UPDATE_ERROR"
            )
        }
    }

    /**
     * Tool 9: Toggle completed status of a reminder.
     */
    suspend fun toggleReminderCompletion(id: Long, isCompleted: Boolean): ToolResult {
        val existing = repository.getReminderById(id)
            ?: return ToolResult(
                success = false,
                message = "یادآور مورد نظر یافت نشد.",
                errorCode = "NOT_FOUND"
            )

        val completedAt = if (isCompleted) System.currentTimeMillis() else null
        return try {
            repository.setCompleted(id, isCompleted)
            if (isCompleted) {
                AlarmScheduler.cancelReminder(context, id)
            } else if (existing.triggerTimestamp > System.currentTimeMillis()) {
                AlarmScheduler.scheduleReminder(context, existing.copy(isCompleted = false))
            }
            val statusText = if (isCompleted) "انجام‌شده" else "در انتظار انجام"
            ToolResult(
                success = true,
                message = "وضعیت یادآور «${existing.title}» به «$statusText» تغییر یافت.",
                data = existing.copy(isCompleted = isCompleted, completedAt = completedAt)
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                message = "خطا در تغییر وضعیت یادآور: ${e.localizedMessage}",
                errorCode = "DB_UPDATE_ERROR"
            )
        }
    }

    /**
     * Tool 10: Verify the operation and real alarm status in the Android system.
     */
    suspend fun verifyOperationAndAlarmStatus(id: Long): ToolResult {
        val rem = repository.getReminderById(id)
        if (rem == null) {
            return ToolResult(
                success = false,
                message = "هیچ رکوردی با شناسه $id در پایگاه داده وجود ندارد.",
                errorCode = "NOT_IN_DB"
            )
        }

        val isScheduled = !rem.isCompleted && rem.triggerTimestamp > System.currentTimeMillis()
        val alarmStatus = if (isScheduled) "آلارم فعال و زمان‌بندی شده است" else "آلارم غیرفعال یا گذشته است"

        return ToolResult(
            success = true,
            message = "رکورد «${rem.title}» در پایگاه داده موجود است. وضعیت: $alarmStatus (تاریخ: ${rem.jalaliDateText} ساعت ${rem.timeText}).",
            data = rem
        )
    }

    /**
     * Helper to find matching reminders for disambiguation.
     * When user says "قرار تماس با مشتری رو حذف کن", returns all matching candidates.
     */
    suspend fun findMatchingReminders(titleKeyword: String, onlyActive: Boolean = true): List<ReminderEntity> {
        val all = repository.getActiveReminders(if (onlyActive) System.currentTimeMillis() else 0L)
        val cleanKeyword = titleKeyword.trim().lowercase()
        return all.filter {
            it.title.lowercase().contains(cleanKeyword) ||
            cleanKeyword.contains(it.title.lowercase()) ||
            it.description.lowercase().contains(cleanKeyword)
        }
    }

    /**
     * Retrieves the most recently active or scheduled reminder.
     */
    suspend fun getLatestReminder(): ReminderEntity? {
        val active = repository.getActiveReminders(System.currentTimeMillis())
        return active.firstOrNull()
    }

    /**
     * Tool 11: Update application default advance notification alert setting.
     * e.g. "از این به بعد تمام یادآورهام رو ۱۵ دقیقه قبل هم اطلاع بده"
     */
    fun updateDefaultAdvanceAlert(minutes: Int): ToolResult {
        return try {
            userPreferencesManager.setDefaultAdvanceAlert(minutes)
            val msg = if (minutes > 0) {
                "تنظیمات برنامه به‌روزرسانی شد: از این به بعد یادآورها $minutes دقیقه قبل از موعد نیز هشدار می‌دهند."
            } else {
                "پیش‌اعلان پیش‌فرض غیرفعال شد؛ یادآورها دقیقاً در موعد مقرر هشدار می‌دهند."
            }
            ToolResult(
                success = true,
                message = msg,
                data = minutes
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                message = "خطا در تغییر تنظیمات پیش‌اعلان: ${e.localizedMessage}",
                errorCode = "PREF_UPDATE_ERROR"
            )
        }
    }

    /**
     * Tool 12: Update application theme mode (LIGHT, DARK, SYSTEM).
     */
    fun updateAppTheme(themeModeStr: String): ToolResult {
        return try {
            val mode = when {
                themeModeStr.contains("تاریک") || themeModeStr.equals("DARK", ignoreCase = true) -> com.example.ui.theme.ThemeMode.DARK
                themeModeStr.contains("روشن") || themeModeStr.equals("LIGHT", ignoreCase = true) -> com.example.ui.theme.ThemeMode.LIGHT
                else -> com.example.ui.theme.ThemeMode.SYSTEM
            }
            themePreferences.setThemeMode(mode)
            ToolResult(
                success = true,
                message = "پوسته برنامه به «${mode.titleFa}» تغییر یافت.",
                data = mode
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                message = "خطا در تغییر تم برنامه: ${e.localizedMessage}",
                errorCode = "THEME_UPDATE_ERROR"
            )
        }
    }

    /**
     * Tool 13: Save a long-term user habit, routine, or personal preference.
     * e.g. "من معمولاً ساعت ۸ صبح بیدار میشم"
     */
    fun saveUserMemoryPreference(key: String, value: String, descriptionFa: String = "اطلاعات کاربر"): ToolResult {
        return try {
            userPreferencesManager.saveMemory(key, value, descriptionFa)
            if (key.contains("بیداری") || key.contains("بیدار")) {
                userPreferencesManager.setWakeUpTime(value)
            } else if (key.contains("خواب")) {
                userPreferencesManager.setSleepTime(value)
            }
            ToolResult(
                success = true,
                message = "اطلاعات «$key: $value» با موفقیت در حافظه بلندمدت دستیار ذخیره شد و در برنامه‌ریزی‌های بعدی لحاظ می‌شود.",
                data = Pair(key, value)
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                message = "خطا در ذخیره حافظه: ${e.localizedMessage}",
                errorCode = "MEMORY_SAVE_ERROR"
            )
        }
    }

    /**
     * Tool 14: Get all long-term user memories and preferences.
     */
    fun getUserMemoryPreferences(): ToolResult {
        val memories = userPreferencesManager.userMemories.value
        val summary = userPreferencesManager.buildContextSummaryForAi()
        return ToolResult(
            success = true,
            message = summary,
            data = memories
        )
    }

    /**
     * Tool 15: Delete a specific item from long-term memory.
     */
    fun deleteUserMemoryPreference(key: String): ToolResult {
        val removed = userPreferencesManager.deleteMemory(key)
        return if (removed) {
            ToolResult(
                success = true,
                message = "اطلاعات «$key» از حافظه دستیار پاک شد."
            )
        } else {
            ToolResult(
                success = false,
                message = "موردی با عنوان «$key» در حافظه یافت نشد.",
                errorCode = "NOT_FOUND"
            )
        }
    }

    /**
     * Tool 16: Create recurring reminders with explicit start date, end date, and pattern.
     * e.g. "برای هر روز سال آینده ساعت ۸ صبح یادآور تنظیم کن"
     */
    suspend fun createRecurringReminder(
        title: String,
        startTimestamp: Long,
        repeatType: String = "DAILY",
        recurrenceEndDate: Long? = null,
        recurrenceDaysOfWeek: String? = null,
        advanceAlertMinutes: Int = 0
    ): ToolResult {
        val jStart = PersianDateEngine.timestampToJalali(startTimestamp, defaultTimeZone)
        val dateText = jStart.formatPersianNumeric()
        val timeText = PersianDateEngine.formatPersianTime(jStart.hour, jStart.minute)

        val task = ParsedTask(
            title = title,
            triggerTimestamp = startTimestamp,
            jalaliDateText = dateText,
            timeText = timeText,
            repeatType = repeatType,
            recurrenceEndDate = recurrenceEndDate,
            recurrenceDaysOfWeek = recurrenceDaysOfWeek,
            advanceAlertMinutes = advanceAlertMinutes
        )
        return createReminder(task)
    }

    /**
     * Tool 17: Get full app state, active alarms, theme, and user routine summary.
     */
    suspend fun getAppSystemStatus(): ToolResult {
        val active = repository.getActiveReminders(System.currentTimeMillis())
        val theme = themePreferences.themeMode.value.titleFa
        val alert = userPreferencesManager.defaultAdvanceAlertMinutes.value
        val memoryCount = userPreferencesManager.userMemories.value.size

        val summary = "وضعیت سیستم Yadet:\n" +
                "- تعداد یادآوری‌های فعال: ${PersianDateEngine.toPersianDigits(active.size)}\n" +
                "- پوسته فعلی: $theme\n" +
                "- پیش‌اعلان پیش‌فرض: ${if (alert > 0) "$alert دقیقه قبل" else "غیرفعال"}\n" +
                "- اطلاعات ثبت‌شده در حافظه: ${PersianDateEngine.toPersianDigits(memoryCount)} مورد"

        return ToolResult(
            success = true,
            message = summary,
            data = mapOf(
                "activeCount" to active.size,
                "theme" to theme,
                "defaultAlertMinutes" to alert,
                "memoryCount" to memoryCount
            )
        )
    }

    /**
     * Tool 18: Delete all reminders from DB and cancel all alarms.
     */
    suspend fun deleteAllReminders(): ToolResult {
        return try {
            val all = repository.getActiveReminders(0L)
            all.forEach { AlarmScheduler.cancelReminder(context, it.id) }
            repository.deleteAll()
            ToolResult(
                success = true,
                message = "تمام یادآورها (${PersianDateEngine.toPersianDigits(all.size)} مورد) و آلارم‌های مربوطه با موفقیت حذف شدند.",
                data = all.size
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                message = "خطا در حذف همه یادآورها: ${e.localizedMessage}",
                errorCode = "DB_DELETE_ALL_ERROR"
            )
        }
    }

    /**
     * Tool 19: Delete reminders within a specific date/time range.
     */
    suspend fun deleteRemindersByDate(startMillis: Long, endMillis: Long, label: String): ToolResult {
        return try {
            val inRange = repository.getActiveReminders(0L).filter { it.triggerTimestamp in startMillis..endMillis }
            inRange.forEach { AlarmScheduler.cancelReminder(context, it.id) }
            repository.deleteBetween(startMillis, endMillis)
            ToolResult(
                success = true,
                message = "یادآورهای $label (${PersianDateEngine.toPersianDigits(inRange.size)} مورد) با موفقیت حذف شدند.",
                data = inRange
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                message = "خطا در حذف یادآورهای $label: ${e.localizedMessage}",
                errorCode = "DB_DELETE_RANGE_ERROR"
            )
        }
    }

    /**
     * Tool 20: Cancel alarms only for all active reminders without deleting them from the calendar.
     */
    suspend fun cancelAllAlarmsOnly(): ToolResult {
        return try {
            val active = repository.getActiveReminders(System.currentTimeMillis())
            active.forEach { AlarmScheduler.cancelReminder(context, it.id) }
            ToolResult(
                success = true,
                message = "تمام آلارم‌ها و هشدارهای فعال (${PersianDateEngine.toPersianDigits(active.size)} مورد) غیرفعال شدند (رویدادها در تقویم باقی ماندند).",
                data = active.size
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                message = "خطا در لغو آلارم‌ها: ${e.localizedMessage}",
                errorCode = "ALARM_CANCEL_ALL_ERROR"
            )
        }
    }

    /**
     * Validates if a calendar timestamp is in the past.
     */
    fun isPastTime(timestamp: Long): Boolean {
        return timestamp < System.currentTimeMillis()
    }
}
