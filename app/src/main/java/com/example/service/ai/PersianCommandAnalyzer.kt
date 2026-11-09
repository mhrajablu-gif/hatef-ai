package com.example.service.ai

import com.example.data.engine.PersianDateEngine
import java.util.Calendar
import java.util.TimeZone

enum class TimeType {
    ABSOLUTE,
    RELATIVE,
    UNSPECIFIED
}

enum class ValidationStatus {
    VALID,
    AMBIGUOUS,
    CONFLICT,
    INSUFFICIENT_DATA
}

data class AnalyzedCommandResult(
    val rawText: String,
    val title: String,
    val referenceTimeMillis: Long,
    val targetTimestamp: Long?,
    val jalaliDateText: String?,
    val calculatedWeekday: String?,
    val requestedWeekday: String?,
    val hour: Int?,
    val minute: Int?,
    val timeZoneId: String,
    val timeType: TimeType,
    val ambiguousFields: List<String>,
    val validationStatus: ValidationStatus,
    val conflictMessage: String?,
    val reasoningExplanation: String,
    val needsUserClarification: Boolean,
    val clarificationQuestion: String?,
    val quickSuggestions: List<String>,
    val category: String = "عمومی",
    val priority: Int = 0,
    val repeatType: String = "NONE"
)

object PersianCommandAnalyzer {

