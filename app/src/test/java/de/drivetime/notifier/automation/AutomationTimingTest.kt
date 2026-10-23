package de.drivetime.notifier.automation

import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class AutomationTimingTest {
    private fun time(value: String) = ZonedDateTime.parse(value + "[Europe/Berlin]")

    @Test fun manualRequestsHaveABoundedLifetime() {
        val now = 1_800_000_000_000L
        assertTrue(AutomationTiming.mayRunManual(now, now))
        assertTrue(AutomationTiming.mayRunManual(now, now - 120 * 60_000))
        assertFalse(AutomationTiming.mayRunManual(now, -1))
        assertFalse(AutomationTiming.mayRunManual(now, now - 120 * 60_000 - 1))
        assertFalse(AutomationTiming.mayRunManual(now, now + 1))
    }
    @Test fun choosesTheNextLocalEvening() {
        val noon = time("2026-10-23T12:00:00+02:00")
        assertEquals(time("2026-10-23T21:00:00+02:00"), AutomationTiming.nextRun(noon, 21, 0))
        assertEquals(time("2026-10-24T21:00:00+02:00"), AutomationTiming.nextRun(noon.withHour(21), 21, 0))
    }
    @Test fun rejectsNoonAndOverdueWorkButAllowsBoundedSystemDelay() {
        val scheduled = time("2026-10-23T21:00:00+02:00").toInstant().toEpochMilli()
        assertFalse(AutomationTiming.mayRun(time("2026-10-23T12:00:00+02:00"), scheduled, 21, 0))
        assertTrue(AutomationTiming.mayRun(time("2026-10-23T21:00:00+02:00"), scheduled, 21, 0))
        assertTrue(AutomationTiming.mayRun(time("2026-10-23T23:00:00+02:00"), scheduled, 21, 0))
        assertFalse(AutomationTiming.mayRun(time("2026-10-23T23:00:01+02:00"), scheduled, 21, 0))
        assertFalse(AutomationTiming.mayRun(time("2026-10-24T12:00:00+02:00"), scheduled, 21, 0))
    }
    @Test fun rejectsMigratedJobsAndChangedSettings() {
        val now = time("2026-10-23T21:00:00+02:00")
        assertFalse(AutomationTiming.mayRun(now, -1, 21, 0))
        assertFalse(AutomationTiming.mayRun(now, now.toInstant().toEpochMilli(), 22, 0))
    }
    @Test fun daylightSavingDoesNotMoveTheLocalHour() {
        val fall = time("2026-10-24T21:00:00+02:00")
        val next = AutomationTiming.nextRun(fall, 21, 0)
        assertEquals(21, next.hour)
        assertEquals(25L, Duration.between(fall, next).toHours())
        val spring = time("2026-03-28T21:00:00+01:00")
        assertEquals(23L, Duration.between(spring, AutomationTiming.nextRun(spring, 21, 0)).toHours())
    }
    @Test fun midnightDelayKeepsTheRequestedPlanningDate() {
        val scheduled = time("2026-10-23T23:30:00+02:00").toInstant().toEpochMilli()
        val now = time("2026-10-24T00:30:00+02:00")
        assertTrue(AutomationTiming.mayRun(now, scheduled, 23, 30))
        assertEquals(LocalDate.of(2026, 10, 24), AutomationTiming.planningDay(scheduled, now))
    }
}
