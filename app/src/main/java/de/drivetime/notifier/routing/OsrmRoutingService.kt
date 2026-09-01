package de.drivetime.notifier.routing

import de.drivetime.notifier.model.RouteEstimate
import de.drivetime.notifier.model.RouteRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OsrmRoutingService(
    private val osrmBaseUrl: String,
    private val nominatimBaseUrl: String,
    private val userAgent: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
) : RoutingService {
    override suspend fun route(request: RouteRequest): RouteEstimate = withContext(Dispatchers.IO) {
        val origin = geocode(request.origin)
        val destination = geocode(request.destination)
        val base = osrmBaseUrl.toHttpUrl()
        require(base.isHttps) { "OSRM-Endpunkt muss HTTPS verwenden." }

        val url = base.newBuilder()
            .addPathSegments("route/v1/driving")
            .addPathSegment("${origin.second},${origin.first};${destination.second},${destination.first}")
            .addQueryParameter("overview", "full")
            .addQueryParameter("geometries", "polyline")
            .addQueryParameter("steps", "false")
            .build()

        val http = Request.Builder().url(url).header("User-Agent", userAgent).get().build()
        client.newCall(http).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("OSRM: HTTP ${response.code}")
            val root = JSONObject(text)
            if (root.optString("code") != "Ok") error("OSRM konnte keine Route berechnen.")
            val route = root.getJSONArray("routes").getJSONObject(0)
            val duration = route.getDouble("duration").toLong()
            RouteEstimate(
                durationSeconds = duration,
                staticDurationSeconds = duration,
                distanceMeters = route.getDouble("distance").toLong(),
                encodedPolyline = route.getString("geometry"),
                warning = "OSRM liefert keine zukünftige Stauprognose. Die Fahrzeit basiert auf dem Routingmodell ohne Live-Verkehr."
            )
        }
    }

    private fun geocode(address: String): Pair<Double, Double> {
        val base = nominatimBaseUrl.toHttpUrl()
        require(base.isHttps) { "Nominatim-Endpunkt muss HTTPS verwenden." }
        val url = base.newBuilder()
            .addPathSegment("search")
            .addQueryParameter("q", address)
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("limit", "1")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Language", "de")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Nominatim: HTTP ${response.code}")
            val results = JSONArray(body)
            if (results.length() == 0) error("Adresse nicht gefunden: $address")
            val item = results.getJSONObject(0)
            return item.getString("lat").toDouble() to item.getString("lon").toDouble()
        }
    }
}
