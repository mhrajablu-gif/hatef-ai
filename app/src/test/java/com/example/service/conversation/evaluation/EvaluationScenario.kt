package com.example.service.conversation.evaluation

import com.example.service.conversation.ConversationIntent

/**
 * Thirty evaluation categories specified for comprehensive Persian Conversational AI assessment.
 */
enum class EvaluationCategory(val categoryId: Int, val titleFa: String) {
    GENERAL_CHAT_AND_DAILY(1, "گفت‌وگوی عمومی و روزمره"),
    INFORMATIONAL_QA(2, "پرسش‌های اطلاعاتی و علمی"),
    CONSULTATION_AND_REASONING(3, "مشاوره و تصمیم‌گیری"),
    INCOMPLETE_REQUESTS(4, "درخواست‌های ناقص"),
    COLLOQUIAL_PERSIAN(5, "جملات محاوره‌ای و عامیانه"),
    TYPO_AND_SLANG(6, "جملات دارای غلط تایپی"),
    LONG_AND_MULTI_CLAUSE(7, "جملات طولانی و چندبخشی"),
    CREATE_REMINDER(8, "ایجاد یادآور"),
    EDIT_REMINDER(9, "ویرایش یادآور"),
    DELETE_REMINDER(10, "حذف یادآور"),
    CHANGE_TIME(11, "تغییر زمان و ساعت"),
    MOVE_DATE(12, "انتقال تاریخ و جابه‌جایی"),
    DAILY_RECURRENCE(13, "تکرار روزانه"),
    WEEKLY_RECURRENCE(14, "تکرار هفتگی"),
    MONTHLY_RECURRENCE(15, "تکرار ماهانه"),
    YEARLY_RECURRENCE(16, "تکرار سالانه"),
    JALALI_CALENDAR_DATE(17, "تاریخ و مناسبت‌های شمسی"),
    RELATIVE_TIME(18, "زمان و فواصل نسبی"),
    AMBIGUOUS_EXPRESSIONS(19, "عبارت‌های مبهم (صبح/عصر)"),
    CORRECTION_OF_PREVIOUS(20, "اصلاح درخواست قبلی"),
    MULTI_TURN_CONVERSATION(21, "مکالمات چندمرحله‌ای"),
    REFERENCE_TO_PAST_MESSAGES(22, "ارجاع به پیام‌ها و قرارهای گذشته"),
    MULTIPLE_MATCHING_REMINDERS(23, "مدیریت چند قرار مشابه"),
    CATEGORY_MANAGEMENT(24, "مدیریت دسته‌بندی‌ها"),
    APP_SETTINGS_CONTROL(25, "تغییر تنظیمات برنامه از طریق چت"),
    COMPOUND_REQUESTS(26, "درخواست‌های ترکیبی"),
    CONTRADICTORY_REQUESTS(27, "درخواست‌های متناقض"),
    IMPOSSIBLE_REQUESTS(28, "درخواست‌های غیرقابل اجرا"),
    VOICE_ASR_ERRORS(29, "خطاهای احتمالی تبدیل گفتار به متن"),
    ERROR_MANAGEMENT_AND_RECOVERY(30, "مدیریت خطا و بازیابی عملیات")
}

/**
 * Standard enterprise evaluation test record for Persian NLP & Agent Assessment.
 * Stores all 10 required audit metrics.
 */
data class EvaluationScenario(
    val id: Int,
    val category: EvaluationCategory,
    val userInput: String,
    val actualIntent: ConversationIntent,
    val requestType: String,
    val extractedSlots: Map<String, String>,
    val unknownSlots: List<String>,
    val needsClarificationQuestion: Boolean,
    val requiredTool: String?,
    val toolParameters: Map<String, Any?>,
    val expectedResult: String,
    val acceptanceCriteria: String
)
