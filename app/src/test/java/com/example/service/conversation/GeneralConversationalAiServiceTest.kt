package com.example.service.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneralConversationalAiServiceTest {

    @Test
    fun unsupportedOfflineQuestionDoesNotReturnTheGenericRepetition() {
        val reply = GeneralConversationalAiService()
            .getOfflineKnowledgeReply("لطفاً درباره موضوعی کاملاً ناشناخته راهنمایی‌ام کن")

        assertNotNull(reply)
        assertFalse(reply!!.contains("لطفاً بیشتر توضیح دهید"))
    }

    @Test
    fun knownOfflineTopicStillHasAnOfflineAnswer() {
        val reply = GeneralConversationalAiService()
            .getOfflineKnowledgeReply("تکنیک پومودورو چیست؟")

        assertNotNull(reply)
        assertTrue(reply!!.contains("۲۵ دقیقه"))
    }

    @Test
    fun html403ResponseIsShownAsShortReadableDiagnostic() {
        val diagnostic = sanitizeServerDiagnostic(
            "<!DOCTYPE html><html><head><title>Error 403 (Forbidden)!!1</title></head><body>blocked</body></html>"
        )

        assertTrue(diagnostic.contains("403"))
        assertTrue(diagnostic.contains("سرور"))
        assertFalse(diagnostic.contains("<html"))
        assertFalse(diagnostic.contains("<!DOCTYPE"))
    }

    @Test
    fun ordinaryJsonDiagnosticKeepsUsefulDetailsAndRemovesApiKeys() {
        val diagnostic = sanitizeServerDiagnostic(
            "PERMISSION_DENIED: invalid key AIza123456789012345678901234567890"
        )

        assertTrue(diagnostic.contains("PERMISSION_DENIED"))
        assertTrue(diagnostic.contains("[کلید مخفی حذف شد]"))
        assertFalse(diagnostic.contains("AIza"))
    }

}
