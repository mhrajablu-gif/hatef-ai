package com.example.data.engine

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * High-precision standalone Jalali (Solar Hijri) calendar calculator using
 * exact Julian Day Number (JDN) algorithms, Persian date formatter,
 * and Persian relative time parsing engine.
 */
object PersianDateEngine {

    val MONTH_NAMES = listOf(
        "فروردین", "اردیبهشت", "خرداد",
        "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر",
        "دی", "بهمن", "اسفند"
    )

    val WEEKDAY_NAMES = listOf(
        "شنبه", "یکشنبه", "دوشنبه",
        "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه"
    )

    data class JalaliDate(
        val year: Int,
        val month: Int, // 1..12
        val day: Int,   // 1..31
        val hour: Int = 0,
        val minute: Int = 0
    ) {
        val monthName: String get() = MONTH_NAMES.getOrElse(month - 1) { "" }

        fun formatFullDate(): String {
            return "${toPersianDigits(year)} $monthName ${toPersianDigits(day)}"
        }

        fun formatNumeric(): String {
            return String.format(Locale.ROOT, "%04d/%02d/%02d", year, month, day)
        }

        fun formatPersianNumeric(): String {
            return toPersianDigits(formatNumeric())
        }
    }

    data class HolidayInfo(
        val title: String,
        val isOfficialHoliday: Boolean
    )

    // Key Iranian official national holidays (Solar-based)
    private val SOLAR_HOLIDAYS = mapOf(
        "01-01" to HolidayInfo("جشن نوروز / سال نو", true),
        "01-02" to HolidayInfo("عید نوروز", true),
        "01-03" to HolidayInfo("عید نوروز", true),
        "01-04" to HolidayInfo("عید نوروز", true),
        "01-12" to HolidayInfo("روز جمهوری اسلامی ایران", true),
        "01-13" to HolidayInfo("روز طبیعت (سیزده‌بدر)", true),
        "03-14" to HolidayInfo("رحلت امام خمینی", true),
        "03-15" to HolidayInfo("قیام ۱۵ خرداد", true),
        "11-22" to HolidayInfo("پیروزی انقلاب اسلامی", true),
        "12-29" to HolidayInfo("روز ملی شدن صنعت نفت", true)
    )

    fun getHoliday(month: Int, day: Int): HolidayInfo? {
        val key = String.format(Locale.ROOT, "%02d-%02d", month, day)
        return SOLAR_HOLIDAYS[key]
    }

