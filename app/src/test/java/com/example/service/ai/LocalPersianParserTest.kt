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
}
