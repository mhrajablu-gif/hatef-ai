package com.example.service.conversation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Phase 1 Comprehensive Test Suite for Yadet AI Conversational Engine.
 * Verifies:
 * 1. Intent classification precision: non-reminder conversational statements MUST NOT create reminders or enter Slot Filling.
 * 2. Explicit reminder triggers properly enter scheduling/slot-filling.
 * 3. Consultation and health questions are routed to conversational AI rather than reminders.
 * 4. Multi-turn preservation and deduplication safeguards.
 */
class Phase1ConversationalEngineTest {

    private lateinit var engine: ConversationalAiEngine
    private val tehranTimeZone = TimeZone.getTimeZone("Asia/Tehran")

    // Fixed reference time: Monday 1405-08-04 10:00 AM local
    private val fixedReferenceMillis: Long by lazy {
        Calendar.getInstance(tehranTimeZone).apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.OCTOBER)
            set(Calendar.DAY_OF_MONTH, 26)
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    @Before
    fun setUp() {
        engine = ConversationalAiEngine(toolRegistry = null, defaultTimeZone = tehranTimeZone)
    }

    // ==========================================
    // بخش ۱: راستی‌آزمایی جملات عمومی و عدم ایجاد یادآور ناخواسته
    // ==========================================

    @Test
    fun test01_ExamStatementDoesNotCreateReminder() = runBlocking {
        val input = "فردا امتحان سختی دارم"
        val response = engine.processTurn(
            userInput = input,
            currentState = ConversationState(),
            referenceTimeMillis = fixedReferenceMillis
        )
        assertNotEquals("نباید نیت ایجاد یادآور فعال شود", ConversationIntent.CREATE_REMINDER, response.newState.activeIntent)
        assertFalse("نباید در حال انتظار فیلد یا اسلات فیلینگ باشد", response.newState.isWaitingForAnswer())
        assertFalse("پاسخ نباید درخواست جزئیات یادآور باشد", response.replyText.contains("چه کاری و در چه زمانی را مایلید یادتان بیندازم"))
    }

    @Test
    fun test02_TirednessAndRestStatementDoesNotCreateReminder() = runBlocking {
        val input = "امروز خیلی خسته‌ام و باید استراحت کنم"
        val response = engine.processTurn(
            userInput = input,
            currentState = ConversationState(),
            referenceTimeMillis = fixedReferenceMillis
        )
        assertNotEquals("نباید نیت ایجاد یادآور فعال شود", ConversationIntent.CREATE_REMINDER, response.newState.activeIntent)
        assertFalse("نباید وارد اسلات فیلینگ شود", response.newState.isWaitingForAnswer())
        assertFalse("پاسخ نباید خطای متوجه نشدم باشد", response.replyText.contains("چه کاری و در چه زمانی را مایلید یادتان بیندازم"))
    }

    @Test
    fun test03_WakingUpHabitStatementDoesNotCreateReminder() = runBlocking {
        val input = "ساعت ۸ معمولاً از خواب بیدار میشوم"
        val response = engine.processTurn(
            userInput = input,
            currentState = ConversationState(),
            referenceTimeMillis = fixedReferenceMillis
        )
        assertNotEquals("نباید نیت یادآور داشته باشد", ConversationIntent.CREATE_REMINDER, response.newState.activeIntent)
        assertFalse("نباید وارد اسلات فیلینگ شود", response.newState.isWaitingForAnswer())
    }

    @Test
    fun test04_HealthCareStatementDoesNotCreateReminder() = runBlocking {
        val input = "باید بیشتر به سلامتی‌ام اهمیت بدهم"
        val response = engine.processTurn(
            userInput = input,
            currentState = ConversationState(),
            referenceTimeMillis = fixedReferenceMillis
        )
        assertNotEquals("نباید نیت یادآور داشته باشد", ConversationIntent.CREATE_REMINDER, response.newState.activeIntent)
        assertFalse("نباید وارد اسلات فیلینگ شود", response.newState.isWaitingForAnswer())
    }

    @Test
    fun test05_AiStudyPlanStatementDoesNotCreateReminder() = runBlocking {
        val input = "فردا می‌خواهم درباره هوش مصنوعی مطالعه کنم"
        val response = engine.processTurn(
            userInput = input,
            currentState = ConversationState(),
            referenceTimeMillis = fixedReferenceMillis
        )
        assertNotEquals("نباید نیت یادآور داشته باشد", ConversationIntent.CREATE_REMINDER, response.newState.activeIntent)
        assertFalse("نباید وارد اسلات فیلینگ شود", response.newState.isWaitingForAnswer())
    }

