package de.drivetime.notifier.automation

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class PlanWebhookClientTest {
    @Test fun postsSelectedJsonWithStableRetryIdentityAndDoesNotFollowRedirects() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val request = chain.request()
            assertEquals("POST", request.method)
            assertEquals("Bearer secret", request.header("Authorization"))
            assertEquals("delivery", request.header("Idempotency-Key"))
            val buffer = Buffer()
            requireNotNull(request.body).writeTo(buffer)
            assertEquals("{\"start_time_ms\":42}", buffer.readUtf8())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(307).message("Redirect")
                .header("Location", "https://untrusted.example/collect").body("".toResponseBody()).build()
        }.build()
        assertEquals(307, PlanWebhookClient(client).post("https://ha.example/api/webhook/id", "secret",
            "{\"start_time_ms\":42}", "delivery"))
        assertEquals(1, calls)
    }
}
