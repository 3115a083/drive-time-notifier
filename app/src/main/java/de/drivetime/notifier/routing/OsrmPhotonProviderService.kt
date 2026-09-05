package de.drivetime.notifier.routing

import de.drivetime.notifier.data.LimitPeriod
import de.drivetime.notifier.data.RoutingProvider
import de.drivetime.notifier.model.AddressSuggestion
import de.drivetime.notifier.model.RouteEstimate
import de.drivetime.notifier.model.RouteRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OsrmPhotonProviderService(
    private val osrmBaseUrl: String,
    private val photonBaseUrl: String,
    private val userAgent: String,
    private val budget: RequestBudgetStore,
    private val dailyCap: Int,
    private val limitPeriod: LimitPeriod = LimitPeriod.DAILY,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
) : RoutingService, AddressSearchService {
    override suspend fun route(request: RouteRequest): RouteEstimate = withContext(Dispatchers.IO) {
        budget.consume(RoutingProvider.OSRM, dailyCap, limitPeriod, 3)
        val origin = geocode(request.origin)
        val destination = geocode(request.destination)
        val base = osrmBaseUrl.toHttpUrl()
        require(base.isHttps) { "OSRM endpoint must use HTTPS." }
        val routePath = "${origin.second},${origin.first};${destination.second},${destination.first}"
        val url = "${base.toString().removeSuffix("/")}/route/v1/driving/$routePath"
            .toHttpUrl().newBuilder()
            .addQueryParameter("overview", "full")
            .addQueryParameter("geometries", "polyline")
            .addQueryParameter("steps", "false")
            .build()
        client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).get().build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("OSRM: HTTP ${response.code}")
            val root = JSONObject(body)
            if (root.optString("code") != "Ok") error("OSRM could not calculate a route.")
            val route = root.getJSONArray("routes").getJSONObject(0)
            val duration = route.getDouble("duration").toLong()
            RouteEstimate(
                durationSeconds = duration,
                staticDurationSeconds = duration,
                distanceMeters = route.getDouble("distance").toLong(),
                encodedPolyline = route.getString("geometry"),
                warning = "OSRM has no predictive live traffic. The result is a static OSM-based estimate.",
                originLatitude = origin.first,
                originLongitude = origin.second,
                destinationLatitude = destination.first,
                destinationLongitude = destination.second
            )
        }
    }

    override suspend fun suggest(query: String, language: String): List<AddressSuggestion> = withContext(Dispatchers.IO) {
        if (query.trim().length < 3) return@withContext emptyList()
        budget.consume(RoutingProvider.OSRM, dailyCap, limitPeriod)
        photon(query.trim(), 6, language)
    }

    private fun geocode(address: String): Pair<Double, Double> {
        val hit = photon(address, 1, "en").firstOrNull() ?: error("Address not found: $address")
        return (hit.latitude ?: error("Missing latitude")) to (hit.longitude ?: error("Missing longitude"))
    }

    private fun photon(query: String, limit: Int, language: String): List<AddressSuggestion> {
        val base = photonBaseUrl.toHttpUrl()
        require(base.isHttps) { "Photon endpoint must use HTTPS." }
        val url = base.newBuilder()
            .addPathSegment("api")
            .addQueryParameter("q", query)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("lang", language)
            .build()
        client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).get().build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Photon: HTTP ${response.code}")
            val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
            return buildList {
                for (i in 0 until features.length()) {
                    val feature = features.getJSONObject(i)
                    val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                    val p = feature.optJSONObject("properties") ?: continue
                    val label = listOf(
                        p.optString("name"),
                        p.optString("street"),
                        p.optString("housenumber"),
                        p.optString("postcode"),
                        p.optString("city"),
                        p.optString("country")
                    ).filter { it.isNotBlank() }.distinct().joinToString(", ")
                    if (label.isNotBlank()) add(AddressSuggestion(label, coords.optDouble(1), coords.optDouble(0)))
                }
            }
        }
    }
}
