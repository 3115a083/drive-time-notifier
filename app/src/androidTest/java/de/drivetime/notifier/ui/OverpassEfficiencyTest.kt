package de.drivetime.notifier.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import de.drivetime.notifier.routing.*
import de.drivetime.notifier.network.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class OverpassEfficiencyTest {
    private val points=listOf(GeoPoint(52.52,13.40))
    @Test fun combinedQueriesUseOneRequestCacheRawResultsAndInvalidateOnAuthChange()=runBlocking {
        val endpoint="https://batch-efficiency.test/api"
        val calls=AtomicInteger()
        val client=OkHttpClient.Builder().addInterceptor {chain ->
            calls.incrementAndGet()
            val query=chain.request().url.queryParameter("data")!!
            assertEquals(2,Regex("out center").findAll(query).count())
            assertTrue(query.contains("charging_station"));assertTrue(query.contains("parking"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"elements":[{"type":"node","id":1,"lat":52.52,"lon":13.40,"tags":{"amenity":"parking","fee":"no"}},{"type":"node","id":2,"lat":52.52,"lon":13.40,"tags":{"amenity":"charging_station","access":"yes","socket:type2":"2"}}]}""".toResponseBody()).build()
        }.build()
        val charging=ChargingSearchOptions(connectors=emptySet(),resultLimit=5,maxDistanceMeters=1500,
            speedPreference=de.drivetime.notifier.data.ChargingSpeedPreference.ANY,preferredOperator="",showOtherOperators=true,useBNetzA=false)
        val service=OsmEnrichmentClient(endpoint,listOf(endpoint),client=client)
        try {
            val result=service.query(points,ParkingSearchOptions(),charging)
            assertFalse(result.unavailable)
            assertTrue(result.pois.any {it.kind==RoutePoi.Kind.PARKING})
            assertTrue(result.pois.any {it.kind==RoutePoi.Kind.CHARGING_STATION})
            service.query(points,ParkingSearchOptions(),charging);assertEquals(1,calls.get())
            EndpointSecurity.save(endpoint,EndpointHeader("X-Test-Key","synthetic-cache-key"))
            service.query(points,ParkingSearchOptions(),charging);assertEquals(2,calls.get())
        } finally {EndpointSecurity.save(endpoint,null)}
    }
    @Test fun failureAttemptsStaySequentialAndStopAfterTwoServers()=runBlocking {
        val calls=mutableListOf<String>()
        val client=OkHttpClient.Builder().addInterceptor {chain ->
            calls+=chain.request().url.host
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("Busy")
                .body("busy".toResponseBody()).build()
        }.build()
        val endpoints=listOf("https://failure-one.test/api","https://failure-two.test/api","https://failure-three.test/api")
        val service=OsmEnrichmentClient(endpoints.first(),endpoints,client=client)
        assertTrue(service.query(points,ParkingSearchOptions()).unavailable)
        assertEquals(listOf("failure-one.test","failure-two.test"),calls)
        // Temporarily failed endpoints are skipped on the next explicit request.
        service.query(points,ParkingSearchOptions())
        assertEquals(listOf("failure-one.test","failure-two.test","failure-three.test"),calls)
    }
    @Test fun splitModeKeepsIndependentQueriesAndCategoryPriority() {
        val service=OsmEnrichmentClient(splitRequests=true)
        val charging=ChargingSearchOptions(connectors=emptySet(),resultLimit=5,maxDistanceMeters=1500,
            speedPreference=de.drivetime.notifier.data.ChargingSpeedPreference.ANY,preferredOperator="",showOtherOperators=true,useBNetzA=false)
        val queries=service.plannedQueries(points,ParkingSearchOptions(),charging)
        assertEquals(2,queries.size)
        assertTrue(queries.first().query.contains("charging_station"))
        assertTrue(queries.last().query.contains("parking"))
    }
}