    fun analyze(
        rawText: String,
        referenceTimeMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): AnalyzedCommandResult {
        val clean = rawText.trim()
        if (clean.isBlank()) {
            return emptyResult(clean, referenceTimeMillis, timeZone)
        }

        // 1. Normalize digits and common spellings
        val normalized = normalizeText(clean)
        val normalizedEnglish = PersianDateEngine.toEnglishDigits(normalized)

        // 2. Check for Relative Offsets (X دقیقه دیگر / نیم ساعت دیگر / X ساعت دیگر)
        val relativeOffsetResult = checkRelativeDurationOffset(clean, normalizedEnglish, referenceTimeMillis, timeZone)
        if (relativeOffsetResult != null) {
            return relativeOffsetResult
        }

        // 3. Check for "همین موقع" (فردا همین موقع / پس‌فردا همین موقع / هفته آینده همین موقع)
        val sameHourResult = checkSameHourPhrases(clean, normalizedEnglish, referenceTimeMillis, timeZone)
        if (sameHourResult != null) {
            return sameHourResult
        }

        // 4. Extract Clock Time (Hour & Minute)
        val timeExtraction = extractClockTime(normalizedEnglish)

        // 5. Extract Date / Weekday
        val dateExtraction = extractDate(normalizedEnglish, referenceTimeMillis, timeZone)

        // 6. Detect Ambiguity in Hour (e.g. "ساعت ۶" without morning/evening)
        val isHourAmbiguous = timeExtraction.hasExplicitHour && !timeExtraction.isPeriodSpecified && timeExtraction.rawHour in 1..11
        val ambiguousFields = mutableListOf<String>()
        if (isHourAmbiguous) {
            ambiguousFields.add("HOUR_PERIOD_AM_PM")
        }
        if (!timeExtraction.hasExplicitHour && !dateExtraction.hasExplicitDate) {
            ambiguousFields.add("MISSING_TIME_AND_DATE")
        }

        // 7. Title extraction
        val rawTimePhrase = timeExtraction.matchedPhrase
        val rawDatePhrase = dateExtraction.matchedPhrase
        val title = extractCleanTitle(clean, rawDatePhrase, rawTimePhrase)

        // 8. Calculate Target Timestamp if possible
        var targetTimestamp: Long? = null
        var calculatedWeekday: String? = null
        var jalaliDateText: String? = null
        var conflictMessage: String? = null
        var status = ValidationStatus.VALID
        val reasoning = StringBuilder()

        val resolvedHour = if (isHourAmbiguous) null else timeExtraction.resolvedHour
        val resolvedMinute = timeExtraction.resolvedMinute

        if (dateExtraction.isConflict) {
            status = ValidationStatus.CONFLICT
            conflictMessage = dateExtraction.conflictMessage
            reasoning.append("تناقض در تاریخ و روز هفته: ${dateExtraction.conflictMessage}. ")
        } else if (isHourAmbiguous) {
            status = ValidationStatus.AMBIGUOUS
            reasoning.append("ساعت ${timeExtraction.rawHour} مشخص شده اما صبح یا عصر بودن آن مشخص نیست. ")
        }

        if (status != ValidationStatus.CONFLICT && (resolvedHour != null || isHourAmbiguous)) {
            val effHour = resolvedHour ?: timeExtraction.rawHour
            val cal = Calendar.getInstance(timeZone).apply {
                timeInMillis = referenceTimeMillis
                set(Calendar.HOUR_OF_DAY, effHour)
                set(Calendar.MINUTE, resolvedMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            if (dateExtraction.exactJalaliYear != null && dateExtraction.exactJalaliMonth != null && dateExtraction.exactJalaliDay != null) {
                targetTimestamp = PersianDateEngine.jalaliToTimestamp(
                    dateExtraction.exactJalaliYear,
                    dateExtraction.exactJalaliMonth,
                    dateExtraction.exactJalaliDay,
                    effHour,
                    resolvedMinute,
                    timeZone
                )
                reasoning.append("تاریخ صوتی شمسی ${dateExtraction.exactJalaliYear}/${dateExtraction.exactJalaliMonth}/${dateExtraction.exactJalaliDay} استخراج شد. ")
            } else if (dateExtraction.dayOffset != null) {
                cal.add(Calendar.DAY_OF_YEAR, dateExtraction.dayOffset)
                // If today and time has already passed without explicit "امروز"
                if (dateExtraction.dayOffset == 0 && cal.timeInMillis <= referenceTimeMillis && !clean.contains("امروز")) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                    reasoning.append("ساعت مشخص‌شده برای امروز سپری شده، به فردا منتقل شد. ")
                }
                targetTimestamp = cal.timeInMillis
                reasoning.append("فاصله روز: ${dateExtraction.dayOffset} روز (${dateExtraction.matchedPhrase}). ")
            } else if (timeExtraction.hasExplicitHour) {
                // Time specified without date (e.g. "ساعت ۶ عصر"): if already passed today, assume tomorrow
                if (cal.timeInMillis <= referenceTimeMillis) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                    reasoning.append("ساعت انتخابی برای امروز گذشته است، لذا فردا در نظر گرفته شد. ")
                } else {
                    reasoning.append("زمان برای امروز ساعت $resolvedHour:$resolvedMinute تنظیم شد. ")
                }
                targetTimestamp = cal.timeInMillis
            }

            if (targetTimestamp != null) {
                calculatedWeekday = PersianDateEngine.getPersianWeekdayName(targetTimestamp, timeZone)
                val jDate = PersianDateEngine.timestampToJalali(targetTimestamp, timeZone)
                jalaliDateText = jDate.formatPersianNumeric()

                // Final Weekday Verification: does calculatedWeekday match requestedWeekday?
                if (dateExtraction.requestedWeekday != null) {
                    val req = normalizeWeekday(dateExtraction.requestedWeekday)
                    val calc = normalizeWeekday(calculatedWeekday)
                    if (req != calc) {
                        status = ValidationStatus.CONFLICT
                        conflictMessage = "عدم انطباق روز هفته: روز محاسبه‌شده «$calc» با روز درخواستی «$req» همخوانی ندارد."
                        targetTimestamp = null
                    }
                }
            }
        }

        // Formulate clarification if needed
        val needsClarification = status == ValidationStatus.AMBIGUOUS || status == ValidationStatus.CONFLICT || title.isBlank()
        val clarificationQuestion = when {
            status == ValidationStatus.CONFLICT -> conflictMessage
            isHourAmbiguous -> {
                val rawH = timeExtraction.rawHour
                val pmHour = if (rawH < 12) rawH + 12 else rawH
                "منظور شما از ساعت $rawH، $rawH صبح است یا $rawH عصر ($pmHour:۰۰)؟"
            }
            !timeExtraction.hasExplicitHour && !dateExtraction.hasExplicitDate -> {
                "ساعت یا تاریخ برای یادآوری «$title» مشخص نشده است؛ مایلید چه زمانی یادآوری شود؟"
            }
            title.isBlank() -> {
                "زمان یادآوری روی ${jalaliDateText ?: "مشخص"} ساعت ${timeExtraction.resolvedHour ?: "نامشخص"} تنظیم شد. لطفاً عنوان یا موضوع این یادآوری را بفرمایید:"
            }
            else -> null
        }

        val suggestions = when {
            isHourAmbiguous -> {
                val rawH = timeExtraction.rawHour
                val pmHour = if (rawH < 12) rawH + 12 else rawH
                listOf("$rawH صبح", "$rawH عصر ($pmHour:۰۰)")
            }
            !timeExtraction.hasExplicitHour && !dateExtraction.hasExplicitDate -> {
                listOf("نیم ساعت دیگر", "۱ ساعت دیگر", "امروز ساعت ۱۸:۰۰", "فردا ۹ صبح")
            }
            title.isBlank() -> {
                listOf("جلسه کاری", "تماس تلفنی", "خرید", "مصرف دارو")
            }
            else -> emptyList()
        }

        // Metadata: Category, Priority, Repeat
        val priority = when {
            clean.contains("خیلی مهم") || clean.contains("فوری") || clean.contains("اورژانسی") -> 2
            clean.contains("مهم") || clean.contains("ضروری") -> 1
            else -> 0
        }

        val repeat = when {
            clean.contains("هر روز") || clean.contains("روزانه") -> "DAILY"
            clean.contains("هر هفته") || clean.contains("هفتگی") || clean.contains("هر جمعه") -> "WEEKLY"
            clean.contains("هر ماه") || clean.contains("ماهانه") -> "MONTHLY"
            else -> "NONE"
        }

        val category = when {
            clean.contains("جلسه") || clean.contains("قرارداد") || clean.contains("پروژه") || clean.contains("شرکت") || clean.contains("کار") -> "کاری"
            clean.contains("خرید") || clean.contains("فروشگاه") || clean.contains("نان") || clean.contains("میوه") -> "خرید"
            clean.contains("دکتر") || clean.contains("دارو") || clean.contains("قرص") || clean.contains("ورزش") || clean.contains("آزمایشگاه") -> "سلامت"
            clean.contains("همسرم") || clean.contains("مادرم") || clean.contains("پدرم") || clean.contains("تولد") -> "شخصی"
            else -> "عمومی"
        }

        return AnalyzedCommandResult(
            rawText = clean,
            title = if (title.isNotBlank()) title else "یادآوری بدون عنوان",
            referenceTimeMillis = referenceTimeMillis,
            targetTimestamp = targetTimestamp,
            jalaliDateText = jalaliDateText,
            calculatedWeekday = calculatedWeekday,
            requestedWeekday = dateExtraction.requestedWeekday,
            hour = resolvedHour ?: (if (timeExtraction.hasExplicitHour) timeExtraction.rawHour else null),
            minute = resolvedMinute,
            timeZoneId = timeZone.id,
            timeType = if (targetTimestamp != null) TimeType.ABSOLUTE else TimeType.UNSPECIFIED,
            ambiguousFields = ambiguousFields,
            validationStatus = status,
            conflictMessage = conflictMessage,
            reasoningExplanation = reasoning.toString().trim(),
            needsUserClarification = needsClarification,
            clarificationQuestion = clarificationQuestion,
            quickSuggestions = suggestions,
            category = category,
            priority = priority,
            repeatType = repeat
        )
    }

