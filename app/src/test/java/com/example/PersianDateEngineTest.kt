package com.example

import com.example.data.engine.PersianDateEngine
import com.example.service.ai.GeminiSemanticParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class PersianDateEngineTest {

    @Test
    fun testPersianDigitConversion() {
        assertEquals("۱۲۳۴۵", PersianDateEngine.toPersianDigits("12345"))
        assertEquals("12345", PersianDateEngine.toEnglishDigits("۱۲۳۴۵"))
    }

    @Test
    fun testGregorianToJalaliAndBack() {
        // Test date: 2026-03-21 -> 1405-01-01 (Nowruz)
        val (jYear, jMonth, jDay) = PersianDateEngine.gregorianToJalali(2026, 3, 21)
        assertEquals(1405, jYear)
        assertEquals(1, jMonth)
        assertEquals(1, jDay)

        val (gY, gM, gD) = PersianDateEngine.jalaliToGregorian(jYear, jMonth, jDay)
        assertEquals(2026, gY)
        assertEquals(3, gM)
        assertEquals(21, gD)
    }

    @Test
    fun testLeapYearCalculation() {
        // 1399 and 1403 are leap years (30 days in Esfand)
        assertTrue(PersianDateEngine.isJalaliLeapYear(1399))
        assertTrue(PersianDateEngine.isJalaliLeapYear(1403))
        assertEquals(30, PersianDateEngine.getDaysInMonth(1403, 12))
        assertEquals(29, PersianDateEngine.getDaysInMonth(1402, 12))
    }

    @Test
    fun testMandatoryScenarios_RelativeMinutes() {
        val baseTime = 1774000000000L // arbitrary fixed timestamp

        // 1. یک ربع دیگه (15 minutes)
        val r1 = PersianDateEngine.parsePersianTimePhrase("یک ربع دیگه بهم یادآوری کن", baseTime)
        assertNotNull(r1)
        assertEquals(baseTime + 15 * 60 * 1000L, r1)

        // 2. پنج دقیقه دیگه (5 minutes)
        val r2 = PersianDateEngine.parsePersianTimePhrase("پنج دقیقه دیگه آب رو چک کنم", baseTime)
        assertNotNull(r2)
        assertEquals(baseTime + 5 * 60 * 1000L, r2)

        // 3. ده دقیقه دیگه (10 minutes)
        val r3 = PersianDateEngine.parsePersianTimePhrase("ده دقیقه دیگه پیام بدم", baseTime)
        assertNotNull(r3)
        assertEquals(baseTime + 10 * 60 * 1000L, r3)

        // 4. نیم ساعت دیگه (30 minutes)
        val r4 = PersianDateEngine.parsePersianTimePhrase("نیم ساعت دیگه جلسه شروع میشه", baseTime)
        assertNotNull(r4)
        assertEquals(baseTime + 30 * 60 * 1000L, r4)
    }

    @Test
    fun testMandatoryScenarios_RelativeHours() {
        val baseTime = 1774000000000L

        // 5. چهار ساعت دیگه (4 hours)
        val r5 = PersianDateEngine.parsePersianTimePhrase("چهار ساعت دیگه لباس‌ها رو بردارم", baseTime)
        assertNotNull(r5)
        assertEquals(baseTime + 4 * 3600 * 1000L, r5)

        // 6. دوازده ساعت دیگه (12 hours)
        val r6 = PersianDateEngine.parsePersianTimePhrase("دوازده ساعت دیگه داروم رو بخورم", baseTime)
        assertNotNull(r6)
        assertEquals(baseTime + 12 * 3600 * 1000L, r6)

        // 7. فردا همین موقع (24 hours)
        val r7 = PersianDateEngine.parsePersianTimePhrase("فردا همین موقع قبض رو پرداخت کنم", baseTime)
        assertNotNull(r7)
        assertEquals(baseTime + 24 * 3600 * 1000L, r7)

        // 8. پس‌فردا همین موقع (48 hours)
        val r8 = PersianDateEngine.parsePersianTimePhrase("پس‌فردا همین موقع پیگیری سفارش", baseTime)
        assertNotNull(r8)
        assertEquals(baseTime + 48 * 3600 * 1000L, r8)
    }

    @Test
    fun testMandatoryScenarios_FutureJalaliDates() {
        val baseTime = 1774000000000L
        val curJalali = PersianDateEngine.timestampToJalali(baseTime)

        // 9. سال آینده ۱۳ آذر ساعت ۱۴
        val r9 = PersianDateEngine.parsePersianTimePhrase("سال آینده سیزدهم آذر ساعت ۱۴ برای تمدید قرارداد یادم بنداز", baseTime)
        assertNotNull(r9)
        val j9 = PersianDateEngine.timestampToJalali(r9!!)
        assertEquals(curJalali.year + 1, j9.year)
        assertEquals(9, j9.month) // Azar is 9th month
        assertEquals(13, j9.day)
        assertEquals(14, j9.hour)

        // 10. سال آینده چهاردهم برج پنج ساعت ۷ غروب (14 Mordad, 19:00)
        val r10 = PersianDateEngine.parsePersianTimePhrase("سال آینده روز ۱۴ برج ۵ ساعت ۷ غروب با علی جلسه دارم", baseTime)
        assertNotNull(r10)
        val j10 = PersianDateEngine.timestampToJalali(r10!!)
        assertEquals(curJalali.year + 1, j10.year)
        assertEquals(5, j10.month) // Mordad is 5th month
        assertEquals(14, j10.day)
        assertEquals(19, j10.hour) // 7 غروب = 19:00
    }

    @Test
    fun testMultiTaskMindDumpParsing() {
        val parser = GeminiSemanticParser()
        val speech = "پنج دقیقه دیگه آب رو چک کنم و فردا ۹ صبح با علی تماس بگیرم و پس‌فردا همین موقع سفارش چاپ رو پیگیری کنم"
        val tasks = parser.fallbackPersianParse(speech)

        assertEquals(3, tasks.size)
        assertTrue(tasks[0].title.contains("آب"))
        assertTrue(tasks[1].title.contains("علی"))
        assertTrue(tasks[2].title.contains("چاپ") || tasks[2].title.contains("سفارش"))
    }

    @Test
    fun testPasFardaHourResolution() {
        val baseTime = 1774000000000L // arbitrary fixed timestamp
        val curCal = Calendar.getInstance().apply { timeInMillis = baseTime }

        // Test 1: "پس فردا ساعت ۳" must be +2 days at 15:00
        val t1 = PersianDateEngine.parsePersianTimePhrase("پس فردا ساعت ۳ جلسه با مدیر", baseTime)
        assertNotNull(t1)
        val cal1 = Calendar.getInstance().apply { timeInMillis = t1!! }
        val exp1 = Calendar.getInstance().apply { timeInMillis = baseTime; add(Calendar.DAY_OF_YEAR, 2) }
        assertEquals(exp1.get(Calendar.DAY_OF_YEAR), cal1.get(Calendar.DAY_OF_YEAR))
        assertEquals(15, cal1.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal1.get(Calendar.MINUTE))

        // Test 2: "پس‌فردا ساعت ۳" (half-space) must also be +2 days at 15:00
        val t2 = PersianDateEngine.parsePersianTimePhrase("پس‌فردا ساعت ۳", baseTime)
        assertNotNull(t2)
        val cal2 = Calendar.getInstance().apply { timeInMillis = t2!! }
        val exp2 = Calendar.getInstance().apply { timeInMillis = baseTime; add(Calendar.DAY_OF_YEAR, 2) }
        assertEquals(exp2.get(Calendar.DAY_OF_YEAR), cal2.get(Calendar.DAY_OF_YEAR))
        assertEquals(15, cal2.get(Calendar.HOUR_OF_DAY))

        // Test 3: "پس فردا ساعت ۳ صبح" must be +2 days at 03:00
        val t3 = PersianDateEngine.parsePersianTimePhrase("پس فردا ساعت ۳ صبح برم فرودگاه", baseTime)
        assertNotNull(t3)
        val cal3 = Calendar.getInstance().apply { timeInMillis = t3!! }
        val exp3 = Calendar.getInstance().apply { timeInMillis = baseTime; add(Calendar.DAY_OF_YEAR, 2) }
        assertEquals(exp3.get(Calendar.DAY_OF_YEAR), cal3.get(Calendar.DAY_OF_YEAR))
        assertEquals(3, cal3.get(Calendar.HOUR_OF_DAY))

        // Test 4: "فردا ساعت ۳" must be +1 day at 15:00
        val t4 = PersianDateEngine.parsePersianTimePhrase("فردا ساعت ۳ دندانپزشکی", baseTime)
        assertNotNull(t4)
        val cal4 = Calendar.getInstance().apply { timeInMillis = t4!! }
        val exp4 = Calendar.getInstance().apply { timeInMillis = baseTime; add(Calendar.DAY_OF_YEAR, 1) }
        assertEquals(exp4.get(Calendar.DAY_OF_YEAR), cal4.get(Calendar.DAY_OF_YEAR))
        assertEquals(15, cal4.get(Calendar.HOUR_OF_DAY))

        // Test 5: "فردا ۹ صبح" must be +1 day at 09:00
        val t5 = PersianDateEngine.parsePersianTimePhrase("فردا ۹ صبح جلسه تیمی", baseTime)
        assertNotNull(t5)
        val cal5 = Calendar.getInstance().apply { timeInMillis = t5!! }
        val exp5 = Calendar.getInstance().apply { timeInMillis = baseTime; add(Calendar.DAY_OF_YEAR, 1) }
        assertEquals(exp5.get(Calendar.DAY_OF_YEAR), cal5.get(Calendar.DAY_OF_YEAR))
        assertEquals(9, cal5.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testUserRequestedPersianCalendarExpressions() {
        val baseTime = 1774000000000L // arbitrary fixed timestamp
        val curCal = Calendar.getInstance().apply { timeInMillis = baseTime }
        val curJalali = PersianDateEngine.timestampToJalali(baseTime)

        // 1. "پسفردا ساعت ۳" (without space in پسفردا) -> Day after tomorrow at 15:00
        val task1 = PersianDateEngine.extractPersianTask("پسفردا ساعت ۳", baseTime)
        assertNotNull(task1.triggerTimestamp)
        val cal1 = Calendar.getInstance().apply { timeInMillis = task1.triggerTimestamp }
        val exp1 = Calendar.getInstance().apply { timeInMillis = baseTime; add(Calendar.DAY_OF_YEAR, 2) }
        assertEquals(exp1.get(Calendar.DAY_OF_YEAR), cal1.get(Calendar.DAY_OF_YEAR))
        assertEquals(15, cal1.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal1.get(Calendar.MINUTE))
        assertEquals("یادآوری پس‌فردا ساعت ۱۵:۰۰", task1.title)

        // 2. "دو ساعت دیگر" -> Exactly +2 hours from now
        val task2 = PersianDateEngine.extractPersianTask("دو ساعت دیگر چک کردن ایمیل", baseTime)
        assertEquals(baseTime + 2 * 3600 * 1000L, task2.triggerTimestamp)
        assertEquals("چک کردن ایمیل", task2.title)

        // 3. "سال دیگه همین موقع" -> Exactly 1 Jalali year later at current time
        val task3 = PersianDateEngine.extractPersianTask("سال دیگه همین موقع تمدید سرور", baseTime)
        val j3 = PersianDateEngine.timestampToJalali(task3.triggerTimestamp)
        assertEquals(curJalali.year + 1, j3.year)
        assertEquals(curJalali.month, j3.month)
        assertEquals(curJalali.day, j3.day)
        assertEquals(curJalali.hour, j3.hour)
        assertEquals(curJalali.minute, j3.minute)
        assertEquals("تمدید سرور", task3.title)

        // 4. "دو روز دیگه ساعت ۸ شب" -> +2 days at 20:00 (8 PM)
        val task4 = PersianDateEngine.extractPersianTask("دو روز دیگه ساعت ۸ شب مهمانی با دوستان", baseTime)
        val cal4 = Calendar.getInstance().apply { timeInMillis = task4.triggerTimestamp }
        val exp4 = Calendar.getInstance().apply { timeInMillis = baseTime; add(Calendar.DAY_OF_YEAR, 2) }
        assertEquals(exp4.get(Calendar.DAY_OF_YEAR), cal4.get(Calendar.DAY_OF_YEAR))
        assertEquals(20, cal4.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal4.get(Calendar.MINUTE))
        assertEquals("مهمانی با دوستان", task4.title)

        // 5. "یک ربع به ۴ عصر" -> 15:45
        val task5 = PersianDateEngine.extractPersianTask("یک ربع به ۴ عصر جلسه کاری", baseTime)
        val cal5 = Calendar.getInstance().apply { timeInMillis = task5.triggerTimestamp }
        assertEquals(15, cal5.get(Calendar.HOUR_OF_DAY))
        assertEquals(45, cal5.get(Calendar.MINUTE))
        assertEquals("جلسه کاری", task5.title)
    }

    @Test
    fun testTitleExtractionCleanliness() {
        val parser = GeminiSemanticParser()
        val speech = "پس فردا ساعت ۳ جلسه با مدیر عامل"
        val tasks = parser.fallbackPersianParse(speech)

        assertEquals(1, tasks.size)
        val task = tasks[0]
        assertEquals("جلسه با مدیر عامل", task.title)
        assertTrue(!task.title.contains("پس"))
        assertTrue(!task.title.contains("ساعت"))
        assertTrue(!task.title.contains("۳"))

        val speech2 = "یادم بنداز پس فردا ساعت ۳ خرید نان سنگک"
        val tasks2 = parser.fallbackPersianParse(speech2)
        assertEquals("خرید نان سنگک", tasks2[0].title)

        val speech3 = "دو روز دیگه ساعت ۸ شب مهمانی"
        val tasks3 = parser.fallbackPersianParse(speech3)
        assertEquals("مهمانی", tasks3[0].title)
    }

    @Test
    fun testClarificationDetectionForMissingTimeOrDate() {
        val parser = GeminiSemanticParser()
        // Input with NO time or date: e.g. "خرید نان سنگک و پنیر"
        val tasks = parser.fallbackPersianParse("خرید نان سنگک و پنیر")
        assertEquals(1, tasks.size)
        val task = tasks[0]
        assertTrue("Task should need clarification when time/date is missing", task.needsClarification)
        assertNotNull(task.clarificationQuestion)
        assertTrue(task.clarificationQuestion!!.contains("تاریخ") && task.clarificationQuestion!!.contains("ساعت"))
    }

    @Test
    fun testClarificationDetectionForMissingTitle() {
        val parser = GeminiSemanticParser()
        // Input with ONLY calendar time: e.g. "پس‌فردا ساعت ۳"
        val tasks = parser.fallbackPersianParse("پس‌فردا ساعت ۳")
        assertEquals(1, tasks.size)
        val task = tasks[0]
        assertTrue("Task should need clarification when title is missing", task.needsClarification)
        assertNotNull(task.clarificationQuestion)
        assertTrue(task.clarificationQuestion!!.contains("عنوان یا موضوع"))
    }

    @Test
    fun testCompleteTaskNeedsNoClarification() {
        val parser = GeminiSemanticParser()
        // Complete input: Title + Date + Time
        val tasks = parser.fallbackPersianParse("فردا ساعت ۹ صبح جلسه با مدیر عامل")
        assertEquals(1, tasks.size)
        val task = tasks[0]
        assertTrue(!task.needsClarification)
        assertEquals(null, task.clarificationQuestion)
        assertEquals("جلسه با مدیر عامل", task.title)
    }
}
