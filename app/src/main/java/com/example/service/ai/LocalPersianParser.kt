package com.example.service.ai

import com.example.data.engine.PersianDateEngine

/**
 * 100% Offline, Zero-Cost Rule-Based Persian Semantic Parser.
 * High-speed local NLP engine for extracting tasks, dates, hours, priorities,
 * repetition, and detecting ambiguities without any paid cloud API calls.
 */
object LocalPersianParser {

    fun parse(text: String, referenceTimeMillis: Long = System.currentTimeMillis()): List<ParsedTask> {
        val clean = text.trim()
        if (clean.isBlank()) return emptyList()

        // Split only at strong task boundaries. Accept common Persian connectors even
        // when the next task begins with "برای" (e.g. "و همچنین برای پس‌فردا ...").
        // Ordinary conjunctions inside a task are deliberately preserved.
        // A second independent action may omit its own date/time. Split on "و" only
        // when the following words form a new action, not when they are merely a list
        // of people or objects (for example, "با علی و رضا جلسه داشته باشم").
        val nextTaskStart = """(?:برای\s+)?(?:پس[\s‌_]?اون[\s‌_]?فردا|پس[\s‌_]?فردا|پسفردا|فردا|امروز|سال\s+آینده|[0-9۰-۹]+\s*روز|پنج\s+دقیقه|یک\s+ربع|نیم\s+ساعت|هر|ساعت|(?:به\s+[\p{L}\p{M}‌_-]+(?:\s+[\p{L}\p{M}‌_-]+){0,2}\s+(?:زنگ\s+بزنم|تماس\s+بگیرم|مراجعه\s+کنم|سر\s+بزنم|برم|بروم|وقت\s+بگیرم|نوبت\s+بگیرم))|(?:برم|بروم)\s+[\p{L}\p{M}‌_-]+|(?:زنگ\s+بزنم|تماس\s+بگیرم|خرید\s+کنم|بخرم|پرداخت\s+کنم|واریز\s+کنم|ارسال\s+کنم|تحویل\s+بدم|تحویل\s+دهم|پیگیری\s+کنم|دارو\s+بخورم|قرص\s+بخورم|مطالعه\s+کنم|کتاب\s+بخونم|ورزش\s+کنم|تمرین\s+کنم))"""
        // Users may omit both the conjunction and the word "ساعت":
        // "فردا ۹ صبح نان بخرم ۱۲ ظهر گوشت بخرم".
        // Split only when the later clock is followed by a concrete action phrase.
        val nextTimedTaskStart = """(?:ساعت\s*)?[0-9۰-۹]{1,2}(?:[:：∶﹕٫][0-9۰-۹]{1,2})?\s*(?:صبح|بامداد|ظهر|بعدازظهر|بعد\s+از\s+ظهر|عصر|غروب|شب)?\s+(?=(?:(?:برم|بروم)\s+[\p{L}\p{M}‌_-]+(?:\s+[\p{L}\p{M}‌_-]+){0,3}|(?:زنگ\s+بزنم|تماس\s+بگیرم|خرید\s+کنم|پرداخت\s+کنم|واریز\s+کنم|ارسال\s+کنم|تحویل\s+بدم|تحویل\s+دهم|پیگیری\s+کنم|مراجعه\s+کنم|سر\s+بزنم|دارو\s+بخورم|قرص\s+بخورم|مطالعه\s+کنم|کتاب\s+بخونم|ورزش\s+کنم|تمرین\s+کنم)|[\p{L}\p{M}‌_-]+(?:\s+[\p{L}\p{M}‌_-]+){0,5}\s+(?:بخرم|بخریم|برم|بروم|زنگ\s+بزنم|تماس\s+بگیرم|پرداخت\s+کنم|واریز\s+کنم|ارسال\s+کنم|تحویل\s+بدم|تحویل\s+دهم|پیگیری\s+کنم|مراجعه\s+کنم|سر\s+بزنم|دارو\s+بخورم|قرص\s+بخورم|مطالعه\s+کنم|کتاب\s+بخونم|ورزش\s+کنم|تمرین\s+کنم)))"""
        // First split only on strong linguistic boundaries. Do not split at the
        // first clock in a sentence: it belongs to the first task.
        val strongSegments = clean.split(
            Regex(
                """(?<=[،؛\n.!؟])|\s+و\s+(?:(?:همچنین|بعدش)\s+)?(?=$nextTaskStart)|\s+(?:سپس|بعدش)\s+(?=$nextTaskStart)"""
            )
        )

        // Then split only subsequent clock+action pairs within each segment.
        // This supports "فردا ساعت ۱۴ برم خانه ساعت ۱۶ برم باشگاه" without
        // accidentally separating the first date/time from its task.
        val timedTaskRegex = Regex(nextTimedTaskStart)
        val segments = strongSegments.flatMap { segment ->
            val subsequentStarts = timedTaskRegex.findAll(segment)
                .drop(1)
                .map { it.range.first }
                .toList()
            if (subsequentStarts.isEmpty()) {
                listOf(segment)
            } else {
                val pieces = mutableListOf<String>()
                var cursor = 0
                subsequentStarts.forEach { start ->
                    if (start > cursor) pieces.add(segment.substring(cursor, start))
                    cursor = start
                }
                if (cursor < segment.length) pieces.add(segment.substring(cursor))
                pieces
            }
        }.map { it.trim().removePrefix("سپس").removePrefix("بعدش").trim().trim('،', '؛', ' ', '.') }
            .filter { it.isNotBlank() }

        val tasks = mutableListOf<ParsedTask>()
        var inheritedDateText: String? = null
        for (segment in segments) {
            val parsed = parseSingleCommand(segment, referenceTimeMillis)
            val task = if (!parsed.hasExplicitDate && parsed.hasExplicitTime && inheritedDateText != null) {
                // In natural lists, users commonly state the date once and then give
                // additional times ("tomorrow 9 bank and 12 doctor"). Carry the date
                // forward only to later items that have their own explicit time.
                val dateParts = PersianDateEngine.toEnglishDigits(inheritedDateText)
                    .split("/", "-", ".")
                    .mapNotNull { it.toIntOrNull() }
                val timeParts = PersianDateEngine.toEnglishDigits(parsed.timeText)
                    .split(":")
                    .mapNotNull { it.toIntOrNull() }
                if (dateParts.size == 3 && timeParts.size >= 2) {
                    val timestamp = PersianDateEngine.jalaliToTimestamp(
                        dateParts[0], dateParts[1], dateParts[2], timeParts[0], timeParts[1]
                    )
                    parsed.copy(
                        triggerTimestamp = timestamp,
                        jalaliDateText = inheritedDateText,
                        timeText = PersianDateEngine.formatPersianTime(timeParts[0], timeParts[1]),
                        hasExplicitDate = true
                    )
                } else parsed
            } else parsed
            if (task.hasExplicitDate) inheritedDateText = task.jalaliDateText
            tasks.add(task)
        }
        return tasks
    }