    private fun checkRelativeDurationOffset(
        rawText: String,
        clean: String,
        referenceTimeMillis: Long,
        timeZone: TimeZone
    ): AnalyzedCommandResult? {
        var offsetMinutes: Long? = null
        var matchedPhrase = ""

        // Check for specific minute phrases
        if (clean.contains("یک ربع دیگر") || clean.contains("یه ربع دیگه") || clean.contains("یک ربع بعد") || clean.contains("یه ربع بعد")) {
            offsetMinutes = 15
            matchedPhrase = "یک ربع دیگر"
        } else if (clean.contains("نیم ساعت دیگر") || clean.contains("نیم ساعت دیگه") || clean.contains("نیم ساعت بعد")) {
            offsetMinutes = 30
            matchedPhrase = "نیم ساعت دیگر"
        }

        // Relative minutes regex: "X دقیقه دیگر/بعد/دیگه"
        if (offsetMinutes == null) {
            val minPattern = Regex("""(?:^|[\s،.\-_])(\d+|یک|یه|دو|سه|چهار|پنج|شش|شیش|هفت|هشت|نه|ده|پانزده|پونزده|بیست|سی|چهل|پنجاه)\s*دقیقه\s*(?:دیگه|بعد|دیگر)""")
            val minMatch = minPattern.find(clean)
            if (minMatch != null) {
                val word = minMatch.groupValues[1]
                val mins = when (word) {
                    "یک", "یه" -> 1L
                    "دو" -> 2L
                    "سه" -> 3L
                    "چهار" -> 4L
                    "پنج" -> 5L
                    "شش", "شیش" -> 6L
                    "هفت" -> 7L
                    "هشت" -> 8L
                    "نه" -> 9L
                    "ده" -> 10L
                    "پانزده", "پونزده" -> 15L
                    "بیست" -> 20L
                    "سی" -> 30L
                    "چهل" -> 40L
                    "پنجاه" -> 50L
                    else -> word.toLongOrNull() ?: 1L
                }
                offsetMinutes = mins
                matchedPhrase = minMatch.value.trim()
            }
        }

        // Relative hours regex: "X ساعت دیگر/بعد/دیگه"
        if (offsetMinutes == null) {
            val hourPattern = Regex("""(?:^|[\s،.\-_])(\d+|یک|یه|دو|سه|چهار|پنج|شش|شیش|هفت|هشت|نه|ده)\s*ساعت\s*(?:دیگه|بعد|دیگر)""")
            val hourMatch = hourPattern.find(clean)
            if (hourMatch != null) {
                val word = hourMatch.groupValues[1]
                val h = when (word) {
                    "یک", "یه" -> 1L
                    "دو" -> 2L
                    "سه" -> 3L
                    "چهار" -> 4L
                    "پنج" -> 5L
                    "شش", "شیش" -> 6L
                    "هفت" -> 7L
                    "هشت" -> 8L
                    "نه" -> 9L
                    "ده" -> 10L
                    else -> word.toLongOrNull() ?: 1L
                }
                offsetMinutes = h * 60
                matchedPhrase = hourMatch.value.trim()
            }
        }

        if (offsetMinutes != null && offsetMinutes > 0) {
            val target = referenceTimeMillis + offsetMinutes * 60 * 1000L
            val jalali = PersianDateEngine.timestampToJalali(target, timeZone)
            val weekday = PersianDateEngine.getPersianWeekdayName(target, timeZone)
            val title = extractCleanTitle(rawText, matchedDate = matchedPhrase, matchedTime = "")

            return AnalyzedCommandResult(
                rawText = rawText,
                title = if (title.isNotBlank()) title else "یادآوری $matchedPhrase",
                referenceTimeMillis = referenceTimeMillis,
                targetTimestamp = target,
                jalaliDateText = jalali.formatPersianNumeric(),
                calculatedWeekday = weekday,
                requestedWeekday = null,
                hour = jalali.hour,
                minute = jalali.minute,
                timeZoneId = timeZone.id,
                timeType = TimeType.RELATIVE,
                ambiguousFields = emptyList(),
                validationStatus = ValidationStatus.VALID,
                conflictMessage = null,
                reasoningExplanation = "محاسبه زمان نسبی دقیق ($matchedPhrase = +$offsetMinutes دقیقه از زمان مرجع).",
                needsUserClarification = title.isBlank(),
                clarificationQuestion = if (title.isBlank()) "زمان روی $matchedPhrase تنظیم شد؛ لطفاً موضوع کار را بفرمایید:" else null,
                quickSuggestions = if (title.isBlank()) listOf("مصرف دارو", "تماس تلفنی", "بررسی کارها") else emptyList()
            )
        }
        return null
    }

