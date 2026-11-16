package com.example.service.alarm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ReminderEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AlarmSchedulerTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun reminder(
        triggerTimestamp: Long,
        isCompleted: Boolean = false
    ) = ReminderEntity(
        id = 123L,
        title = "آزمون آلارم",
        triggerTimestamp = triggerTimestamp,
        jalaliDateText = "۱۴۰۵/۰۸/۲۰",
        timeText = "۱۲:۳۰",
        isCompleted = isCompleted
    )

    @Test
    fun `past reminder is not scheduled`() {
        val result = AlarmScheduler.scheduleReminder(
            context,
            reminder(System.currentTimeMillis() - 60_000L)
        )

        assertEquals(AlarmScheduleResult.ALREADY_DUE, result)
    }

    @Test
    fun `completed reminder is not scheduled even when its time is in future`() {
        val result = AlarmScheduler.scheduleReminder(
            context,
            reminder(System.currentTimeMillis() + 60 * 60_000L, isCompleted = true)
        )

        assertEquals(AlarmScheduleResult.ALREADY_DUE, result)
    }
}
