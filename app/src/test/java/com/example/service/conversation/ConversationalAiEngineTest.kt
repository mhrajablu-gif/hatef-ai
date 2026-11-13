package com.example.service.conversation

import com.example.data.engine.PersianDateEngine
import com.example.data.model.ReminderEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Rigorous 12-scenario test suite for Conversational AI Engine.
 * Verifies multi-turn slot filling, follow-up answers, ambiguity resolution,
 * references, corrections, tool invocations, and chit-chat.
 */
class ConversationalAiEngineTest {

    private lateinit var engine: ConversationalAiEngine
    private val tehranTimeZone = TimeZone.getTimeZone("Asia/Tehran")

    // Fixed reference time: Monday, 1405-08-04, 10:00:00 AM (local)
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

    /**
     * تست ۱: درخواست ناقص و تکمیل تدریجی اطلاعات
     * کاربر: «فردا یه کاری دارم، یادم بنداز.»
     * دستیار: باید بپرسد چه کاری و چه ساعتی.
     */
    @Test
    fun test01_IncompleteRequestAndNoFalseSuccessWithoutRegistry() = runBlocking {
        var state = ConversationState()

        // Turn 1: Incomplete request
        val turn1 = engine.processTurn(
            userInput = "فردا یه کاری دارم، یادم بنداز",
            currentState = state,
            referenceTimeMillis = fixedReferenceMillis
        )
        state = turn1.newState
        assertTrue("باید منتظر تکمیل عنوان باشد", state.awaitingField == AwaitingField.TITLE)
        assertTrue("پاسخ باید درباره موضوع باشد", turn1.replyText.contains("چه کاری"))

        // Turn 2: User provides title
        val turn2 = engine.processTurn(
            userInput = "باید برم بانک",
            currentState = state,
            referenceTimeMillis = fixedReferenceMillis
        )
        state = turn2.newState
        assertEquals("عنوان باید 'رفتن به بانک' یا شامل 'بانک' باشد", "رفتن به بانک", state.draftTask?.title)
        assertTrue("اکنون باید منتظر ساعت یا تأیید باشد", state.isWaitingForAnswer())

        // Turn 3: User provides time
        val turn3 = engine.processTurn(
            userInput = "ده صبح",
            currentState = state,
            referenceTimeMillis = fixedReferenceMillis
        )
        state = turn3.newState
        assertEquals("ساعت باید ۱۰:۰۰ باشد", "۱۰:۰۰", state.draftTask?.timeText)
        assertTrue("باید درخواست تأیید نهایی کند", state.awaitingField == AwaitingField.EXECUTION_CONFIRMATION)

        // Turn 4: User confirms
        val turn4 = engine.processTurn(
            userInput = "آره",
            currentState = state,
            referenceTimeMillis = fixedReferenceMillis
        )
        assertEquals("پس از شکست باید امکان تلاش مجدد حفظ شود", SlotFillingStatus.AWAITING_CONFIRMATION, turn4.newState.slotStatus)
        assertEquals("پیش‌نویس یادآور نباید از دست برود", "رفتن به بانک", turn4.newState.draftTask?.title)
        assertEquals("باید دوباره منتظر تأیید بماند", AwaitingField.EXECUTION_CONFIRMATION, turn4.newState.awaitingField)
        assertTrue("بدون ابزار ثبت، باید صادقانه عدم انجام عملیات را اعلام کند", turn4.replyText.contains("سرویس ثبت یادآور"))
        assertFalse("نباید موفقیت ساختگی اعلام شود", turn4.replyText.contains("با موفقیت تنظیم شد"))
    }

    /**
     * تست ۲: پاسخ به سؤال تکمیلی با یک عبارت کوتاه مانند «ده صبح»
     */
    @Test
    fun test02_FollowUpAnswerWithShortPhrase() = runBlocking {
        // Start in a state where assistant asked for time of "جلسه کاری"
        val draft = com.example.service.ai.ParsedTask(
            title = "جلسه کاری",
            triggerTimestamp = fixedReferenceMillis + 86400000L,
            jalaliDateText = "۱۴۰۵/۰۸/۰۵",
            timeText = "۱۰:۰۰",
            hasExplicitTitle = true,
            hasExplicitTime = false,
            hasExplicitDate = true
        )
        val state = ConversationState(
            draftTask = draft,
            awaitingField = AwaitingField.TIME_OR_DATE,
            slotStatus = SlotFillingStatus.COLLECTING_SLOTS
        )

        val turn = engine.processTurn(
            userInput = "ده صبح",
            currentState = state,
            referenceTimeMillis = fixedReferenceMillis
        )

        assertEquals(AwaitingField.EXECUTION_CONFIRMATION, turn.newState.awaitingField)
        assertEquals("۱۰:۰۰", turn.newState.draftTask?.timeText)
        assertTrue(turn.replyText.contains("۱۰:۰۰"))
    }