    private fun checkSameHourPhrases(
        rawText: String,
        clean: String,
        referenceTimeMillis: Long,
        timeZone: TimeZone
    ): AnalyzedCommandResult? {
        val isSameHour = clean.contains("همین موقع") || clean.contains("همین ساعت")
        if (!isSameHour) return null

        var dayOffset: Int? = null
        var phrase = ""
        when {
            clean.contains("پس اون فردا") || clean.contains("پس‌اون‌فردا") || clean.contains("3 روز دیگر") -> {
                dayOffset = 3
                phrase = "پس اون فردا همین موقع"
            }
            clean.contains("پس فردا") || clean.contains("پس‌فردا") || clean.contains("پسفردا") || clean.contains("2 روز دیگر") -> {
                dayOffset = 2
                phrase = "پس‌فردا همین موقع"
            }
            clean.contains("فردا") || clean.contains("1 روز دیگر") -> {
                dayOffset = 1
                phrase = "فردا همین موقع"
            }
            clean.contains("هفته بعد") || clean.contains("هفته آینده") || clean.contains("7 روز دیگر") -> {
                dayOffset = 7
                phrase = "هفته آینده همین موقع"
            }
        }

        if (dayOffset != null) {
            val cal = Calendar.getInstance(timeZone).apply {
                timeInMillis = referenceTimeMillis
                add(Calendar.DAY_OF_YEAR, dayOffset)
            }
            val target = cal.timeInMillis
            val jalali = PersianDateEngine.timestampToJalali(target, timeZone)
            val weekday = PersianDateEngine.getPersianWeekdayName(target, timeZone)
            val title = extractCleanTitle(rawText, matchedDate = phrase, matchedTime = "")

            return AnalyzedCommandResult(
                rawText = rawText,
                title = if (title.isNotBlank()) title else "یادآوری $phrase",
                referenceTimeMillis = referenceTimeMillis,
                targetTimestamp = target,
                jalaliDateText = jalali.formatPersianNumeric(),
                calculatedWeekday = weekday,
                requestedWeekday = null,
                hour = jalali.hour,
                minute = jalali.minute,
                timeZoneId = timeZone.id,
                timeType = TimeType.RELATIVE,
                ambiguousFields = emptyList(),
                validationStatus = ValidationStatus.VALID,
                conflictMessage = null,
                reasoningExplanation = "محاسبه بر اساس ساعت دقیق فعلی با جابجایی تقویمی $dayOffset روز ($phrase).",
                needsUserClarification = title.isBlank(),
                clarificationQuestion = if (title.isBlank()) "زمان روی $phrase تنظیم شد؛ لطفاً موضوع کار را بفرمایید:" else null,
                quickSuggestions = if (title.isBlank()) listOf("بررسی کارها", "جلسه هفتگی", "تماس") else emptyList()
            )
        }
        return null
    }

    private data class ExtractedClock(
        val rawHour: Int,
        val rawMinute: Int,
        val period: String?,
        val isPeriodSpecified: Boolean,
        val resolvedHour: Int?,
        val resolvedMinute: Int,
        val hasExplicitHour: Boolean,
        val matchedPhrase: String
    )

