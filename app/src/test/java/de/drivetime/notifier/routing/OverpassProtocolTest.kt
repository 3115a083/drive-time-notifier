package de.drivetime.notifier.routing

import de.drivetime.notifier.network.awaitResponse
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.osmdroid.util.GeoPoint

class OverpassProtocolTest {
    @Test fun smallQueriesUseEncodedGetAndLeaveRoomForServerQueueing() {
        val q="[out:json][timeout:20];node(1);out ids;"
        val request=OverpassProtocol.request("https://example.org/api/interpreter",q)
        assertEquals("GET",request.method)
        assertEquals(q,request.url.queryParameter("data"))
        assertTrue(OverpassProtocol.client.callTimeoutMillis>=45_000)
        assertNull(request.header("Connection"))
    }
    @Test fun runtimeErrorsInsideHttp200AreFailuresAndEmptySuccessIsValid() {
        try {OverpassProtocol.parse("""{"remark":"runtime error: Query timed out","elements":[]}""");fail("Runtime errors must fail")}
        catch(_:java.io.IOException) { }
        assertEquals(0,OverpassProtocol.parse("""{"elements":[]}""").getJSONArray("elements").length())
    }
    @Test fun aRuntimeFailureFallsBackAndSuccessfulResultsAreCached()=runBlocking {
        val seen=mutableListOf<String>()
        val prefix="test-${System.nanoTime()}"
        val client=OkHttpClient.Builder().addInterceptor { chain->
            val host=chain.request().url.host;seen+=host
            val body=if(host.startsWith("bad"))"""{"remark":"runtime error","elements":[]}"""
            else """{"elements":[{"lat":52.5,"lon":13.4,"tags":{"amenity":"parking","fee":"no"}}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val endpoints=listOf("https://bad-$prefix.example/api/interpreter","https://good-$prefix.example/api/interpreter")
        val osm=OsmEnrichmentClient(configuredEndpoints=endpoints,client=client)
        val first=osm.query(listOf(GeoPoint(52.5,13.4)),parking=ParkingSearchOptions())
        assertFalse(first.unavailable)
        assertEquals(1,first.pois.size)
        assertEquals(2,seen.size)
        val second=osm.query(listOf(GeoPoint(52.5,13.4)),parking=ParkingSearchOptions())
        assertEquals(first.pois.size,second.pois.size)
        assertEquals(2,seen.size)
    }
}
