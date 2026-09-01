package de.drivetime.notifier.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DrivePlannerTest {
    @Test
    fun keepsFullBufferWhenPossible() {
        val destination = 2_000_000L
        val plan = DrivePlanner.plan(destination, 600, 15, 500_000L)
        assertEquals(15, plan.appliedBufferMinutes)
        assertNull(plan.warning)
        assertEquals(destination - 600_000L - 900_000L, plan.departureMillis)
    }

    @Test
    fun shortensBufferWhenEventsAreTight() {
        val destination = 2_000_000L
        val previousEnd = 1_200_000L
        val plan = DrivePlanner.plan(destination, 600, 15, previousEnd)
        assertEquals(previousEnd, plan.departureMillis)
        assertEquals(3, plan.appliedBufferMinutes)
        assertNotNull(plan.warning)
    }

    @Test
    fun overlapsPreviousEventToReachDestinationOnTime() {
        val destination = 2_000_000L
        val previousEnd = 1_700_000L
        val plan = DrivePlanner.plan(destination, 600, 15, previousEnd)
        assertEquals(0, plan.appliedBufferMinutes)
        assertEquals(destination - 600_000L, plan.departureMillis)
        assertEquals(destination, plan.arrivalMillis)
        assertNotNull(plan.warning)
    }
}