    private fun extractClockTime(clean: String): ExtractedClock {
        // Matches expressions like:
        // ساعت 6:30 عصر / ساعت 6 عصر / ساعت 6 و نیم عصر / ساعت 18:30 / 6 صبح / 7 عصر / ساعت 6
        val clockRegex = Regex("""(?:ساعت\s*)?(\d{1,2})(?::(\d{1,2}))?(?:\s*و\s*(نیم|ربع|15|30))?\s*(صبح|بامداد|ظهر|عصر|غروب|شب|بعدازظهر)?""")
        val match = clockRegex.find(clean)

        if (match != null && match.groupValues[1].isNotBlank()) {
            val rawH = match.groupValues[1].toIntOrNull() ?: 12
            var rawM = match.groupValues[2].toIntOrNull() ?: 0

            val fraction = match.groupValues[3].ifBlank { null }
            if (fraction != null) {
                if (fraction == "نیم" || fraction == "30") {
                    rawM = 30
                } else if (fraction == "ربع" || fraction == "15") {
                    rawM = 15
                }
            }

            val period = match.groupValues[4].ifBlank { null }
            val isPeriodSpecified = period != null

            // If hour is >= 13, it's explicitly 24-hour (e.g. 18, 14, 20)
            if (rawH in 13..23) {
                return ExtractedClock(
                    rawHour = rawH,
                    rawMinute = rawM,
                    period = "عصر",
                    isPeriodSpecified = true,
                    resolvedHour = rawH,
                    resolvedMinute = rawM,
                    hasExplicitHour = true,
                    matchedPhrase = match.value
                )
            }

            if (isPeriodSpecified) {
                var h = rawH
                when (period) {
                    "صبح", "بامداد" -> {
                        if (h == 12) h = 0
                    }
                    "ظهر" -> {
                        if (h < 12) h += 0
                    }
                    "بعدازظهر", "عصر", "غروب", "شب" -> {
                        if (h < 12) h += 12
                    }
                }
                return ExtractedClock(
                    rawHour = rawH,
                    rawMinute = rawM,
                    period = period,
                    isPeriodSpecified = true,
                    resolvedHour = h,
                    resolvedMinute = rawM,
                    hasExplicitHour = true,
                    matchedPhrase = match.value
                )
            }

            // Period is NOT specified.
            if (rawH == 12) {
                return ExtractedClock(
                    rawHour = 12,
                    rawMinute = rawM,
                    period = null,
                    isPeriodSpecified = false,
                    resolvedHour = 12,
                    resolvedMinute = rawM,
                    hasExplicitHour = true,
                    matchedPhrase = match.value
                )
            }

            // Ambiguous hour (e.g. ساعت ۶)
            return ExtractedClock(
                rawHour = rawH,
                rawMinute = rawM,
                period = null,
                isPeriodSpecified = false,
                resolvedHour = null,
                resolvedMinute = rawM,
                hasExplicitHour = true,
                matchedPhrase = match.value
            )
        }

        return ExtractedClock(
            rawHour = 0,
            rawMinute = 0,
            period = null,
            isPeriodSpecified = false,
            resolvedHour = null,
            resolvedMinute = 0,
            hasExplicitHour = false,
            matchedPhrase = ""
        )
    }

    private data class ExtractedDate(
        val hasExplicitDate: Boolean,
        val dayOffset: Int?,
        val exactJalaliYear: Int?,
        val exactJalaliMonth: Int?,
        val exactJalaliDay: Int?,
        val requestedWeekday: String?,
        val matchedPhrase: String,
        val isConflict: Boolean = false,
        val conflictMessage: String? = null
    )

