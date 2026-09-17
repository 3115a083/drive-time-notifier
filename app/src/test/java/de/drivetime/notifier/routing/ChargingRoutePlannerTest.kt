package de.drivetime.notifier.routing

import de.drivetime.notifier.data.ChargingSpeedPreference
import org.junit.Assert.assertEquals
import org.junit.Test

class ChargingRoutePlannerTest {
    @Test
    fun slowPreferencePrefersSlowChargers() {
        assertEquals(0, ChargingSpeedPreference.SLOW.penalty(11.0))
        assertEquals(3, ChargingSpeedPreference.SLOW.penalty(150.0))
    }

    @Test
    fun hpcPreferencePrefersHighPower() {
        assertEquals(0, ChargingSpeedPreference.HPC.penalty(300.0))
        assertEquals(2, ChargingSpeedPreference.HPC.penalty(22.0))
        assertEquals(3, ChargingSpeedPreference.HPC.penalty(11.0))
    }
}
