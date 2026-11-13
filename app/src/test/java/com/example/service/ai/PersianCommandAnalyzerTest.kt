package com.example.service.ai

import com.example.data.engine.PersianDateEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class PersianCommandAnalyzerTest {

    private val tehranTimeZone = TimeZone.getTimeZone("Asia/Tehran")
    // Fixed reference time: Monday, 2026-10-26 10:00:00 (دوشنبه ۵ آبان ۱۴۰۵)
    private var baseTime: Long = 0L

    @Before
    fun setup() {
        val cal = Calendar.getInstance(tehranTimeZone).apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.OCTOBER)
            set(Calendar.DAY_OF_MONTH, 26) // Monday
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        baseTime = cal.timeInMillis
    }

    // آزمون ۱: «برای فردا ساعت ۶ یادآوری کن کتاب بخوانم.»
    @Test
    fun testCase01_TomorrowHour6_AmbiguousMorningOrEvening() {
        val input = "برای فردا ساعت ۶ یادآوری کن کتاب بخوانم."
        val result = PersianCommandAnalyzer.analyze(input, baseTime, tehranTimeZone)

        assertTrue("عنوان باید استخراج شود", result.title.contains("کتاب"))
        assertTrue("ساعت ۶ بدون صبح/عصر باید مبهم باشد", result.ambiguousFields.contains("HOUR_PERIOD_AM_PM"))
        assertEquals("وضعیت اعتبارسنجی باید AMBIGUOUS باشد", ValidationStatus.AMBIGUOUS, result.validationStatus)
        assertTrue("باید نیاز به پرسش از کاربر داشته باشد", result.needsUserClarification)
        assertNotNull("باید سؤال ابهام تولید شود", result.clarificationQuestion)
        assertTrue(result.clarificationQuestion!!.contains("صبح") && result.clarificationQuestion!!.contains("عصر"))
    }

    // آزمون ۲: «برای سه‌شنبه ساعت ۷ عصر یادآوری کن با همکارم تماس بگیرم.»
    @Test
    fun testCase02_Tuesday7Pm_ContactColleague() {
        val input = "برای سه‌شنبه ساعت ۷ عصر یادآوری کن با همکارم تماس بگیرم."
        val result = PersianCommandAnalyzer.analyze(input, baseTime, tehranTimeZone)

        assertEquals("وضعیت باید معتبر باشد", ValidationStatus.VALID, result.validationStatus)
        assertEquals("روز درخواستی باید سه‌شنبه باشد", "سه‌شنبه", result.requestedWeekday)
        assertEquals("روز محاسبه‌شده باید سه‌شنبه باشد (نه شنبه!)", "سه‌شنبه", result.calculatedWeekday)
        assertEquals("ساعت باید ۱۹:۰۰ باشد", 19, result.hour)
        assertEquals("دقیقه باید ۰ باشد", 0, result.minute)
        assertTrue("عنوان باید تماس با همکار باشد", result.title.contains("تماس") && result.title.contains("همکار"))
        assertNotNull("زمان نهایی باید محاسبه شود", result.targetTimestamp)

        // Verify independent weekday calculation:
        val checkWeekday = PersianDateEngine.getPersianWeekdayName(result.targetTimestamp!!)
        assertEquals("سه‌شنبه", checkWeekday)
    }

    // آزمون ۳: «شنبه ساعت ۹ صبح یادم بنداز خرید کنم.»
    @Test
    fun testCase03_Saturday9Am_Shopping() {
        val input = "شنبه ساعت ۹ صبح یادم بنداز خرید کنم."
        val result = PersianCommandAnalyzer.analyze(input, baseTime, tehranTimeZone)

        assertEquals("وضعیت باید معتبر باشد", ValidationStatus.VALID, result.validationStatus)
        assertEquals("شنبه", result.requestedWeekday)
        assertEquals("شنبه", result.calculatedWeekday)
        assertEquals(9, result.hour)
        assertEquals(0, result.minute)
        assertTrue("عنوان باید خرید باشد", result.title.contains("خرید"))

        val checkWeekday = PersianDateEngine.getPersianWeekdayName(result.targetTimestamp!!)
        assertEquals("شنبه", checkWeekday)
    }

    // آزمون ۴: «یک ربع دیگر یادآوری کن.»
    @Test
    fun testCase04_QuarterHourLater() {
        val input = "یک ربع دیگر یادآوری کن."
        val result = PersianCommandAnalyzer.analyze(input, baseTime, tehranTimeZone)

        assertEquals(ValidationStatus.VALID, result.validationStatus)
        assertEquals(TimeType.RELATIVE, result.timeType)
        assertNotNull(result.targetTimestamp)
        assertEquals(baseTime + 15 * 60 * 1000L, result.targetTimestamp)
    }

    // آزمون ۵: «فردا همین موقع یادم بنداز.»
    @Test
    fun testCase05_TomorrowSameTime() {
        val input = "فردا همین موقع یادم بنداز."
        val result = PersianCommandAnalyzer.analyze(input, baseTime, tehranTimeZone)

        assertEquals(ValidationStatus.VALID, result.validationStatus)
        assertNotNull(result.targetTimestamp)
        assertEquals(baseTime + 24 * 3600 * 1000L, result.targetTimestamp)
        assertEquals(10, result.hour)
        assertEquals(0, result.minute)
    }

    // آزمون ۶: «پسفردا ساعت ۱۴ به من یادآوری کن.»
    @Test
    fun testCase06_DayAfterTomorrowHour14() {
        val input = "پسفردا ساعت ۱۴ به من یادآوری کن."
        val result = PersianCommandAnalyzer.analyze(input, baseTime, tehranTimeZone)

        assertEquals(ValidationStatus.VALID, result.validationStatus)
        assertEquals(14, result.hour)
        assertEquals(0, result.minute)
        assertNotNull(result.targetTimestamp)

        // Day after tomorrow (+2 days)
        val cal = Calendar.getInstance(tehranTimeZone).apply {
            timeInMillis = baseTime
            add(Calendar.DAY_OF_YEAR, 2)
            set(Calendar.HOUR_OF_DAY, 14)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals(cal.timeInMillis, result.targetTimestamp)
    }

    // آزمون ۷: «سه‌شنبه هفته بعد ساعت ۱۸ جلسه دارم.»
    @Test
    fun testCase07_NextWeekTuesdayHour18() {
        val input = "سه‌شنبه هفته بعد ساعت ۱۸ جلسه دارم."
        val result = PersianCommandAnalyzer.analyze(input, baseTime, tehranTimeZone)

        assertEquals(ValidationStatus.VALID, result.validationStatus)
        assertEquals("سه‌شنبه", result.requestedWeekday)
        assertEquals("سه‌شنبه", result.calculatedWeekday)
        assertEquals(18, result.hour)
        assertEquals(0, result.minute)
        assertTrue(result.title.contains("جلسه"))

        // Reference time is Monday (دوشنبه).
        // This Tuesday is in 1 day (+1). Next week Tuesday is in 1 + 7 = 8 days (+8).
        val cal = Calendar.getInstance(tehranTimeZone).apply {
            timeInMillis = baseTime
            add(Calendar.DAY_OF_YEAR, 8)
            set(Calendar.HOUR_OF_DAY, 18)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals(cal.timeInMillis, result.targetTimestamp)
        assertEquals("سه‌شنبه", PersianDateEngine.getPersianWeekdayName(result.targetTimestamp!!))
    }

    // آزمون ۸: «برای فردا ساعت ۶ عصر یادآوری کن.»
    @Test
    fun testCase08_Tomorrow6Pm_Unambiguous() {
        val input = "برای فردا ساعت ۶ عصر یادآوری کن."
        val result = PersianCommandAnalyzer.analyze(input, baseTime, tehranTimeZone)

        assertEquals(ValidationStatus.VALID, result.validationStatus)
        assertEquals(18, result.hour)
        assertEquals(0, result.minute)
        assertFalse(result.ambiguousFields.contains("HOUR_PERIOD_AM_PM"))

        val cal = Calendar.getInstance(tehranTimeZone).apply {
            timeInMillis = baseTime
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 18)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals(cal.timeInMillis, result.targetTimestamp)
    }

    // آزمون ۹: «فردا ساعت ۶ صبح یادآوری کن.»
    @Test
    fun testCase09_Tomorrow6Am_Unambiguous() {
        val input = "فردا ساعت ۶ صبح یادآوری کن."
        val result = PersianCommandAnalyzer.analyze(input, baseTime, tehranTimeZone)

        assertEquals(ValidationStatus.VALID, result.validationStatus)
        assertEquals(6, result.hour)
        assertEquals(0, result.minute)
        assertFalse(result.ambiguousFields.contains("HOUR_PERIOD_AM_PM"))

        val cal = Calendar.getInstance(tehranTimeZone).apply {
            timeInMillis = baseTime
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 6)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals(cal.timeInMillis, result.targetTimestamp)
    }

    // آزمون ۱۰: «سه ساعت دیگر یادآوری کن.»
    @Test
    fun testCase10_ThreeHoursLater_RelativeDuration() {
        val input = "سه ساعت دیگر یادآوری کن."
        val result = PersianCommandAnalyzer.analyze(input, baseTime, tehranTimeZone)

        assertEquals(ValidationStatus.VALID, result.validationStatus)
        assertEquals(TimeType.RELATIVE, result.timeType)
        assertNotNull(result.targetTimestamp)
        // Must be exactly 3 hours (+180 minutes)
        assertEquals(baseTime + 3 * 3600 * 1000L, result.targetTimestamp)
    }

    // آزمون ۱۱: «فردا ساعت ۶ یادآوری کن» (حالت بدون صبح/عصر؛ سیستم باید ابهام را گزارش کند)
    @Test
    fun testCase11_TomorrowHour6_MustBeAmbiguous() {
        val input = "فردا ساعت ۶ یادآوری کن"
        val result = PersianCommandAnalyzer.analyze(input, baseTime, tehranTimeZone)

        assertEquals(ValidationStatus.AMBIGUOUS, result.validationStatus)
        assertTrue(result.needsUserClarification)
        assertTrue(result.ambiguousFields.contains("HOUR_PERIOD_AM_PM"))
        assertNotNull(result.clarificationQuestion)
        assertTrue(result.quickSuggestions.size >= 2)
    }

    // آزمون ۱۲: تاریخ مشخص با روز هفته متناقض
    @Test
    fun testCase12_ContradictionDetection() {
        // Reference time is Monday (دوشنبه). "فردا" is Tuesday (سه‌شنبه).
        // If user says "فردا که جمعه است یادآوری کن":
        val input = "فردا که جمعه است یادآوری کن"
        val result = PersianCommandAnalyzer.analyze(input, baseTime, tehranTimeZone)

        assertEquals(ValidationStatus.CONFLICT, result.validationStatus)
        assertNotNull(result.conflictMessage)
        assertTrue(result.needsUserClarification)
        assertNull("در صورت تناقض نباید تاریخ نامعتبر به عنوان زمان نهایی تأیید شود", result.targetTimestamp)
    }

    // آزمون ۱۳: آزمایش‌های مرزی نزدیک نیمه‌شب و تغییر روز
    @Test
    fun testCase13_NearMidnightBoundary() {
        // Reference time at 23:55 (5 minutes before midnight)
        val midnightCal = Calendar.getInstance(tehranTimeZone).apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.OCTOBER)
            set(Calendar.DAY_OF_MONTH, 26)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 55)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val refMidnight = midnightCal.timeInMillis

        // 10 minutes later (crosses midnight into tomorrow 00:05)
        val input = "ده دقیقه دیگر یادآوری کن دارو بخورم"
        val result = PersianCommandAnalyzer.analyze(input, refMidnight, tehranTimeZone)

        assertEquals(ValidationStatus.VALID, result.validationStatus)
        assertEquals(refMidnight + 10 * 60 * 1000L, result.targetTimestamp)
        assertEquals(0, result.hour)
        assertEquals(5, result.minute)
    }

    // آزمون ۱۴: ارقام فارسی/عربی، املای حروفی «شش» و «هفت»، و زبان محاوره‌ای
    @Test
    fun testCase14_PersianArabicDigitsAndColloquialWords() {
        // "ساعت شش عصر" with written word and Persian colloquial
        val input1 = "برای فردا ساعت شش عصر یادم بنداز"
        val result1 = PersianCommandAnalyzer.analyze(input1, baseTime, tehranTimeZone)
        assertEquals(18, result1.hour)

        // Arabic/Persian digits: "ساعت ۱۸:۳۰"
        val input2 = "فردا ساعت ۱۸:۳۰ جلسه"
        val result2 = PersianCommandAnalyzer.analyze(input2, baseTime, tehranTimeZone)
        assertEquals(18, result2.hour)
        assertEquals(30, result2.minute)

        // "ساعت ۷ و نیم عصر"
        val input3 = "پس فردا ساعت ۷ و نیم عصر یادآوری کن"
        val result3 = PersianCommandAnalyzer.analyze(input3, baseTime, tehranTimeZone)
        assertEquals(19, result3.hour)
        assertEquals(30, result3.minute)
    }

    @Test
    fun testCase15_SpokenPersianRelativeDaysAndCleanTitle() {
        val input = "برای سه روز دیگه نون بخرم ساعت ۱۰"
        val result = PersianCommandAnalyzer.analyze(input, baseTime, tehranTimeZone)

        val expected = Calendar.getInstance(tehranTimeZone).apply {
            timeInMillis = baseTime
            add(Calendar.DAY_OF_YEAR, 3)
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        assertEquals("عبارت سه روز دیگه باید دقیقاً سه روز جلو برود", expected, result.targetTimestamp)
        assertTrue("عنوان باید کار اصلی را نگه دارد", result.title.contains("نون"))
        assertFalse("عبارت تاریخ نباید در عنوان باقی بماند", result.title.contains("سه روز"))
        assertTrue("ساعت ۱۰ بدون صبح/عصر باید ابهام داشته باشد", result.ambiguousFields.contains("HOUR_PERIOD_AM_PM"))
    }

    @Test
    fun testCase16_NumericRelativeDaysStillWork() {
        val result = PersianCommandAnalyzer.analyze("3 روز دیگه ساعت ۱۸ خرید نان", baseTime, tehranTimeZone)
        val expected = Calendar.getInstance(tehranTimeZone).apply {
            timeInMillis = baseTime
            add(Calendar.DAY_OF_YEAR, 3)
            set(Calendar.HOUR_OF_DAY, 18)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        assertEquals(expected, result.targetTimestamp)
        assertTrue(result.title.contains("خرید نان"))
        assertFalse(result.title.contains("3 روز"))
    }

    @Test
    fun dateOnlyRequiresTimeAndDoesNotInventTimestamp() {
        val result = PersianCommandAnalyzer.analyze("فردا یادم بنداز برم بانک", baseTime, tehranTimeZone)
        assertTrue(result.hasExplicitDate)
        assertFalse(result.hasExplicitTime)
        assertNull(result.targetTimestamp)
        assertNotNull(result.jalaliDateText)
        assertTrue(result.needsUserClarification)
    }

    @Test
    fun timeOnlyRequiresDateAndDoesNotAssumeTomorrow() {
        val result = PersianCommandAnalyzer.analyze("ساعت ۱۰ صبح یادم بنداز برم بانک", baseTime, tehranTimeZone)
        assertFalse(result.hasExplicitDate)
        assertTrue(result.hasExplicitTime)
        assertNull(result.targetTimestamp)
        assertNull(result.jalaliDateText)
        assertTrue(result.needsUserClarification)
    }

    @Test
    fun missingBothDateAndTimeRequiresClarification() {
        val result = PersianCommandAnalyzer.analyze("یادم بنداز برم بانک", baseTime, tehranTimeZone)
        assertFalse(result.hasExplicitDate)
        assertFalse(result.hasExplicitTime)
        assertTrue(result.needsUserClarification)
        assertNotNull(result.clarificationQuestion)
    }

    @Test
    fun compactFourDigitClockFromPersianSpeechIsParsedAsHourAndMinute() {
        val result = PersianCommandAnalyzer.analyze(
            "فردا ساعت ۱۸۳۵ دقیقه یادم بنداز برم بانک",
            baseTime,
            tehranTimeZone
        )

        assertEquals("ساعت باید ۱۸ باشد", 18, result.hour)
        assertEquals("دقیقه باید ۳۵ باشد، نه بخشی از عنوان", 35, result.minute)
        assertEquals(ValidationStatus.VALID, result.validationStatus)
        assertTrue("عنوان باید فقط کار بانکی را نگه دارد", result.title.contains("بانک"))
        assertFalse("عدد ۳۵ نباید در عنوان باقی بماند", result.title.contains("35"))
        assertNotNull(result.targetTimestamp)
    }

    @Test
    fun persianClockWithColonAndMinuteWordKeepsMinuteOutOfTitle() {
        val result = PersianCommandAnalyzer.analyze(
            "فردا ساعت ۱۸:۳۵ دقیقه باید برم بانک",
            baseTime,
            tehranTimeZone
        )

        assertEquals(18, result.hour)
        assertEquals(35, result.minute)
        assertEquals(ValidationStatus.VALID, result.validationStatus)
        assertTrue(result.title.contains("بانک"))
        assertFalse(result.title.contains("35"))
        assertNotNull(result.targetTimestamp)
    }
}
