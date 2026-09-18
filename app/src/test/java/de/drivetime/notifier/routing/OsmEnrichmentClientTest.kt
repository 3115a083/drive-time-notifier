package de.drivetime.notifier.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

class OsmEnrichmentClientTest {
    @Test
    fun combinesEnabledPoiTypesIntoOneRequest() {
        val options = ChargingSearchOptions(
            connectors = emptySet(),
            maxDistanceMeters = 2_500,
            speedPreference = de.drivetime.notifier.data.ChargingSpeedPreference.ANY,
            preferredOperator = "",
            showOtherOperators = true,
            useBNetzA = true
        )

        val query = OsmEnrichmentClient().combinedQuery(
            points = listOf(GeoPoint(52.50, 13.40), GeoPoint(52.51, 13.41)),
            cameras = true,
            parking = true,
            charging = options
        )

        assertEquals(1, Regex("\\[out:json]").findAll(query).count())
        assertEquals(1, Regex("out center").findAll(query).count())
        assertTrue(query.contains("highway\"=\"speed_camera"))
        assertTrue(query.contains("amenity\"=\"parking"))
        assertTrue(query.contains("amenity\"=\"charging_station"))
        assertTrue(query.contains("around:2500"))
    }

    @Test
    fun omitsDisabledPoiTypes() {
        val query = OsmEnrichmentClient().combinedQuery(
            points = listOf(GeoPoint(52.50, 13.40), GeoPoint(52.51, 13.41)),
            cameras = false,
            parking = true,
            charging = null
        )

        assertFalse(query.contains("speed_camera"))
        assertFalse(query.contains("charging_station"))
        assertTrue(query.contains("amenity\"=\"parking"))
    }
}
