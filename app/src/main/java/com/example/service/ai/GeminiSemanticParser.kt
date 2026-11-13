package com.example.service.ai

import android.util.Log
import com.example.data.engine.PersianDateEngine
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

data class ParsedTask(
    val title: String,
    val description: String = "",
    val rawTimePhrase: String = "",
    val triggerTimestamp: Long,
    val jalaliDateText: String,
    val timeText: String,
    val category: String = "عمومی",
    val priority: Int = 0, // 0: عادی, 1: مهم, 2: فوری
    val repeatType: String = "NONE", // NONE, DAILY, WEEKLY, MONTHLY, YEARLY, WORKDAYS, CUSTOM
    val recurrenceEndDate: Long? = null,
    val recurrenceDaysOfWeek: String? = null,
    val advanceAlertMinutes: Int = 0,
    val hasExplicitTime: Boolean = true,
    val hasExplicitDate: Boolean = true,
    val hasExplicitTitle: Boolean = true,
    val isAmbiguous: Boolean = false,
    val clarificationQuestion: String? = null,
    val quickSuggestions: List<String> = emptyList(),
    val analyzedResult: AnalyzedCommandResult? = null
) {
    val hasResolvedRelativeDateTime: Boolean
        get() = rawTimePhrase.isRelativeDateTimePhrase()

    val hasAmbiguousTimePeriod: Boolean
        get() = analyzedResult?.ambiguousFields?.contains("HOUR_PERIOD_AM_PM") == true ||
            (isAmbiguous && hasExplicitTime && hasExplicitDate)

    val needsClarification: Boolean
        get() = clarificationQuestion != null || isAmbiguous || hasAmbiguousTimePeriod || !hasExplicitTitle ||
            !hasExplicitTime || (!hasExplicitDate && !hasResolvedRelativeDateTime)
}

private fun String.isRelativeDateTimePhrase(): Boolean {
    val phrase = this
    val hasRelativeMarker = phrase.contains("دیگه") || phrase.contains("بعد") || phrase.contains("دیگر")
    val hasDurationUnit = phrase.contains("دقیقه") || phrase.contains("ساعت")
    return hasRelativeMarker && hasDurationUnit
}