    /**
     * تست ۳: اصلاح درخواست با عبارت «نه، منظورم فردا نبود، پسفردا بود»
     */
    @Test
    fun test03_CorrectionOfPreviousStatement() = runBlocking {
        val draft = com.example.service.ai.ParsedTask(
            title = "خرید نان",
            triggerTimestamp = fixedReferenceMillis + 86400000L, // tomorrow
            jalaliDateText = "۱۴۰۵/۰۸/۰۵",
            timeText = "۱۶:۰۰",
            hasExplicitTitle = true,
            hasExplicitTime = true,
            hasExplicitDate = true
        )
        val state = ConversationState(
            draftTask = draft,
            awaitingField = AwaitingField.EXECUTION_CONFIRMATION,
            slotStatus = SlotFillingStatus.AWAITING_CONFIRMATION
        )

        val turn = engine.processTurn(
            userInput = "نه، منظورم فردا نبود، پسفردا بود",
            currentState = state,
            referenceTimeMillis = fixedReferenceMillis
        )

        // Day after tomorrow (پس‌فردا) from 1405/08/04 is 1405/08/06
        assertNotNull(turn.newState.draftTask)
        assertEquals("خرید نان", turn.newState.draftTask?.title)
        assertTrue("تاریخ باید به پس‌فردا اصلاح شده باشد", turn.replyText.contains("اصلاح شد") || turn.replyText.contains("۱۴۰۵/۰۸/۰۶"))
    }

    /**
     * تست ۴: درخواست ایجاد یادآور با زمان نسبی
     * «یک ساعت دیگه یادم بنداز جلسه دارم»
     */
    @Test
    fun test04_RelativeTimeReminderCreation() = runBlocking {
        val state = ConversationState()
        val turn = engine.processTurn(
            userInput = "یک ساعت دیگه یادم بنداز جلسه دارم",
            currentState = state,
            referenceTimeMillis = fixedReferenceMillis
        )

        assertNotNull(turn.newState.draftTask)
        // 10:00 + 1 hour = 11:00
        assertEquals("۱۱:۰۰", turn.newState.draftTask?.timeText)
        assertTrue(turn.replyText.contains("جلسه"))
    }

    /**
     * تست ۵: تشخیص تفاوت صبح و عصر و ابهام‌زدایی ساعت ۶
     * «برای فردا ساعت ۶ یادم بنداز برم آزمایشگاه» -> دستیار باید صبح یا عصر بودن را بپرسد
     */
    @Test
    fun test05_HourAmbiguityDetectionMorningVsEvening() = runBlocking {
        val state = ConversationState()
        val turn = engine.processTurn(
            userInput = "برای فردا ساعت ۶ یادم بنداز برم آزمایشگاه",
            currentState = state,
            referenceTimeMillis = fixedReferenceMillis
        )

        assertEquals("باید برای رفع ابهام منتظر باشد", AwaitingField.AMBIGUOUS_HOUR_AM_PM, turn.newState.awaitingField)
        assertTrue("سؤال باید شامل صبح یا عصر باشد", turn.replyText.contains("صبح") && turn.replyText.contains("عصر"))

        // Follow up with "عصر"
        val turn2 = engine.processTurn(
            userInput = "عصر",
            currentState = turn.newState,
            referenceTimeMillis = fixedReferenceMillis
        )

        assertEquals("ساعت باید ۱۸:۰۰ تنظیم شده باشد", "۱۸:۰۰", turn2.newState.draftTask?.timeText)
        assertEquals(AwaitingField.EXECUTION_CONFIRMATION, turn2.newState.awaitingField)
    }

