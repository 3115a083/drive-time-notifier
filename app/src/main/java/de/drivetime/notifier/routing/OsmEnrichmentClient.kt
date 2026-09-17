package de.drivetime.notifier.routing

import de.drivetime.notifier.data.ChargingConnectorPreference
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
    val access: String? = null,
    val fee: String? = null,
    val openingHours: String? = null,
    val maxStay: String? = null,
    val capacity: Int? = null,
    val parkingType: String? = null,
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
        .followRedirects(false)
        .followSslRedirects(false)
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
                // Some fuel stations expose charging only through fuel:electricity=yes.
                add("node(around:$radius,${destination.latitude},${destination.longitude})[\"amenity\"=\"fuel\"][\"fuel:electricity\"=\"yes\"];")
                add("way(around:$radius,${destination.latitude},${destination.longitude})[\"amenity\"=\"fuel\"][\"fuel:electricity\"=\"yes\"];")
                add("relation(around:$radius,${destination.latitude},${destination.longitude})[\"amenity\"=\"fuel\"][\"fuel:electricity\"=\"yes\"];")
            }
        }.joinToString("")
        val query = "[out:json][timeout:10];($parts);out center 240;"
        val request = Request.Builder()
            .url("https://overpass-api.de/api/interpreter")
            .header("User-Agent", "DriveTimeNotifier/1.1")
            .post(FormBody.Builder().add("data", query).build())
            .build()

        val osmResults = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList<RoutePoi>()
            val body = response.body ?: return@use emptyList<RoutePoi>()
            if (body.contentLength() > MAX_RESPONSE_BYTES) return@use emptyList<RoutePoi>()
            val bytes = body.source().readByteArray(MAX_RESPONSE_BYTES + 1L)
            if (bytes.size > MAX_RESPONSE_BYTES) return@use emptyList<RoutePoi>()
            val elements = JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("elements")
                ?: return@use emptyList<RoutePoi>()
            val out = mutableListOf<RoutePoi>()
            for (i in 0 until elements.length()) {
                val e = elements.optJSONObject(i) ?: continue
                val tags = e.optJSONObject("tags") ?: continue
                val center = e.optJSONObject("center")
                val lat = if (e.has("lat")) e.optDouble("lat") else center?.optDouble("lat") ?: continue
                val lon = if (e.has("lon")) e.optDouble("lon") else center?.optDouble("lon") ?: continue
                if (!lat.isFinite() || !lon.isFinite()) continue
                val point = GeoPoint(lat, lon)
                when {
                    tags.optString("highway") == "speed_camera" -> {
                        if (distanceToRouteMeters(point, points) <= 120.0) {
                            out += RoutePoi(point, RoutePoi.Kind.SPEED_CAMERA, clean(tags.optString("name")))
                        }
                    }
                    tags.optString("amenity") == "parking" -> {
                        out += RoutePoi(
                            point = point,
                            kind = RoutePoi.Kind.PARKING,
                            name = clean(tags.optString("name")) ?: "Parking",
                            distanceFromDestinationMeters = haversineMeters(point, destination).roundToInt(),
                            access = clean(tags.optString("access")),
                            fee = clean(tags.optString("fee")),
                            openingHours = clean(tags.optString("opening_hours")),
                            maxStay = clean(tags.optString("maxstay")) ?: clean(tags.optString("parking:maxstay")),
                            capacity = firstInt(tags, "capacity", "capacity:car"),
                            parkingType = clean(tags.optString("parking")) ?: clean(tags.optString("parking:condition"))
                        )
                    }
                    isCharging(tags) && charging != null -> {
                        val access = clean(tags.optString("access"))
                        if (!isUsablePublicAccess(access)) continue
                        val distance = haversineMeters(point, destination).roundToInt()
                        if (distance > charging.maxDistanceMeters) continue
                        val operator = clean(tags.optString("operator"))
                        val network = clean(tags.optString("network"))
                        val name = clean(tags.optString("name"))
                            ?: clean(tags.optString("brand"))
                            ?: operator
                            ?: network
                        out += RoutePoi(
                            point = point,
                            kind = RoutePoi.Kind.CHARGING_STATION,
                            name = name,
                            distanceFromDestinationMeters = distance,
                            operator = operator,
                            network = network,
                            connectorTypes = parseConnectors(tags),
                            maxPowerKw = parseMaxPowerKw(tags),
                            access = access,
                            fee = clean(tags.optString("fee")),
                            openingHours = clean(tags.optString("opening_hours")),
                            maxStay = clean(tags.optString("maxstay")) ?: clean(tags.optString("parking:maxstay")),
                            capacity = firstInt(tags, "capacity:charging", "capacity"),
                            parkingType = clean(tags.optString("parking"))
                                ?: clean(tags.optString("parking:condition"))
                                ?: clean(tags.optString("parking:lane")),
                            address = address(tags),
                            sources = setOf(RoutePoiSource.OSM)
                        )
                    }
                }
            }
            out
        }

        val camerasOut = osmResults.filter { it.kind == RoutePoi.Kind.SPEED_CAMERA }
            .distinctBy(::key)
        val parkingOut = osmResults.filter { it.kind == RoutePoi.Kind.PARKING }
            .distinctBy(::key)
            .sortedBy { it.distanceFromDestinationMeters ?: Int.MAX_VALUE }
            .take(5)
        val chargingOut = if (charging != null) {
            val osmCharging = osmResults.filter { it.kind == RoutePoi.Kind.CHARGING_STATION }
                .distinctBy(::key)
            val registry = if (charging.useBNetzA) {
                runCatching { BNetzAChargingClient().query(destination, charging.maxDistanceMeters) }
                    .getOrDefault(emptyList())
            } else emptyList()
            ChargingStationSelector.mergeAndRank(osmCharging, registry, charging)
        } else emptyList()

        camerasOut + chargingOut + parkingOut
    }

    private fun isCharging(tags: JSONObject): Boolean =
        tags.optString("amenity") == "charging_station" ||
            (tags.optString("amenity") == "fuel" && tags.optString("fuel:electricity").equals("yes", true))

    private fun isUsablePublicAccess(access: String?): Boolean {
        // "customers" remains usable for public fuel/retail charging. Truly private,
        // permit/member-only and explicitly forbidden locations are excluded.
        val restricted = setOf("private", "no", "permit", "members", "military")
        return access == null || access.lowercase(Locale.ROOT) !in restricted
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
        val raw = clean(tags.optString(key))?.lowercase(Locale.ROOT) ?: return false
        if (raw == "no" || raw == "0") return false
        return raw == "yes" || raw.toIntOrNull()?.let { it > 0 } == true || ';' in raw
    }

    private fun parseMaxPowerKw(tags: JSONObject): Double? {
        val values = mutableListOf<Double>()
        val keys = tags.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "maxpower" || key == "charging_station:output" || key.endsWith(":output")) {
                parsePowerKw(tags.optString(key))?.let(values::add)
            }
        }
        return values.maxOrNull()
    }

    private fun parsePowerKw(raw: String): Double? {
        val lower = raw.lowercase(Locale.ROOT).replace(',', '.')
        val values = Regex("[0-9]+(?:\\.[0-9]+)?").findAll(lower)
            .mapNotNull { it.value.toDoubleOrNull() }
            .toList()
        if (values.isEmpty()) return null
        val max = values.maxOrNull() ?: return null
        return when {
            "mw" in lower -> max * 1000.0
            "w" in lower && "kw" !in lower -> max / 1000.0
            else -> max
        }
    }

    private fun firstInt(tags: JSONObject, vararg names: String): Int? =
        names.asSequence().mapNotNull { tags.optString(it).toIntOrNull() }.firstOrNull()

    private fun address(tags: JSONObject): String? = listOf(
        listOf(clean(tags.optString("addr:street")), clean(tags.optString("addr:housenumber")))
            .filterNotNull().joinToString(" "),
        listOf(clean(tags.optString("addr:postcode")), clean(tags.optString("addr:city")))
            .filterNotNull().joinToString(" ")
    ).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }

    private fun clean(value: String?): String? = value?.trim()?.takeIf {
        it.isNotEmpty() && !it.equals("null", true) && !it.equals("none", true)
    }

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

    private fun haversineMeters(a: GeoPoint, b: GeoPoint): Double =
        ChargingStationSelector.haversineMeters(a, b)

    companion object {
        private const val MAX_RESPONSE_BYTES = 2_000_000L
    }
}
