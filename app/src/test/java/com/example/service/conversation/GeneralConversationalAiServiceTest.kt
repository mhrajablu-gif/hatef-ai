package com.example.service.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneralConversationalAiServiceTest {

    @Test
    fun unsupportedOfflineQuestionDoesNotReturnTheGenericRepetition() {
        val reply = GeneralConversationalAiService()
            .getOfflineKnowledgeReply("لطفاً درباره طراحی یک کسب‌وکار هنری راهنمایی‌ام کن")

        assertFalse(reply.contains("من سوال یا دیدگاه شما را بررسی کردم"))
        assertFalse(reply.contains("جزئیات بیشتری درباره موضوع بفرمایید"))
        assertTrue(reply.contains("دسترسی"))
        assertTrue(reply.contains("پاسخ ساختگی"))
    }

    @Test
    fun knownOfflineTopicStillHasAnOfflineAnswer() {
        val reply = GeneralConversationalAiService()
            .getOfflineKnowledgeReply("تکنیک پومودورو چیست؟")

        assertTrue(reply.contains("۲۵ دقیقه"))
    }
}
