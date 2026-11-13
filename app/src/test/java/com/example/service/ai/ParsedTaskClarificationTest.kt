package com.example.service.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsedTaskClarificationTest {

    private fun task(
        hasTime: Boolean,
        hasDate: Boolean,
        rawTimePhrase: String = "",
        ambiguous: Boolean = false,
        hasTitle: Boolean = true
    ) = ParsedTask(
        title = "خرید نان",
        triggerTimestamp = 1_800_000_000_000L,
        jalaliDateText = "۱۴۰۵/۰۸/۱۸",
        timeText = "۱۰:۰۰",
        rawTimePhrase = rawTimePhrase,
        hasExplicitTime = hasTime,
        hasExplicitDate = hasDate,
        hasExplicitTitle = hasTitle,
        isAmbiguous = ambiguous
    )

    @Test
    fun dateWithoutTimeRequiresClarification() {
        assertTrue(task(hasTime = false, hasDate = true).needsClarification)
    }

    @Test
    fun clockTimeWithoutDateRequiresClarification() {
        assertTrue(task(hasTime = true, hasDate = false, rawTimePhrase = "ساعت ۱۰").needsClarification)
    }

    @Test
    fun relativeDurationResolvesBothDateAndTime() {
        val relative = task(hasTime = true, hasDate = false, rawTimePhrase = "۳۰ دقیقه دیگه")
        assertTrue(relative.hasResolvedRelativeDateTime)
        assertFalse(relative.needsClarification)
    }

    @Test
    fun ambiguousMorningOrEveningTimeRequiresClarification() {
        val ambiguous = task(
            hasTime = true,
            hasDate = true,
            ambiguous = true
        )
        assertTrue(ambiguous.hasAmbiguousTimePeriod)
        assertTrue(ambiguous.needsClarification)
    }

    @Test
    fun ambiguousHourStillRequiresAmPmClarificationWhenTitleIsMissing() {
        val ambiguous = task(hasTime = true, hasDate = true, ambiguous = true, hasTitle = false)
        assertTrue(ambiguous.hasAmbiguousTimePeriod)
        assertTrue(ambiguous.needsClarification)
    }

    @Test
    fun explicitDateAndTimeNeedNoClarification() {
        assertFalse(task(hasTime = true, hasDate = true).needsClarification)
    }
}