class GeminiSemanticParser {
    private val TAG = "GeminiSemanticParser"

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
            }
        )
    }

    /**
     * Parses Persian speech with Gemini when Firebase AI Logic is configured.
     * The local parser remains the deterministic offline fallback.
     */
    suspend fun parseCommand(userSpeech: String, referenceTimeMillis: Long = System.currentTimeMillis()): List<ParsedTask> {
        val cleanSpeech = userSpeech.trim()
        if (cleanSpeech.isBlank()) return emptyList()

        return try {
            withTimeout(12_000L) {
                parseWithGemini(cleanSpeech, referenceTimeMillis)
            }.ifEmpty {
                LocalPersianParser.parse(cleanSpeech, referenceTimeMillis)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Gemini semantic parsing failed; using local Persian parser: ${e.message}", e)
            LocalPersianParser.parse(cleanSpeech, referenceTimeMillis)
        }
    }

    private suspend fun parseWithGemini(userSpeech: String, referenceTimeMillis: Long): List<ParsedTask> {
        val curJalali = PersianDateEngine.timestampToJalali(referenceTimeMillis)

        // Precompute exact Jalali dates for relative expressions so the LLM doesn't have to calculate calendar math
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = referenceTimeMillis }

        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        val tomorrowJalali = PersianDateEngine.timestampToJalali(cal.timeInMillis)

        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        val dayAfterTomorrowJalali = PersianDateEngine.timestampToJalali(cal.timeInMillis)

        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        val threeDaysLaterJalali = PersianDateEngine.timestampToJalali(cal.timeInMillis)

        val prompt = """
            You are an expert Persian NLP task extraction and calendar assistant for the "یادت" application.
            Current reference Jalali time:
            - TODAY ("امروز"): Year ${curJalali.year}, Month ${curJalali.month} (${curJalali.monthName}), Day ${curJalali.day}, Time: ${curJalali.hour}:${curJalali.minute}.
            - TOMORROW ("فردا"): Year ${tomorrowJalali.year}, Month ${tomorrowJalali.month}, Day ${tomorrowJalali.day}.
            - DAY AFTER TOMORROW ("پس فردا" / "پس‌فردا"): Year ${dayAfterTomorrowJalali.year}, Month ${dayAfterTomorrowJalali.month}, Day ${dayAfterTomorrowJalali.day}.

            User spoken input in Persian: "$userSpeech"

            CRITICAL PARSING RULES:
            1. Title & Subject Understanding:
               - "title": Extract ONLY the core task or action in Persian (e.g. "جلسه با مدیر عامل", "خرید نان سنگک", "رفتن به آزمایشگاه", "تماس با احمد").
               - NEVER include relative date expressions ("پس فردا", "فردا", "امروز", "ساعت ۳", "ساعت ۹") in the title!
               - NEVER include conversation phrases ("یادم بنداز", "لطفاً", "بهم یادآوری کن", "ثبت کن") in the title!
            2. Relative Date Resolution:
               - "پس فردا" or "پس‌فردا" -> MUST set: targetJalaliYear=${dayAfterTomorrowJalali.year}, targetJalaliMonth=${dayAfterTomorrowJalali.month}, targetJalaliDay=${dayAfterTomorrowJalali.day}.
               - "فردا" -> MUST set: targetJalaliYear=${tomorrowJalali.year}, targetJalaliMonth=${tomorrowJalali.month}, targetJalaliDay=${tomorrowJalali.day}.
               - "امروز" -> MUST set: targetJalaliYear=${curJalali.year}, targetJalaliMonth=${curJalali.month}, targetJalaliDay=${curJalali.day}.
               - "سه روز دیگه" / "سه روز بعد" / "3 روز دیگر" -> MUST set: targetJalaliYear=${threeDaysLaterJalali.year}, targetJalaliMonth=${threeDaysLaterJalali.month}, targetJalaliDay=${threeDaysLaterJalali.day}.
               - For any other explicit "N روز دیگر/بعد/دیگه", calculate the date by adding exactly N calendar days to TODAY; never reinterpret a multi-day offset as tomorrow.
            3. Hour Resolution:
               - NEVER guess morning or evening when the user says an hour from 1 to 11 without a period.
               - In that case set isAmbiguous=true, preserve the stated hour in targetHour, and ask a concise clarification such as "منظورتان ساعت ۱۰ صبح است یا ۱۰ شب؟".
               - "ساعت ۳ بعدازظهر" / "ساعت ۳ عصر" -> targetHour: 15, targetMinute: 0.
               - "ساعت ۳ صبح" / "۳ بامداد" -> targetHour: 3, targetMinute: 0.
               - "ساعت ۹ صبح" -> targetHour: 9, targetMinute: 0.
               - "ساعت ۹ شب" -> targetHour: 21, targetMinute: 0.
            4. Relative Minutes/Hours:
               - "یک ربع دیگه" -> offsetMinutes: 15
               - "نیم ساعت دیگه" -> offsetMinutes: 30
               - "۱۰ دقیقه دیگه" -> offsetMinutes: 10
               - "۲ ساعت دیگه" -> offsetMinutes: 120
            5. Mind-Dump / Multi-Task:
               - If the text contains multiple distinct tasks (e.g. separated by "و", "بعدش", "سپس", "همچنین"), extract each item as a separate task in the "tasks" array.
            6. Schema fields for each task:
               - "title": Persian clean action title (e.g. "جلسه با مدیر")
               - "description": Additional details or empty string
               - "rawTimePhrase": The time phrase in the user's speech (e.g. "پس فردا ساعت ۳")
               - "offsetMinutes": Integer or null
               - "targetJalaliYear": Integer
               - "targetJalaliMonth": Integer (1..12)
               - "targetJalaliDay": Integer (1..31)
               - "targetHour": Integer (0..23)
               - "targetMinute": Integer (0..59)
               - "repeatType": "NONE", "DAILY", "WEEKLY", or "MONTHLY"
               - "advanceAlertMinutes": Integer (e.g. 15, 30, or 0)
               - "priority": 0 for normal, 1 for important ("مهم"), 2 for urgent ("فوری")
               - "category": One of "کاری", "شخصی", "سلامت", "خرید", "عمومی"
               - "hasExplicitDate": Boolean; true ONLY if the user explicitly supplied a date or relative day
               - "hasExplicitTime": Boolean; true ONLY if the user explicitly supplied a clock time or relative duration
               - "hasExplicitTitle": Boolean; true ONLY if an actual task/action title was understood
               - "isAmbiguous": Boolean
               - "clarificationQuestion": String or null
               - NEVER infer a missing date, time, or task title. For a missing date, use TODAY only as a temporary placeholder and set hasExplicitDate=false.
               - For a missing time, use the current reference time only as a temporary placeholder and set hasExplicitTime=false.
               - Do not turn a past time today into tomorrow unless the user explicitly says tomorrow.

            Respond ONLY with a JSON object:
            {
              "tasks": [ ... ]
            }
        """.trimIndent()

        val response = model.generateContent(prompt)
        val jsonText = response.text ?: throw IllegalStateException("Empty model response")
        return parseJsonResponse(jsonText, referenceTimeMillis)
    }

    private fun parseJsonResponse(jsonStr: String, referenceTimeMillis: Long): List<ParsedTask> {
        val root = JSONObject(jsonStr)
        val array = root.optJSONArray("tasks") ?: JSONArray()
        val results = mutableListOf<ParsedTask>()

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val title = obj.optString("title", "یادآوری بدون عنوان")
            val desc = obj.optString("description", "")
            val rawTime = obj.optString("rawTimePhrase", "")
            val repeat = obj.optString("repeatType", "NONE")
            val priority = obj.optInt("priority", 0)
            val category = obj.optString("category", "عمومی")
            val advanceMins = obj.optInt("advanceAlertMinutes", 0)
            val isAmbiguous = obj.optBoolean("isAmbiguous", false)
            val question = if (!obj.isNull("clarificationQuestion")) obj.optString("clarificationQuestion") else null

            var targetMillis: Long
            val offsetMins = if (obj.has("offsetMinutes") && !obj.isNull("offsetMinutes")) obj.optLong("offsetMinutes") else null
            if (offsetMins != null && offsetMins > 0) {
                targetMillis = referenceTimeMillis + (offsetMins * 60 * 1000L)
            } else {
                val curJalali = PersianDateEngine.timestampToJalali(referenceTimeMillis)
                val tYear = obj.optInt("targetJalaliYear", curJalali.year)
                val tMonth = obj.optInt("targetJalaliMonth", curJalali.month)
                val tDay = obj.optInt("targetJalaliDay", curJalali.day)
                val tHour = obj.optInt("targetHour", curJalali.hour)
                val tMin = obj.optInt("targetMinute", curJalali.minute)

                targetMillis = PersianDateEngine.jalaliToTimestamp(tYear, tMonth, tDay, tHour, tMin)
            }

            val targetJalali = PersianDateEngine.timestampToJalali(targetMillis)
            val jalaliText = targetJalali.formatPersianNumeric()
            val timeText = PersianDateEngine.formatPersianTime(targetJalali.hour, targetJalali.minute)

            val hasRelativeOffset = offsetMins != null && offsetMins > 0
            val hasExplicitTime = obj.optBoolean("hasExplicitTime", false) || hasRelativeOffset
            val hasExplicitDate = obj.optBoolean("hasExplicitDate", false) || hasRelativeOffset
            val hasExplicitTitle = obj.optBoolean("hasExplicitTitle", false) && title.isNotBlank() && !title.startsWith("یادآوری ساعت")

            results.add(
                ParsedTask(
                    title = title,
                    description = desc,
                    rawTimePhrase = rawTime,
                    triggerTimestamp = targetMillis,
                    jalaliDateText = jalaliText,
                    timeText = timeText,
                    category = category,
                    priority = priority,
                    repeatType = repeat,
                    advanceAlertMinutes = advanceMins,
                    hasExplicitTime = hasExplicitTime,
                    hasExplicitDate = hasExplicitDate,
                    hasExplicitTitle = hasExplicitTitle,
                    isAmbiguous = isAmbiguous,
                    clarificationQuestion = question
                )
            )
        }

        return if (results.isNotEmpty()) results else fallbackPersianParse(jsonStr, referenceTimeMillis)
    }

    /**
     * High-reliability local Persian rule-based parser for offline or instant command processing.
     */
    fun fallbackPersianParse(text: String, referenceTimeMillis: Long = System.currentTimeMillis()): List<ParsedTask> {
        // Split on sentences / conjunctions for mind dump handling
        val segments = text.split(Regex("""(?<=[،\n])|\s+و\s+(?=(?:پس[\s‌_]?اون[\s‌_]?فردا|پس[\s‌_]?فردا|پسفردا|فردا|امروز|سال آینده|پنج دقیقه|یک ربع|نیم ساعت|هر|ساعت))"""))
            .map { it.trim().trim('،', ' ', '.') }
            .filter { it.isNotBlank() }

        val tasks = mutableListOf<ParsedTask>()
        for (segment in segments) {
            val task = parseSingleSegment(segment, referenceTimeMillis)
            tasks.add(task)
        }
        return tasks
    }

    private fun parseSingleSegment(segment: String, referenceTimeMillis: Long): ParsedTask {
        val extracted = PersianDateEngine.extractPersianTask(segment, referenceTimeMillis)

        val priority = when {
            segment.contains("فوری") || segment.contains("خیلی مهم") -> 2
            segment.contains("مهم") || segment.contains("ضروری") -> 1
            else -> 0
        }

        val repeat = when {
            segment.contains("هر روز") -> "DAILY"
            segment.contains("هر جمعه") || segment.contains("هر هفته") -> "WEEKLY"
            segment.contains("هر ماه") -> "MONTHLY"
            else -> "NONE"
        }

        val advanceMins = when {
            segment.contains("نیم ساعت قبل") -> 30
            segment.contains("یک ساعت قبل") -> 60
            segment.contains("ده دقیقه قبل") || segment.contains("10 دقیقه قبل") -> 10
            else -> 0
        }

        val category = when {
            segment.contains("جلسه") || segment.contains("پروژه") || segment.contains("قرارداد") || segment.contains("چاپ") -> "کاری"
            segment.contains("خرید") || segment.contains("سفارش") || segment.contains("فروشگاه") -> "خرید"
            segment.contains("دکتر") || segment.contains("دارو") || segment.contains("ورزش") || segment.contains("آب") || segment.contains("آزمایشگاه") -> "سلامت"
            segment.contains("همسرم") || segment.contains("مادرم") || segment.contains("پدرم") || segment.contains("تولد") -> "شخصی"
            else -> "عمومی"
        }

        val hasExplicitTitle = extracted.hasExplicitTitle
        val hasRelativeDateTime = extracted.rawTimePhrase.isRelativeDateTimePhrase()
        val hasExplicitTime = extracted.hasExplicitTime || hasRelativeDateTime
        val hasExplicitDate = extracted.hasExplicitDate || hasRelativeDateTime
        val analysis = PersianCommandAnalyzer.analyze(segment, referenceTimeMillis)
        val hasAmbiguousTimePeriod = analysis.ambiguousFields.contains("HOUR_PERIOD_AM_PM")

        val clarification = when {
            hasAmbiguousTimePeriod -> "منظورتان ساعت ${extracted.timeText.substringBefore(":")} صبح است یا شب؟"
            !hasExplicitTime || !hasExplicitDate -> "برای یادآوری «${extracted.title}»، ${if (!hasExplicitDate && !hasExplicitTime) "تاریخ و ساعت" else if (!hasExplicitDate) "تاریخ" else "ساعت"} مشخص نشده است؛ لطفاً آن را تعیین کنید."
            !hasExplicitTitle -> "زمان یادآوری روی ${extracted.jalaliDateText} ساعت ${extracted.timeText} مشخص شد. لطفاً عنوان یا موضوع این یادآوری را بفرمایید:"
            else -> null
        }

        return ParsedTask(
            title = extracted.title.capitalizeFirstLetter(),
            description = segment,
            rawTimePhrase = extracted.rawTimePhrase,
            triggerTimestamp = extracted.triggerTimestamp,
            jalaliDateText = extracted.jalaliDateText,
            timeText = extracted.timeText,
            category = category,
            priority = priority,
            repeatType = repeat,
            advanceAlertMinutes = advanceMins,
            hasExplicitTime = hasExplicitTime,
            hasExplicitDate = hasExplicitDate,
            hasExplicitTitle = hasExplicitTitle,
            isAmbiguous = hasAmbiguousTimePeriod,
            clarificationQuestion = clarification,
            analyzedResult = analysis
        )
    }

    private fun String.capitalizeFirstLetter(): String {
        return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
