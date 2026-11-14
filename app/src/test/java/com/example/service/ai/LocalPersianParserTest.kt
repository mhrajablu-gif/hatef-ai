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
    fun keepsTonightAndTomorrowMorningAsTwoIndependentTasksWhenFirstHourIsAmbiguous() {
        val input = "امشب ساعت ۱۱ برم نون بخرم و فردا ۷ صبح برم چایی بخرم"

        val tasks = LocalPersianParser.parse(input, referenceTimeMillis = 1_794_556_800_000L)

        assertEquals("Each dated clause must remain a separate task", 2, tasks.size)
        assertEquals(true, tasks[0].hasExplicitDate)
        assertEquals(true, tasks[0].hasExplicitTime)
        assertEquals(true, tasks[0].isAmbiguous)
        assertEquals(true, tasks[0].title.contains("نون"))
        assertEquals(true, tasks[1].hasExplicitDate)
        assertEquals(true, tasks[1].hasExplicitTime)
        assertEquals(false, tasks[1].isAmbiguous)
        assertEquals(true, tasks[1].title.contains("چایی"))
        assertEquals("۰۷:۰۰", tasks[1].timeText)
    }

    @Test
    fun doesNotTreatAmbiguousFirstTaskAsPermissionToMergeTheWholeMessage() {
        val input = "امشب ساعت ۱۱ برم نون بخرم و فردا ۷ صبح برم چایی بخرم"

        val tasks = LocalPersianParser.parse(input, referenceTimeMillis = 1_794_556_800_000L)

        assertEquals(2, tasks.size)
        assertEquals(true, tasks.any { it.needsClarification })
        assertEquals(1, tasks.count { !it.needsClarification })
    }

}
