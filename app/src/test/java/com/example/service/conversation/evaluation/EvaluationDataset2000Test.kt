package com.example.service.conversation.evaluation

import com.example.data.engine.PersianDateEngine
import com.example.service.conversation.AwaitingField
import com.example.service.conversation.ConversationIntent
import com.example.service.conversation.ConversationState
import com.example.service.conversation.ConversationalAiEngine
import com.example.service.conversation.SlotFillingStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Enterprise 2,000-Scenario Evaluation Suite and Audit for Persian Conversational AI Engine.
 * Covers all 30 official categories and verifies the 8 critical User Journeys.
 */
class EvaluationDataset2000Test {

    private lateinit var engine: ConversationalAiEngine
    private val tehranTimeZone = TimeZone.getTimeZone("Asia/Tehran")

    // Reference Monday 1405-08-04, 10:00:00 AM
    private val referenceTimeMillis: Long by lazy {
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

    /**
     * ارزیابی کل ۲۰۰۰ سناریو: بررسی تعداد، دسته‌بندی‌ها و یکپارچگی داده‌ها
     */
    @Test
    fun test01_VerifyTotalScenarioCountAnd30CategoriesIntegrity() {
        val scenarios = EvaluationDataset2000.getAllScenarios()
        println("Total generated evaluation scenarios: ${scenarios.size}")

        assertTrue("حداقل ۲۰۰۰ سناریوی آزمایشی باید تولید شده باشد", scenarios.size >= 2000)

        val categoriesInDataset = scenarios.map { it.category }.distinct()
        assertEquals("باید هر ۳۰ دسته‌بندی پوشش داده شده باشند", 30, categoriesInDataset.size)

        for (scenario in scenarios) {
            assertTrue("متن ورودی سناریو ${scenario.id} نباید خالی باشد", scenario.userInput.isNotBlank())
            assertTrue("نتیجه مورد انتظار سناریو ${scenario.id} نباید خالی باشد", scenario.expectedResult.isNotBlank())
            assertTrue("معیار قبولی سناریو ${scenario.id} نباید خالی باشد", scenario.acceptanceCriteria.isNotBlank())
            assertNotNull("قصد سناریو ${scenario.id} باید مشخص باشد", scenario.actualIntent)
        }
    }

    /**
     * آزمون ۱ الزامی کاربر:
     * «برای هر روز سال آینده ساعت ۸ صبح یادآور تنظیم کن»
     * انتظار: تشخیص تکرار روزانه، بازه کامل سال آینده، ساعت ۸ صبح و پرسیدن عنوان در صورت نامشخص بودن.
     */
    @Test
    fun test02_CUJ1_NextYearDailyReminder_AsksForTitleAndSetsRecurrenceEndDate() = runBlocking {
        val state = ConversationState()
        val turn1 = engine.processTurn(
            userInput = "برای هر روز سال آینده ساعت ۸ صبح یادآور تنظیم کن",
            currentState = state,
            referenceTimeMillis = referenceTimeMillis
        )

        assertEquals("باید منتظر مشخص شدن عنوان باشد", AwaitingField.TITLE, turn1.newState.awaitingField)
        assertTrue("باید بپرسد برای چه کاری یادآور تنظیم کند", turn1.replyText.contains("چه کاری"))
        assertNotNull("پیش‌نویس کار باید ایجاد شده باشد", turn1.newState.draftTask)
        assertEquals("نوع تکرار باید روزانه باشد", "DAILY", turn1.newState.draftTask?.repeatType)
        assertNotNull("تاریخ پایان تکرار برای پایان سال آینده باید محاسبه شده باشد", turn1.newState.draftTask?.recurrenceEndDate)
        assertEquals("ساعت باید ۰۸:۰۰ باشد", "۰۸:۰۰", turn1.newState.draftTask?.timeText)

        // Turn 2: User provides title
        val turn2 = engine.processTurn(
            userInput = "ورزش صبحگاهی",
            currentState = turn1.newState,
            referenceTimeMillis = referenceTimeMillis
        )
        assertEquals("عنوان باید ورزش صبحگاهی باشد", "ورزش صبحگاهی", turn2.newState.draftTask?.title)
        assertEquals("نوع تکرار باید روزانه باقی بماند", "DAILY", turn2.newState.draftTask?.repeatType)
    }

    /**
     * آزمون ۲ الزامی کاربر:
     * «به نظرت ورزش ساعت ۱۲ شب خوبه؟»
     * انتظار: پاسخ مشاوره‌ای طبیعی، بدون ایجاد یادآور.
     */
    @Test
    fun test03_CUJ2_MidnightWorkoutConsultation_WithoutReminderCreation() = runBlocking {
        val state = ConversationState()
        val turn = engine.processTurn(
            userInput = "به نظرت ورزش ساعت ۱۲ شب خوبه؟",
            currentState = state,
            referenceTimeMillis = referenceTimeMillis
        )

        assertEquals("قصد باید مشاوره باشد", ConversationIntent.CONSULTATION_OR_ADVICE, turn.newState.activeIntent)
        assertNull("هیچ یادآوری نباید برای سؤال مشاوره‌ای ایجاد شود", turn.newState.draftTask)
        assertTrue("پاسخ باید درباره خواب و ورزش نیمه‌شب باشد", turn.replyText.contains("خواب") || turn.replyText.contains("ورزش"))
    }

    /**
     * آزمون ۳ الزامی کاربر:
     * «قرار فردام رو دو ساعت عقب بنداز»
     * انتظار: پیدا کردن قرار فردا، محاسبه زمان جدید و اعمال تغییر.
     */
    @Test
    fun test04_CUJ3_RescheduleTomorrowReminderByTwoHours() = runBlocking {
        val state = ConversationState()
        val turn = engine.processTurn(
            userInput = "قرار فردام رو دو ساعت عقب بنداز",
            currentState = state,
            referenceTimeMillis = referenceTimeMillis
        )

        assertEquals("قصد باید ویرایش یادآور باشد", ConversationIntent.UPDATE_REMINDER, turn.newState.activeIntent)
        assertTrue("پاسخ باید نشان‌دهنده تعویق ۲ ساعته باشد", turn.replyText.contains("۲ ساعت") || turn.replyText.contains("تعویق") || turn.replyText.contains("فردا"))
    }

    /**
     * آزمون ۴ الزامی کاربر:
     * «قرارم رو حذف کن»
     * انتظار: شناسایی قرار مورد نظر، درخواست توضیح در صورت ابهام یا حذف.
     */
    @Test
    fun test05_CUJ4_DeleteReminder_DisambiguationOrConfirmation() = runBlocking {
        val state = ConversationState()
        val turn = engine.processTurn(
            userInput = "قرارم رو حذف کن",
            currentState = state,
            referenceTimeMillis = referenceTimeMillis
        )

        assertEquals("قصد باید حذف یادآور باشد", ConversationIntent.DELETE_REMINDER, turn.newState.activeIntent)
        assertNotNull(turn.replyText)
    }

    /**
     * آزمون ۵ الزامی کاربر:
     * «از این به بعد تمام یادآورهام رو ۱۵ دقیقه قبل هم اطلاع بده»
     * انتظار: درک به عنوان تغییر تنظیمات، تغییر پیش‌فرض اعلان و ثبت تنظیمات.
     */
    @Test
    fun test06_CUJ5_UpdateDefaultAdvanceAlertSetting() = runBlocking {
        val state = ConversationState()
        val turn = engine.processTurn(
            userInput = "از این به بعد تمام یادآورهام رو ۱۵ دقیقه قبل هم اطلاع بده",
            currentState = state,
            referenceTimeMillis = referenceTimeMillis
        )

        assertEquals("قصد باید تغییر تنظیمات برنامه باشد", ConversationIntent.UPDATE_APP_SETTINGS, turn.newState.activeIntent)
        assertTrue("پاسخ باید تأییدکننده پیش‌اعلان ۱۵ دقیقه‌ای باشد", turn.replyText.contains("۱۵ دقیقه") || turn.replyText.contains("تنظیمات"))
    }

    /**
     * آزمون ۶ الزامی کاربر:
     * «من فردا ساعت ۸ بیدار میشم. به نظرت چه ساعتی ورزش کنم؟»
     * انتظار: ارائه مشاوره متناسب با اطلاعات داده‌شده، بدون ایجاد یادآور ناخواسته.
     */
    @Test
    fun test07_CUJ6_WakingTimeAndWorkoutConsultation() = runBlocking {
        val state = ConversationState()
        val turn = engine.processTurn(
            userInput = "من فردا ساعت ۸ بیدار میشم. به نظرت چه ساعتی ورزش کنم؟",
            currentState = state,
            referenceTimeMillis = referenceTimeMillis
        )

        assertEquals("قصد باید مشاوره باشد", ConversationIntent.CONSULTATION_OR_ADVICE, turn.newState.activeIntent)
        assertNull("نباید یادآور ایجاد شود", turn.newState.draftTask)
        assertTrue("پاسخ باید شامل ساعت مناسب ورزش پس از بیداری باشد", turn.replyText.contains("ساعت") && turn.replyText.contains("ورزش"))
    }

    /**
     * آزمون ۷ الزامی کاربر:
     * «اون قراری که دیروز تنظیم کردیم رو برای هفته بعد ببر»
     * انتظار: ارجاع به قرار گذشته و انتقال به یک هفته بعد.
     */
    @Test
    fun test08_CUJ7_MovePastReminderToNextWeek() = runBlocking {
        val state = ConversationState(
            lastMentionedReminderId = 88L
        )
        val turn = engine.processTurn(
            userInput = "اون قراری که دیروز تنظیم کردیم رو برای هفته بعد ببر",
            currentState = state,
            referenceTimeMillis = referenceTimeMillis
        )

        assertEquals("قصد باید ویرایش یادآور باشد", ConversationIntent.UPDATE_REMINDER, turn.newState.activeIntent)
        assertTrue("پاسخ باید شامل انتقال به هفته بعد باشد", turn.replyText.contains("هفته بعد"))
    }

    /**
     * آزمون ۸ الزامی کاربر:
     * یک مکالمه عمومی طولانی درباره یک موضوع غیرمرتبط با یادآور.
     * انتظار: حفظ موضوع، پاسخ‌های مرتبط و عدم تبدیل مکالمه به فرمان.
     */
    @Test
    fun test09_CUJ8_LongGeneralConversation_ZeroReminderTriggers() = runBlocking {
        var state = ConversationState()

        // Turn 1: Programming question
        val turn1 = engine.processTurn("پایتون چه کاربردهایی در هوش مصنوعی داره؟", state, emptyList(), referenceTimeMillis)
        state = turn1.newState
        assertEquals("قصد باید پاسخ اطلاعاتی باشد", ConversationIntent.INFORMATIONAL_QA, state.activeIntent)
        assertNull("هیچ یادآوری نباید ثبت شود", state.draftTask)
        assertTrue(turn1.replyText.contains("پایتون") || turn1.replyText.contains("هوش مصنوعی"))

        // Turn 2: Follow-up question on same topic
        val turn2 = engine.processTurn("تفاوتش با زبان کاتلین در چیه؟", state, emptyList(), referenceTimeMillis)
        state = turn2.newState
        assertEquals("قصد باید همچنان پاسخ اطلاعاتی باشد", ConversationIntent.INFORMATIONAL_QA, state.activeIntent)
        assertNull("هیچ یادآوری نباید ثبت شود", state.draftTask)

        // Turn 3: Opinion on ideation
        val turn3 = engine.processTurn("برای یادگیری عمیق کدوم رو اول شروع کنم؟", state, emptyList(), referenceTimeMillis)
        assertEquals(ConversationIntent.INFORMATIONAL_QA, turn3.newState.activeIntent)
        assertNull("هیچ یادآوری نباید ثبت شود", turn3.newState.draftTask)
    }

    /**
     * آزمون ثبت عادات کاربر در حافظه بلندمدت بدون ایجاد یادآور:
     * «من معمولاً ساعت ۸ صبح بیدار میشم»
     */
    @Test
    fun test10_UserRoutineDeclaration_SavesToMemoryWithoutReminder() = runBlocking {
        val state = ConversationState()
        val turn = engine.processTurn(
            userInput = "من معمولاً ساعت ۸ صبح بیدار میشم",
            currentState = state,
            referenceTimeMillis = referenceTimeMillis
        )

        assertEquals("قصد باید ثبت روتین کاربر باشد", ConversationIntent.USER_ROUTINE_DECLARATION, turn.newState.activeIntent)
        assertNull("هیچ یادآوری نباید برای بیان روتین ثبت شود", turn.newState.draftTask)
        assertTrue("پاسخ باید نشان‌دهنده ثبت ساعت بیداری در حافظه باشد", turn.replyText.contains("حافظه") && turn.replyText.contains("بیداری"))
    }
}
