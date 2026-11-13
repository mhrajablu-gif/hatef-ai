package com.example.service.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiSemanticParserTest {
    private val parser = GeminiSemanticParser()
    private val referenceTime = 1_795_000_000_000L

    @Test
    fun fallbackDoesNotTreatMissingDateAndTimeAsExplicit() {
        val task = parser.fallbackPersianParse("یادم بنداز کتاب بخونم", referenceTime).first()
        assertFalse(task.hasExplicitDate)
        assertFalse(task.hasExplicitTime)
        assertTrue(task.needsClarification)
    }

    @Test
    fun fallbackAsksAmPmForAmbiguousClockHour() {
        val task = parser.fallbackPersianParse("فردا ساعت ۶ یادم بنداز جلسه کاری", referenceTime).first()
        assertTrue(task.hasAmbiguousTimePeriod)
        assertTrue(task.needsClarification)
    }

    @Test
    fun fallbackFlagsAmbiguousTimeEvenWithoutDate() {
        val task = parser.fallbackPersianParse("ساعت ۶ یادم بنداز برم بانک", referenceTime).first()
        assertTrue(task.hasExplicitTime)
        assertFalse(task.hasExplicitDate)
        assertTrue(task.hasAmbiguousTimePeriod)
        assertTrue(task.needsClarification)
    }
    @Test
    fun fallbackResolvesRelativeDurationAsDateAndTime() {
        val task = parser.fallbackPersianParse("نیم ساعت دیگه یادم بنداز آب بخورم", referenceTime).first()
        assertTrue(task.hasExplicitDate)
        assertTrue(task.hasExplicitTime)
        assertFalse(task.needsClarification)
    }
}
