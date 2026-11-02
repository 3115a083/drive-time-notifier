package de.drivetime.notifier.routing

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class PhotonFallbackTest {
    @Test fun configuredPhotonOrderIsUsedWhenTheFirstEndpointFails()=runBlocking {
        val seen=mutableListOf<String>()
        val client=OkHttpClient.Builder().addInterceptor {chain->
            val host=chain.request().url.host;seen+=host
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if(host=="one.example")503 else 200).message("test")
                .body("""{"features":[{"geometry":{"coordinates":[13.4,52.5]},"properties":{"name":"Place"}}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val place=PhotonSearchService("https://one.example","test",listOf("https://two.example"),client).geocode("Example Street")
        assertEquals(listOf("one.example","two.example"),seen)
        assertEquals(52.5,place.latitude!!,0.0)
    }
}