    @Test
    fun test06_FeelingUnwellStatementDoesNotCreateReminder() = runBlocking {
        val input = "امروز حالم خوب نیست"
        val response = engine.processTurn(
            userInput = input,
            currentState = ConversationState(),
            referenceTimeMillis = fixedReferenceMillis
        )
        assertNotEquals("نباید نیت یادآور داشته باشد", ConversationIntent.CREATE_REMINDER, response.newState.activeIntent)
        assertFalse("نباید وارد اسلات فیلینگ شود", response.newState.isWaitingForAnswer())
    }

    @Test
    fun test07_TravelStatementDoesNotCreateReminder() = runBlocking {
        val input = "هفته آینده احتمالاً سفر می‌روم"
        val response = engine.processTurn(
            userInput = input,
            currentState = ConversationState(),
            referenceTimeMillis = fixedReferenceMillis
        )
        assertNotEquals("نباید نیت یادآور داشته باشد", ConversationIntent.CREATE_REMINDER, response.newState.activeIntent)
        assertFalse("نباید وارد اسلات فیلینگ شود", response.newState.isWaitingForAnswer())
    }

    @Test
    fun test08_LateNightWorkoutAdviceQuestion() = runBlocking {
        val input = "به نظرت ورزش ساعت ۱۲ شب خوبه؟"
        val response = engine.processTurn(
            userInput = input,
            currentState = ConversationState(),
            referenceTimeMillis = fixedReferenceMillis
        )
        // Consultation question should route to CONSULTATION_OR_ADVICE or INFORMATIONAL_QA, never CREATE_REMINDER
        assertNotEquals("نباید یادآور ایجاد کند", ConversationIntent.CREATE_REMINDER, response.newState.activeIntent)
        assertFalse("نباید در حال اسلات فیلینگ باشد", response.newState.isWaitingForAnswer())
        assertTrue("پاسخ باید معنادار و بدون خطای متوجه نشدم باشد", response.replyText.isNotBlank())
    }

    // ==========================================
    // بخش ۲: راستی‌آزمایی دستورات صریح تنظیم یادآور و آلارم
    // ==========================================

    @Test
    fun test09_ExplicitReminderBankCreation() = runBlocking {
        val input = "فردا ساعت ۸ یادم بنداز به بانک بروم"
        val response = engine.processTurn(
            userInput = input,
            currentState = ConversationState(),
            referenceTimeMillis = fixedReferenceMillis
        )
        // Must recognize intent as reminder creation
        assertEquals("نیت باید CREATE_REMINDER باشد", ConversationIntent.CREATE_REMINDER, response.newState.activeIntent)
        assertNotNull("پیش‌نویس یادآور باید ساخته شود", response.newState.draftTask)
        assertTrue("عنوان باید شامل بانک باشد", response.newState.draftTask?.title?.contains("بانک") == true)
        assertEquals("ساعت باید ۰۸:۰۰ باشد", "۰۸:۰۰", response.newState.draftTask?.timeText)
    }

    @Test
    fun test10_ExplicitSlotFillingWhenTitleMissing() = runBlocking {
        val input = "لطفاً برای ساعت ۱۰ یک یادآور ثبت کن"
        val response = engine.processTurn(
            userInput = input,
            currentState = ConversationState(),
            referenceTimeMillis = fixedReferenceMillis
        )
        assertEquals("نیت باید CREATE_REMINDER باشد", ConversationIntent.CREATE_REMINDER, response.newState.activeIntent)
        assertTrue("باید منتظر دریافت عنوان باشد", response.newState.awaitingField == AwaitingField.TITLE)
        assertTrue("پاسخ باید بپرسد چه کاری", response.replyText.contains("چه کاری"))
    }

    @Test
    fun test11_ExplicitReminderWithContact() = runBlocking {
        val input = "پنجشنبه ساعت ۶ عصر یادآوری کن با احمد تماس بگیرم"
        val response = engine.processTurn(
            userInput = input,
            currentState = ConversationState(),
            referenceTimeMillis = fixedReferenceMillis
        )
        assertEquals("نیت باید CREATE_REMINDER باشد", ConversationIntent.CREATE_REMINDER, response.newState.activeIntent)
        assertNotNull("پیش‌نویس یادآور باید ساخته شود", response.newState.draftTask)
        assertTrue("عنوان باید تماس با احمد باشد", response.newState.draftTask?.title?.contains("احمد") == true)
    }

