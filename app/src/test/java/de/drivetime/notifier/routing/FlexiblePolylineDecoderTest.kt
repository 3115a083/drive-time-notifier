package de.drivetime.notifier.routing

import org.junit.Assert.assertEquals
import org.junit.Test

class FlexiblePolylineDecoderTest {
    @Test
    fun decodesHereReferencePolyline() {
        val points = FlexiblePolylineDecoder.decode("BFoz5xJ67i1B1B7PzIhaxL7Y")
        assertEquals(4, points.size)
        assertEquals(50.10228, points[0].latitude, 0.000001)
        assertEquals(8.69821, points[0].longitude, 0.000001)
        assertEquals(50.09878, points[3].latitude, 0.000001)
        assertEquals(8.68752, points[3].longitude, 0.000001)
    }

    @Test
    fun standardPolylineRoundTrips() {
        val input = listOf(
            org.osmdroid.util.GeoPoint(52.5200, 13.4050),
            org.osmdroid.util.GeoPoint(52.5170, 13.3889),
            org.osmdroid.util.GeoPoint(52.5096, 13.3760)
        )
        val encoded = PolylineEncoder.encode(input)
        val decoded = PolylineDecoder.decode(encoded)
        assertEquals(input.size, decoded.size)
        input.zip(decoded).forEach { (expected, actual) ->
            assertEquals(expected.latitude, actual.latitude, 0.00002)
            assertEquals(expected.longitude, actual.longitude, 0.00002)
        }
    }
}
