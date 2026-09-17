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
    fun speedBandsMatchDocumentedBoundaries() {
        assertEquals(0, ChargingSpeedPreference.SLOW.penalty(11.0))
        assertEquals(0, ChargingSpeedPreference.MEDIUM.penalty(22.0))
        assertEquals(0, ChargingSpeedPreference.FAST.penalty(100.0))
        assertEquals(0, ChargingSpeedPreference.HPC.penalty(101.0))
    }

    @Test
    fun hpcPenalizesSlowCharging() {
        assertEquals(3, ChargingSpeedPreference.HPC.penalty(11.0))
    }
}
