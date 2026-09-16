package de.drivetime.notifier.routing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.*

data class RoutePoi(
    val point: GeoPoint,
    val kind: Kind,
    val name: String? = null,
    val distanceFromDestinationMeters: Int? = null,
    val operator: String? = null,
    val network: String? = null,
    val connectors: List<String> = emptyList(),
    val maxPowerKw: Double? = null,
    val access: String? = null,
    val fee: String? = null,
    val openingHours: String? = null,
    val maxStay: String? = null,
    val capacity: Int? = null,
    val parkingType: String? = null
) {
    enum class Kind { SPEED_CAMERA, CHARGING_STATION, PARKING }
}

data class ChargingQueryOptions(
    val enabled: Boolean = false,
    val maxDistanceMeters: Int = 1_000,
    val connectorTypes: Set<String> = emptySet(),
    val preferredOperator: String? = null,
    val showOtherOperators: Boolean = true
)

class OsmEnrichmentClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
) {
    suspend fun query(
        points: List<GeoPoint>,
        cameras: Boolean,
        parking: Boolean,
        charging: ChargingQueryOptions = ChargingQueryOptions()
    ): List<RoutePoi> = withContext(Dispatchers.IO) {
        if (points.isEmpty() || (!cameras && !parking && !charging.enabled)) return@withContext emptyList()
        val destination = points.last()
        val pad = 0.002
        val minLat = points.minOf { it.latitude } - pad
        val maxLat = points.maxOf { it.latitude } + pad
        val minLon = points.minOf { it.longitude } - pad
        val maxLon = points.maxOf { it.longitude } + pad
        val bbox = "$minLat,$minLon,$maxLat,$maxLon"
        val radius = charging.maxDistanceMeters.coerceIn(100, 5_000)

        val parts = buildList {
            if (cameras) add("node[\"highway\"=\"speed_camera\"]($bbox);")
            if (parking) {
                add("node(around:1400,${destination.latitude},${destination.longitude})[\"amenity\"=\"parking\"];")
                add("way(around:1400,${destination.latitude},${destination.longitude})[\"amenity\"=\"parking\"];")
                add("relation(around:1400,${destination.latitude},${destination.longitude})[\"amenity\"=\"parking\"];")
            }
            if (charging.enabled) {
                add("node(around:$radius,${destination.latitude},${destination.longitude})[\"amenity\"=\"charging_station\"];")
                add("way(around:$radius,${destination.latitude},${destination.longitude})[\"amenity\"=\"charging_station\"];")
                add("relation(around:$radius,${destination.latitude},${destination.longitude})[\"amenity\"=\"charging_station\"];")
            }
        }.joinToString("")
        val query = "[out:json][timeout:8];($parts);out center 150;"
        val request = Request.Builder()
            .url("https://overpass-api.de/api/interpreter")
            .header("User-Agent", "DriveTimeNotifier/1.1")
            .post(FormBody.Builder().add("data", query).build())
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val elements = JSONObject(response.body?.string().orEmpty()).optJSONArray("elements")
                ?: return@withContext emptyList()
            val camerasOut = mutableListOf<RoutePoi>()
            val parkingOut = mutableListOf<RoutePoi>()
            val chargingOut = mutableListOf<RoutePoi>()
            for (i in 0 until elements.length()) {
                val e = elements.getJSONObject(i)
                val tags = e.optJSONObject("tags") ?: continue
                val center = e.optJSONObject("center")
                val lat = if (e.has("lat")) e.optDouble("lat") else center?.optDouble("lat") ?: continue
                val lon = if (e.has("lon")) e.optDouble("lon") else center?.optDouble("lon") ?: continue
                val point = GeoPoint(lat, lon)
                when {
                    tags.optString("highway") == "speed_camera" -> {
                        if (distanceToRouteMeters(point, points) <= 120.0) {
                            camerasOut += RoutePoi(point, RoutePoi.Kind.SPEED_CAMERA, clean(tags.optString("name")))
                        }
                    }
                    tags.optString("amenity") == "charging_station" -> {
                        val access = clean(tags.optString("access"))
                        if (!isPublicAccess(access)) continue
                        val distance = haversineMeters(point, destination).roundToInt()
                        if (distance > radius) continue
                        val connectors = connectorLabels(tags)
                        if (charging.connectorTypes.isNotEmpty() && connectors.none { label ->
                                charging.connectorTypes.any { wanted -> label.contains(wanted, ignoreCase = true) }
                            }) continue
                        val operator = clean(tags.optString("operator"))
                        val network = clean(tags.optString("network"))
                        val preferred = charging.preferredOperator?.trim()?.takeIf { it.isNotEmpty() }
                        if (!charging.showOtherOperators && preferred != null &&
                            !operator.equals(preferred, true) && !network.equals(preferred, true)) continue
                        chargingOut += RoutePoi(
                            point = point,
                            kind = RoutePoi.Kind.CHARGING_STATION,
                            name = clean(tags.optString("name")) ?: clean(tags.optString("brand")) ?: operator ?: network,
                            distanceFromDestinationMeters = distance,
                            operator = operator,
                            network = network,
                            connectors = connectors,
                            maxPowerKw = maxPowerKw(tags),
                            access = access,
                            fee = clean(tags.optString("fee")),
                            openingHours = clean(tags.optString("opening_hours")),
                            maxStay = clean(tags.optString("maxstay")) ?: clean(tags.optString("parking:maxstay")),
                            capacity = tags.optString("capacity").toIntOrNull(),
                            parkingType = clean(tags.optString("parking")) ?: clean(tags.optString("parking:condition"))
                        )
                    }
                    tags.optString("amenity") == "parking" -> {
                        val distance = haversineMeters(point, destination).roundToInt()
                        parkingOut += RoutePoi(
                            point,
                            RoutePoi.Kind.PARKING,
                            clean(tags.optString("name")) ?: "Parking",
                            distance,
                            access = clean(tags.optString("access")),
                            fee = clean(tags.optString("fee")),
                            openingHours = clean(tags.optString("opening_hours")),
                            maxStay = clean(tags.optString("maxstay")),
                            capacity = tags.optString("capacity").toIntOrNull(),
                            parkingType = clean(tags.optString("parking"))
                        )
                    }
                }
            }
            val preferred = charging.preferredOperator?.trim()?.takeIf { it.isNotEmpty() }
            camerasOut.distinctBy { key(it) } +
                chargingOut.distinctBy { key(it) }
                    .sortedWith(compareBy<RoutePoi>(
                        { if (preferred != null && (it.operator.equals(preferred, true) || it.network.equals(preferred, true))) 0 else 1 },
                        { if (it.parkingType == "multi-storey" || it.parkingType == "underground") 0 else 1 },
                        { it.distanceFromDestinationMeters ?: Int.MAX_VALUE }
                    )).take(5) +
                parkingOut.distinctBy { key(it) }
                    .sortedBy { it.distanceFromDestinationMeters ?: Int.MAX_VALUE }
                    .take(5)
        }
    }

    private fun isPublicAccess(access: String?): Boolean = access == null || access.lowercase(Locale.ROOT) !in setOf(
        "private", "no", "customers", "permit", "delivery", "destination"
    )

    private fun connectorLabels(tags: JSONObject): List<String> = buildList {
        fun addSocket(key: String, label: String) {
            val value = clean(tags.optString(key)) ?: return
            if (value.equals("no", true) || value == "0") return
            val count = value.toIntOrNull()
            add(if (count != null && count > 1) "$label ($count)" else label)
        }
        addSocket("socket:type2_combo", "CCS/Combo 2")
        addSocket("socket:type2", "Type 2")
        addSocket("socket:chademo", "CHAdeMO")
        addSocket("socket:tesla_supercharger_ccs", "Tesla CCS")
        addSocket("socket:tesla_supercharger", "Tesla Supercharger")
    }.distinct()

    private fun maxPowerKw(tags: JSONObject): Double? {
        val values = mutableListOf<Double>()
        listOf("maxpower", "charging_station:output", "socket:type2:output", "socket:type2_combo:output", "socket:chademo:output").forEach { key ->
            clean(tags.optString(key))?.let { raw -> parsePowerKw(raw)?.let(values::add) }
        }
        return values.maxOrNull()
    }

    private fun parsePowerKw(raw: String): Double? {
        val normalized = raw.lowercase(Locale.ROOT).replace(',', '.').trim()
        val number = Regex("([0-9]+(?:\\.[0-9]+)?)").find(normalized)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        return if (normalized.contains("mw")) number * 1000.0 else if (normalized.contains(" w") && !normalized.contains("kw")) number / 1000.0 else number
    }

    private fun clean(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) }
    private fun key(poi: RoutePoi) = "%.5f,%.5f".format(Locale.ROOT, poi.point.latitude, poi.point.longitude)

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
