package de.drivetime.notifier.routing

import de.drivetime.notifier.data.ChargingConnectorPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.util.concurrent.TimeUnit
import kotlin.math.*

enum class RoutePoiSource { OSM, BUNDESNETZAGENTUR }

data class RoutePoi(
    val point: GeoPoint,
    val kind: Kind,
    val name: String? = null,
    val distanceFromDestinationMeters: Int? = null,
    val operator: String? = null,
    val network: String? = null,
    val connectorTypes: Set<ChargingConnectorPreference> = emptySet(),
    val maxPowerKw: Double? = null,
    val openingHours: String? = null,
    val address: String? = null,
    val sources: Set<RoutePoiSource> = setOf(RoutePoiSource.OSM)
) {
    enum class Kind { SPEED_CAMERA, PARKING, CHARGING_STATION }
}

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
        charging: ChargingSearchOptions? = null
    ): List<RoutePoi> = withContext(Dispatchers.IO) {
        if (points.isEmpty() || (!cameras && !parking && charging == null)) return@withContext emptyList()
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
            charging?.let { options ->
                val radius = options.maxDistanceMeters.coerceIn(100, 10_000)
                add("node(around:$radius,${destination.latitude},${destination.longitude})[\"amenity\"=\"charging_station\"];")
                add("way(around:$radius,${destination.latitude},${destination.longitude})[\"amenity\"=\"charging_station\"];")
                add("relation(around:$radius,${destination.latitude},${destination.longitude})[\"amenity\"=\"charging_station\"];")
            }
        }.joinToString("")
        val query = "[out:json][timeout:10];($parts);out center 180;"
        val request = Request.Builder()
            .url("https://overpass-api.de/api/interpreter")
            .header("User-Agent", "DriveTimeNotifier/1.1")
            .post(FormBody.Builder().add("data", query).build())
            .build()

        val osmResults = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList<RoutePoi>()
            val elements = JSONObject(response.body?.string().orEmpty()).optJSONArray("elements")
                ?: return@use emptyList<RoutePoi>()
            val out = mutableListOf<RoutePoi>()
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
                            out += RoutePoi(point, RoutePoi.Kind.SPEED_CAMERA, tags.optString("name").ifBlank { null })
                        }
                    }
                    tags.optString("amenity") == "parking" -> {
                        val distance = haversineMeters(point, destination).roundToInt()
                        out += RoutePoi(
                            point = point,
                            kind = RoutePoi.Kind.PARKING,
                            name = tags.optString("name").ifBlank { "Parking" },
                            distanceFromDestinationMeters = distance
                        )
                    }
                    tags.optString("amenity") == "charging_station" && charging != null -> {
                        if (!isPublicCharging(tags)) continue
                        val distance = haversineMeters(point, destination).roundToInt()
                        if (distance > charging.maxDistanceMeters) continue
                        val connectors = parseConnectors(tags)
                        val power = parseMaxPowerKw(tags)
                        val operator = tags.optString("operator").ifBlank { null }
                        val network = tags.optString("network").ifBlank { null }
                        val name = tags.optString("name")
                            .ifBlank { tags.optString("brand") }
                            .ifBlank { operator.orEmpty() }
                            .ifBlank { network.orEmpty() }
                            .ifBlank { "Ladestation" }
                        out += RoutePoi(
                            point = point,
                            kind = RoutePoi.Kind.CHARGING_STATION,
                            name = name,
                            distanceFromDestinationMeters = distance,
                            operator = operator,
                            network = network,
                            connectorTypes = connectors,
                            maxPowerKw = power,
                            openingHours = tags.optString("opening_hours").ifBlank { null },
                            sources = setOf(RoutePoiSource.OSM)
                        )
                    }
                }
            }
            out
        }

        val camerasOut = osmResults.filter { it.kind == RoutePoi.Kind.SPEED_CAMERA }
            .distinctBy { "${it.point.latitude},${it.point.longitude}" }
        val parkingOut = osmResults.filter { it.kind == RoutePoi.Kind.PARKING }
            .distinctBy { "${it.point.latitude},${it.point.longitude}" }
            .sortedBy { it.distanceFromDestinationMeters ?: Int.MAX_VALUE }
            .take(5)
        val chargingOut = if (charging != null) {
            val osmCharging = osmResults.filter { it.kind == RoutePoi.Kind.CHARGING_STATION }
                .distinctBy { "${it.point.latitude},${it.point.longitude}" }
            val registry = if (charging.useBNetzA) {
                runCatching {
                    BNetzAChargingClient().query(destination, charging.maxDistanceMeters)
                }.getOrDefault(emptyList())
            } else emptyList()
            ChargingStationSelector.mergeAndRank(osmCharging, registry, charging)
        } else emptyList()

        camerasOut + chargingOut + parkingOut
    }

    private fun isPublicCharging(tags: JSONObject): Boolean {
        val restricted = setOf("private", "no", "customers", "destination", "permit", "members")
        val access = tags.optString("access").trim().lowercase()
        val motorcar = tags.optString("motorcar").trim().lowercase()
        return access !in restricted && motorcar !in setOf("private", "no")
    }

    private fun parseConnectors(tags: JSONObject): Set<ChargingConnectorPreference> = buildSet {
        if (positiveSocket(tags, "socket:type2_combo") || positiveSocket(tags, "socket:tesla_supercharger_ccs")) {
            add(ChargingConnectorPreference.CCS)
        }
        if (positiveSocket(tags, "socket:type2") || positiveSocket(tags, "socket:type2_cable")) {
            add(ChargingConnectorPreference.TYPE2)
        }
        if (positiveSocket(tags, "socket:chademo")) add(ChargingConnectorPreference.CHADEMO)
    }

    private fun positiveSocket(tags: JSONObject, key: String): Boolean {
        val raw = tags.optString(key).trim().lowercase()
        if (raw.isBlank() || raw == "no" || raw == "0") return false
        return raw == "yes" || raw.toIntOrNull()?.let { it > 0 } == true || raw.contains(';')
    }

    private fun parseMaxPowerKw(tags: JSONObject): Double? {
        val values = mutableListOf<Double>()
        val keys = tags.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "charging_station:output" || key.endsWith(":output")) {
                parsePowerKw(tags.optString(key))?.let(values::add)
            }
        }
        return values.maxOrNull()
    }

    private fun parsePowerKw(raw: String): Double? {
        if (raw.isBlank()) return null
        val lower = raw.lowercase().replace(',', '.')
        val values = Regex("[0-9]+(?:\\.[0-9]+)?").findAll(lower)
            .mapNotNull { it.value.toDoubleOrNull() }
            .toList()
        if (values.isEmpty()) return null
        val max = values.maxOrNull() ?: return null
        return if ("mw" in lower) max * 1000.0 else if ("w" in lower && "kw" !in lower) max / 1000.0 else max
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

    private fun haversineMeters(a: GeoPoint, b: GeoPoint): Double =
        ChargingStationSelector.haversineMeters(a, b)
}
