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
import java.util.concurrent.TimeUnit

class GraphHopperProviderService(
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
        require(apiKey.isNotBlank()) { "GraphHopper API key is missing." }
        budget.consume(RoutingProvider.GRAPHHOPPER, dailyCap, 3)
        val origin = geocode(request.origin)
        val destination = geocode(request.destination)
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https").host("graphhopper.com")
            .addPathSegments("api/1/route")
            .addQueryParameter("point", "${origin.first},${origin.second}")
            .addQueryParameter("point", "${destination.first},${destination.second}")
            .addQueryParameter("profile", "car")
            .addQueryParameter("points_encoded", "true")
            .addQueryParameter("instructions", "false")
            .addQueryParameter("key", apiKey)
            .build()
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("GraphHopper: HTTP ${response.code}")
            val paths = JSONObject(body).optJSONArray("paths")
            if (paths == null || paths.length() == 0) error("GraphHopper returned no route.")
            val route = paths.getJSONObject(0)
            val duration = route.getLong("time") / 1000L
            RouteEstimate(
                durationSeconds = duration,
                staticDurationSeconds = duration,
                distanceMeters = route.getDouble("distance").toLong(),
                encodedPolyline = route.getString("points"),
                warning = "GraphHopper time is based on its routing model. Predictive live traffic is not used by this integration.",
                originLatitude = origin.first,
                originLongitude = origin.second,
                destinationLatitude = destination.first,
                destinationLongitude = destination.second
            )
        }
    }

    override suspend fun suggest(query: String, language: String): List<AddressSuggestion> = withContext(Dispatchers.IO) {
        if (query.trim().length < 3 || apiKey.isBlank()) return@withContext emptyList()
        budget.consume(RoutingProvider.GRAPHHOPPER, dailyCap)
        geocodeSuggestions(query.trim(), 6, language)
    }

    private fun geocode(address: String): Pair<Double, Double> {
        val hit = geocodeSuggestions(address, 1, "en").firstOrNull() ?: error("Address not found: $address")
        return (hit.latitude ?: error("Missing latitude")) to (hit.longitude ?: error("Missing longitude"))
    }

    private fun geocodeSuggestions(query: String, limit: Int, language: String): List<AddressSuggestion> {
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https").host("graphhopper.com")
            .addPathSegments("api/1/geocode")
            .addQueryParameter("q", query)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("locale", language)
            .addQueryParameter("key", apiKey)
            .build()
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("GraphHopper Geocoding: HTTP ${response.code}")
            val hits = JSONObject(body).optJSONArray("hits") ?: return emptyList()
            return buildList {
                for (i in 0 until hits.length()) {
                    val h = hits.getJSONObject(i)
                    val point = h.optJSONObject("point") ?: continue
                    val label = listOf(
                        h.optString("name"),
                        h.optString("street"),
                        h.optString("housenumber"),
                        h.optString("city"),
                        h.optString("country")
                    ).filter { it.isNotBlank() }.distinct().joinToString(", ")
                    if (label.isNotBlank()) add(AddressSuggestion(label, point.optDouble("lat"), point.optDouble("lng")))
                }
            }
        }
    }
}
