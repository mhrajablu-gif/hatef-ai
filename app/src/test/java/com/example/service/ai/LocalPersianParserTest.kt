package com.example.service.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPersianParserTest {

    @Test
    fun splitsTasksAfterAlsoForPhraseWithForBeforeRelativeDate() {
        val input = "فردا ساعت ۱۱ قرص بخورم و همچنین برای پس فردا ساعت ۳ بعدازظهر آب بخورم"

        val tasks = LocalPersianParser.parse(input, referenceTimeMillis = 1_794_556_800_000L)

        assertEquals(2, tasks.size)
    }

    @Test
    fun splitsTasksConnectedByThen() {
        val input = "فردا ساعت ۹ به بانک بروم، سپس پس فردا ساعت ۸ به باشگاه بروم"

        val tasks = LocalPersianParser.parse(input, referenceTimeMillis = 1_794_556_800_000L)

        assertEquals(2, tasks.size)
    }

    @Test
    fun doesNotSplitOrdinaryConjunctionInsideSingleTask() {
        val input = "فردا ساعت ۹ با علی و رضا جلسه داشته باشم"

        val tasks = LocalPersianParser.parse(input, referenceTimeMillis = 1_794_556_800_000L)

        assertEquals(1, tasks.size)
    }
    @Test
    fun keepsIncompleteSecondTaskSeparateAndDoesNotInventItsTime() {
        val input = "فردا ساعت ۹ به بانک بروم و پس فردا به دکتر زنگ بزنم"

        val tasks = LocalPersianParser.parse(input, referenceTimeMillis = 1_794_556_800_000L)

        assertEquals(2, tasks.size)
        assertTrue(tasks[0].hasExplicitDate)
        assertTrue(tasks[0].hasExplicitTime)
        assertTrue(tasks[0].hasExplicitTitle)
        assertTrue(tasks[1].hasExplicitDate)
        assertFalse(tasks[1].hasExplicitTime)
        assertTrue(tasks[1].hasExplicitTitle)
    }

    @Test
    fun splitsTwoTasksWhenSecondHourNeedsAmPmClarification() {
        val input = "فردا ساعت ۱۸ نون بخرم و ساعت ۵ برم سبزی بخرم"

        val tasks = LocalPersianParser.parse(input, referenceTimeMillis = 1_794_556_800_000L)

        assertEquals(2, tasks.size)
        assertEquals(true, tasks[0].hasExplicitDate)
        assertEquals(true, tasks[0].hasExplicitTime)
        assertEquals(false, tasks[0].isAmbiguous)
        assertEquals(true, tasks[1].hasExplicitDate)
        assertEquals(true, tasks[1].hasExplicitTime)
        assertEquals(true, tasks[1].isAmbiguous)
        assertEquals(tasks[0].jalaliDateText, tasks[1].jalaliDateText)
        assertEquals("۱۸:۰۰", tasks[0].timeText)
        assertEquals("۰۵:۰۰", tasks[1].timeText)
    }

}
