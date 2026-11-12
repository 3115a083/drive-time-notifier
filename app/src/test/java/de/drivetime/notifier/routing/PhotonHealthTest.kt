package de.drivetime.notifier.routing

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class PhotonHealthTest {
    private fun service(body:String)=PhotonSearchService("https://example.test","test",client=OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody()).build()
    }.build())
    @Test fun aValidEmptySearchIsReachableEvenWhenBerlinIsOutsideTheDataset()=runBlocking {
        service("{\"features\":[]}").testEndpoint()
    }
    @Test fun anHttpSuccessWithTheWrongSchemaIsAnApiError()=runBlocking {
        try {service("{\"error\":\"invalid dataset\"}").testEndpoint();fail("Invalid schema must not look healthy")}
        catch(expected:org.json.JSONException) {assertNotNull(expected)}
    }
}
