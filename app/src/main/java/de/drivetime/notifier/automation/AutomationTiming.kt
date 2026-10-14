package de.drivetime.notifier.automation

import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime

/** Local clock scheduling, including daylight-saving changes. */
object AutomationTiming {
    const val MAX_LATENESS_MINUTES = 120L

    fun nextRun(now: ZonedDateTime, hour: Int, minute: Int): ZonedDateTime {
        val time = java.time.LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        val today = now.toLocalDate().atTime(time).atZone(now.zone)
        return if (today.isAfter(now)) today else now.toLocalDate().plusDays(1).atTime(time).atZone(now.zone)
    }

    fun mayRun(now: ZonedDateTime, scheduledMillis: Long, hour: Int, minute: Int): Boolean {
        if (scheduledMillis <= 0L) return false // Old jobs must be rescheduled, not executed immediately.
        val scheduled = java.time.Instant.ofEpochMilli(scheduledMillis).atZone(now.zone)
        val expected = scheduled.toLocalDate()
            .atTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59)).atZone(now.zone)
        val age = Duration.between(scheduled, now)
        return scheduled.toInstant() == expected.toInstant() &&
            !age.isNegative && age <= Duration.ofMinutes(MAX_LATENESS_MINUTES)
    }

    fun planningDay(scheduledMillis: Long, now: ZonedDateTime): LocalDate =
        if (scheduledMillis > 0) java.time.Instant.ofEpochMilli(scheduledMillis)
            .atZone(now.zone).toLocalDate().plusDays(1)
        else now.toLocalDate().plusDays(1)
}
