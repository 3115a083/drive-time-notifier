package de.drivetime.notifier.routing

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object OverpassProtocol {
    const val QUERY_SECONDS = 20
    const val CALL_SECONDS = 50L
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(CALL_SECONDS, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    fun request(endpoint: String, query: String): Request {
        val url = endpoint.toHttpUrl()
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty()) { "HTTPS endpoint required." }
        val builder = Request.Builder().header("User-Agent", "DriveTimeNotifier (+https://github.com/3115a083/drive-time-notifier)")
            .header("Accept", "application/json")
        // Both methods are supported by Overpass. Small queries avoid POST-only proxy failures.
        return if (query.length <= 6000) builder.url(url.newBuilder().setQueryParameter("data", query).build()).get().build()
        else builder.url(url).post(FormBody.Builder().add("data", query).build()).build()
    }

    fun parse(body: String): JSONObject {
        val json = JSONObject(body)
        val remark = json.optString("remark").trim()
        if (remark.isNotEmpty()) throw IOException("Overpass: ${remark.take(300)}")
        require(json.optJSONArray("elements") != null) { "Overpass response contains no elements array." }
        return json
    }
}
