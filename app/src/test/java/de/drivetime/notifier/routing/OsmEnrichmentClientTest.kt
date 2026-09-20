package de.drivetime.notifier.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint

class OsmEnrichmentClientTest {
    @Test
    fun prioritizesChargingThenParking() {
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
            parking = true,
            charging = options
        )

        assertEquals(
            listOf(OsmQueryKind.CHARGING, OsmQueryKind.PARKING),
            queries.map { it.kind }
        )
        assertTrue(queries[0].query.contains("amenity\"=\"charging_station"))
        assertTrue(queries[0].query.contains("node(52."))
        assertTrue(!queries[0].query.contains("around:"))
        assertTrue(queries[1].query.contains("amenity\"=\"parking"))
        assertTrue(queries.all { Regex("\\[out:json]").findAll(it.query).count() == 1 })
    }

    @Test
    fun omitsDisabledPoiTypes() {
        val queries = OsmEnrichmentClient().prioritizedQueries(
            points = listOf(GeoPoint(52.50, 13.40), GeoPoint(52.51, 13.41)),
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
        assertEquals(
            listOf("https://two.example/api", "https://one.example/api"),
            OsmEnrichmentClient.normalizeConfiguredEndpoints(
                listOf("https://two.example/api/", "invalid", "https://one.example/api")
            )
        )
    }

    @Test
    fun splitModeStartsParkingAtSecondEndpoint() {
        val endpoints = listOf("https://one.example/api", "https://two.example/api", "https://three.example/api")

        assertEquals(
            endpoints,
            OsmEnrichmentClient.requestEndpointOrder(endpoints, OsmQueryKind.CHARGING, splitRequests = true)
        )
        assertEquals(
            listOf(endpoints[1], endpoints[2], endpoints[0]),
            OsmEnrichmentClient.requestEndpointOrder(endpoints, OsmQueryKind.PARKING, splitRequests = true)
        )
        assertEquals(
            endpoints,
            OsmEnrichmentClient.requestEndpointOrder(endpoints, OsmQueryKind.PARKING, splitRequests = false)
        )
    }
}