    fun parseSingleCommand(rawSegment: String, referenceTimeMillis: Long = System.currentTimeMillis()): ParsedTask {
        val analysis = PersianCommandAnalyzer.analyze(rawSegment, referenceTimeMillis)

        val triggerTimestamp = analysis.targetTimestamp ?: (referenceTimeMillis + 3600 * 1000L)
        val jDate = PersianDateEngine.timestampToJalali(triggerTimestamp)
        val timeText = if (analysis.hour != null && analysis.minute != null) {
            PersianDateEngine.formatPersianTime(analysis.hour, analysis.minute)
        } else {
            PersianDateEngine.formatPersianTime(jDate.hour, jDate.minute)
        }
        val dateText = analysis.jalaliDateText ?: jDate.formatPersianNumeric()

        val hasRelativeDateTime = rawSegment.contains("دیگه") || rawSegment.contains("بعد") || rawSegment.contains("دیگر")
        val hasRelativeDuration = hasRelativeDateTime &&
            (rawSegment.contains("دقیقه") || rawSegment.contains("ساعت"))
        val hasExplicitTime = analysis.hasExplicitTime || hasRelativeDuration
        val hasExplicitDate = analysis.hasExplicitDate || hasRelativeDuration
        val hasExplicitTitle = analysis.title.isNotBlank() && !analysis.title.startsWith("یادآوری ساعت")

        return ParsedTask(
            title = analysis.title,
            description = rawSegment,
            rawTimePhrase = rawSegment,
            triggerTimestamp = triggerTimestamp,
            jalaliDateText = dateText,
            timeText = timeText,
            category = analysis.category,
            priority = analysis.priority,
            repeatType = analysis.repeatType,
            advanceAlertMinutes = 0,
            hasExplicitTime = hasExplicitTime,
            hasExplicitDate = hasExplicitDate,
            hasExplicitTitle = hasExplicitTitle,
            // Only an actual AM/PM ambiguity belongs in this flag.
            // Missing title/date/time is handled by the corresponding slot checks.
            isAmbiguous = analysis.validationStatus == ValidationStatus.AMBIGUOUS,
            clarificationQuestion = analysis.clarificationQuestion,
            quickSuggestions = analysis.quickSuggestions,
            analyzedResult = analysis
        )
    }
}
