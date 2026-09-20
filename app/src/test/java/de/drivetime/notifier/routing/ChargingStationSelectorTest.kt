package de.drivetime.notifier.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingStationSelectorTest {
    @Test
    fun longStayRanksAheadOfShortStay() {
        assertTrue(ChargingStationSelector.stayPenalty("4 h") < ChargingStationSelector.stayPenalty("30 min"))
    }

    @Test
    fun parsesHourAndMinuteDurations() {
        assertEquals(120, ChargingStationSelector.parseMaxStayMinutes("2 h"))
        assertEquals(90, ChargingStationSelector.parseMaxStayMinutes("1:30"))
    }
}
