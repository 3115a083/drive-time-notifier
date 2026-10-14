package de.drivetime.notifier.automation

import de.drivetime.notifier.network.awaitResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class PlanWebhookClient(client: OkHttpClient = OkHttpClient()) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    suspend fun post(url: String, token: String, payload: String, deliveryId: String): Int {
        require(PlanHookConfig.validWebhookUrl(url))
        require(token.length <= 4_096 && token.none(Char::isISOControl))
        val request = Request.Builder().url(url).post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Idempotency-Key", deliveryId)
        if (token.isNotBlank()) request.header("Authorization", "Bearer $token")
        return client.newCall(request.build()).awaitResponse().use { it.code }
    }
}
