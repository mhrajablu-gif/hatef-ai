package com.example.domain.recurrence

/**
 * A validated, provider-independent recurrence rule.
 *
 * Weekday numbering follows the Iranian week: Saturday=1 ... Friday=7.
 * The occurrence timestamp remains the source of the local wall-clock time.
 */
data class RecurrenceRule(
    val frequency: Frequency,
    val interval: Int = 1,
    val weekdays: Set<Int> = emptySet(),
    val monthDay: Int? = null,
    val month: Int? = null,
    val endAtMillis: Long? = null
) {
    enum class Frequency { NONE, DAILY, WEEKLY, MONTHLY, YEARLY, WORKDAYS, CUSTOM }

    init {
        require(interval >= 1) { "interval must be at least 1" }
        require(weekdays.all { it in 1..7 }) { "weekdays must use Saturday=1 through Friday=7" }
        require(monthDay == null || monthDay in 1..31) { "monthDay must be in 1..31" }
        require(month == null || month in 1..12) { "month must be in 1..12" }
        require(frequency != Frequency.CUSTOM || weekdays.isNotEmpty()) {
            "CUSTOM recurrence requires at least one weekday"
        }
        require(frequency != Frequency.WEEKLY || weekdays.isNotEmpty()) {
            "WEEKLY recurrence requires at least one weekday"
        }
        require(frequency != Frequency.YEARLY || month != null && monthDay != null) {
            "YEARLY recurrence requires month and monthDay"
        }
        require(frequency != Frequency.MONTHLY || monthDay != null) {
            "MONTHLY recurrence requires monthDay"
        }
    }
}
