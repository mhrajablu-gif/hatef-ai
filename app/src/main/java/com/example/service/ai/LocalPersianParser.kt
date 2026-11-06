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

        // Handle mind-dump / multi-task separated by "و همچنین", "سپس", "بعدش", or "،"
        val segments = clean.split(
            Regex("""(?<=[،\n])|\s+و\s+(?:همچنین\s+)?(?=(?:پس[\s‌_]?اون[\s‌_]?فردا|پس[\s‌_]?فردا|پسفردا|فردا|امروز|سال آینده|پنج دقیقه|یک ربع|نیم ساعت|هر|ساعت))""")
        ).map { it.trim().trim('،', ' ', '.') }.filter { it.isNotBlank() }

        val tasks = mutableListOf<ParsedTask>()
        for (segment in segments) {
            tasks.add(parseSingleCommand(segment, referenceTimeMillis))
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

        val hasExplicitTime = analysis.hour != null
        val hasExplicitDate = analysis.jalaliDateText != null
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
            isAmbiguous = analysis.needsUserClarification,
            clarificationQuestion = analysis.clarificationQuestion,
            quickSuggestions = analysis.quickSuggestions,
            analyzedResult = analysis
        )
    }
}
