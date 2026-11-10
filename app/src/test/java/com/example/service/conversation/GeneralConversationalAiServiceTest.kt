package com.example.service.conversation

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneralConversationalAiServiceTest {

    @Test
    fun unsupportedOfflineQuestionDoesNotReturnTheGenericRepetition() {
        val reply = GeneralConversationalAiService()
            .getOfflineKnowledgeReply("لطفاً درباره موضوعی کاملاً ناشناخته راهنمایی‌ام کن")

        assertNull(reply)
    }

    @Test
    fun knownOfflineTopicStillHasAnOfflineAnswer() {
        val reply = GeneralConversationalAiService()
            .getOfflineKnowledgeReply("تکنیک پومودورو چیست؟")

        assertNotNull(reply)
        assertTrue(reply!!.contains("۲۵ دقیقه"))
    }
}
