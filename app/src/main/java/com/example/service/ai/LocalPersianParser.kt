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

        // First split explicit separators, then split only later independent clock cues.
        // This avoids treating the first "از ساعت..." as a new task or splitting "تا ساعت ۸" ranges.
        val separatedSegments = clean.split(
            Regex("""(?<=[،\n])|\s+و\s+(?:همچنین\s+)?(?=(?:پس[\s‌_]?اون[\s‌_]?فردا|پس[\s‌]?فردا|پسفردا|فردا|امروز|سال آینده|پنج دقیقه|یک ربع|نیم ساعت|هر|ساعت))""")
        ).map { it.trim().trim('،', ' ', '.') }.filter { it.isNotBlank() }
        val segments = separatedSegments.flatMap { splitAtSubsequentClockTimes(it) }

        val tasks = mutableListOf<ParsedTask>()
        var inheritedDateContext: String? = null
        val dateContextPattern = Regex("""(?:پس[\s‌]?فردا|فردا|امروز|هفته\s+(?:بعد|آینده)|چهارشنبه|پنجشنبه|پنج‌شنبه|سه‌شنبه|سه\s+شنبه|یکشنبه|دوشنبه|جمعه|شنبه|[\d۰-۹]{1,2}\s*(?:فروردین|اردیبهشت|خرداد|تیر|مرداد|شهریور|مهر|آبان|آذر|دی|بهمن|اسفند))""")
        for (segment in segments) {
            val initial = parseSingleCommand(segment, referenceTimeMillis)
            val effectiveSegment = if (!initial.hasExplicitDate && initial.hasExplicitTime && inheritedDateContext != null) {
                "${inheritedDateContext} $segment"
            } else {
                segment
            }
            val task = if (effectiveSegment == segment) initial else parseSingleCommand(effectiveSegment, referenceTimeMillis)
            tasks.add(task)
            val dateSearchText = PersianCommandAnalyzer.normalizeText(segment)
                .replace(Regex("""(?:به\s*جز|به‌جز|بجز|غیر\s*از)\s*(شنبه|یکشنبه|دوشنبه|سه‌شنبه|چهارشنبه|پنجشنبه|جمعه)(?:‌?ها)?"""), " ")
            val dateMatch = dateContextPattern.find(dateSearchText)
            if (dateMatch != null) inheritedDateContext = dateMatch.value
        }
        return tasks
    }


    private fun splitAtSubsequentClockTimes(segment: String): List<String> {
        // Recognize both explicit "ساعت ۱۲" and colloquial "۱۲ ظهر/عصر/صبح/شب".
        // The second clock cue starts a new task even when "ساعت" is omitted.
        val clockPattern = Regex("""(?:ساعت\s*)?[0-9۰-۹]{1,2}(?::[0-9۰-۹]{2})?(?:\s*(?:صبح|ظهر|عصر|شب))?""")
        val matches = clockPattern.findAll(segment).filter { match ->
            val token = match.value.trim()
            token.any { it.isDigit() } && (token.contains("ساعت") || token.contains("صبح") ||
                token.contains("ظهر") || token.contains("عصر") || token.contains("شب"))
        }.toList()
        if (matches.size < 2) return listOf(segment)

        val boundaries = matches.drop(1).filter { match ->
            val prefix = segment.substring(0, match.range.first).trimEnd()
            !prefix.endsWith("تا") && !prefix.endsWith("الی")
        }.map { it.range.first }

        if (boundaries.isEmpty()) return listOf(segment)

        val result = mutableListOf<String>()
        var start = 0
        for (boundary in boundaries) {
            result.add(segment.substring(start, boundary).trim())
            start = boundary
        }
        result.add(segment.substring(start).trim())
        return result.filter { it.isNotBlank() }
    }

    private fun hasExplicitDatePhrase(input: String): Boolean {
        val normalized = PersianCommandAnalyzer.normalizeText(input)
        val withoutExcludedDays = normalized.replace(
            Regex("""(?:به\s*جز|به‌جز|بجز|غیر\s*از)\s*(شنبه|یکشنبه|دوشنبه|سه‌شنبه|چهارشنبه|پنجشنبه|جمعه)(?:‌?ها)?"""),
            " "
        )
        val datePattern = Regex("""(?:امروز|امشب|فردا|پس[\s‌]?فردا|پس\s+اون\s+فردا|هفته\s+(?:بعد|آینده)|چهارشنبه|پنجشنبه|پنج‌شنبه|سه‌شنبه|سه\s+شنبه|یکشنبه|دوشنبه|جمعه|شنبه|[\d۰-۹]{1,2}\s*(?:فروردین|اردیبهشت|خرداد|تیر|مرداد|شهریور|مهر|آبان|آذر|دی|بهمن|اسفند))""")
        return datePattern.containsMatchIn(withoutExcludedDays)
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
        val hasExplicitDate = hasExplicitDatePhrase(rawSegment)
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
            recurrenceDaysOfWeek = analysis.recurrenceDaysOfWeek,
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