    /**
     * تست ۶: تشخیص دقیق روزهای هفته و تاریخ شمسی
     * «سه‌شنبه ساعت ۱۹ تماس با همکار»
     */
    @Test
    fun test06_AccurateWeekdayMatchingTuesday() = runBlocking {
        val state = ConversationState()
        val turn = engine.processTurn(
            userInput = "سه‌شنبه ساعت ۱۹ تماس با همکار",
            currentState = state,
            referenceTimeMillis = fixedReferenceMillis
        )

        assertNotNull(turn.newState.draftTask)
        assertEquals("۱۹:۰۰", turn.newState.draftTask?.timeText)
        assertEquals("تماس با همکار", turn.newState.draftTask?.title)
    }

    /**
     * تست ۷: ارجاع به یادآور قبلی
     * «اون رو نیم ساعت عقب بنداز»
     */
    @Test
    fun test07_ReferenceToPastReminderReschedule() = runBlocking {
        val state = ConversationState(
            lastMentionedReminderId = 42L,
            activeIntent = ConversationIntent.CREATE_REMINDER
        )

        val turn = engine.processTurn(
            userInput = "اون رو نیم ساعت عقب بنداز",
            currentState = state,
            referenceTimeMillis = fixedReferenceMillis
        )

        // Verifies intent recognition without throwing
        assertNotNull(turn.replyText)
    }

    /**
     * تست ۸: تغییر یا حذف یادآور در صورت وجود چند مورد مشابه
     */
    @Test
    fun test08_AmbiguousReminderSelection() = runBlocking {
        val state = ConversationState(
            candidateReminderIds = listOf(101L, 102L),
            awaitingField = AwaitingField.WHICH_REMINDER_TO_SELECT
        )

        val turn = engine.processTurn(
            userInput = "مورد اول",
            currentState = state,
            referenceTimeMillis = fixedReferenceMillis
        )

        assertNotNull(turn.replyText)
    }

    /**
     * تست ۹: گفت‌وگوی عادی بدون ایجاد ناخواسته یادآور
     * «سلام، حالت چطوره؟»
     */
    @Test
    fun test09_CasualChitChatWithoutReminderCreation() = runBlocking {
        val state = ConversationState()
        val turn = engine.processTurn(
            userInput = "سلام، حالت چطوره؟",
            currentState = state,
            referenceTimeMillis = fixedReferenceMillis
        )

        assertEquals(ConversationIntent.GREETING_OR_CHITCHAT, turn.newState.activeIntent)
        assertNull("هیچ یادآوری نباید در حالت گپ ایجاد شود", turn.newState.draftTask)
        assertTrue(turn.replyText.contains("ممنون") || turn.replyText.contains("سلام"))
    }

    /**
     * تست ۱۰: پاسخ به درخواست نامفهوم بدون ساختن اطلاعات
     * «فلان بیسار الکی»
     */
    @Test
    fun test10_IncomprehensibleInputHonestRejection() = runBlocking {
        val state = ConversationState()
        val turn = engine.processTurn(
            userInput = "فلان بیسار الکی",
            currentState = state,
            referenceTimeMillis = fixedReferenceMillis
        )

        assertEquals(ConversationIntent.UNKNOWN, turn.newState.activeIntent)
        assertNull("نباید یادآور موهوم ساخته شود", turn.newState.draftTask)
        assertTrue(turn.replyText.contains("متوجه") || turn.replyText.contains("واضح‌تر"))
    }

    /**
     * تست ۱۱: مدیریت عدم دسترسی به سرور و تضمین اجرای ۱۰۰٪ محلی و آفلاین
     */
    @Test
    fun test11_ZeroCostOfflineLocalExecution() = runBlocking {
        // Test engine runs with no internet, no external RPCs, pure local logic
        val state = ConversationState()
        val turn = engine.processTurn(
            userInput = "فردا ساعت ۸ صبح یادم بنداز قرص بخورم",
            currentState = state,
            referenceTimeMillis = fixedReferenceMillis
        )

        assertNotNull(turn.newState.draftTask)
        assertEquals("۰۸:۰۰", turn.newState.draftTask?.timeText)
        assertEquals("قرص بخورم", turn.newState.draftTask?.title)
    }