    @Test
    fun test12_ExplicitRelativeOffsetReminder() = runBlocking {
        val input = "نیم ساعت دیگر یادم بنداز غذا را از روی گاز بردارم"
        val response = engine.processTurn(
            userInput = input,
            currentState = ConversationState(),
            referenceTimeMillis = fixedReferenceMillis
        )
        assertEquals("نیت باید CREATE_REMINDER باشد", ConversationIntent.CREATE_REMINDER, response.newState.activeIntent)
        assertNotNull("پیش‌نویس باید ساخته شود", response.newState.draftTask)
        assertTrue("عنوان باید شامل گاز یا غذا باشد", response.newState.draftTask?.title?.contains("غذا") == true || response.newState.draftTask?.title?.contains("گاز") == true)
    }

    // ==========================================
    // بخش ۳: راستی‌آزمایی تداوم تاریخچه مکالمه و عدم تکرار
    // ==========================================

    @Test
    fun test13_ConversationHistoryMultiTurnPreservation() = runBlocking {
        val history = listOf(
            ChatMessage(sender = MessageSender.USER, text = "سلام"),
            ChatMessage(sender = MessageSender.ASSISTANT, text = "درود! چطور می‌تونم کمکتون کنم؟")
        )
        val response = engine.processTurn(
            userInput = "امروز حالم زیاد خوب نیست، سرم درد می‌کنه",
            currentState = ConversationState(),
            recentHistory = history,
            referenceTimeMillis = fixedReferenceMillis
        )
        assertNotEquals("نباید یادآور ایجاد کند", ConversationIntent.CREATE_REMINDER, response.newState.activeIntent)
        assertFalse("نباید منتظر اسلات باشد", response.newState.isWaitingForAnswer())
        assertTrue("پاسخ هوش مصنوعی نباید خالی باشد", response.replyText.isNotBlank())
    }

    // ==========================================
    // بخش ۴: سناریوهای الزامی رفع باگ «تماس با»، «جلسه با»، «قرار با»
    // ==========================================

    @Test
    fun test14_CallWithAliStatementDoesNotCreateReminder() = runBlocking {
        val input = "فردا با علی تماس دارم."
        val response = engine.processTurn(
            userInput = input,
            currentState = ConversationState(),
            referenceTimeMillis = fixedReferenceMillis
        )
        assertNotEquals("«فردا با علی تماس دارم.» نباید یادآور ایجاد کند", ConversationIntent.CREATE_REMINDER, response.newState.activeIntent)
        assertFalse("نباید در حال اسلات فیلینگ باشد", response.newState.isWaitingForAnswer())
    }

    @Test
    fun test15_ManagerMeetingStatementDoesNotCreateReminder() = runBlocking {
        val input = "امروز با مدیر جلسه دارم."
        val response = engine.processTurn(
            userInput = input,
            currentState = ConversationState(),
            referenceTimeMillis = fixedReferenceMillis
        )
        assertNotEquals("«امروز با مدیر جلسه دارم.» نباید یادآور ایجاد کند", ConversationIntent.CREATE_REMINDER, response.newState.activeIntent)
        assertFalse("نباید در حال اسلات فیلینگ باشد", response.newState.isWaitingForAnswer())
    }

    @Test
    fun test16_DoctorConsultationStatementDoesNotCreateReminder() = runBlocking {
        val input = "هفته آینده باید با پزشک صحبت کنم."
        val response = engine.processTurn(
            userInput = input,
            currentState = ConversationState(),
            referenceTimeMillis = fixedReferenceMillis
        )
        assertNotEquals("«هفته آینده باید با پزشک صحبت کنم.» نباید یادآور ایجاد کند", ConversationIntent.CREATE_REMINDER, response.newState.activeIntent)
        assertFalse("نباید در حال اسلات فیلینگ باشد", response.newState.isWaitingForAnswer())
    }