    private fun extractDate(
        clean: String,
        referenceTimeMillis: Long,
        timeZone: TimeZone
    ): ExtractedDate {
        var dayOffset: Int? = null
        var exactY: Int? = null
        var exactM: Int? = null
        var exactD: Int? = null
        var reqWeekday: String? = null
        var phrase = ""

        // Relative calendar days: support digits and spoken Persian numbers
        // e.g. "3 روز دیگه", "سه روز بعد", "بیست و سه روز دیگر".
        val relativeDaysPattern = Regex(
            """(?:^|[^\\p{L}\\p{N}])((?:\\d{1,3}|یک|یه|دو|سه|چهار|پنج|شش|شیش|هفت|هشت|نه|ده|یازده|دوازده|سیزده|چهارده|پانزده|پونزده|شانزده|شونزده|هفده|هجده|نوزده|بیست|سی|چهل|پنجاه|شصت|هفتاد|هشتاد|نود|صد)(?:\\s*و\\s*(?:یک|یه|دو|سه|چهار|پنج|شش|شیش|هفت|هشت|نه|ده|یازده|دوازده|سیزده|چهارده|پانزده|پونزده|شانزده|شونزده|هفده|هجده|نوزده))?)\\s*روز\\s*(دیگه|بعد|دیگر)(?=$|[^\\p{L}\\p{N}])"""
        )
        val relativeDaysMatch = relativeDaysPattern.find(clean)
        val spokenDayOffset = relativeDaysMatch?.let {
            parsePersianNumber(it.groupValues[1])
        }?.takeIf { it in 1..365 }

        if (relativeDaysMatch != null && spokenDayOffset != null) {
            dayOffset = spokenDayOffset
            phrase = relativeDaysMatch.value.trim()
        } else if (clean.contains("پس اون فردا") || clean.contains("پس‌اون‌فردا")) {
            dayOffset = 3
            phrase = if (clean.contains("پس‌اون‌فردا")) "پس‌اون‌فردا" else "پس اون فردا"
        } else if (clean.contains("پس فردا") || clean.contains("پس‌فردا") || clean.contains("پسفردا")) {
            dayOffset = 2
            phrase = when {
                clean.contains("پس‌فردا") -> "پس‌فردا"
                clean.contains("پسفردا") -> "پسفردا"
                else -> "پس فردا"
            }
        } else if (clean.contains("فردا")) {
            dayOffset = 1
            phrase = "فردا"
        } else if (clean.contains("امروز") || clean.contains("امشب")) {
            dayOffset = 0
            phrase = if (clean.contains("امشب")) "امشب" else "امروز"
        }

        // 5. Weekdays matching ordered by length descending to prevent "شنبه" matching inside compound words
        val orderedWeekdays = listOf(
            "چهارشنبه",
            "پنجشنبه",
            "پنج‌شنبه",
            "سه‌شنبه",
            "سه شنبه",
            "یکشنبه",
            "دوشنبه",
            "جمعه",
            "شنبه"
        )

        for (w in orderedWeekdays) {
            if (clean.contains(w)) {
                val canonicalWd = normalizeWeekday(w)
                reqWeekday = canonicalWd

                val isNextWeek = clean.contains("هفته بعد") || clean.contains("هفته آینده") || clean.contains("آینده")
                val days = calculateDaysUntilWeekday(canonicalWd, referenceTimeMillis, isNextWeek, timeZone)

                if (dayOffset == null) {
                    dayOffset = days
                    phrase = if (isNextWeek) "$canonicalWd هفته بعد" else canonicalWd
                } else {
                    // Contradiction check: user specified both relative date and a weekday (e.g. "فردا که جمعه است")
                    val cal = Calendar.getInstance(timeZone).apply {
                        timeInMillis = referenceTimeMillis
                        add(Calendar.DAY_OF_YEAR, dayOffset)
                    }
                    val actualWd = PersianDateEngine.getPersianWeekdayName(cal.timeInMillis, timeZone)
                    if (normalizeWeekday(actualWd) != canonicalWd) {
                        return ExtractedDate(
                            hasExplicitDate = true,
                            dayOffset = null,
                            exactJalaliYear = null,
                            exactJalaliMonth = null,
                            exactJalaliDay = null,
                            requestedWeekday = canonicalWd,
                            matchedPhrase = "$phrase و $w",
                            isConflict = true,
                            conflictMessage = "تناقض زمانی: تاریخ «$phrase» برابر با روز «$actualWd» است، نه «$canonicalWd»."
                        )
                    }
                }
                break // Stop on the first and longest matching weekday!
            }
        }

        // 6. Explicit Day and Month (e.g. "15 بهمن", "22 بهمن", "1 فروردین")
        PersianDateEngine.MONTH_NAMES.forEachIndexed { idx, mName ->
            val monthRegex = Regex("""(\d{1,2})\s*""" + mName)
            monthRegex.find(clean)?.let { m ->
                exactD = m.groupValues[1].toIntOrNull()
                exactM = idx + 1
                val curJalali = PersianDateEngine.timestampToJalali(referenceTimeMillis, timeZone)
                val isNextYear = clean.contains("سال بعد") || clean.contains("سال آینده")
                exactY = if (isNextYear) curJalali.year + 1 else curJalali.year
                phrase = m.value

                // If weekday was also specified, check for contradiction with this calendar date!
                if (reqWeekday != null && exactD != null && exactM != null && exactY != null) {
                    val ts = PersianDateEngine.jalaliToTimestamp(exactY!!, exactM!!, exactD!!, 12, 0, timeZone)
                    val realWeekday = PersianDateEngine.getPersianWeekdayName(ts, timeZone)
                    if (normalizeWeekday(realWeekday) != reqWeekday) {
                        return ExtractedDate(
                            hasExplicitDate = true,
                            dayOffset = null,
                            exactJalaliYear = null,
                            exactJalaliMonth = null,
                            exactJalaliDay = null,
                            requestedWeekday = reqWeekday,
                            matchedPhrase = "$phrase و $reqWeekday",
                            isConflict = true,
                            conflictMessage = "تاریخ «$phrase» در تقویم مصادف با «$realWeekday» است، نه «$reqWeekday»."
                        )
                    }
                }
            }
        }

        return ExtractedDate(
            hasExplicitDate = dayOffset != null || (exactY != null && exactM != null && exactD != null),
            dayOffset = dayOffset,
            exactJalaliYear = exactY,
            exactJalaliMonth = exactM,
            exactJalaliDay = exactD,
            requestedWeekday = reqWeekday,
            matchedPhrase = phrase
        )
    }

