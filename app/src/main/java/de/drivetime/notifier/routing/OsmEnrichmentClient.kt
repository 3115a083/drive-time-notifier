package de.drivetime.notifier.routing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.util.concurrent.TimeUnit
import kotlin.math.*

data class RoutePoi(
    val point: GeoPoint,
    val kind: Kind,
    val name: String? = null,
    val distanceFromDestinationMeters: Int? = null
) {
    enum class Kind { SPEED_CAMERA, PARKING }
}

class OsmEnrichmentClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
) {
    suspend fun query(points: List<GeoPoint>, cameras: Boolean, parking: Boolean): List<RoutePoi> =
        withContext(Dispatchers.IO) {
            if (points.isEmpty() || (!cameras && !parking)) return@withContext emptyList()
            val destination = points.last()
            val pad = 0.002
            val minLat = points.minOf { it.latitude } - pad
            val maxLat = points.maxOf { it.latitude } + pad
            val minLon = points.minOf { it.longitude } - pad
            val maxLon = points.maxOf { it.longitude } + pad
            val bbox = "$minLat,$minLon,$maxLat,$maxLon"

            val parts = buildList {
                if (cameras) add("node[\"highway\"=\"speed_camera\"]($bbox);")
                if (parking) {
                    add("node(around:1400,${destination.latitude},${destination.longitude})[\"amenity\"=\"parking\"];")
                    add("way(around:1400,${destination.latitude},${destination.longitude})[\"amenity\"=\"parking\"];")
                    add("relation(around:1400,${destination.latitude},${destination.longitude})[\"amenity\"=\"parking\"];")
                }
            }.joinToString("")
            val query = "[out:json][timeout:8];($parts);out center 100;"
            val request = Request.Builder()
                .url("https://overpass-api.de/api/interpreter")
                .header("User-Agent", "DriveTimeNotifier/1.0")
                .post(FormBody.Builder().add("data", query).build())
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val elements = JSONObject(response.body?.string().orEmpty()).optJSONArray("elements")
                    ?: return@withContext emptyList()
                val camerasOut = mutableListOf<RoutePoi>()
                val parkingOut = mutableListOf<RoutePoi>()
                for (i in 0 until elements.length()) {
                    val e = elements.getJSONObject(i)
                    val tags = e.optJSONObject("tags") ?: continue
                    val center = e.optJSONObject("center")
                    val lat = if (e.has("lat")) e.optDouble("lat") else center?.optDouble("lat") ?: continue
                    val lon = if (e.has("lon")) e.optDouble("lon") else center?.optDouble("lon") ?: continue
                    val point = GeoPoint(lat, lon)
                    if (tags.optString("highway") == "speed_camera") {
                        if (distanceToRouteMeters(point, points) <= 120.0) {
                            camerasOut += RoutePoi(point, RoutePoi.Kind.SPEED_CAMERA, tags.optString("name").ifBlank { null })
                        }
                    } else if (tags.optString("amenity") == "parking") {
                        val distance = haversineMeters(point, destination).roundToInt()
                        parkingOut += RoutePoi(
                            point,
                            RoutePoi.Kind.PARKING,
                            tags.optString("name").ifBlank { "Parking" },
                            distance
                        )
                    }
                }
                camerasOut.distinctBy { "${it.point.latitude},${it.point.longitude}" } +
                    parkingOut.distinctBy { "${it.point.latitude},${it.point.longitude}" }
                        .sortedBy { it.distanceFromDestinationMeters ?: Int.MAX_VALUE }
                        .take(5)
            }
        }

    private fun distanceToRouteMeters(point: GeoPoint, route: List<GeoPoint>): Double {
        if (route.isEmpty()) return Double.MAX_VALUE
        var best = Double.MAX_VALUE
        val step = max(1, route.size / 1200)
        var i = 0
        while (i < route.size) {
            best = min(best, haversineMeters(point, route[i]))
            i += step
        }
        best = min(best, haversineMeters(point, route.last()))
        return best
    }

    private fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val r = 6_371_000.0
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }
}