    @Test
    fun test17_CallAtEightStatementDoesNotCreateReminder() = runBlocking {
        val input = "فردا ساعت ۸ با علی تماس میگیرم."
        val response = engine.processTurn(
            userInput = input,
            currentState = ConversationState(),
            referenceTimeMillis = fixedReferenceMillis
        )
        assertNotEquals("«فردا ساعت ۸ با علی تماس میگیرم.» نباید یادآور ایجاد کند", ConversationIntent.CREATE_REMINDER, response.newState.activeIntent)
        assertFalse("نباید در حال اسلات فیلینگ باشد", response.newState.isWaitingForAnswer())
    }

    @Test
    fun test18_WakeUpEarlyStatementDoesNotCreateReminder() = runBlocking {
        val input = "فردا باید زود بیدار شوم."
        val response = engine.processTurn(
            userInput = input,
            currentState = ConversationState(),
            referenceTimeMillis = fixedReferenceMillis
        )
        assertNotEquals("«فردا باید زود بیدار شوم.» نباید یادآور ایجاد کند", ConversationIntent.CREATE_REMINDER, response.newState.activeIntent)
        assertFalse("نباید در حال اسلات فیلینگ باشد", response.newState.isWaitingForAnswer())
    }

    @Test
    fun test19_WorkoutAtMidnightStatementDoesNotCreateReminder() = runBlocking {
        val input = "ساعت ۱۲ شب ورزش کردن مناسب نیست."
        val response = engine.processTurn(
            userInput = input,
            currentState = ConversationState(),
            referenceTimeMillis = fixedReferenceMillis
        )
        assertNotEquals("«ساعت ۱۲ شب ورزش کردن مناسب نیست.» نباید یادآور ایجاد کند", ConversationIntent.CREATE_REMINDER, response.newState.activeIntent)
        assertFalse("نباید در حال اسلات فیلینگ باشد", response.newState.isWaitingForAnswer())
    }

    @Test
    fun test20_ExplicitCallReminderCreated() = runBlocking {
        val input = "یادم بنداز فردا ساعت ۸ با علی تماس بگیرم."
        val response = engine.processTurn(
            userInput = input,
            currentState = ConversationState(),
            referenceTimeMillis = fixedReferenceMillis
        )
        assertEquals("باید نیت CREATE_REMINDER باشد", ConversationIntent.CREATE_REMINDER, response.newState.activeIntent)
        assertNotNull("پیش‌نویس یادآور باید ساخته شود", response.newState.draftTask)
        assertTrue("عنوان باید شامل علی یا تماس باشد", response.newState.draftTask?.title?.contains("علی") == true || response.newState.draftTask?.title?.contains("تماس") == true)
        assertEquals("ساعت باید ۰۸:۰۰ باشد", "۰۸:۰۰", response.newState.draftTask?.timeText)
    }

    @Test
    fun test21_ExplicitMeetingReminderCreated() = runBlocking {
        val input = "برای فردا ساعت ۱۰ جلسه با مدیر ثبت کن."
        val response = engine.processTurn(
            userInput = input,
            currentState = ConversationState(),
            referenceTimeMillis = fixedReferenceMillis
        )
        assertEquals("باید نیت CREATE_REMINDER باشد", ConversationIntent.CREATE_REMINDER, response.newState.activeIntent)
        assertNotNull("پیش‌نویس یادآور باید ساخته شود", response.newState.draftTask)
        assertTrue("عنوان باید شامل جلسه یا مدیر باشد", response.newState.draftTask?.title?.contains("مدیر") == true || response.newState.draftTask?.title?.contains("جلسه") == true)
        assertEquals("ساعت باید ۱۰:۰۰ باشد", "۱۰:۰۰", response.newState.draftTask?.timeText)
    }

    @Test
    fun test22_ExplicitDoctorAppointmentReminderCreated() = runBlocking {
        val input = "لطفاً برای پنجشنبه وقت دکتر تنظیم کن."
        val response = engine.processTurn(
            userInput = input,
            currentState = ConversationState(),
            referenceTimeMillis = fixedReferenceMillis
        )
        assertEquals("باید نیت CREATE_REMINDER باشد", ConversationIntent.CREATE_REMINDER, response.newState.activeIntent)
        assertNotNull("پیش‌نویس یادآور باید ساخته شود", response.newState.draftTask)
        assertTrue("عنوان باید شامل دکتر یا وقت باشد", response.newState.draftTask?.title?.contains("دکتر") == true || response.newState.draftTask?.title?.contains("وقت") == true)
    }
}