    fun toPersianDigits(input: Any): String {
        val text = input.toString()
        val builder = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                in '0'..'9' -> builder.append((ch.code - '0'.code + 0x06F0).toChar())
                else -> builder.append(ch)
            }
        }
        return builder.toString()
    }

    fun toEnglishDigits(input: String): String {
        val builder = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                in '۰'..'۹' -> builder.append((ch.code - 0x06F0).toString())
                in '٠'..'٩' -> builder.append((ch.code - 0x0660).toString())
                else -> builder.append(ch)
            }
        }
        return builder.toString()
    }

    fun gregorianToJdn(gYear: Int, gMonth: Int, gDay: Int): Long {
        val a = (14 - gMonth) / 12
        val y = gYear + 4800 - a
        val m = gMonth + 12 * a - 3
        return gDay + (153 * m + 2) / 5 + 365L * y + y / 4 - y / 100 + y / 400 - 32045
    }

    fun jdnToGregorian(jdn: Long): Triple<Int, Int, Int> {
        val a = jdn + 32044
        val b = (4 * a + 3) / 146097
        val c = a - (146097 * b) / 4
        val d = (4 * c + 3) / 1461
        val e = c - (1461 * d) / 4
        val m = (5 * e + 2) / 153
        val day = (e - (153 * m + 2) / 5 + 1).toInt()
        val month = (m + 3 - 12 * (m / 10)).toInt()
        val year = (100 * b + d - 4800 + m / 10).toInt()
        return Triple(year, month, day)
    }

    fun isJalaliLeapYear(year: Int): Boolean {
        val rem = ((year % 33) + 33) % 33
        return rem in intArrayOf(1, 5, 9, 13, 17, 22, 26, 30)
    }

    private fun leapYearsBefore(year: Int): Long {
        val prev = year - 1L
        val cycles = prev / 33
        val rem = ((prev % 33) + 33).toInt() % 33
        val leapsInCycle = intArrayOf(1, 5, 9, 13, 17, 22, 26, 30).count { it <= rem }
        return cycles * 8 + leapsInCycle
    }

    fun jalaliToJdn(jYear: Int, jMonth: Int, jDay: Int): Long {
        val daysFromEpoch = (jYear - 1L) * 365L + leapYearsBefore(jYear)
        val dayOfYear = (if (jMonth <= 7) (jMonth - 1) * 31 else 186 + (jMonth - 7) * 30) + jDay - 1
        return 1948320L + daysFromEpoch + dayOfYear
    }

    fun jdnToJalali(jdn: Long): Triple<Int, Int, Int> {
        val days = jdn - 1948320L
        var year = (days / 365.2424).toInt() + 1
        while (jalaliToJdn(year + 1, 1, 1) <= jdn) {
            year++
        }
        while (jalaliToJdn(year, 1, 1) > jdn) {
            year--
        }
        val dayOfYear = (jdn - jalaliToJdn(year, 1, 1)).toInt()
        val month: Int
        val day: Int
        if (dayOfYear < 186) {
            month = dayOfYear / 31 + 1
            day = dayOfYear % 31 + 1
        } else {
            val remaining = dayOfYear - 186
            month = 7 + remaining / 30
            day = remaining % 30 + 1
        }
        return Triple(year, month, day)
    }

    fun gregorianToJalali(gYear: Int, gMonth: Int, gDay: Int): Triple<Int, Int, Int> {
        val jdn = gregorianToJdn(gYear, gMonth, gDay)
        return jdnToJalali(jdn)
    }

    fun jalaliToGregorian(jYear: Int, jMonth: Int, jDay: Int): Triple<Int, Int, Int> {
        val jdn = jalaliToJdn(jYear, jMonth, jDay)
        return jdnToGregorian(jdn)
    }

    fun getDaysInMonth(year: Int, month: Int): Int {
        return when {
            month in 1..6 -> 31
            month in 7..11 -> 30
            month == 12 -> if (isJalaliLeapYear(year)) 30 else 29
            else -> 30
        }
    }

    fun timestampToJalali(timestampMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): JalaliDate {
        val cal = Calendar.getInstance(timeZone).apply {
            timeInMillis = timestampMillis
        }
        val gYear = cal.get(Calendar.YEAR)
        val gMonth = cal.get(Calendar.MONTH) + 1
        val gDay = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)

        val (jY, jM, jD) = gregorianToJalali(gYear, gMonth, gDay)
        return JalaliDate(jY, jM, jD, hour, minute)
    }

    fun jalaliToTimestamp(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long {
        val (gY, gM, gD) = jalaliToGregorian(year, month, day)
        val cal = Calendar.getInstance(timeZone).apply {
            set(Calendar.YEAR, gY)
            set(Calendar.MONTH, gM - 1)
            set(Calendar.DAY_OF_MONTH, gD)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    fun getPersianWeekdayName(timestampMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = timestampMillis }
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SATURDAY -> "شنبه"
            Calendar.SUNDAY -> "یکشنبه"
            Calendar.MONDAY -> "دوشنبه"
            Calendar.TUESDAY -> "سه‌شنبه"
            Calendar.WEDNESDAY -> "چهارشنبه"
            Calendar.THURSDAY -> "پنجشنبه"
            Calendar.FRIDAY -> "جمعه"
            else -> "شنبه"
        }
    }

    fun formatTime24(hour: Int, minute: Int): String {
        return String.format(Locale.ROOT, "%02d:%02d", hour, minute)
    }

    fun formatPersianTime(hour: Int, minute: Int): String {
        return toPersianDigits(formatTime24(hour, minute))
    }

    data class PersianExtractedTask(
        val title: String,
        val triggerTimestamp: Long,
        val jalaliDate: JalaliDate,
        val timeText: String,
        val jalaliDateText: String,
        val rawTimePhrase: String = "",
        val hasExplicitTime: Boolean = false,
        val hasExplicitDate: Boolean = false,
        val hasExplicitTitle: Boolean = true
    )

    fun normalizePersianWordsToDigits(text: String): String {
        var s = text
        // Normalize Arabic characters and zero-width spaces
        s = s.replace('\u064A', '\u06CC') // Arabic Yeh to Persian Yeh
        s = s.replace('\u0643', '\u06A9') // Arabic Kaf to Persian Kaf
        s = s.replace('\u0629', 'ه')      // Teh Marbuta to Heh
        s = s.replace('\u00A0', ' ')      // Non-breaking space
        s = s.replace("بعد از ظهر", "بعدازظهر")

        // Unify "دیگر" to "دیگه" for conversational temporal phrases
        s = s.replace("روز دیگر", "روز دیگه")
            .replace("ساعت دیگر", "ساعت دیگه")
            .replace("دقیقه دیگر", "دقیقه دیگه")
            .replace("سال دیگر", "سال دیگه")
            .replace("ماه دیگر", "ماه دیگه")
            .replace("هفته دیگر", "هفته دیگه")

        // Normalize compound relative day words to single standard forms
        s = s.replace("پس اون فردا", "پس_اون_فردا")
            .replace("پس‌اون‌فردا", "پس_اون_فردا")
            .replace("پس اونفردا", "پس_اون_فردا")
            .replace("پس آن فردا", "پس_اون_فردا")
            .replace("پس‌آن‌فردا", "پس_اون_فردا")
            .replace("پس آنفردا", "پس_اون_فردا")
            .replace("پسونفردا", "پس_اون_فردا")
            .replace("پس اون فرداش", "پس_اون_فردا")

        s = s.replace("پس فردا", "پس_فردا")
            .replace("پس‌فردا", "پس_فردا")
            .replace("پسفردا", "پس_فردا")
            .replace("پس از فردا", "پس_فردا")
            .replace("پس فرداش", "پس_فردا")
            .replace("پسفرداش", "پس_فردا")

        s = s.replace("فرداش", "فردا")
            .replace("فردای امروز", "فردا")

        // Relative duration normalization
        s = s.replace("بیست و چهار ساعت", "24 ساعت")
            .replace("دوازده ساعت", "12 ساعت")
            .replace("ده ساعت", "10 ساعت")
            .replace("نه ساعت", "9 ساعت")
            .replace("هشت ساعت", "8 ساعت")
            .replace("هفت ساعت", "7 ساعت")
            .replace("شش ساعت", "6 ساعت")
            .replace("شیش ساعت", "6 ساعت")
            .replace("پنج ساعت", "5 ساعت")
            .replace("چهار ساعت", "4 ساعت")
            .replace("سه ساعت", "3 ساعت")
            .replace("دو ساعت", "2 ساعت")
            .replace("یک ساعت", "1 ساعت")
            .replace("یه ساعت", "1 ساعت")

        s = s.replace("چهل و پنج دقیقه", "45 دقیقه")
            .replace("سی دقیقه", "30 دقیقه")
            .replace("نیم ساعت", "30 دقیقه")
            .replace("یک ربع", "15 دقیقه")
            .replace("یه ربع", "15 دقیقه")
            .replace("بیست دقیقه", "20 دقیقه")
            .replace("پانزده دقیقه", "15 دقیقه")
            .replace("پونزده دقیقه", "15 دقیقه")
            .replace("ده دقیقه", "10 دقیقه")
            .replace("نه دقیقه", "9 دقیقه")
            .replace("هشت دقیقه", "8 دقیقه")
            .replace("هفت دقیقه", "7 دقیقه")
            .replace("شش دقیقه", "6 دقیقه")
            .replace("شیش دقیقه", "6 دقیقه")
            .replace("پنج دقیقه", "5 دقیقه")
            .replace("چهار دقیقه", "4 دقیقه")
            .replace("سه دقیقه", "3 دقیقه")
            .replace("دو دقیقه", "2 دقیقه")
            .replace("یک دقیقه", "1 دقیقه")
            .replace("یه دقیقه", "1 دقیقه")

        // Days count normalization
        s = s.replace("سه روز دیگه", "3 روز دیگه")
            .replace("سه روز بعد", "3 روز بعد")
            .replace("دو روز دیگه", "2 روز دیگه")
            .replace("دو روز بعد", "2 روز بعد")
            .replace("یک روز دیگه", "1 روز دیگه")
            .replace("یک روز بعد", "1 روز بعد")
            .replace("چهار روز دیگه", "4 روز دیگه")
            .replace("پنج روز دیگه", "5 روز دیگه")
            .replace("یک هفته دیگه", "7 روز دیگه")
            .replace("یه هفته دیگه", "7 روز دیگه")
            .replace("دو هفته دیگه", "14 روز دیگه")
            .replace("یک ماه دیگه", "1 ماه دیگه")
            .replace("یه ماه دیگه", "1 ماه دیگه")
            .replace("یک سال دیگه", "1 سال دیگه")
            .replace("یه سال دیگه", "1 سال دیگه")

        // Clock hours with "ساعت"
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

        // Conversational hours without "ساعت" prefix (e.g. ۹ صبح, ۳ عصر)
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
            .replace("نه صبح", "ساعت 9 صبح")
            .replace("هشت صبح", "ساعت 8 صبح")
            .replace("هفت صبح", "ساعت 7 صبح")
            .replace("شش صبح", "ساعت 6 صبح")

        // Borj (month numbers) normalization
        s = s.replace("برج دوازدهم", "برج 12").replace("برج دوازده", "برج 12")
            .replace("برج یازدهم", "برج 11").replace("برج یازده", "برج 11")
            .replace("برج دهم", "برج 10").replace("برج ده", "برج 10")
            .replace("برج نهم", "برج 9").replace("برج نه", "برج 9")
            .replace("برج هشتم", "برج 8").replace("برج هشت", "برج 8")
            .replace("برج هفتم", "برج 7").replace("برج هفت", "برج 7")
            .replace("برج ششم", "برج 6").replace("برج شش", "برج 6")
            .replace("برج پنجم", "برج 5").replace("برج پنج", "برج 5")
            .replace("برج چهارم", "برج 4").replace("برج چهار", "برج 4")
            .replace("برج سوم", "برج 3").replace("برج سه", "برج 3")
            .replace("برج دوم", "برج 2").replace("برج دو", "برج 2")
            .replace("برج اول", "برج 1").replace("برج یکم", "برج 1").replace("برج یک", "برج 1")

        // Ordinal day of month numbers
        s = s.replace("سی و یکم", "31").replace("سی‌ام", "30").replace("سی ام", "30")
            .replace("بیست و نهم", "29").replace("بیست و هشتم", "28")
            .replace("بیست و هفتم", "27").replace("بیست و ششم", "26")
            .replace("بیست و پنجم", "25").replace("بیست و چهارم", "24")
            .replace("بیست و سوم", "23").replace("بیست و دوم", "22")
            .replace("بیست و یکم", "21").replace("بیستم", "20")
            .replace("نوزدهم", "19").replace("هجدهم", "18").replace("هفدهم", "17")
            .replace("شانزدهم", "16").replace("شونزدهم", "16")
            .replace("پانزدهم", "15").replace("پونزدهم", "15")
            .replace("چهاردهم", "14").replace("سیزدهم", "13")
            .replace("دوازدهم", "12").replace("یازدهم", "11")
            .replace("دهم", "10").replace("نهم", "9").replace("هشتم", "8")
            .replace("هفتم", "7").replace("ششم", "6").replace("پنجم", "5")
            .replace("چهارم", "4").replace("سوم", "3").replace("دوم", "2")
            .replace("یکم", "1")

        // Missing spaces around "ساعت" and numbers
        s = s.replace(Regex("""ساعت\s*([0-9\u06F0-\u06F9\u0660-\u0669]+)"""), "ساعت $1")
        s = s.replace(Regex("""([0-9\u06F0-\u06F9\u0660-\u0669]+)\s*و\s*نیم"""), "$1:30")
        s = s.replace(Regex("""([0-9\u06F0-\u06F9\u0660-\u0669]+)\s*و\s*ربع"""), "$1:15")

        return s
    }

    private fun resolveHour(hour: Int, period: String?): Int {
        var h = hour
        when (period) {
            "صبح", "بامداد" -> {
                if (h == 12) h = 0
            }
            "ظهر" -> {
                if (h < 12) h = 12
            }
            "بعدازظهر", "عصر", "غروب", "شب" -> {
                if (h < 12) h += 12
            }
            else -> {
                // In Persian appointment & reminder context:
                // Hours 1 to 6 without explicit "صبح" represent afternoon (13:00 to 18:00)
                // e.g. "ساعت ۳" -> 15:00
                // Hours 7 to 11 represent morning (07:00 to 11:00)
                // Hour 12 represents noon (12:00)
                if (h in 1..6) {
                    h += 12
                }
            }
        }
        return h
    }

    /**
     * Resolves day of week offset from reference time.
     * e.g. "شنبه" -> days until next Saturday.
     */
    fun calculateDaysUntilWeekday(targetWeekdayName: String, referenceTimeMillis: Long, isNextWeek: Boolean): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = referenceTimeMillis }
        val currentDayOfWeek = cal.get(Calendar.DAY_OF_WEEK)

        val targetCalendarDay = when (targetWeekdayName) {
            "شنبه" -> Calendar.SATURDAY
            "یکشنبه" -> Calendar.SUNDAY
            "دوشنبه" -> Calendar.MONDAY
            "سه‌شنبه", "سه شنبه", "سه_شنبه" -> Calendar.TUESDAY
            "چهارشنبه" -> Calendar.WEDNESDAY
            "پنجشنبه", "پنج‌شنبه", "پنج_شنبه" -> Calendar.THURSDAY
            "جمعه" -> Calendar.FRIDAY
            else -> -1
        }
        if (targetCalendarDay == -1) return 0

        var daysToAdd = (targetCalendarDay - currentDayOfWeek + 7) % 7
        if (daysToAdd == 0) {
            daysToAdd = if (isNextWeek) 7 else 0
        } else if (isNextWeek) {
            daysToAdd += 7
        }
        return daysToAdd
    }

    /**
     * Core Persian date/time phrase parser returning precise timestamp in milliseconds, or null if no temporal pattern found.
     */
    fun parsePersianTimePhrase(phrase: String, referenceTimeMillis: Long = System.currentTimeMillis()): Long? {
        val extracted = extractPersianTask(phrase, referenceTimeMillis)
        return if (extracted.hasExplicitTime || extracted.hasExplicitDate) extracted.triggerTimestamp else null
    }

    /**
     * Full-featured Persian NLP task extractor.
     * Decouples date components, clock components, and semantic title cleanly.
     */
    fun extractPersianTask(phrase: String, referenceTimeMillis: Long = System.currentTimeMillis()): PersianExtractedTask {
        val normalized = normalizePersianWordsToDigits(phrase.trim())
        val clean = toEnglishDigits(normalized)

        var hasExplicitDate = false
        var hasExplicitTime = false
        var rawExtractedDate = ""
        var rawExtractedTime = ""

        // 1. Direct relative time offsets from current moment (e.g. 15 دقیقه دیگه, نیم ساعت دیگه, ۲ ساعت دیگه)
        val relMinRegex = Regex("""(\d+)\s*دقیقه(?:\s*(?:دیگه|بعد|دیگر))?""")
        val relMinMatch = relMinRegex.find(clean)
        if (relMinMatch != null && (clean.contains("دیگه") || clean.contains("بعد") || clean.contains("دیگر"))) {
            val mins = relMinMatch.groupValues[1].toLongOrNull() ?: 0L
            if (mins > 0) {
                val targetMillis = referenceTimeMillis + mins * 60 * 1000L
                val jal = timestampToJalali(targetMillis)
                val cleanTitle = cleanTaskTitle(phrase, matchedDate = relMinMatch.value, matchedTime = "")
                return PersianExtractedTask(
                    title = if (cleanTitle.isNotBlank()) cleanTitle else "یادآوری $mins دقیقه بعد",
                    triggerTimestamp = targetMillis,
                    jalaliDate = jal,
                    timeText = formatPersianTime(jal.hour, jal.minute),
                    jalaliDateText = jal.formatPersianNumeric(),
                    rawTimePhrase = relMinMatch.value,
                    hasExplicitTime = true,
                    hasExplicitDate = false,
                    hasExplicitTitle = cleanTitle.isNotBlank()
                )
            }
        }

        val relHourRegex = Regex("""(\d+)\s*ساعت\s*(?:دیگه|بعد|دیگر)""")
        val relHourMatch = relHourRegex.find(clean)
        if (relHourMatch != null) {
            val hours = relHourMatch.groupValues[1].toLongOrNull() ?: 0L
            if (hours > 0) {
                val targetMillis = referenceTimeMillis + hours * 3600 * 1000L
                val jal = timestampToJalali(targetMillis)
                val cleanTitle = cleanTaskTitle(phrase, matchedDate = relHourMatch.value, matchedTime = "")
                return PersianExtractedTask(
                    title = if (cleanTitle.isNotBlank()) cleanTitle else "یادآوری $hours ساعت بعد",
                    triggerTimestamp = targetMillis,
                    jalaliDate = jal,
                    timeText = formatPersianTime(jal.hour, jal.minute),
                    jalaliDateText = jal.formatPersianNumeric(),
                    rawTimePhrase = relHourMatch.value,
                    hasExplicitTime = true,
                    hasExplicitDate = false,
                    hasExplicitTitle = cleanTitle.isNotBlank()
                )
            }
        }

        // Relative exact duplicate of current time (همین موقع / همین ساعت)
        val isSameHour = clean.contains("همین موقع") || clean.contains("همین ساعت")
        if (isSameHour) {
            if (clean.contains("پس_اون_فردا") || clean.contains("3 روز دیگه") || clean.contains("3 روز بعد")) {
                val targetMillis = referenceTimeMillis + 72 * 3600 * 1000L
                val jal = timestampToJalali(targetMillis)
                val cleanTitle = cleanTaskTitle(phrase, "پس اون فردا", "همین موقع")
                return PersianExtractedTask(
                    title = if (cleanTitle.isNotBlank()) cleanTitle else "یادآوری پس‌اون‌فردا همین موقع",
                    triggerTimestamp = targetMillis,
                    jalaliDate = jal,
                    timeText = formatPersianTime(jal.hour, jal.minute),
                    jalaliDateText = jal.formatPersianNumeric(),
                    rawTimePhrase = "پس اون فردا همین موقع",
                    hasExplicitTime = true,
                    hasExplicitDate = true,
                    hasExplicitTitle = cleanTitle.isNotBlank()
                )
            }
            if (clean.contains("پس_فردا") || clean.contains("2 روز دیگه") || clean.contains("2 روز بعد")) {
                val targetMillis = referenceTimeMillis + 48 * 3600 * 1000L
                val jal = timestampToJalali(targetMillis)
                val cleanTitle = cleanTaskTitle(phrase, "پس‌فردا", "همین موقع")
                return PersianExtractedTask(
                    title = if (cleanTitle.isNotBlank()) cleanTitle else "یادآوری پس‌فردا همین موقع",
                    triggerTimestamp = targetMillis,
                    jalaliDate = jal,
                    timeText = formatPersianTime(jal.hour, jal.minute),
                    jalaliDateText = jal.formatPersianNumeric(),
                    rawTimePhrase = "پس‌فردا همین موقع",
                    hasExplicitTime = true,
                    hasExplicitDate = true,
                    hasExplicitTitle = cleanTitle.isNotBlank()
                )
            }
            if (clean.contains("فردا") || clean.contains("1 روز دیگه") || clean.contains("1 روز بعد")) {
                val targetMillis = referenceTimeMillis + 24 * 3600 * 1000L
                val jal = timestampToJalali(targetMillis)
                val cleanTitle = cleanTaskTitle(phrase, "فردا", "همین موقع")
                return PersianExtractedTask(
                    title = if (cleanTitle.isNotBlank()) cleanTitle else "یادآوری فردا همین موقع",
                    triggerTimestamp = targetMillis,
                    jalaliDate = jal,
                    timeText = formatPersianTime(jal.hour, jal.minute),
                    jalaliDateText = jal.formatPersianNumeric(),
                    rawTimePhrase = "فردا همین موقع",
                    hasExplicitTime = true,
                    hasExplicitDate = true,
                    hasExplicitTitle = cleanTitle.isNotBlank()
                )
            }
            if (clean.contains("سال دیگه") || clean.contains("سال بعد") || clean.contains("سال آینده") || clean.contains("1 سال دیگه")) {
                val curJalali = timestampToJalali(referenceTimeMillis)
                val targetYear = curJalali.year + 1
                val targetMonth = curJalali.month
                val maxDays = getDaysInMonth(targetYear, targetMonth)
                val targetDay = minOf(curJalali.day, maxDays)
                val targetMillis = jalaliToTimestamp(targetYear, targetMonth, targetDay, curJalali.hour, curJalali.minute)
                val jal = timestampToJalali(targetMillis)
                val cleanTitle = cleanTaskTitle(phrase, "سال آینده", "همین موقع")
                return PersianExtractedTask(
                    title = if (cleanTitle.isNotBlank()) cleanTitle else "یادآوری سال آینده همین موقع",
                    triggerTimestamp = targetMillis,
                    jalaliDate = jal,
                    timeText = formatPersianTime(jal.hour, jal.minute),
                    jalaliDateText = jal.formatPersianNumeric(),
                    rawTimePhrase = "سال آینده همین موقع",
                    hasExplicitTime = true,
                    hasExplicitDate = true,
                    hasExplicitTitle = cleanTitle.isNotBlank()
                )
            }
        }

        // 2. Resolve Clock Time (Hour & Minute)
        var parsedHour: Int? = null
        var parsedMinute: Int = 0

        // 2A. "یک ربع به X" / "۱۰ دقیقه به X" / "نیم ساعت به X"
        val fractionalToHourRegex = Regex("""(?:(یک ربع|یه ربع|15 دقیقه|نیم ساعت|30 دقیقه|10 دقیقه|ده دقیقه|20 دقیقه|بیست دقیقه)\s*(?:مانده\s*)?به\s*(?:ساعت\s*)?(\d{1,2}))\s*(صبح|بامداد|ظهر|عصر|غروب|شب|بعدازظهر)?""")
        val fractionalMatch = fractionalToHourRegex.find(clean)
        if (fractionalMatch != null) {
            hasExplicitTime = true
            rawExtractedTime = fractionalMatch.value
            val fractionText = fractionalMatch.groupValues[1]
            val rawTargetHour = fractionalMatch.groupValues[2].toIntOrNull() ?: 12
            val period = fractionalMatch.groupValues[3].ifBlank { null }
            val resolvedTargetHour = resolveHour(rawTargetHour, period)

            val minusMinutes = when {
                fractionText.contains("15") || fractionText.contains("ربع") -> 15
                fractionText.contains("30") || fractionText.contains("نیم") -> 30
                fractionText.contains("10") || fractionText.contains("ده") -> 10
                fractionText.contains("20") || fractionText.contains("بیست") -> 20
                else -> 15
            }
            if (minusMinutes == 15) {
                parsedHour = if (resolvedTargetHour == 0) 23 else resolvedTargetHour - 1
                parsedMinute = 45
            } else if (minusMinutes == 30) {
                parsedHour = if (resolvedTargetHour == 0) 23 else resolvedTargetHour - 1
                parsedMinute = 30
            } else if (minusMinutes == 10) {
                parsedHour = if (resolvedTargetHour == 0) 23 else resolvedTargetHour - 1
                parsedMinute = 50
            } else if (minusMinutes == 20) {
                parsedHour = if (resolvedTargetHour == 0) 23 else resolvedTargetHour - 1
                parsedMinute = 40
            }
        }

        // 2B. Standard hour with optional minutes or fractions (e.g. ساعت 3:30, ساعت 3, ساعت 15:00, 9 صبح)
        if (parsedHour == null) {
            val standardClockRegex = Regex("""(?:ساعت\s*(\d{1,2})(?::(\d{1,2}))?|\b(\d{1,2})(?::(\d{1,2}))\b|\b(\d{1,2})\s*(صبح|بامداد|ظهر|عصر|غروب|شب|بعدازظهر))\s*(صبح|بامداد|ظهر|عصر|غروب|شب|بعدازظهر)?""")
            val clockMatch = standardClockRegex.find(clean)
            if (clockMatch != null) {
                hasExplicitTime = true
                rawExtractedTime = clockMatch.value
                val rawH = clockMatch.groupValues[1].toIntOrNull()
                    ?: clockMatch.groupValues[3].toIntOrNull()
                    ?: clockMatch.groupValues[5].toIntOrNull()
                    ?: 9
                val rawM = clockMatch.groupValues[2].toIntOrNull()
                    ?: clockMatch.groupValues[4].toIntOrNull()
                    ?: 0
                val period = (clockMatch.groupValues[6].ifBlank { null }
                    ?: clockMatch.groupValues[7].ifBlank { null })

                parsedHour = resolveHour(rawH, period)
                parsedMinute = rawM
            }
        }

        // 3. Resolve Date Component
        var dayOffset: Int? = null
        var exactJalaliYear: Int? = null
        var exactJalaliMonth: Int? = null
        var exactJalaliDay: Int? = null

        // 3A. "پس_اون_فردا" (+3 days)
        if (clean.contains("پس_اون_فردا") || clean.contains("3 روز دیگه") || clean.contains("3 روز بعد")) {
            hasExplicitDate = true
            dayOffset = 3
            rawExtractedDate = "پس اون فردا"
        }
        // 3B. "پس_فردا" (+2 days) - CRITICAL: checked before "فردا"
        else if (clean.contains("پس_فردا") || clean.contains("2 روز دیگه") || clean.contains("2 روز بعد")) {
            hasExplicitDate = true
            dayOffset = 2
            rawExtractedDate = "پس‌فردا"
        }
        // 3C. "فردا" (+1 day)
        else if (clean.contains("فردا") || clean.contains("1 روز دیگه") || clean.contains("1 روز بعد")) {
            hasExplicitDate = true
            dayOffset = 1
            rawExtractedDate = "فردا"
        }
        // 3D. "امروز" or "امشب" (0 days)
        else if (clean.contains("امروز") || clean.contains("امشب")) {
            hasExplicitDate = true
            dayOffset = 0
            rawExtractedDate = "امروز"
        }
        // 3E. Weekdays (شنبه، یکشنبه، دوشنبه، سه‌شنبه، چهارشنبه، پنجشنبه، جمعه)
        else {
            val weekdays = listOf(
                "شنبه", "یکشنبه", "دوشنبه", "سه‌شنبه", "سه شنبه", "چهارشنبه", "پنجشنبه", "پنج‌شنبه", "جمعه"
            )
            for (w in weekdays) {
                if (clean.contains(w)) {
                    hasExplicitDate = true
                    val isNextWeek = clean.contains("هفته بعد") || clean.contains("هفته آینده") || clean.contains("آینده")
                    val days = calculateDaysUntilWeekday(w, referenceTimeMillis, isNextWeek)
                    dayOffset = days
                    rawExtractedDate = if (isNextWeek) "$w هفته بعد" else w
                    break
                }
            }
        }

        // 3F. Relative Days Count: "N روز دیگه"
        if (dayOffset == null) {
            val relDaysRegex = Regex("""(\d+)\s*روز\s*(?:دیگه|بعد)""")
            relDaysRegex.find(clean)?.let { match ->
                hasExplicitDate = true
                dayOffset = match.groupValues[1].toIntOrNull() ?: 1
                rawExtractedDate = match.value
            }
        }

        // 3G. Solar Hijri Month and Day (e.g. 15 آبان, روز 14 برج 5)
        if (dayOffset == null) {
            val borjRegex = Regex("""(?:روز\s*)?(\d{1,2})\s*برج\s*(\d{1,2})""")
            borjRegex.find(clean)?.let { match ->
                hasExplicitDate = true
                exactJalaliDay = match.groupValues[1].toIntOrNull() ?: 1
                exactJalaliMonth = match.groupValues[2].toIntOrNull() ?: 1
                val curJalali = timestampToJalali(referenceTimeMillis)
                val isNextYear = clean.contains("سال آینده") || clean.contains("سال بعد")
                exactJalaliYear = if (isNextYear) curJalali.year + 1 else curJalali.year
                rawExtractedDate = match.value
            }
        }

        if (dayOffset == null && exactJalaliMonth == null) {
            MONTH_NAMES.forEachIndexed { index, mName ->
                val mRegex = Regex("""(\d{1,2})\s*""" + mName)
                mRegex.find(clean)?.let { match ->
                    hasExplicitDate = true
                    exactJalaliDay = match.groupValues[1].toIntOrNull() ?: 1
                    exactJalaliMonth = index + 1
                    val curJalali = timestampToJalali(referenceTimeMillis)
                    val isNextYear = clean.contains("سال آینده") || clean.contains("سال بعد") || clean.contains("سال دیگه")
                    exactJalaliYear = if (isNextYear) curJalali.year + 1 else curJalali.year
                    rawExtractedDate = match.value
                }
            }
        }

        // 3H. Relative Year or Month without specific day (e.g. "سال دیگه همین موقع", "یک سال دیگه", "ماه دیگه همین موقع")
        val isYearlyPhrase = clean.contains("سال دیگه") || clean.contains("سال بعد") || clean.contains("سال آینده") || clean.contains("1 سال دیگه")
        val isMonthlyPhrase = clean.contains("ماه دیگه") || clean.contains("ماه بعد") || clean.contains("ماه آینده") || clean.contains("1 ماه دیگه")

        if (dayOffset == null && exactJalaliMonth == null) {
            if (isYearlyPhrase) {
                hasExplicitDate = true
                val curJalali = timestampToJalali(referenceTimeMillis)
                exactJalaliYear = curJalali.year + 1
                exactJalaliMonth = curJalali.month
                val maxDays = getDaysInMonth(exactJalaliYear, exactJalaliMonth)
                exactJalaliDay = minOf(curJalali.day, maxDays)
                rawExtractedDate = if (isSameHour) "سال آینده همین موقع" else "سال آینده"
            } else if (isMonthlyPhrase) {
                hasExplicitDate = true
                val curJalali = timestampToJalali(referenceTimeMillis)
                exactJalaliYear = if (curJalali.month == 12) curJalali.year + 1 else curJalali.year
                exactJalaliMonth = if (curJalali.month == 12) 1 else curJalali.month + 1
                val maxDays = getDaysInMonth(exactJalaliYear, exactJalaliMonth)
                exactJalaliDay = minOf(curJalali.day, maxDays)
                rawExtractedDate = if (isSameHour) "ماه آینده همین موقع" else "ماه آینده"
            }
        }

        // 4. Assemble final timestamp
        val referenceCalendar = Calendar.getInstance().apply { timeInMillis = referenceTimeMillis }
        // Missing clock fields are placeholders only; callers must check hasExplicitTime before saving.
        val targetHour = parsedHour ?: referenceCalendar.get(Calendar.HOUR_OF_DAY)
        val targetMinute = if (parsedHour == null) referenceCalendar.get(Calendar.MINUTE) else parsedMinute

        val targetTimestamp: Long = when {
            exactJalaliYear != null && exactJalaliMonth != null && exactJalaliDay != null -> {
                jalaliToTimestamp(exactJalaliYear, exactJalaliMonth, exactJalaliDay, targetHour, targetMinute)
            }
            dayOffset != null -> {
                val cal = Calendar.getInstance().apply {
                    timeInMillis = referenceTimeMillis
                    add(Calendar.DAY_OF_YEAR, dayOffset)
                    set(Calendar.HOUR_OF_DAY, targetHour)
                    set(Calendar.MINUTE, targetMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                cal.timeInMillis
            }
            hasExplicitTime -> {
                // Keep today's date as a placeholder; hasExplicitDate remains false.
                val cal = Calendar.getInstance().apply {
                    timeInMillis = referenceTimeMillis
                    set(Calendar.HOUR_OF_DAY, targetHour)
                    set(Calendar.MINUTE, targetMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                cal.timeInMillis
            }
            else -> referenceTimeMillis
        }

        val finalJalali = timestampToJalali(targetTimestamp)
        val timeText = formatPersianTime(finalJalali.hour, finalJalali.minute)
        val dateText = finalJalali.formatPersianNumeric()

        val cleanTitle = cleanTaskTitle(phrase, matchedDate = rawExtractedDate, matchedTime = rawExtractedTime)
        val finalTitle = if (cleanTitle.isNotBlank()) {
            cleanTitle
        } else {
            buildDefaultTitle(rawExtractedDate, timeText)
        }

        return PersianExtractedTask(
            title = finalTitle,
            triggerTimestamp = targetTimestamp,
            jalaliDate = finalJalali,
            timeText = timeText,
            jalaliDateText = dateText,
            rawTimePhrase = "$rawExtractedDate $rawExtractedTime".trim(),
            hasExplicitTime = hasExplicitTime || isSameHour,
            hasExplicitDate = hasExplicitDate,
            hasExplicitTitle = cleanTitle.isNotBlank()
        )
    }

    private fun cleanTaskTitle(rawSpeech: String, matchedDate: String, matchedTime: String): String {
        var s = normalizePersianWordsToDigits(rawSpeech)
        s = toEnglishDigits(s)

        // 1. Remove standard Persian assistant command prefixes
        s = s.replace(Regex("""^(?:لطفاً|لطفا|باید|می‌خوام|میخوام|قرار دارم|قرار شد)?\s*(?:یادم بنداز که|یادم بنداز|یادم بیار که|یادم بیار|بهم یادآوری کن که|بهم یادآوری کن|یادآوری کن که|یادآوری کن|ثبت کن که|ثبت کن|اضافه کن که|اضافه کن|تنظیم کن که|تنظیم کن)\s*"""), "")

        // 2. Remove date markers
        s = s.replace(Regex("""(?:پس[\s‌_]?اون[\s‌_]?فردا|سه\s*روز\s*دیگ[هتر]|3\s*روز\s*دیگ[هتر]|پس[\s‌_]?فردا|پسفردا|2\s*روز\s*دیگ[هتر]|1\s*روز\s*دیگ[هتر]|فردا|امروز|امشب)"""), "")
        s = s.replace(Regex("""(?:سال[\s‌_]?آینده|سال[\s‌_]?بعد|سال[\s‌_]?دیگ[هتر]|1\s*سال\s*دیگ[هتر])"""), "")
        s = s.replace(Regex("""(?:ماه[\s‌_]?آینده|ماه[\s‌_]?بعد|ماه[\s‌_]?دیگ[هتر]|1\s*ماه\s*دیگ[هتر])"""), "")
        s = s.replace(Regex("""(?:هفته[\s‌_]?آینده|هفته[\s‌_]?بعد|هفته[\s‌_]?دیگ[هتر]|(?:1|2)\s*هفته\s*دیگ[هتر])"""), "")
        s = s.replace(Regex("""(?:شنبه|یکشنبه|دوشنبه|سه‌شنبه|سه شنبه|سه_شنبه|چهارشنبه|پنجشنبه|پنج‌شنبه|پنج_شنبه|جمعه)(?:\s*(?:هفته\s*(?:بعد|آینده)|آینده))?"""), "")
        s = s.replace(Regex("""\d+\s*روز\s*(?:دیگه|بعد)"""), "")
        s = s.replace(Regex("""\d+\s*هفته\s*(?:دیگه|بعد)"""), "")
        s = s.replace(Regex("""\d+\s*ماه\s*(?:دیگه|بعد)"""), "")
        s = s.replace(Regex("""\d+\s*سال\s*(?:دیگه|بعد)"""), "")
        s = s.replace(Regex("""(?:روز\s*)?\d{1,2}\s*برج\s*\d{1,2}"""), "")
        MONTH_NAMES.forEach { m ->
            s = s.replace(Regex("""\d{1,2}\s*""" + m), "")
        }

        // 3. Remove relative times
        s = s.replace(Regex("""\d+\s*دقیقه\s*(?:دیگه|بعد|دیگر)?"""), "")
        s = s.replace(Regex("""\d+\s*ساعت\s*(?:دیگه|بعد|دیگر)?"""), "")
        s = s.replace(Regex("""همین\s*(?:موقع|ساعت)"""), "")

        // 4. Remove clock times
        s = s.replace(Regex("""(?:یک ربع|یه ربع|نیم ساعت|15 دقیقه|30 دقیقه|10 دقیقه|20 دقیقه)\s*(?:مانده\s*)?به\s*(?:ساعت\s*)?\d{1,2}(?:\s*(?:صبح|بامداد|ظهر|بعدازظهر|عصر|غروب|شب))?"""), "")
        s = s.replace(Regex("""ساعت\s*\d{1,2}(?::\d{1,2})?\s*(?:صبح|بامداد|ظهر|بعدازظهر|عصر|غروب|شب)?"""), "")
        s = s.replace(Regex("""\b\d{1,2}(?::\d{1,2})\b"""), "")
        s = s.replace(Regex("""(?<!\d)\d{1,2}\s*(?:صبح|بامداد|ظهر|بعدازظهر|عصر|غروب|شب)"""), "")

        // 5. Remove trailing verbs & filler
        s = s.replace(Regex("""\s*(?:رو یادم بنداز|را یادم بنداز|رو یادم بیار|را یادم بیار|رو ثبت کن|را ثبت کن|رو فراموش نکنم|را فراموش نکنم|داشته باشم|داشته باشیم|رو هماهنگ کنم)$"""), "")
        s = s.replace(Regex("""\s+(?:رو|را)$"""), "")

        // 6. Clean dangling prepositions and spaces
        s = s.trim { it in " ،.,-\n\t_" }
        s = s.removePrefix("که ").removePrefix("به ").removePrefix("برای ").trim()

        return s
    }

    private fun buildDefaultTitle(dateMarker: String, timeText: String): String {
        val datePart = when {
            dateMarker.contains("سال") -> dateMarker
            dateMarker.contains("ماه") -> dateMarker
            dateMarker.contains("پس اون") -> "پس‌اون‌فردا"
            dateMarker.contains("پس") -> "پس‌فردا"
            dateMarker.contains("فردا") -> "فردا"
            dateMarker.contains("امروز") -> "امروز"
            dateMarker.isNotBlank() -> dateMarker
            else -> ""
        }
        return if (datePart.isNotBlank()) {
            "یادآوری $datePart ساعت $timeText"
        } else {
            "یادآوری ساعت $timeText"
        }
    }
}