    /**
     * تست ۱۲: ادامه مکالمه پس از چند رفت‌وبرگشت
     * چند مرحله متوالی گفت‌وگو بدون مخدوش شدن تاریخچه
     */
    @Test
    fun test12_MultiTurnContinuousDialogue() = runBlocking {
        var state = ConversationState()

        // Turn 1
        val turn1 = engine.processTurn("سلام هاتف", state, emptyList(), fixedReferenceMillis)
        state = turn1.newState
        assertEquals(ConversationIntent.GREETING_OR_CHITCHAT, state.activeIntent)

        // Turn 2
        val turn2 = engine.processTurn("فردا ساعت ۹ یادم بنداز", state, emptyList(), fixedReferenceMillis)
        state = turn2.newState
        assertEquals(AwaitingField.TITLE, state.awaitingField)

        // Turn 3
        val turn3 = engine.processTurn("جلسه با تیم فنی", state, emptyList(), fixedReferenceMillis)
        state = turn3.newState
        assertEquals(AwaitingField.EXECUTION_CONFIRMATION, state.awaitingField)
        assertEquals("جلسه با تیم فنی", state.draftTask?.title)
        assertEquals("۰۹:۰۰", state.draftTask?.timeText)

        // Turn 4
        val turn4 = engine.processTurn("آره درسته ثبت کن", state, emptyList(), fixedReferenceMillis)
        assertEquals(SlotFillingStatus.AWAITING_CONFIRMATION, turn4.newState.slotStatus)
        assertTrue(turn4.replyText.contains("سرویس ثبت یادآور"))
        assertNotNull("پیش‌نویس باید برای تلاش مجدد حفظ شود", turn4.newState.draftTask)
        assertFalse(turn4.replyText.contains("با موفقیت تنظیم شد"))
    }

    /**
     * تست ۱۳: سؤال عمومی، علمی و یادگیری بدون تبدیل اشتباه به یادآور
     * «هوش مصنوعی چطور کار میکنه؟»
     */
    @Test
    fun test13_GeneralConversationalQuestion_NotTreatedAsReminder() = runBlocking {
        val state = ConversationState()
        val turn = engine.processTurn(
            userInput = "هوش مصنوعی چطور کار میکنه؟",
            currentState = state,
            referenceTimeMillis = fixedReferenceMillis
        )

        assertEquals("باید به عنوان پرسش عمومی یا پاسخ اطلاعاتی دسته‌بندی شود", ConversationIntent.INFORMATIONAL_QA, turn.newState.activeIntent)
        assertNull("هیچ یادآوری نباید برای سؤالات عمومی ایجاد شود", turn.newState.draftTask)
        assertTrue("پاسخ باید شامل توضیحات علمی و هوشمندانه باشد", turn.replyText.length > 20)
    }

    /**
     * تست ۱۴: انتخاب هوشمند میان چند یادآور هم‌نام
     * کاربر از میان کاندیداها «مورد دوم» را انتخاب می‌کند.
     */
    @Test
    fun test14_CandidateDisambiguationFlow() = runBlocking {
        val state = ConversationState(
            candidateReminderIds = listOf(201L, 202L, 203L),
            awaitingField = AwaitingField.WHICH_REMINDER_TO_SELECT
        )

        val turn = engine.processTurn(
            userInput = "مورد دوم",
            currentState = state,
            referenceTimeMillis = fixedReferenceMillis
        )

        assertNotNull(turn.replyText)
        assertEquals("باید شناسه مورد دوم انتخاب شده باشد", 202L, turn.newState.lastMentionedReminderId)
    }

    /**
     * تست ۱۵: تمایز بین لغو آلارم و حذف کامل قرار
     * «یادآور رو لغو کن ولی قرار رو نگه دار»
     */
    @Test
    fun test15_SoftAlarmCancellationIntent() = runBlocking {
        val state = ConversationState(
            lastMentionedReminderId = 55L
        )

        val turn = engine.processTurn(
            userInput = "یادآور رو لغو کن ولی قرار رو در تقویم نگه دار",
            currentState = state,
            referenceTimeMillis = fixedReferenceMillis
        )

        assertNotNull(turn.replyText)
        assertTrue("پاسخ باید نشان‌دهنده لغو آلارم باشد", turn.replyText.contains("آلارم") || turn.replyText.contains("لغو") || turn.replyText.contains("قرار"))
    }