    private fun parsePersianNumber(raw: String): Int? {
        val normalized = PersianDateEngine.toEnglishDigits(raw).trim()
        normalized.toIntOrNull()?.let { return it }

        val values = mapOf(
            "یک" to 1, "یه" to 1, "دو" to 2, "سه" to 3, "چهار" to 4,
            "پنج" to 5, "شش" to 6, "شیش" to 6, "هفت" to 7, "هشت" to 8,
            "نه" to 9, "ده" to 10, "یازده" to 11, "دوازده" to 12,
            "سیزده" to 13, "چهارده" to 14, "پانزده" to 15, "پونزده" to 15,
            "شانزده" to 16, "شونزده" to 16, "هفده" to 17, "هجده" to 18,
            "نوزده" to 19, "بیست" to 20, "سی" to 30, "چهل" to 40,
            "پنجاه" to 50, "شصت" to 60, "هفتاد" to 70, "هشتاد" to 80,
            "نود" to 90, "صد" to 100
        )
        val parts = normalized.split(Regex("""\\s*و\\s*""")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        var total = 0
        for (part in parts) {
            total += values[part] ?: return null
        }
        return total
    }

    private fun normalizeWeekday(weekday: String): String {
        return when (weekday.trim()) {
            "سه‌شنبه", "سه شنبه", "سه_شنبه" -> "سه‌شنبه"
            "پنجشنبه", "پنج‌شنبه", "پنج_شنبه" -> "پنجشنبه"
            "یکشنبه", "یک شنبه" -> "یکشنبه"
            "دوشنبه", "دو شنبه" -> "دوشنبه"
            "چهارشنبه", "چهار شنبه" -> "چهارشنبه"
            "جمعه" -> "جمعه"
            "شنبه" -> "شنبه"
            else -> weekday.trim()
        }
    }

    private fun calculateDaysUntilWeekday(
        canonicalWeekday: String,
        referenceTimeMillis: Long,
        isNextWeek: Boolean,
        timeZone: TimeZone
    ): Int {
        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = referenceTimeMillis }
        val currentDayOfWeek = cal.get(Calendar.DAY_OF_WEEK)

        val targetCalendarDay = when (canonicalWeekday) {
            "شنبه" -> Calendar.SATURDAY
            "یکشنبه" -> Calendar.SUNDAY
            "دوشنبه" -> Calendar.MONDAY
            "سه‌شنبه" -> Calendar.TUESDAY
            "چهارشنبه" -> Calendar.WEDNESDAY
            "پنجشنبه" -> Calendar.THURSDAY
            "جمعه" -> Calendar.FRIDAY
            else -> -1
        }
        if (targetCalendarDay == -1) return 0

        var daysToAdd = (targetCalendarDay - currentDayOfWeek + 7) % 7
        if (daysToAdd == 0) {
            daysToAdd = if (isNextWeek) 7 else 7
        } else if (isNextWeek) {
            daysToAdd += 7
        }
        return daysToAdd
    }

    private fun extractCleanTitle(rawText: String, matchedDate: String, matchedTime: String): String {
        var t = PersianDateEngine.toEnglishDigits(rawText)

        if (matchedDate.isNotBlank()) {
            t = t.replace(PersianDateEngine.toEnglishDigits(matchedDate), " ")
        }
        if (matchedTime.isNotBlank()) {
            t = t.replace(PersianDateEngine.toEnglishDigits(matchedTime), " ")
        }

        // Remove conversational time words and weekdays
        t = t.replace(Regex("""(?:امروز|فردا|پس‌?فردا|پسفردا|پس\s+اون\s+فردا|همین\s+موقع|هفته\s+(?:بعد|آینده)|سال\s+(?:بعد|آینده))"""), " ")
        t = t.replace(Regex("""(?:چهارشنبه|پنج‌?شنبه|سه‌?شنبه|سه\s+شنبه|یکشنبه|دوشنبه|جمعه|شنبه)"""), " ")
        t = t.replace(Regex("""ساعت\s*[\d۰-۹]{1,2}(?::[\d۰-۹]{1,2})?\s*(?:صبح|عصر|شب|ظهر|بامداد|بعدازظهر)?"""), " ")
        t = t.replace(Regex("""[\d۰-۹]{1,2}\s*(?:صبح|عصر|شب|ظهر|بامداد|بعدازظهر)"""), " ")
        t = t.replace(Regex("""[\d۰-۹]+\s*(?:دقیقه|ساعت|روز|هفته|ماه)\s*(?:دیگه|بعد|دیگر)"""), " ")

        // Remove conversational prefixes and commands
        t = t.replace(Regex("""^(?:لطفاً|لطفا|لطفاً\s+به\s+من|به\s+من|بهم|برای)\s*"""), " ")
        t = t.replace(Regex("""(?:یادآوری\s+کن|یادم\s+بنداز|یادم\s+باشه|تنظیم\s+کن|ثبت\s+کن|یادآوری\s+بگذار|قرار\s+بده)"""), " ")
        t = t.replace(Regex("""^(?:که|تا|رو|را)\s*"""), " ")

        // Clean extra particles
        t = t.replace(Regex("""\s+را\s*$"""), " ")
        t = t.replace(Regex("""\s+رو\s*$"""), " ")
        t = t.replace(Regex("""\s+دارم\s*$"""), " ")
        t = t.replace(Regex("""\s+کنم\s*$"""), " ")
        t = t.replace(Regex("""\s+بگیرم\s*$"""), " ")
        t = t.replace(Regex("""\s+بخوانم\s*$"""), " ")

        t = t.trim().trim('،', ' ', '.', ':', '-')
        return t
    }

