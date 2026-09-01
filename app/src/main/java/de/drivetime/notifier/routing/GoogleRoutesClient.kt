package de.drivetime.notifier.routing

import de.drivetime.notifier.model.RouteEstimate
import de.drivetime.notifier.model.RouteRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

class GoogleRoutesClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    suspend fun route(request: RouteRequest, apiKey: String): RouteEstimate = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Google Routes API-Key fehlt." }
        val origin = geocode(request.origin, apiKey)
        val destination = geocode(request.destination, apiKey)

        // Routes for DRIVE use a future departureTime. First estimate with a conservative
        // 30-minute guess, then ask again using the estimated duration to approximate
        // the departure time required for the requested appointment arrival.
        val firstDeparture = (request.arrivalMillis - 30 * 60_000L).coerceAtLeast(System.currentTimeMillis())
        val first = compute(origin, destination, firstDeparture, apiKey)
        val refinedDeparture = (request.arrivalMillis - first.durationSeconds * 1000L)
            .coerceAtLeast(System.currentTimeMillis())
        compute(origin, destination, refinedDeparture, apiKey)
    }

    private fun compute(
        origin: Pair<Double, Double>,
        destination: Pair<Double, Double>,
        departureMillis: Long,
        apiKey: String
    ): RouteEstimate {
        val body = JSONObject().apply {
            put("origin", waypoint(origin.first, origin.second))
            put("destination", waypoint(destination.first, destination.second))
            put("travelMode", "DRIVE")
            put("routingPreference", "TRAFFIC_AWARE_OPTIMAL")
            put("departureTime", Instant.ofEpochMilli(departureMillis).toString())
            put("computeAlternativeRoutes", false)
            put("languageCode", "de-DE")
            put("units", "METRIC")
        }.toString()

        val http = Request.Builder()
            .url("https://routes.googleapis.com/directions/v2:computeRoutes")
            .header("X-Goog-Api-Key", apiKey)
            .header("X-Goog-FieldMask", "routes.duration,routes.staticDuration,routes.distanceMeters,routes.polyline.encodedPolyline")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(http).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Routingdienst: HTTP ${response.code}")
            val routes = JSONObject(text).optJSONArray("routes")
            if (routes == null || routes.length() == 0) error("Keine befahrbare Route gefunden.")
            val route = routes.getJSONObject(0)
            return RouteEstimate(
                durationSeconds = seconds(route.getString("duration")),
                staticDurationSeconds = seconds(route.optString("staticDuration", route.getString("duration"))),
                distanceMeters = route.optLong("distanceMeters", 0),
                encodedPolyline = route.getJSONObject("polyline").getString("encodedPolyline"),
                originLatitude = origin.first,
                originLongitude = origin.second,
                destinationLatitude = destination.first,
                destinationLongitude = destination.second
            )
        }
    }

    private fun geocode(address: String, apiKey: String): Pair<Double, Double> {
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https").host("maps.googleapis.com")
            .addPathSegments("maps/api/geocode/json")
            .addQueryParameter("address", address)
            .addQueryParameter("key", apiKey)
            .build()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Geocoding: HTTP ${response.code}")
            val results = JSONObject(body).getJSONArray("results")
            if (results.length() == 0) error("Adresse nicht gefunden: $address")
            val loc = results.getJSONObject(0).getJSONObject("geometry").getJSONObject("location")
            return loc.getDouble("lat") to loc.getDouble("lng")
        }
    }

    private fun waypoint(lat: Double, lng: Double) = JSONObject().apply {
        put("location", JSONObject().apply {
            put("latLng", JSONObject().apply {
                put("latitude", lat)
                put("longitude", lng)
            })
        })
    }

    private fun seconds(value: String): Long = value.removeSuffix("s").toDouble().toLong()
}