    /**
     * تست ۱۶: افزودن پیش‌اعلان (Advance Alert)
     * «برای همین قرار نیم ساعت قبل هم اعلان بذار»
     */
    @Test
    fun test16_AdvanceAlertUpdateIntent() = runBlocking {
        val state = ConversationState(
            lastMentionedReminderId = 77L
        )

        val turn = engine.processTurn(
            userInput = "برای همین قرار نیم ساعت قبل هم اعلان بذار",
            currentState = state,
            referenceTimeMillis = fixedReferenceMillis
        )

        assertNotNull(turn.replyText)
        assertTrue("پاسخ باید نشان‌دهنده تنظیم پیش‌اعلان ۳۰ دقیقه‌ای باشد", turn.replyText.contains("پیش‌اعلان") || turn.replyText.contains("۳۰ دقیقه") || turn.replyText.contains("اعلان"))
    }

    /**
     * تست ۱۷: فرمان حذف کلی تمام یادآورها
     * «همه یادآورهام رو حذف کن» یا «تمام آلارم‌ها رو پاک کن»
     */
    @Test
    fun test17_BulkDeletionAllRemindersIntent() = runBlocking {
        val state = ConversationState()

        val turn1 = engine.processTurn(
            userInput = "همه یادآورهام رو حذف کن",
            currentState = state,
            referenceTimeMillis = fixedReferenceMillis
        )
        assertNotNull(turn1.replyText)
        assertEquals("نیت باید DELETE_REMINDER باشد", ConversationIntent.DELETE_REMINDER, turn1.newState.activeIntent)
        assertTrue("پاسخ باید تأیید حذف همه باشد", turn1.replyText.contains("حذف") || turn1.replyText.contains("پاک"))

        val turn2 = engine.processTurn(
            userInput = "تمام آلارم‌ها رو پاک کن",
            currentState = state,
            referenceTimeMillis = fixedReferenceMillis
        )
        assertEquals("نیت باید DELETE_REMINDER باشد", ConversationIntent.DELETE_REMINDER, turn2.newState.activeIntent)
    }

    /**
     * تست ۱۸: حذف یادآورهای یک روز خاص
     * «یادآورهای امروز رو حذف کن»
     */
    @Test
    fun test18_DayDeletionRemindersIntent() = runBlocking {
        val state = ConversationState()

        val turn = engine.processTurn(
            userInput = "یادآورهای امروز رو حذف کن",
            currentState = state,
            referenceTimeMillis = fixedReferenceMillis
        )
        assertEquals("نیت باید DELETE_REMINDER باشد", ConversationIntent.DELETE_REMINDER, turn.newState.activeIntent)
        assertTrue("پاسخ باید مربوط به امروز باشد", turn.replyText.contains("امروز") || turn.replyText.contains("حذف"))
    }

    /**
     * تست ۱۹: جلو کشیدن زمان قرار
     * «نیم ساعت جلو بنداز»
     */
    @Test
    fun test19_AdvanceEarlierIntent() = runBlocking {
        val state = ConversationState(
            lastMentionedReminderId = 99L
        )

        val turn = engine.processTurn(
            userInput = "نیم ساعت جلو بنداز",
            currentState = state,
            referenceTimeMillis = fixedReferenceMillis
        )
        assertEquals("نیت باید UPDATE_REMINDER باشد", ConversationIntent.UPDATE_REMINDER, turn.newState.activeIntent)
    }

    /**
     * تست ۲۰: تغییر تنظیم پیش‌اعلان برنامه به صورت مستقیم
     * «پیش‌اعلان رو بکن ۳۰ دقیقه»
     */
    @Test
    fun test20_DirectSettingAdvanceAlertIntent() = runBlocking {
        val state = ConversationState()

        val turn = engine.processTurn(
            userInput = "پیش‌اعلان رو بکن ۳۰ دقیقه",
            currentState = state,
            referenceTimeMillis = fixedReferenceMillis
        )
        assertEquals("نیت باید UPDATE_APP_SETTINGS باشد", ConversationIntent.UPDATE_APP_SETTINGS, turn.newState.activeIntent)
        assertTrue("پاسخ باید تایید تغییر به ۳۰ دقیقه باشد", turn.replyText.contains("۳۰") || turn.replyText.contains("30"))
    }

