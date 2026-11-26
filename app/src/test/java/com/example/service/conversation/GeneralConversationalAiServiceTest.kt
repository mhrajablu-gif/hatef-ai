package com.example.service.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneralConversationalAiServiceTest {

    @Test
    fun unknownOfflineQuestionDoesNotReturnTheOldGenericCannedReply() {
        val service = GeneralConversationalAiService()
        val reply = service.getOfflineKnowledgeReply("یک پرسش علمی کمتر رایج درباره فیزیک دارم")

        assertFalse(reply.contains("من سوال یا دیدگاه شما را بررسی کردم"))
        assertTrue(reply.contains("اتصال هوش مصنوعی") || reply.contains("این پرسش در پاسخ‌های آفلاینم"))
    }

    @Test
    fun knownOfflineKnowledgeQuestionStillGetsAnActualAnswer() {
        val service = GeneralConversationalAiService()
        val reply = service.getOfflineKnowledgeReply("تکنیک پومودورو چیست؟")

        assertTrue(reply.contains("۲۵ دقیقه"))
    }
    @Test
    fun spouseBirthdayPlanningAsksForDateWithoutCreatingReminderOrReturningGenericOfflineError() {
        val service = GeneralConversationalAiService()
        val reply = service.getOfflineKnowledgeReply("سال آینده همین موقع تولد خانمم")

        assertTrue(reply.contains("روز و ماه تولد"))
        assertTrue(reply.contains("هیچ یادآوری ثبت نکردم"))
        assertFalse(reply.contains("ارتباط با هوش مصنوعی هم برقرار نشد"))
    }

}
