package com.example.service.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsedTaskClarificationTest {

    private fun task(
        hasTime: Boolean,
        hasDate: Boolean,
        rawTimePhrase: String = ""
    ) = ParsedTask(
        title = "خرید نان",
        triggerTimestamp = 1_800_000_000_000L,
        jalaliDateText = "۱۴۰۵/۰۸/۱۸",
        timeText = "۱۰:۰۰",
        rawTimePhrase = rawTimePhrase,
        hasExplicitTime = hasTime,
        hasExplicitDate = hasDate
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
    fun explicitDateAndTimeNeedNoClarification() {
        assertFalse(task(hasTime = true, hasDate = true).needsClarification)
    }
}
