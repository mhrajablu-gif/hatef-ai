package com.example.service.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun splitsSecondActionEvenWhenItHasNoDateOrTime() {
        val input = "فردا ساعت ۹ به بانک بروم و به دکتر زنگ بزنم"

        val tasks = LocalPersianParser.parse(input, referenceTimeMillis = 1_794_556_800_000L)

        assertEquals(2, tasks.size)
        assertTrue(tasks[0].hasExplicitDate)
        assertTrue(tasks[0].hasExplicitTime)
        assertTrue(tasks[1].title.contains("دکتر"))
        assertFalse(tasks[1].hasExplicitDate)
        assertFalse(tasks[1].hasExplicitTime)
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

    @Test
    fun splitsAdjacentClockTasksWithoutConjunctionAndInheritsTomorrowDate() {
        val input = "فردا ۹ صبح نون بخرم ۱۲ ظهر گوشت بخرم"

        val tasks = LocalPersianParser.parse(input, referenceTimeMillis = 1_794_556_800_000L)

        assertEquals(2, tasks.size)
        assertTrue(tasks[0].title.contains("نون"))
        assertTrue(tasks[0].hasExplicitTitle)
        assertTrue(tasks[0].hasExplicitDate)
        assertTrue(tasks[0].hasExplicitTime)
        assertTrue(tasks[1].title.contains("گوشت"))
        assertTrue(tasks[1].hasExplicitTitle)
        assertTrue(tasks[1].hasExplicitDate)
        assertTrue(tasks[1].hasExplicitTime)
        assertEquals(tasks[0].jalaliDateText, tasks[1].jalaliDateText)
        assertEquals("۰۹:۰۰", tasks[0].timeText)
        assertEquals("۱۲:۰۰", tasks[1].timeText)
    }


    @Test
    fun keepsExplicitMorningOnFirstTaskAndSplitsSecondAmbiguousHour() {
        val input = "فردا ۸صبح برم خونه ی مامان و ساعت ۱۱ برم باشگاه"

        val tasks = LocalPersianParser.parse(input, referenceTimeMillis = 1_794_556_800_000L)

        assertEquals(2, tasks.size)
        assertTrue(tasks[0].title.contains("مامان"))
        assertTrue(tasks[0].hasExplicitDate)
        assertTrue(tasks[0].hasExplicitTime)
        assertFalse(tasks[0].hasAmbiguousTimePeriod)
        assertEquals("۰۸:۰۰", tasks[0].timeText)
        assertTrue(tasks[1].title.contains("باشگاه"))
        assertTrue(tasks[1].hasExplicitDate)
        assertTrue(tasks[1].hasExplicitTime)
        assertTrue(tasks[1].hasAmbiguousTimePeriod)
        assertEquals("۱۱:۰۰", tasks[1].timeText)
    }

    @Test
    fun splitsSecondTaskWith24HourClockAndVerbBeforeDestination() {
        val input = "فردا ساعت ۱۴ برم خونه ساعت ۱۶ برم باشگاه"

        val tasks = LocalPersianParser.parse(input, referenceTimeMillis = 1_794_556_800_000L)

        assertEquals(2, tasks.size)
        assertTrue(tasks[0].title.contains("خونه"))
        assertEquals("۱۴:۰۰", tasks[0].timeText)
        assertTrue(tasks[0].hasExplicitDate)
        assertTrue(tasks[0].hasExplicitTime)
        assertFalse(tasks[0].hasAmbiguousTimePeriod)
        assertTrue(tasks[1].title.contains("باشگاه"))
        assertEquals("۱۶:۰۰", tasks[1].timeText)
        assertTrue(tasks[1].hasExplicitDate)
        assertTrue(tasks[1].hasExplicitTime)
        assertFalse(tasks[1].hasAmbiguousTimePeriod)
    }

    @Test
    fun splitsAdjacentTimedTasksWhenSecondStartsWithHam() {
        val input = "فردا ساعت ۱۰ برم نون بخرم ساعت ۱۴ هم برم سرکار"

        val tasks = LocalPersianParser.parse(input, referenceTimeMillis = 1_794_556_800_000L)

        assertEquals(2, tasks.size)
        assertTrue(tasks[0].title.contains("نون"))
        assertEquals("۱۰:۰۰", tasks[0].timeText)
        assertTrue(tasks[0].hasExplicitDate)
        assertTrue(tasks[1].title.contains("سرکار"))
        assertEquals("۱۴:۰۰", tasks[1].timeText)
        assertTrue(tasks[1].hasExplicitDate)
        assertEquals(tasks[0].jalaliDateText, tasks[1].jalaliDateText)
    }



    @Test
    fun parsesEveryDayExceptFridayAsWorkdayRecurrence() {
        val tasks = LocalPersianParser.parse(
            "هر روز به‌جز جمعه ساعت ۸ صبح دارو بخورم",
            referenceTimeMillis = 1_793_016_000_000L
        )

        assertEquals(1, tasks.size)
        assertEquals("WORKDAYS", tasks.single().repeatType)
        assertTrue(tasks.single().hasExplicitTime)
        assertTrue(tasks.single().hasExplicitTitle)
        assertTrue(tasks.single().title.contains("دارو"))
    }

}
