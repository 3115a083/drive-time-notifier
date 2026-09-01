package de.drivetime.notifier.routing

import android.content.Context
import de.drivetime.notifier.data.AppSettings
import de.drivetime.notifier.data.RoutingProvider
import de.drivetime.notifier.model.RouteEstimate
import de.drivetime.notifier.model.RouteRequest
import de.drivetime.notifier.security.SecureApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class UnifiedRoutingService(
    private val context: Context,
    private val settings: AppSettings,
    private val keyStore: SecureApiKeyStore,
    private val budget: RequestBudgetStore,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(16, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
) : RoutingService {

    override suspend fun route(request: RouteRequest): RouteEstimate = withContext(Dispatchers.IO) {
        val geocoder = PhotonSearchService(settings.photonBaseUrl, context.packageName, client)
        val origin = geocoder.geocode(request.origin)
        val destination = geocoder.geocode(request.destination)
        val oLat = origin.latitude ?: error("Origin latitude missing.")
        val oLon = origin.longitude ?: error("Origin longitude missing.")
        val dLat = destination.latitude ?: error("Destination latitude missing.")
        val dLon = destination.longitude ?: error("Destination longitude missing.")

        when (settings.routingProvider) {
            RoutingProvider.VALHALLA -> valhalla(oLat, oLon, dLat, dLon, request.arrivalMillis)
            RoutingProvider.OPENROUTESERVICE -> openRouteService(oLat, oLon, dLat, dLon)
            RoutingProvider.OSRM -> osrm(oLat, oLon, dLat, dLon)
            RoutingProvider.GRAPHHOPPER -> graphHopper(oLat, oLon, dLat, dLon)
            RoutingProvider.GOOGLE -> google(oLat, oLon, dLat, dLon, request.arrivalMillis)
            RoutingProvider.HERE -> here(oLat, oLon, dLat, dLon, request.arrivalMillis)
            RoutingProvider.TOMTOM -> tomTom(oLat, oLon, dLat, dLon, request.arrivalMillis)
        }
    }

    private fun valhalla(oLat: Double, oLon: Double, dLat: Double, dLon: Double, arrival: Long): RouteEstimate {
        budget.consume(RoutingProvider.VALHALLA, settings.providerCaps.valhalla)
        val base = settings.valhallaBaseUrl.toHttpUrl()
        val body = JSONObject().apply {
            put("locations", JSONArray().apply {
                put(JSONObject().put("lat", oLat).put("lon", oLon))
                put(JSONObject().put("lat", dLat).put("lon", dLon))
            })
            put("costing", "auto")
            put("units", "kilometers")
            put("date_time", JSONObject().put("type", 2).put(
                "value",
                Instant.ofEpochMilli(arrival).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
            ))
        }.toString()
        val request = Request.Builder()
            .url(base.newBuilder().addPathSegment("route").build())
            .header("X-Client-Id", context.packageName)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Valhalla: HTTP ${response.code}")
            val trip = JSONObject(text).getJSONObject("trip")
            val summary = trip.getJSONObject("summary")
            val legs = trip.getJSONArray("legs")
            val points = mutableListOf<GeoPoint>()
            for (i in 0 until legs.length()) {
                val decoded = PolylineDecoder.decode(legs.getJSONObject(i).getString("shape"), 6)
                if (points.isNotEmpty() && decoded.isNotEmpty()) points += decoded.drop(1) else points += decoded
            }
            require(points.size >= 2) { "Valhalla returned no route geometry." }
            RouteEstimate(
                durationSeconds = summary.getDouble("time").toLong(),
                staticDurationSeconds = summary.getDouble("time").toLong(),
                distanceMeters = (summary.getDouble("length") * 1000.0).toLong(),
                encodedPolyline = PolylineEncoder.encode(points),
                warning = "Valhalla public routing is free/fair-use and does not include a guaranteed live-traffic feed.",
                originLatitude = oLat, originLongitude = oLon,
                destinationLatitude = dLat, destinationLongitude = dLon
            )
        }
    }

    private fun openRouteService(oLat: Double, oLon: Double, dLat: Double, dLon: Double): RouteEstimate {
        val key = keyStore.read(RoutingProvider.OPENROUTESERVICE).orEmpty()
        require(key.isNotBlank()) { "openrouteservice API key is missing." }
        budget.consume(RoutingProvider.OPENROUTESERVICE, settings.providerCaps.openRouteService)
        val body = JSONObject().put("coordinates", JSONArray().apply {
            put(JSONArray().put(oLon).put(oLat))
            put(JSONArray().put(dLon).put(dLat))
        }).toString()
        val request = Request.Builder()
            .url("https://api.heigit.org/openrouteservice/v2/directions/driving-car/geojson")
            .header("Authorization", key)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("openrouteservice: HTTP ${response.code}")
            val feature = JSONObject(text).getJSONArray("features").getJSONObject(0)
            val summary = feature.getJSONObject("properties").getJSONObject("summary")
            val coords = feature.getJSONObject("geometry").getJSONArray("coordinates")
            val points = buildList {
                for (i in 0 until coords.length()) {
                    val pair = coords.getJSONArray(i)
                    add(GeoPoint(pair.getDouble(1), pair.getDouble(0)))
                }
            }
            val duration = summary.getDouble("duration").toLong()
            RouteEstimate(duration, duration, summary.getDouble("distance").toLong(), PolylineEncoder.encode(points),
                "openrouteservice does not provide predictive live traffic in this integration.",
                oLat, oLon, dLat, dLon)
        }
    }

    private fun osrm(oLat: Double, oLon: Double, dLat: Double, dLon: Double): RouteEstimate {
        budget.consume(RoutingProvider.OSRM, settings.providerCaps.osrm)
        val base = settings.osrmBaseUrl.trimEnd('/')
        val url = "$base/route/v1/driving/$oLon,$oLat;$dLon,$dLat".toHttpUrl().newBuilder()
            .addQueryParameter("overview", "full")
            .addQueryParameter("geometries", "polyline")
            .build()
        client.newCall(Request.Builder().url(url).header("User-Agent", context.packageName).get().build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("OSRM: HTTP ${response.code}")
            val root = JSONObject(body)
            if (root.optString("code") != "Ok") error("OSRM could not calculate a route.")
            val route = root.getJSONArray("routes").getJSONObject(0)
            val duration = route.getDouble("duration").toLong()
            RouteEstimate(duration, duration, route.getDouble("distance").toLong(), route.getString("geometry"),
                "OSRM is a static OSM-based estimate without predictive live traffic.",
                oLat, oLon, dLat, dLon)
        }
    }

    private fun graphHopper(oLat: Double, oLon: Double, dLat: Double, dLon: Double): RouteEstimate {
        val key = keyStore.read(RoutingProvider.GRAPHHOPPER).orEmpty()
        require(key.isNotBlank()) { "GraphHopper API key is missing." }
        budget.consume(RoutingProvider.GRAPHHOPPER, settings.providerCaps.graphHopper)
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("graphhopper.com").addPathSegments("api/1/route")
            .addQueryParameter("point", "$oLat,$oLon")
            .addQueryParameter("point", "$dLat,$dLon")
            .addQueryParameter("profile", "car")
            .addQueryParameter("points_encoded", "true")
            .addQueryParameter("instructions", "false")
            .addQueryParameter("key", key).build()
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("GraphHopper: HTTP ${response.code}")
            val route = JSONObject(body).getJSONArray("paths").getJSONObject(0)
            val duration = route.getLong("time") / 1000L
            RouteEstimate(duration, duration, route.getDouble("distance").toLong(), route.getString("points"),
                "GraphHopper does not use predictive live traffic in this integration.",
                oLat, oLon, dLat, dLon)
        }
    }

    private fun google(oLat: Double, oLon: Double, dLat: Double, dLon: Double, arrival: Long): RouteEstimate {
        val key = keyStore.read(RoutingProvider.GOOGLE).orEmpty()
        require(key.isNotBlank()) { "Google Routes API key is missing." }
        budget.consume(RoutingProvider.GOOGLE, settings.providerCaps.google, 2)
        fun compute(departureMillis: Long): RouteEstimate {
            val body = JSONObject().apply {
                put("origin", waypoint(oLat, oLon))
                put("destination", waypoint(dLat, dLon))
                put("travelMode", "DRIVE")
                put("routingPreference", "TRAFFIC_AWARE_OPTIMAL")
                put("departureTime", Instant.ofEpochMilli(departureMillis).toString())
                put("languageCode", "en")
                put("units", "METRIC")
            }.toString()
            val request = Request.Builder()
                .url("https://routes.googleapis.com/directions/v2:computeRoutes")
                .header("X-Goog-Api-Key", key)
                .header("X-Goog-FieldMask", "routes.duration,routes.staticDuration,routes.distanceMeters,routes.polyline.encodedPolyline")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("Google Routes: HTTP ${response.code}")
                val route = JSONObject(text).getJSONArray("routes").getJSONObject(0)
                val duration = seconds(route.getString("duration"))
                val static = seconds(route.optString("staticDuration", route.getString("duration")))
                return RouteEstimate(duration, static, route.getLong("distanceMeters"),
                    route.getJSONObject("polyline").getString("encodedPolyline"), null, oLat, oLon, dLat, dLon)
            }
        }
        val first = compute((arrival - 30 * 60_000L).coerceAtLeast(System.currentTimeMillis()))
        return compute((arrival - first.durationSeconds * 1000L).coerceAtLeast(System.currentTimeMillis()))
    }

    private fun here(oLat: Double, oLon: Double, dLat: Double, dLon: Double, arrival: Long): RouteEstimate {
        val key = keyStore.read(RoutingProvider.HERE).orEmpty()
        require(key.isNotBlank()) { "HERE API key is missing." }
        budget.consume(RoutingProvider.HERE, settings.providerCaps.here)
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("router.hereapi.com").addPathSegments("v8/routes")
            .addQueryParameter("transportMode", "car")
            .addQueryParameter("origin", "$oLat,$oLon")
            .addQueryParameter("destination", "$dLat,$dLon")
            .addQueryParameter("arrivalTime", Instant.ofEpochMilli(arrival).toString())
            .addQueryParameter("return", "summary,polyline")
            .addQueryParameter("apiKey", key).build()
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HERE Routing: HTTP ${response.code}")
            val sections = JSONObject(body).getJSONArray("routes").getJSONObject(0).getJSONArray("sections")
            var duration = 0L
            var baseDuration = 0L
            var distance = 0L
            val points = mutableListOf<GeoPoint>()
            for (i in 0 until sections.length()) {
                val section = sections.getJSONObject(i)
                val summary = section.getJSONObject("summary")
                duration += summary.getLong("duration")
                baseDuration += summary.optLong("baseDuration", summary.getLong("duration"))
                distance += summary.getLong("length")
                val decoded = FlexiblePolylineDecoder.decode(section.getString("polyline"))
                if (points.isNotEmpty() && decoded.isNotEmpty()) points += decoded.drop(1) else points += decoded
            }
            RouteEstimate(duration, baseDuration.coerceAtLeast(1), distance, PolylineEncoder.encode(points), null, oLat, oLon, dLat, dLon)
        }
    }

    private fun tomTom(oLat: Double, oLon: Double, dLat: Double, dLon: Double, arrival: Long): RouteEstimate {
        val key = keyStore.read(RoutingProvider.TOMTOM).orEmpty()
        require(key.isNotBlank()) { "TomTom API key is missing." }
        budget.consume(RoutingProvider.TOMTOM, settings.providerCaps.tomTom)
        val locations = "$oLat,$oLon:$dLat,$dLon"
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("api.tomtom.com")
            .addPathSegments("routing/1/calculateRoute/$locations/json")
            .addQueryParameter("key", key)
            .addQueryParameter("traffic", "true")
            .addQueryParameter("computeTravelTimeFor", "all")
            .addQueryParameter("arriveAt", Instant.ofEpochMilli(arrival).toString())
            .addQueryParameter("routeRepresentation", "polyline")
            .build()
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("TomTom Routing: HTTP ${response.code}")
            val route = JSONObject(body).getJSONArray("routes").getJSONObject(0)
            val summary = route.getJSONObject("summary")
            val points = mutableListOf<GeoPoint>()
            val legs = route.getJSONArray("legs")
            for (i in 0 until legs.length()) {
                val raw = legs.getJSONObject(i).getJSONArray("points")
                for (j in 0 until raw.length()) {
                    val point = raw.getJSONObject(j)
                    val gp = GeoPoint(point.getDouble("latitude"), point.getDouble("longitude"))
                    if (points.lastOrNull() != gp) points += gp
                }
            }
            val duration = summary.getLong("travelTimeInSeconds")
            val static = summary.optLong("noTrafficTravelTimeInSeconds",
                (duration - summary.optLong("trafficDelayInSeconds", 0)).coerceAtLeast(1))
            RouteEstimate(duration, static, summary.getLong("lengthInMeters"), PolylineEncoder.encode(points), null, oLat, oLon, dLat, dLon)
        }
    }

    private fun waypoint(lat: Double, lon: Double) = JSONObject().put("location",
        JSONObject().put("latLng", JSONObject().put("latitude", lat).put("longitude", lon)))

    private fun seconds(value: String): Long = value.removeSuffix("s").toDouble().toLong()
}
