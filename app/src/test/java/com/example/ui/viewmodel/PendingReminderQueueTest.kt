package com.example.ui.viewmodel

import com.example.service.ai.ParsedTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingReminderQueueTest {
    private fun task(description: String, hasTime: Boolean, time: String) = ParsedTask(
        title = "تماس با دکتر",
        description = description,
        rawTimePhrase = description,
        triggerTimestamp = 1_800_000_000_000L,
        jalaliDateText = "۱۴۰۵/۰۹/۰۱",
        timeText = time,
        hasExplicitTitle = true,
        hasExplicitDate = true,
        hasExplicitTime = hasTime
    )

    @Test
    fun clarifiedCopyReplacesOnlyCurrentTaskAndCanAdvanceQueue() {
        val first = task("پس فردا به دکتر زنگ بزنم", hasTime = false, time = "۱۰:۰۰")
        val second = task("فردا ساعت ۹ به بانک بروم", hasTime = true, time = "۰۹:۰۰")
        val clarified = first.copy(
            timeText = "۱۸:۰۰",
            hasExplicitTime = true,
            triggerTimestamp = first.triggerTimestamp + 8 * 60 * 60 * 1000L
        )
        val queue = PendingReminderQueue()
        queue.addAll(listOf(first, second))

        assertTrue(queue.isFirstSameSource(first))
        assertTrue(queue.replaceFirst(clarified))
        assertEquals(clarified, queue.firstOrNull())
        assertTrue(queue.removeFirstIfSameSource(clarified))
        assertEquals(second, queue.firstOrNull())
    }

    @Test
    fun clarificationForDifferentUtteranceCannotReplaceQueueHead() {
        val first = task("پس فردا به دکتر زنگ بزنم", hasTime = false, time = "۱۰:۰۰")
        val unrelated = task("فردا به بانک بروم", hasTime = true, time = "۰۹:۰۰")
        val queue = PendingReminderQueue()
        queue.addAll(listOf(first, unrelated))

        assertTrue(queue.isFirstSameSource(first))
        assertFalse(queue.isFirstSameSource(unrelated))
        assertFalse(queue.replaceFirst(unrelated))
        assertEquals(first, queue.firstOrNull())
        assertFalse(queue.removeFirstIfSameSource(unrelated))
        assertEquals(first, queue.firstOrNull())
    }
}
