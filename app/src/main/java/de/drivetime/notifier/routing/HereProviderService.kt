package de.drivetime.notifier.routing

import de.drivetime.notifier.data.RoutingProvider
import de.drivetime.notifier.model.AddressSuggestion
import de.drivetime.notifier.model.RouteEstimate
import de.drivetime.notifier.model.RouteRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.time.Instant
import java.util.concurrent.TimeUnit

class HereProviderService(
    private val apiKey: String,
    private val budget: RequestBudgetStore,
    private val dailyCap: Int,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(16, TimeUnit.SECONDS)
        .build()
) : RoutingService, AddressSearchService {
    override suspend fun route(request: RouteRequest): RouteEstimate = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "HERE API key is missing." }
        budget.consume(RoutingProvider.HERE, dailyCap, 3)
        val origin = geocode(request.origin)
        val destination = geocode(request.destination)
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https").host("router.hereapi.com")
            .addPathSegments("v8/routes")
            .addQueryParameter("transportMode", "car")
            .addQueryParameter("origin", "${origin.first},${origin.second}")
            .addQueryParameter("destination", "${destination.first},${destination.second}")
            .addQueryParameter("arrivalTime", Instant.ofEpochMilli(request.arrivalMillis).toString())
            .addQueryParameter("return", "summary,polyline")
            .addQueryParameter("apiKey", apiKey)
            .build()
        val http = Request.Builder().url(url).get().build()
        client.newCall(http).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HERE Routing: HTTP ${response.code}")
            val routes = JSONObject(body).optJSONArray("routes")
            if (routes == null || routes.length() == 0) error("HERE returned no route.")
            val sections = routes.getJSONObject(0).getJSONArray("sections")
            var duration = 0L
            var baseDuration = 0L
            var distance = 0L
            val points = mutableListOf<GeoPoint>()
            for (i in 0 until sections.length()) {
                val section = sections.getJSONObject(i)
                val summary = section.getJSONObject("summary")
                duration += summary.optLong("duration")
                baseDuration += summary.optLong("baseDuration", summary.optLong("duration"))
                distance += summary.optLong("length")
                val decoded = FlexiblePolylineDecoder.decode(section.getString("polyline"))
                if (points.isNotEmpty() && decoded.isNotEmpty() && points.last() == decoded.first()) {
                    points += decoded.drop(1)
                } else points += decoded
            }
            RouteEstimate(
                durationSeconds = duration,
                staticDurationSeconds = baseDuration.coerceAtLeast(1),
                distanceMeters = distance,
                encodedPolyline = PolylineEncoder.encode(points),
                originLatitude = origin.first,
                originLongitude = origin.second,
                destinationLatitude = destination.first,
                destinationLongitude = destination.second
            )
        }
    }

    override suspend fun suggest(query: String, language: String): List<AddressSuggestion> = withContext(Dispatchers.IO) {
        if (query.trim().length < 3 || apiKey.isBlank()) return@withContext emptyList()
        budget.consume(RoutingProvider.HERE, dailyCap)
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https").host("autocomplete.search.hereapi.com")
            .addPathSegments("v1/autocomplete")
            .addQueryParameter("q", query.trim())
            .addQueryParameter("limit", "6")
            .addQueryParameter("lang", language)
            .addQueryParameter("apiKey", apiKey)
            .build()
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val items = JSONObject(response.body?.string().orEmpty()).optJSONArray("items") ?: return@withContext emptyList()
            buildList {
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val label = item.optJSONObject("address")?.optString("label").takeUnless { it.isNullOrBlank() }
                        ?: item.optString("title")
                    if (!label.isNullOrBlank()) add(AddressSuggestion(label))
                }
            }.distinctBy { it.label }.take(6)
        }
    }

    private fun geocode(address: String): Pair<Double, Double> {
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https").host("geocode.search.hereapi.com")
            .addPathSegments("v1/geocode")
            .addQueryParameter("q", address)
            .addQueryParameter("limit", "1")
            .addQueryParameter("apiKey", apiKey)
            .build()
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HERE Geocoding: HTTP ${response.code}")
            val items = JSONObject(body).optJSONArray("items")
            if (items == null || items.length() == 0) error("Address not found: $address")
            val pos = items.getJSONObject(0).getJSONObject("position")
            return pos.getDouble("lat") to pos.getDouble("lng")
        }
    }
}
