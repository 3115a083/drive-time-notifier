package de.drivetime.notifier.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ChargingPreferencesTest {
    @Test
    fun connectorIdsRoundTrip() {
        ChargingConnectorPreference.entries.forEach { value ->
            assertEquals(value, ChargingConnectorPreference.fromId(value.id))
        }
    }

    @Test
    fun slowChargingPrefersLowPower() {
        val preference = ChargingSpeedPreference.SLOW
        assertEquals(0, preference.penalty(11.0))
        assertEquals(0, preference.penalty(22.0))
        assertEquals(3, preference.penalty(150.0))
    }

    @Test
    fun hpcChargingPrefersHighPower() {
        val preference = ChargingSpeedPreference.HPC
        assertEquals(0, preference.penalty(300.0))
        assertEquals(3, preference.penalty(22.0))
    }
}