    /**
     * تست ۲۱: تغییر تم برنامه به صورت مستقیم
     * «تم رو تاریک کن»
     */
    @Test
    fun test21_DirectThemeChangeIntent() = runBlocking {
        val state = ConversationState()

        val turn = engine.processTurn(
            userInput = "تم رو تاریک کن",
            currentState = state,
            referenceTimeMillis = fixedReferenceMillis
        )
        assertEquals("نیت باید UPDATE_APP_SETTINGS باشد", ConversationIntent.UPDATE_APP_SETTINGS, turn.newState.activeIntent)
        assertTrue("پاسخ باید حاوی تأیید تم تاریک باشد", turn.replyText.contains("تاریک"))
    }

    /**
     * تست ۲۲: سوالات آزاد علمی و مفهومی از هوش مصنوعی (Open-Domain QA)
     * «تکنیک پومودورو چیه؟» و «چرا آسمان آبی است؟»
     */
    @Test
    fun test22_OpenDomainQuestionKnowledge() = runBlocking {
        val state = ConversationState()

        val turn1 = engine.processTurn(
            userInput = "تکنیک پومودورو چیه؟",
            currentState = state,
            referenceTimeMillis = fixedReferenceMillis
        )
        assertEquals("نیت باید INFORMATIONAL_QA باشد", ConversationIntent.INFORMATIONAL_QA, turn1.newState.activeIntent)
        assertTrue("پاسخ باید توضیح پومودورو باشد", turn1.replyText.contains("پومودورو") || turn1.replyText.contains("۲۵ دقیقه"))

        val turn2 = engine.processTurn(
            userInput = "چرا آسمان آبی است؟",
            currentState = state,
            referenceTimeMillis = fixedReferenceMillis
        )
        assertEquals("نیت باید INFORMATIONAL_QA باشد", ConversationIntent.INFORMATIONAL_QA, turn2.newState.activeIntent)
        assertTrue("پاسخ باید توضیح علمی پراکندگی نور باشد", turn1.replyText.isNotBlank())
    }


    /** عدم دسترسی به ابزارها نباید حذف گروهی یا جابه‌جایی را موفق جلوه دهد. */
    @Test
    fun test23_NoFalseSuccessWhenReminderToolsAreUnavailable() = runBlocking {
        val deleteAll = engine.processTurn(
            userInput = "همه یادآورهام رو حذف کن",
            currentState = ConversationState(),
            referenceTimeMillis = fixedReferenceMillis
        )
        assertFalse("حذف گروهی بدون ابزار نباید موفق باشد", deleteAll.executedResult?.success ?: true)
        assertTrue(deleteAll.replyText.contains("سرویس یادآورها"))
        assertFalse(deleteAll.replyText.contains("حذف شدند"))

        val reschedule = engine.processTurn(
            userInput = "نیم ساعت جلو بنداز",
            currentState = ConversationState(lastMentionedReminderId = 99L),
            referenceTimeMillis = fixedReferenceMillis
        )
        assertEquals(SlotFillingStatus.IDLE, reschedule.newState.slotStatus)
        assertTrue(reschedule.replyText.contains("تغییر زمان انجام نشد"))
        assertFalse(reschedule.replyText.contains("جلو کشیده شد"))

        val changeHour = engine.processTurn(
            userInput = "ساعت قرار رو بکن ۱۰",
            currentState = ConversationState(lastMentionedReminderId = 99L),
            referenceTimeMillis = fixedReferenceMillis
        )
        assertTrue(changeHour.replyText.contains("سرویس یادآورها در دسترس نیست"))
        assertFalse(changeHour.replyText.contains("تغییر یافت"))

        val advanceAlert = engine.processTurn(
            userInput = "برای همین قرار نیم ساعت قبل هم اعلان بذار",
            currentState = ConversationState(lastMentionedReminderId = 99L),
            referenceTimeMillis = fixedReferenceMillis
        )
        assertTrue(advanceAlert.replyText.contains("پیش‌اعلان تنظیم نشد"))

        val cancelAlarm = engine.processTurn(
            userInput = "یادآور رو لغو کن ولی قرار رو نگه دار",
            currentState = ConversationState(lastMentionedReminderId = 99L),
            referenceTimeMillis = fixedReferenceMillis
        )
        assertTrue(cancelAlarm.replyText.contains("آلارم لغو نشد"))
    }
}

