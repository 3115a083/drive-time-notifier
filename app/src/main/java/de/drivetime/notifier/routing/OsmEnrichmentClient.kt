package de.drivetime.notifier.routing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.util.concurrent.TimeUnit

data class RoutePoi(val point: GeoPoint, val kind: Kind) {
    enum class Kind { SPEED_CAMERA, PARKING }
}

class OsmEnrichmentClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    suspend fun query(points: List<GeoPoint>, cameras: Boolean, parking: Boolean): List<RoutePoi> =
        withContext(Dispatchers.IO) {
            if (points.isEmpty() || (!cameras && !parking)) return@withContext emptyList()
            val minLat = points.minOf { it.latitude }
            val maxLat = points.maxOf { it.latitude }
            val minLon = points.minOf { it.longitude }
            val maxLon = points.maxOf { it.longitude }
            val bbox = "$minLat,$minLon,$maxLat,$maxLon"
            val parts = buildList {
                if (cameras) add("node[\"highway\"=\"speed_camera\"]($bbox);")
                if (parking) {
                    add("node[\"amenity\"=\"parking\"]($bbox);")
                    add("way[\"amenity\"=\"parking\"]($bbox);")
                }
            }.joinToString("")
            val query = "[out:json][timeout:10];($parts);out center 80;"
            val request = Request.Builder()
                .url("https://overpass-api.de/api/interpreter")
                .post(FormBody.Builder().add("data", query).build())
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val elements = JSONObject(response.body?.string().orEmpty()).optJSONArray("elements")
                    ?: return@withContext emptyList()
                buildList {
                    for (i in 0 until elements.length()) {
                        val e = elements.getJSONObject(i)
                        val tags = e.optJSONObject("tags") ?: continue
                        val lat = if (e.has("lat")) e.optDouble("lat") else e.optJSONObject("center")?.optDouble("lat") ?: continue
                        val lon = if (e.has("lon")) e.optDouble("lon") else e.optJSONObject("center")?.optDouble("lon") ?: continue
                        val kind = if (tags.optString("highway") == "speed_camera") RoutePoi.Kind.SPEED_CAMERA else RoutePoi.Kind.PARKING
                        add(RoutePoi(GeoPoint(lat, lon), kind))
                    }
                }
            }
        }
}
