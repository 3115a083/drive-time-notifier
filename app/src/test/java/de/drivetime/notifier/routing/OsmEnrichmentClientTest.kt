package de.drivetime.notifier.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

class OsmEnrichmentClientTest {
    @Test
    fun prioritizesChargingThenParkingThenSpeedCameras() {
        val options = ChargingSearchOptions(
            connectors = emptySet(),
            maxDistanceMeters = 2_500,
            speedPreference = de.drivetime.notifier.data.ChargingSpeedPreference.ANY,
            preferredOperator = "",
            showOtherOperators = true,
            useBNetzA = true
        )

        val queries = OsmEnrichmentClient().prioritizedQueries(
            points = listOf(GeoPoint(52.50, 13.40), GeoPoint(52.51, 13.41)),
            cameras = true,
            parking = true,
            charging = options
        )

        assertEquals(
            listOf(OsmQueryKind.CHARGING, OsmQueryKind.PARKING, OsmQueryKind.SPEED_CAMERAS),
            queries.map { it.kind }
        )
        assertTrue(queries[0].query.contains("amenity\"=\"charging_station"))
        assertTrue(queries[0].query.contains("around:2500"))
        assertTrue(queries[1].query.contains("amenity\"=\"parking"))
        assertTrue(queries[2].query.contains("highway\"=\"speed_camera"))
        assertTrue(queries.all { Regex("\\[out:json]").findAll(it.query).count() == 1 })
    }

    @Test
    fun omitsDisabledPoiTypes() {
        val queries = OsmEnrichmentClient().prioritizedQueries(
            points = listOf(GeoPoint(52.50, 13.40), GeoPoint(52.51, 13.41)),
            cameras = false,
            parking = true,
            charging = null
        )

        assertEquals(listOf(OsmQueryKind.PARKING), queries.map { it.kind })
        assertTrue(queries.single().query.contains("amenity\"=\"parking"))
    }

    @Test
    fun configuredEndpointIsUsedFirstAndInvalidEndpointFallsBack() {
        assertEquals(
            "https://example.org/custom/interpreter",
            OsmEnrichmentClient.endpointOrder("https://example.org/custom/interpreter/").first()
        )
        assertEquals(
            OsmEnrichmentClient.DEFAULT_OVERPASS_ENDPOINT,
            OsmEnrichmentClient.endpointOrder("http://insecure.example.org").first()
        )
    }
}