    fun normalizeText(input: String): String {
        var s = input
            .replace("\u200C", "‌")
            .replace("ي", "ی")
            .replace("ك", "ک")

        // Weekday forms
        s = s.replace("سه شنبه", "سه‌شنبه")
            .replace("سه_شنبه", "سه‌شنبه")
            .replace("پنج شنبه", "پنجشنبه")
            .replace("پنج‌شنبه", "پنجشنبه")
            .replace("پنج_شنبه", "پنجشنبه")
            .replace("یک شنبه", "یکشنبه")
            .replace("دو شنبه", "دوشنبه")
            .replace("چهار شنبه", "چهارشنبه")

        // Relative duration normalization
        s = s.replace("یک ربع", "15 دقیقه")
            .replace("یه ربع", "15 دقیقه")
            .replace("نیم ساعت", "30 دقیقه")
            .replace("ده دقیقه", "10 دقیقه")
            .replace("بیست دقیقه", "20 دقیقه")

        // Written clock hours
        s = s.replace("ساعت بیست و چهار", "ساعت 24")
            .replace("ساعت بیست و سه", "ساعت 23")
            .replace("ساعت بیست و دو", "ساعت 22")
            .replace("ساعت بیست و یک", "ساعت 21")
            .replace("ساعت بیست", "ساعت 20")
            .replace("ساعت نوزده", "ساعت 19")
            .replace("ساعت هجده", "ساعت 18")
            .replace("ساعت هفده", "ساعت 17")
            .replace("ساعت شانزده", "ساعت 16")
            .replace("ساعت شونزده", "ساعت 16")
            .replace("ساعت پانزده", "ساعت 15")
            .replace("ساعت پونزده", "ساعت 15")
            .replace("ساعت چهارده", "ساعت 14")
            .replace("ساعت سیزده", "ساعت 13")
            .replace("ساعت دوازده", "ساعت 12")
            .replace("ساعت یازده", "ساعت 11")
            .replace("ساعت ده", "ساعت 10")
            .replace("ساعت نه", "ساعت 9")
            .replace("ساعت هشت", "ساعت 8")
            .replace("ساعت هفت", "ساعت 7")
            .replace("ساعت شش", "ساعت 6")
            .replace("ساعت شیش", "ساعت 6")
            .replace("ساعت پنج", "ساعت 5")
            .replace("ساعت چهار", "ساعت 4")
            .replace("ساعت سه", "ساعت 3")
            .replace("ساعت دو", "ساعت 2")
            .replace("ساعت یک", "ساعت 1")
            .replace("ساعت یه", "ساعت 1")

        // Unprefixed written hours
        s = s.replace("دوازده ظهر", "ساعت 12 ظهر")
            .replace("یازده شب", "ساعت 11 شب")
            .replace("ده شب", "ساعت 10 شب")
            .replace("نه شب", "ساعت 9 شب")
            .replace("هشت شب", "ساعت 8 شب")
            .replace("هفت شب", "ساعت 7 شب")
            .replace("شش عصر", "ساعت 6 عصر")
            .replace("شیش عصر", "ساعت 6 عصر")
            .replace("پنج عصر", "ساعت 5 عصر")
            .replace("چهار عصر", "ساعت 4 عصر")
            .replace("سه عصر", "ساعت 3 عصر")
            .replace("دو ظهر", "ساعت 2 ظهر")
            .replace("یک ظهر", "ساعت 1 ظهر")
            .replace("دوازده شب", "ساعت 12 شب")
            .replace("یازده صبح", "ساعت 11 صبح")
            .replace("ده صبح", "ساعت 10 صبح")
            .replace("نه صبح", "ساعت 9 صبح")
            .replace("هشت صبح", "ساعت 8 صبح")
            .replace("هفت صبح", "ساعت 7 صبح")
            .replace("شش صبح", "ساعت 6 صبح")
            .replace("شیش صبح", "ساعت 6 صبح")
            .replace("پنج صبح", "ساعت 5 صبح")
            .replace("چهار صبح", "ساعت 4 صبح")
            .replace("شیش عصر", "ساعت 6 عصر")
            .replace("شش صبح", "ساعت 6 صبح")
            .replace("شیش صبح", "ساعت 6 صبح")
            .replace("پنج عصر", "ساعت 5 عصر")
            .replace("چهار عصر", "ساعت 4 عصر")
            .replace("سه عصر", "ساعت 3 عصر")
            .replace("دو ظهر", "ساعت 2 ظهر")
            .replace("یک ظهر", "ساعت 1 ظهر")
            .replace("نه صبح", "ساعت 9 صبح")
            .replace("هشت صبح", "ساعت 8 صبح")
            .replace("هفت صبح", "ساعت 7 صبح")

        return s
    }

    private fun emptyResult(rawText: String, referenceTimeMillis: Long, timeZone: TimeZone): AnalyzedCommandResult {
        return AnalyzedCommandResult(
            rawText = rawText,
            title = "",
            referenceTimeMillis = referenceTimeMillis,
            targetTimestamp = null,
            jalaliDateText = null,
            calculatedWeekday = null,
            requestedWeekday = null,
            hour = null,
            minute = null,
            timeZoneId = timeZone.id,
            timeType = TimeType.UNSPECIFIED,
            ambiguousFields = listOf("EMPTY_INPUT"),
            validationStatus = ValidationStatus.INSUFFICIENT_DATA,
            conflictMessage = null,
            reasoningExplanation = "ورودی خالی است.",
            needsUserClarification = true,
            clarificationQuestion = "فرمان یا یادآوری خود را بفرمایید.",
            quickSuggestions = emptyList()
        )
    }
}
