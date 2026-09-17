from pathlib import Path
import re
import textwrap

ROOT = Path(__file__).resolve().parents[1]


def p(rel: str) -> Path:
    return ROOT / rel


def read(rel: str) -> str:
    return p(rel).read_text(encoding="utf-8")


def write(rel: str, content: str) -> None:
    path = p(rel)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(textwrap.dedent(content).lstrip("\n"), encoding="utf-8")


def replace_once(rel: str, old: str, new: str) -> None:
    content = read(rel)
    if new in content:
        return
    if old not in content:
        raise RuntimeError(f"Expected block not found in {rel}: {old[:120]!r}")
    p(rel).write_text(content.replace(old, new, 1), encoding="utf-8")


# ---------------------------------------------------------------------------
# Pure planning preferences and dynamic arrival buffer
# ---------------------------------------------------------------------------
write("app/src/main/java/de/drivetime/notifier/data/PlanningPreferences.kt", r'''
package de.drivetime.notifier.data

enum class DynamicBufferLevel(val id: String) {
    LOW("low"),
    BALANCED("balanced"),
    CAUTIOUS("cautious");

    companion object {
        fun fromId(id: String?): DynamicBufferLevel =
            entries.firstOrNull { it.id == id } ?: BALANCED
    }
}
''')

write("app/src/main/java/de/drivetime/notifier/core/DynamicArrivalBuffer.kt", r'''
package de.drivetime.notifier.core

import de.drivetime.notifier.data.AppSettings
import de.drivetime.notifier.data.DynamicBufferLevel
import kotlin.math.ceil

/**
 * Adds a transparent uncertainty reserve on top of the user's fixed arrival buffer.
 * It deliberately does not pretend to know motorway/city shares because the common
 * RouteEstimate model does not expose reliable road-class composition for every provider.
 * Providers without traffic awareness receive a slightly larger uncertainty allowance.
 */
object DynamicArrivalBuffer {
    fun totalMinutes(
        settings: AppSettings,
        routeDurationSeconds: Long,
        trafficDelaySeconds: Long = 0L
    ): Int = (settings.bufferMinutes + extraMinutes(settings, routeDurationSeconds, trafficDelaySeconds))
        .coerceIn(0, 180)

    fun extraMinutes(
        settings: AppSettings,
        routeDurationSeconds: Long,
        trafficDelaySeconds: Long = 0L
    ): Int {
        if (!settings.dynamicBufferEnabled) return 0
        val minutes = (routeDurationSeconds.coerceAtLeast(0L) / 60.0)
        val base = when (settings.dynamicBufferLevel) {
            DynamicBufferLevel.LOW -> when {
                minutes < 30 -> 0
                minutes < 60 -> 2
                minutes < 120 -> 4
                minutes < 180 -> 7
                else -> 10
            }
            DynamicBufferLevel.BALANCED -> when {
                minutes < 20 -> 0
                minutes < 45 -> 3
                minutes < 90 -> 6
                minutes < 150 -> 10
                minutes < 240 -> 15
                else -> 20
            }
            DynamicBufferLevel.CAUTIOUS -> when {
                minutes < 20 -> 2
                minutes < 45 -> 5
                minutes < 90 -> 10
                minutes < 150 -> 15
                minutes < 240 -> 22
                else -> 30
            }
        }

        // Static-routing providers cannot account for current congestion. Add a small,
        // bounded uncertainty reserve rather than pretending to know the road mix.
        val nonTrafficAware = if (!settings.routingProvider.trafficAware) {
            ceil(minutes * 0.03).toInt().coerceAtMost(10)
        } else 0

        // Traffic-aware providers already include the known delay in the ETA. Only a
        // small volatility reserve is added, avoiding double counting of congestion.
        val trafficVolatility = if (settings.routingProvider.trafficAware && trafficDelaySeconds > 0) {
            ceil((trafficDelaySeconds / 60.0) * 0.10).toInt().coerceAtMost(5)
        } else 0

        return (base + nonTrafficAware + trafficVolatility).coerceIn(0, 45)
    }
}
''')

# ---------------------------------------------------------------------------
# Charging preferences and ranking
# ---------------------------------------------------------------------------
write("app/src/main/java/de/drivetime/notifier/data/ChargingPreferences.kt", r'''
package de.drivetime.notifier.data

enum class ChargingConnectorPreference(val id: String) {
    ANY("any"),
    CCS("ccs"),
    TYPE2("type2"),
    CHADEMO("chademo");

    companion object {
        fun fromId(id: String?): ChargingConnectorPreference =
            entries.firstOrNull { it.id == id } ?: ANY
    }
}

enum class ChargingSpeedPreference(val id: String) {
    ANY("any"),
    SLOW("slow"),
    MEDIUM("medium"),
    FAST("fast"),
    HPC("hpc");

    companion object {
        fun fromId(id: String?): ChargingSpeedPreference =
            entries.firstOrNull { it.id == id } ?: ANY
    }

    fun penalty(powerKw: Double?): Int {
        if (this == ANY) return 0
        val power = powerKw ?: return 3
        val band = when {
            power <= 11.0 -> SLOW
            power <= 22.0 -> MEDIUM
            power <= 100.0 -> FAST
            else -> HPC
        }
        if (band == this) return 0
        val order = listOf(SLOW, MEDIUM, FAST, HPC)
        return kotlin.math.abs(order.indexOf(band) - order.indexOf(this)).coerceIn(1, 3)
    }
}
''')

write("app/src/main/java/de/drivetime/notifier/routing/ChargingStationSupport.kt", r'''
package de.drivetime.notifier.routing

import de.drivetime.notifier.data.AppSettings
import de.drivetime.notifier.data.ChargingConnectorPreference
import de.drivetime.notifier.data.ChargingSpeedPreference
import org.osmdroid.util.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt


data class ChargingSearchOptions(
    val connectors: Set<ChargingConnectorPreference>,
    val maxDistanceMeters: Int,
    val speedPreference: ChargingSpeedPreference,
    val preferredOperator: String,
    val showOtherOperators: Boolean,
    val useBNetzA: Boolean
) {
    companion object {
        fun from(settings: AppSettings): ChargingSearchOptions? {
            if (!settings.showChargingStations) return null
            val selected = settings.chargingConnectors
                .filterNot { it == ChargingConnectorPreference.ANY }
                .toSet()
                .ifEmpty {
                    settings.chargingConnector
                        .takeUnless { it == ChargingConnectorPreference.ANY }
                        ?.let(::setOf)
                        ?: emptySet()
                }
            return ChargingSearchOptions(
                connectors = selected,
                maxDistanceMeters = settings.chargingMaxDistanceMeters.coerceIn(100, 10_000),
                speedPreference = settings.chargingSpeedPreference,
                preferredOperator = settings.chargingPreferredOperator.trim(),
                showOtherOperators = settings.chargingShowOtherOperators,
                useBNetzA = settings.chargingUseBNetzA
            )
        }
    }
}

internal object ChargingStationSelector {
    fun mergeAndRank(
        osm: List<RoutePoi>,
        registry: List<RoutePoi>,
        options: ChargingSearchOptions
    ): List<RoutePoi> {
        val merged = osm.toMutableList()
        registry.forEach { registryStation ->
            val index = merged.indexOfFirst {
                haversineMeters(it.point, registryStation.point) <= 120.0
            }
            if (index >= 0) {
                val existing = merged[index]
                merged[index] = existing.copy(
                    name = clean(existing.name) ?: clean(registryStation.name),
                    operator = clean(registryStation.operator) ?: clean(existing.operator),
                    network = clean(existing.network) ?: clean(registryStation.network),
                    connectorTypes = existing.connectorTypes + registryStation.connectorTypes,
                    maxPowerKw = listOfNotNull(existing.maxPowerKw, registryStation.maxPowerKw).maxOrNull(),
                    access = clean(existing.access) ?: clean(registryStation.access),
                    fee = clean(existing.fee) ?: clean(registryStation.fee),
                    openingHours = clean(registryStation.openingHours) ?: clean(existing.openingHours),
                    maxStay = clean(registryStation.maxStay) ?: clean(existing.maxStay),
                    capacity = registryStation.capacity ?: existing.capacity,
                    parkingType = clean(registryStation.parkingType) ?: clean(existing.parkingType),
                    address = clean(registryStation.address) ?: clean(existing.address),
                    sources = existing.sources + registryStation.sources,
                    distanceFromDestinationMeters = minOf(
                        existing.distanceFromDestinationMeters ?: Int.MAX_VALUE,
                        registryStation.distanceFromDestinationMeters ?: Int.MAX_VALUE
                    ).takeIf { it != Int.MAX_VALUE }
                )
            } else {
                merged += registryStation
            }
        }

        val preferred = options.preferredOperator.trim()
        return merged.asSequence()
            .filter { it.kind == RoutePoi.Kind.CHARGING_STATION }
            .filter { station ->
                options.connectors.isEmpty() || station.connectorTypes.any { it in options.connectors }
            }
            .filter { station ->
                preferred.isBlank() || options.showOtherOperators || operatorMatches(station, preferred)
            }
            .sortedWith(
                compareBy<RoutePoi> { station -> options.speedPreference.penalty(station.maxPowerKw) }
                    .thenBy { station -> if (preferred.isBlank() || operatorMatches(station, preferred)) 0 else 1 }
                    .thenBy { station -> stayPenalty(station.maxStay) }
                    .thenBy { station -> parkingPenalty(station) }
                    .thenBy { station -> station.distanceFromDestinationMeters ?: Int.MAX_VALUE }
            )
            .take(5)
            .toList()
    }

    private fun operatorMatches(station: RoutePoi, preferred: String): Boolean {
        val needle = preferred.lowercase().trim()
        return sequenceOf(station.operator, station.network, station.name)
            .mapNotNull(::clean)
            .any { it.lowercase().contains(needle) }
    }

    private fun parkingPenalty(station: RoutePoi): Int {
        val parking = station.parkingType?.lowercase().orEmpty()
        val longEnough = parseMaxStayMinutes(station.maxStay)?.let { it >= 60 } == true
        return if (longEnough && ("multi-storey" in parking || "underground" in parking || "parking" in parking)) 0 else 1
    }

    internal fun stayPenalty(raw: String?): Int = when (val minutes = parseMaxStayMinutes(raw)) {
        null -> 2
        in 0..59 -> 3
        in 60..119 -> 1
        else -> 0
    }

    internal fun parseMaxStayMinutes(raw: String?): Int? {
        val text = clean(raw)?.lowercase() ?: return null
        Regex("(\\d+)\\s*[:h]\\s*(\\d{1,2})").find(text)?.let { match ->
            val h = match.groupValues[1].toIntOrNull() ?: return@let
            val m = match.groupValues[2].toIntOrNull() ?: 0
            return h * 60 + m
        }
        val number = Regex("\\d+(?:[.,]\\d+)?").find(text)?.value?.replace(',', '.')?.toDoubleOrNull() ?: return null
        return when {
            "day" in text || "tag" in text -> (number * 1440).toInt()
            "hour" in text || "std" in text || " h" in " $text" -> (number * 60).toInt()
            "min" in text -> number.toInt()
            text.matches(Regex("\\d+")) -> number.toInt()
            else -> null
        }
    }

    private fun clean(value: String?): String? = value?.trim()?.takeIf {
        it.isNotEmpty() && !it.equals("null", true) && !it.equals("none", true)
    }

    fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val r = 6_371_000.0
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }
}
''')

# ---------------------------------------------------------------------------
# OSM enrichment, including fuel stations that explicitly advertise electricity
# ---------------------------------------------------------------------------
write("app/src/main/java/de/drivetime/notifier/routing/OsmEnrichmentClient.kt", r'''
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
''')

# ---------------------------------------------------------------------------
# Bundesnetzagentur enrichment hardening and metadata
# ---------------------------------------------------------------------------
write("app/src/main/java/de/drivetime/notifier/routing/BNetzAChargingClient.kt", r'''
package de.drivetime.notifier.routing

import de.drivetime.notifier.data.ChargingConnectorPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

class BNetzAChargingClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(9, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
) {
    suspend fun query(destination: GeoPoint, radiusMeters: Int): List<RoutePoi> = withContext(Dispatchers.IO) {
        val radius = radiusMeters.coerceIn(100, 10_000)
        val deltaLat = radius / 111_320.0
        val cosLat = max(0.2, cos(Math.toRadians(destination.latitude)))
        val deltaLon = radius / (111_320.0 * cosLat)
        val envelope = listOf(
            destination.longitude - deltaLon,
            destination.latitude - deltaLat,
            destination.longitude + deltaLon,
            destination.latitude + deltaLat
        ).joinToString(",")

        val url = BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("f", "json")
            .addQueryParameter("where", "1=1")
            .addQueryParameter("geometry", envelope)
            .addQueryParameter("geometryType", "esriGeometryEnvelope")
            .addQueryParameter("inSR", "4326")
            .addQueryParameter("spatialRel", "esriSpatialRelIntersects")
            .addQueryParameter("outFields", "*")
            .addQueryParameter("returnGeometry", "true")
            .addQueryParameter("outSR", "4326")
            .addQueryParameter("resultRecordCount", "250")
            .build()

        client.newCall(Request.Builder().url(url).header("User-Agent", "DriveTimeNotifier/1.1").get().build())
            .execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body ?: return@withContext emptyList()
                if (body.contentLength() > MAX_RESPONSE_BYTES) return@withContext emptyList()
                val bytes = body.source().readByteArray(MAX_RESPONSE_BYTES + 1L)
                if (bytes.size > MAX_RESPONSE_BYTES) return@withContext emptyList()
                val root = JSONObject(String(bytes, Charsets.UTF_8))
                if (root.has("error")) return@withContext emptyList()
                val features = root.optJSONArray("features") ?: return@withContext emptyList()
                buildList {
                    for (i in 0 until features.length()) {
                        val feature = features.optJSONObject(i) ?: continue
                        val attrs = feature.optJSONObject("attributes") ?: continue
                        val status = clean(attrs.optString("Status"))?.lowercase().orEmpty()
                        if (status.contains("außer betrieb") || status.contains("ausser betrieb") || status.contains("deaktiv")) continue

                        val geometry = feature.optJSONObject("geometry")
                        val lat = geometry?.optDouble("y", Double.NaN)?.takeIf { it.isFinite() }
                            ?: attrs.optDouble("Breitengrad", Double.NaN).takeIf { it.isFinite() }
                            ?: continue
                        val lon = geometry?.optDouble("x", Double.NaN)?.takeIf { it.isFinite() }
                            ?: attrs.optDouble("Längengrad", Double.NaN).takeIf { it.isFinite() }
                            ?: continue
                        val point = GeoPoint(lat, lon)
                        val distance = ChargingStationSelector.haversineMeters(point, destination)
                        if (distance > radius) continue

                        val connectors = buildSet {
                            for (index in 1..8) addAll(parseConnector(attrs.optString("Steckertypen$index")))
                        }
                        val powerValues = buildList {
                            attrs.optDouble("Nennleistung_Ladeeinrichtung__kW_", Double.NaN)
                                .takeIf { it.isFinite() && it > 0.0 }?.let(::add)
                            for (index in 1..8) parsePowerKw(attrs.optString("Nennleistung_Stecker$index"))?.let(::add)
                        }
                        val operator = first(attrs, "Betreiber", "Betreibername")
                        val name = first(attrs, "Anzeigename__Karte_", "Standortbezeichnung") ?: operator
                        val openingHours = first(
                            attrs,
                            "Öffnungszeiten",
                            "Oeffnungszeiten",
                            "Öffnungszeiten__Wochentage"
                        ) ?: listOfNotNull(
                            clean(attrs.optString("Öffnungszeiten__Wochentage")),
                            clean(attrs.optString("Öffnungszeiten__Tageszeiten"))
                        ).joinToString(" ").ifBlank { null }
                        val maxStay = first(
                            attrs,
                            "Maximale_Parkdauer",
                            "Maximale Parkdauer",
                            "Parkdauer",
                            "Parkraumbeschränkung",
                            "Parkraumbeschraenkung",
                            "Parkraumbeschränkungen"
                        )
                        val fee = first(attrs, "Gebuehrenpflicht", "Gebührenpflicht", "Kostenpflichtig")
                        val parkingType = first(attrs, "Parkplatztyp", "Parkraumtyp", "Standortart")
                        val capacity = listOf("Anzahl_Ladepunkte", "Anzahl Ladepunkte", "Ladepunkte")
                            .asSequence().mapNotNull { attrs.optString(it).toIntOrNull() }.firstOrNull()
                        val address = listOf(
                            listOf(first(attrs, "Straße", "Strasse"), first(attrs, "Hausnummer"))
                                .filterNotNull().joinToString(" "),
                            listOf(first(attrs, "Postleitzahl", "PLZ"), first(attrs, "Ort"))
                                .filterNotNull().joinToString(" ")
                        ).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }

                        add(
                            RoutePoi(
                                point = point,
                                kind = RoutePoi.Kind.CHARGING_STATION,
                                name = name,
                                distanceFromDestinationMeters = distance.roundToInt(),
                                operator = operator,
                                connectorTypes = connectors,
                                maxPowerKw = powerValues.maxOrNull(),
                                access = "public",
                                fee = fee,
                                openingHours = openingHours,
                                maxStay = maxStay,
                                capacity = capacity,
                                parkingType = parkingType,
                                address = address,
                                sources = setOf(RoutePoiSource.BUNDESNETZAGENTUR)
                            )
                        )
                    }
                }
            }
    }

    private fun first(attrs: JSONObject, vararg keys: String): String? =
        keys.asSequence().mapNotNull { clean(attrs.optString(it)) }.firstOrNull()

    private fun clean(raw: String?): String? = raw?.trim()?.takeIf {
        it.isNotEmpty() && !it.equals("null", true) && !it.equals("none", true)
    }

    private fun parseConnector(raw: String): Set<ChargingConnectorPreference> {
        if (raw.isBlank()) return emptySet()
        val text = raw.lowercase()
        return buildSet {
            if ("combo" in text || "ccs" in text) add(ChargingConnectorPreference.CCS)
            if ("chademo" in text) add(ChargingConnectorPreference.CHADEMO)
            if ("typ 2" in text || "type 2" in text) add(ChargingConnectorPreference.TYPE2)
        }
    }

    private fun parsePowerKw(raw: String): Double? = NUMBER.findAll(raw.replace(',', '.'))
        .mapNotNull { it.value.toDoubleOrNull() }
        .filter { it > 0.0 }
        .maxOrNull()

    companion object {
        // Public Esri representation of Bundesnetzagentur charging-register data, CC BY 4.0.
        internal const val BASE_URL = "https://services2.arcgis.com/jUpNdisbWqRpMo35/arcgis/rest/services/Ladesaeulen_in_Deutschland/FeatureServer/0/query"
        private val NUMBER = Regex("[0-9]+(?:\\.[0-9]+)?")
        private const val MAX_RESPONSE_BYTES = 1_500_000L
    }
}
''')

write("app/src/main/java/de/drivetime/notifier/routing/ChargingOperatorCatalog.kt", r'''
package de.drivetime.notifier.routing

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ChargingOperatorCatalog(context: Context) {
    private val prefs = context.getSharedPreferences("charging_operator_catalog", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    fun operators(): List<String> = (BUILT_IN + (prefs.getStringSet(KEY, emptySet()) ?: emptySet()))
        .mapNotNull(::clean)
        .distinctBy { it.lowercase() }
        .sortedWith(String.CASE_INSENSITIVE_ORDER)

    suspend fun refresh(): List<String> = withContext(Dispatchers.IO) {
        val url = BNetzAChargingClient.BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("f", "json")
            .addQueryParameter("where", "1=1")
            .addQueryParameter("outFields", "Betreiber")
            .addQueryParameter("returnGeometry", "false")
            .addQueryParameter("returnDistinctValues", "true")
            .addQueryParameter("orderByFields", "Betreiber")
            .addQueryParameter("resultRecordCount", "2500")
            .build()
        val remote = runCatching {
            client.newCall(Request.Builder().url(url).header("User-Agent", "DriveTimeNotifier/1.1").get().build())
                .execute().use { response ->
                    if (!response.isSuccessful) return@use emptySet<String>()
                    val body = response.body ?: return@use emptySet<String>()
                    if (body.contentLength() > MAX_BYTES) return@use emptySet<String>()
                    val bytes = body.source().readByteArray(MAX_BYTES + 1L)
                    if (bytes.size > MAX_BYTES) return@use emptySet<String>()
                    val root = JSONObject(String(bytes, Charsets.UTF_8))
                    val features = root.optJSONArray("features") ?: return@use emptySet<String>()
                    buildSet {
                        for (i in 0 until features.length()) {
                            clean(features.optJSONObject(i)?.optJSONObject("attributes")?.optString("Betreiber"))?.let(::add)
                        }
                    }
                }
        }.getOrDefault(emptySet())
        if (remote.isNotEmpty()) prefs.edit().putStringSet(KEY, remote).apply()
        operators()
    }

    private fun clean(value: String?): String? = value?.trim()?.takeIf {
        it.isNotEmpty() && !it.equals("null", true)
    }

    companion object {
        private const val KEY = "operators"
        private const val MAX_BYTES = 1_000_000L
        private val BUILT_IN = setOf(
            "EnBW", "IONITY", "Tesla", "Aral pulse", "Allego", "E.ON Drive", "Shell Recharge",
            "Mer", "Pfalzwerke", "Westfalen Weser", "Stadtwerke München", "Stadtwerke Düsseldorf",
            "Stadtwerke Dortmund", "Stadtwerke Unna"
        )
    }
}
''')

# ---------------------------------------------------------------------------
# Calendar output: no literal nulls, opening hours and maximum stay when known
# ---------------------------------------------------------------------------
write("app/src/main/java/de/drivetime/notifier/calendar/DriveEventDescriptionBuilder.kt", r'''
package de.drivetime.notifier.calendar

import de.drivetime.notifier.data.AppLanguage
import de.drivetime.notifier.data.ChargingConnectorPreference
import de.drivetime.notifier.data.RoutingProvider
import de.drivetime.notifier.model.RouteEstimate
import de.drivetime.notifier.routing.ChargingNavigation
import de.drivetime.notifier.routing.RoutePoi
import de.drivetime.notifier.routing.RoutePoiSource
import de.drivetime.notifier.ui.formatDuration
import de.drivetime.notifier.ui.tr
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

object DriveEventDescriptionBuilder {
    fun build(
        language: AppLanguage,
        provider: RoutingProvider,
        origin: String,
        destination: String,
        route: RouteEstimate,
        pois: List<RoutePoi>,
        chargingNavigation: ChargingNavigation? = null
    ): String {
        val lat = route.destinationLatitude
        val lon = route.destinationLongitude
        val encodedDestination = URLEncoder.encode(destination, StandardCharsets.UTF_8.toString())
        val charging = pois.filter { it.kind == RoutePoi.Kind.CHARGING_STATION }.take(5)
        val navigationStation = chargingNavigation?.station
        val navLat = navigationStation?.point?.latitude ?: lat
        val navLon = navigationStation?.point?.longitude ?: lon
        val googleMaps = if (navLat != null && navLon != null) {
            "https://www.google.com/maps/dir/?api=1&destination=$navLat,$navLon&travelmode=driving"
        } else {
            "https://www.google.com/maps/dir/?api=1&destination=$encodedDestination&travelmode=driving"
        }
        val geo = if (navLat != null && navLon != null) "geo:$navLat,$navLon?q=$navLat,$navLon" else "geo:0,0?q=$encodedDestination"
        val cameras = pois.filter { it.kind == RoutePoi.Kind.SPEED_CAMERA }
        val parking = pois.filter { it.kind == RoutePoi.Kind.PARKING }
            .sortedBy { it.distanceFromDestinationMeters ?: Int.MAX_VALUE }
            .take(5)

        return buildString {
            appendLine(tr(language, "Drive automatically planned by Drive Time Notifier.", "Fahrt automatisch durch Drive Time Notifier geplant."))
            appendLine()
            appendLine("${tr(language, "From", "Von")}: $origin")
            appendLine("${tr(language, "To", "Nach")}: $destination")
            appendLine("${tr(language, "Routing provider", "Routingdienst")}: ${provider.displayName}")
            appendLine("${tr(language, "Estimated drive time", "Geschätzte Fahrzeit")}: ${formatDuration(route.durationSeconds, language)}")
            appendLine("${tr(language, "Distance", "Distanz")}: ${"%.1f".format(route.distanceMeters / 1000.0)} km")
            appendLine()
            appendLine("${tr(language, "Start navigation", "Navigation starten")}:")
            appendLine("Google Maps: $googleMaps")
            appendLine("${tr(language, "Installed navigation app", "Installierte Navigations-App")}: $geo")

            if (navigationStation != null) {
                val pLat = navigationStation.point.latitude
                val pLon = navigationStation.point.longitude
                val walking = "https://www.google.com/maps/dir/?api=1&origin=$pLat,$pLon&destination=$encodedDestination&travelmode=walking"
                appendLine("${tr(language, "Navigation target", "Navigationsziel")}: ${clean(navigationStation.name) ?: tr(language, "Charging station", "Ladestation")}")
                chargingNavigation?.let { nav ->
                    appendLine("${tr(language, "Approximate walk to appointment", "Ungefährer Fußweg zum Termin")}: ~${nav.walkingDistanceMeters} m / ${formatDuration(nav.walkingDurationSeconds, language)}")
                }
                appendLine("${tr(language, "Then walk to the appointment destination", "Danach zu Fuß zum Terminziel")}: $walking")
            }

            if (charging.isNotEmpty()) {
                appendLine()
                appendLine(tr(language, "Nearby public charging stations:", "Öffentliche Ladesäulen in Zielnähe:"))
                charging.forEachIndexed { index, poi ->
                    val pLat = poi.point.latitude
                    val pLon = poi.point.longitude
                    val driving = "https://www.google.com/maps/dir/?api=1&destination=$pLat,$pLon&travelmode=driving"
                    val walking = "https://www.google.com/maps/dir/?api=1&origin=$pLat,$pLon&destination=$encodedDestination&travelmode=walking"
                    appendLine("${index + 1}. ${clean(poi.name) ?: tr(language, "Charging station", "Ladestation")}")
                    val compact = buildList {
                        if (poi.connectorTypes.isNotEmpty()) add(poi.connectorTypes.joinToString("/") { connectorLabel(language, it) })
                        poi.maxPowerKw?.takeIf { it > 0.0 }?.let { power ->
                            add((if (power % 1.0 == 0.0) power.toInt().toString() else "%.1f".format(power)) + " kW")
                        }
                        poi.distanceFromDestinationMeters?.let { add("~$it m ${tr(language, "from destination", "vom Ziel")}") }
                    }
                    if (compact.isNotEmpty()) appendLine("   ${compact.joinToString(" · ")}")
                    clean(poi.operator)?.let { appendLine("   ${tr(language, "Operator", "Betreiber")}: $it") }
                    clean(poi.network)?.takeIf { !it.equals(clean(poi.operator), true) }?.let {
                        appendLine("   ${tr(language, "Network", "Netzwerk")}: $it")
                    }
                    clean(poi.openingHours)?.let { appendLine("   ${tr(language, "Opening hours", "Öffnungszeiten")}: $it") }
                    clean(poi.maxStay)?.let { appendLine("   ${tr(language, "Maximum stay", "Maximale Standzeit")}: $it") }
                    clean(poi.fee)?.let { appendLine("   ${tr(language, "Fee information", "Gebührenhinweis")}: $it") }
                    poi.capacity?.takeIf { it > 0 }?.let { appendLine("   ${tr(language, "Charging points", "Ladepunkte")}: $it") }
                    clean(poi.address)?.let { appendLine("   ${tr(language, "Address", "Adresse")}: $it") }
                    appendLine("   ${tr(language, "Navigation", "Navigation")}: $driving")
                    appendLine("   ${tr(language, "Walk to destination", "Zum Ziel laufen")}: $walking")
                }
                val hasOsm = charging.any { RoutePoiSource.OSM in it.sources }
                val hasBNetzA = charging.any { RoutePoiSource.BUNDESNETZAGENTUR in it.sources }
                if (hasOsm) appendLine(tr(language, "Charging data: OpenStreetMap via Overpass. Private/member-only access is excluded; community data can be incomplete.", "Ladedaten: OpenStreetMap über Overpass. Private bzw. nur für Mitglieder zugängliche Stationen werden ausgeschlossen; Community-Daten können unvollständig sein."))
                if (hasBNetzA) appendLine(tr(language, "Registry enrichment: Bundesnetzagentur.de data, CC BY 4.0, through the configured public register service.", "Register-Anreicherung: Daten von Bundesnetzagentur.de, CC BY 4.0, über den konfigurierten öffentlichen Registerdienst."))
            }

            if (parking.isNotEmpty()) {
                appendLine()
                appendLine(tr(language, "Nearby parking, sorted by approximate walking distance:", "Nahegelegene Parkplätze, sortiert nach ungefährer Laufentfernung:"))
                parking.forEachIndexed { index, poi ->
                    val straight = poi.distanceFromDestinationMeters ?: 0
                    val walk = (straight * 1.25).roundToInt()
                    val pLat = poi.point.latitude
                    val pLon = poi.point.longitude
                    val link = "https://www.google.com/maps/dir/?api=1&destination=$pLat,$pLon&travelmode=driving"
                    appendLine("${index + 1}. ${clean(poi.name) ?: tr(language, "Parking", "Parkplatz")} (~$walk m ${tr(language, "walk", "Fußweg")}): $link")
                }
            }

            if (cameras.isNotEmpty()) {
                appendLine()
                appendLine("${tr(language, "Speed cameras on the selected route", "Blitzer auf der gewählten Strecke")}: ${cameras.size}")
                cameras.forEachIndexed { index, poi ->
                    appendLine("${index + 1}. ${"%.5f".format(poi.point.latitude)}, ${"%.5f".format(poi.point.longitude)}")
                }
                appendLine(tr(language, "Source: OpenStreetMap highway=speed_camera via Overpass. Community data may be incomplete.", "Quelle: OpenStreetMap highway=speed_camera über Overpass. Community-Daten können unvollständig sein."))
            }
        }.trim()
    }

    private fun clean(value: String?): String? = value?.trim()?.takeIf {
        it.isNotEmpty() && !it.equals("null", true) && !it.equals("none", true)
    }

    private fun connectorLabel(language: AppLanguage, connector: ChargingConnectorPreference): String = when (connector) {
        ChargingConnectorPreference.CCS -> "CCS / Combo 2"
        ChargingConnectorPreference.TYPE2 -> tr(language, "Type 2", "Typ 2")
        ChargingConnectorPreference.CHADEMO -> "CHAdeMO"
        ChargingConnectorPreference.ANY -> tr(language, "Any connector", "Beliebiger Stecker")
    }
}
''')

# ---------------------------------------------------------------------------
# Update checker with fixed host, response limits and no telemetry
# ---------------------------------------------------------------------------
write("app/src/main/java/de/drivetime/notifier/update/UpdateChecker.kt", r'''
package de.drivetime.notifier.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed interface UpdateCheckResult {
    data class UpToDate(val latestVersion: String) : UpdateCheckResult
    data class UpdateAvailable(val latestVersion: String, val releaseUrl: String) : UpdateCheckResult
    data class Error(val reason: String) : UpdateCheckResult
}

class UpdateChecker(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
) {
    suspend fun check(installedVersion: String, debugBuild: Boolean): UpdateCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(
                Request.Builder()
                    .url(LATEST_RELEASE_URL)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "DriveTimeNotifier/${installedVersion.take(40)}")
                    .get()
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) return@use UpdateCheckResult.Error("HTTP ${response.code}")
                val body = response.body ?: return@use UpdateCheckResult.Error("Empty response")
                if (body.contentLength() > MAX_BYTES) return@use UpdateCheckResult.Error("Response too large")
                val bytes = body.source().readByteArray(MAX_BYTES + 1L)
                if (bytes.size > MAX_BYTES) return@use UpdateCheckResult.Error("Response too large")
                parseReleaseJson(installedVersion, debugBuild, String(bytes, Charsets.UTF_8))
            }
        }.getOrElse { UpdateCheckResult.Error(it.message?.take(120) ?: "Network error") }
    }

    internal fun parseReleaseJson(installedVersion: String, debugBuild: Boolean, json: String): UpdateCheckResult {
        val root = runCatching { JSONObject(json) }.getOrElse { return UpdateCheckResult.Error("Invalid response") }
        if (root.optBoolean("draft", false) || root.optBoolean("prerelease", false)) {
            return UpdateCheckResult.Error("No stable release")
        }
        val latest = normalize(root.optString("tag_name")) ?: return UpdateCheckResult.Error("Missing version")
        val releaseUrl = root.optString("html_url").takeIf {
            it.startsWith("https://github.com/3115a083/drive-time-notifier/releases/")
        } ?: "https://github.com/3115a083/drive-time-notifier/releases"
        val installed = normalize(installedVersion) ?: return UpdateCheckResult.Error("Invalid installed version")
        return if (compareVersions(latest, installed) > 0) {
            UpdateCheckResult.UpdateAvailable(latest, releaseUrl)
        } else {
            // For debug builds this means the debug build is based on the current or newer code version.
            UpdateCheckResult.UpToDate(latest)
        }
    }

    companion object {
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/3115a083/drive-time-notifier/releases/latest"
        private const val MAX_BYTES = 256_000L

        internal fun normalize(raw: String): String? {
            val text = raw.trim().removePrefix("v").substringBefore('-').substringBefore('+')
            return text.takeIf { it.matches(Regex("\\d+(?:\\.\\d+){0,3}")) }
        }

        internal fun compareVersions(a: String, b: String): Int {
            val aa = a.split('.').map { it.toIntOrNull() ?: 0 }
            val bb = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(aa.size, bb.size)) {
                val av = aa.getOrElse(i) { 0 }
                val bv = bb.getOrElse(i) { 0 }
                if (av != bv) return av.compareTo(bv)
            }
            return 0
        }
    }
}
''')

# ---------------------------------------------------------------------------
# Settings persistence: dynamic buffer + multi connector selection
# ---------------------------------------------------------------------------
replace_once(
    "app/src/main/java/de/drivetime/notifier/data/SettingsStore.kt",
    '    val bufferMinutes: Int = 15,\n    val reminderLeadMinutes: Int = 0,',
    '    val bufferMinutes: Int = 15,\n    val dynamicBufferEnabled: Boolean = false,\n    val dynamicBufferLevel: DynamicBufferLevel = DynamicBufferLevel.BALANCED,\n    val reminderLeadMinutes: Int = 0,'
)
replace_once(
    "app/src/main/java/de/drivetime/notifier/data/SettingsStore.kt",
    '    val chargingConnector: ChargingConnectorPreference = ChargingConnectorPreference.ANY,\n    val chargingMaxDistanceMeters: Int = 1_500,',
    '    val chargingConnector: ChargingConnectorPreference = ChargingConnectorPreference.ANY,\n    val chargingConnectors: Set<ChargingConnectorPreference> = emptySet(),\n    val chargingMaxDistanceMeters: Int = 1_500,'
)
replace_once(
    "app/src/main/java/de/drivetime/notifier/data/SettingsStore.kt",
    '        val BUFFER = intPreferencesKey("buffer_minutes")\n        val REMINDER = intPreferencesKey("reminder_lead")',
    '        val BUFFER = intPreferencesKey("buffer_minutes")\n        val DYNAMIC_BUFFER = booleanPreferencesKey("dynamic_buffer_enabled")\n        val DYNAMIC_BUFFER_LEVEL = stringPreferencesKey("dynamic_buffer_level")\n        val REMINDER = intPreferencesKey("reminder_lead")'
)
replace_once(
    "app/src/main/java/de/drivetime/notifier/data/SettingsStore.kt",
    '        val CHARGING_CONNECTOR = stringPreferencesKey("charging_connector")\n        val CHARGING_MAX_DISTANCE = intPreferencesKey("charging_max_distance_meters")',
    '        val CHARGING_CONNECTOR = stringPreferencesKey("charging_connector")\n        val CHARGING_CONNECTORS = stringSetPreferencesKey("charging_connectors")\n        val CHARGING_MAX_DISTANCE = intPreferencesKey("charging_max_distance_meters")'
)
replace_once(
    "app/src/main/java/de/drivetime/notifier/data/SettingsStore.kt",
    '            bufferMinutes = p[K.BUFFER] ?: 15,\n            reminderLeadMinutes = p[K.REMINDER] ?: 0,',
    '            bufferMinutes = p[K.BUFFER] ?: 15,\n            dynamicBufferEnabled = p[K.DYNAMIC_BUFFER] ?: false,\n            dynamicBufferLevel = DynamicBufferLevel.fromId(p[K.DYNAMIC_BUFFER_LEVEL]),\n            reminderLeadMinutes = p[K.REMINDER] ?: 0,'
)
replace_once(
    "app/src/main/java/de/drivetime/notifier/data/SettingsStore.kt",
    '            chargingConnector = ChargingConnectorPreference.fromId(p[K.CHARGING_CONNECTOR]),\n            chargingMaxDistanceMeters = (p[K.CHARGING_MAX_DISTANCE] ?: 1_500).coerceIn(100, 10_000),',
    '''            chargingConnector = ChargingConnectorPreference.fromId(p[K.CHARGING_CONNECTOR]),
            chargingConnectors = p[K.CHARGING_CONNECTORS]
                ?.map { ChargingConnectorPreference.fromId(it) }
                ?.filterNot { it == ChargingConnectorPreference.ANY }
                ?.toSet()
                ?: ChargingConnectorPreference.fromId(p[K.CHARGING_CONNECTOR])
                    .takeUnless { it == ChargingConnectorPreference.ANY }
                    ?.let(::setOf)
                    .orEmpty(),
            chargingMaxDistanceMeters = (p[K.CHARGING_MAX_DISTANCE] ?: 1_500).coerceIn(100, 10_000),'''
)
replace_once(
    "app/src/main/java/de/drivetime/notifier/data/SettingsStore.kt",
    '        p[K.BUFFER] = s.bufferMinutes.coerceIn(0, 180)\n        p[K.REMINDER] = s.reminderLeadMinutes.coerceIn(0, 180)',
    '        p[K.BUFFER] = s.bufferMinutes.coerceIn(0, 180)\n        p[K.DYNAMIC_BUFFER] = s.dynamicBufferEnabled\n        p[K.DYNAMIC_BUFFER_LEVEL] = s.dynamicBufferLevel.id\n        p[K.REMINDER] = s.reminderLeadMinutes.coerceIn(0, 180)'
)
replace_once(
    "app/src/main/java/de/drivetime/notifier/data/SettingsStore.kt",
    '        p[K.CHARGING_CONNECTOR] = s.chargingConnector.id\n        p[K.CHARGING_MAX_DISTANCE] = s.chargingMaxDistanceMeters.coerceIn(100, 10_000)',
    '''        p[K.CHARGING_CONNECTOR] = s.chargingConnectors.firstOrNull()?.id ?: ChargingConnectorPreference.ANY.id
        p[K.CHARGING_CONNECTORS] = s.chargingConnectors.filterNot { it == ChargingConnectorPreference.ANY }.map { it.id }.toSet()
        p[K.CHARGING_MAX_DISTANCE] = s.chargingMaxDistanceMeters.coerceIn(100, 10_000)'''
)

# ---------------------------------------------------------------------------
# Encrypted backup: include new settings and cap malicious input size
# ---------------------------------------------------------------------------
replace_once(
    "app/src/main/java/de/drivetime/notifier/security/PasswordBackup.kt",
    'import java.io.InputStream\nimport java.io.OutputStream',
    'import java.io.ByteArrayOutputStream\nimport java.io.InputStream\nimport java.io.OutputStream'
)
replace_once(
    "app/src/main/java/de/drivetime/notifier/security/PasswordBackup.kt",
    '    private const val KEY_BITS = 256',
    '    private const val KEY_BITS = 256\n    private const val MAX_BACKUP_BYTES = 4 * 1024 * 1024'
)
replace_once(
    "app/src/main/java/de/drivetime/notifier/security/PasswordBackup.kt",
    '        val bytes = input.buffered().use { it.readBytes() }',
    '        val bytes = input.buffered().use { readLimited(it) }'
)
replace_once(
    "app/src/main/java/de/drivetime/notifier/security/PasswordBackup.kt",
    '        put("bufferMinutes", s.bufferMinutes)\n        put("reminderLeadMinutes", s.reminderLeadMinutes)',
    '        put("bufferMinutes", s.bufferMinutes)\n        put("dynamicBufferEnabled", s.dynamicBufferEnabled)\n        put("dynamicBufferLevel", s.dynamicBufferLevel.id)\n        put("reminderLeadMinutes", s.reminderLeadMinutes)'
)
replace_once(
    "app/src/main/java/de/drivetime/notifier/security/PasswordBackup.kt",
    '        put("chargingConnector", s.chargingConnector.id)\n        put("chargingMaxDistanceMeters", s.chargingMaxDistanceMeters)',
    '        put("chargingConnector", s.chargingConnector.id)\n        put("chargingConnectors", JSONArray(s.chargingConnectors.map { it.id }))\n        put("chargingMaxDistanceMeters", s.chargingMaxDistanceMeters)'
)
replace_once(
    "app/src/main/java/de/drivetime/notifier/security/PasswordBackup.kt",
    '            bufferMinutes = j.optInt("bufferMinutes", defaults.bufferMinutes),\n            reminderLeadMinutes = j.optInt("reminderLeadMinutes", defaults.reminderLeadMinutes),',
    '            bufferMinutes = j.optInt("bufferMinutes", defaults.bufferMinutes),\n            dynamicBufferEnabled = j.optBoolean("dynamicBufferEnabled", defaults.dynamicBufferEnabled),\n            dynamicBufferLevel = DynamicBufferLevel.fromId(j.optString("dynamicBufferLevel", defaults.dynamicBufferLevel.id)),\n            reminderLeadMinutes = j.optInt("reminderLeadMinutes", defaults.reminderLeadMinutes),'
)
replace_once(
    "app/src/main/java/de/drivetime/notifier/security/PasswordBackup.kt",
    '            chargingConnector = ChargingConnectorPreference.fromId(j.optString("chargingConnector", defaults.chargingConnector.id)),\n            chargingMaxDistanceMeters = j.optInt("chargingMaxDistanceMeters", defaults.chargingMaxDistanceMeters).coerceIn(100, 10_000),',
    '''            chargingConnector = ChargingConnectorPreference.fromId(j.optString("chargingConnector", defaults.chargingConnector.id)),
            chargingConnectors = if (j.has("chargingConnectors")) {
                j.stringList("chargingConnectors")
                    .map { ChargingConnectorPreference.fromId(it) }
                    .filterNot { it == ChargingConnectorPreference.ANY }
                    .toSet()
            } else {
                ChargingConnectorPreference.fromId(j.optString("chargingConnector", defaults.chargingConnector.id))
                    .takeUnless { it == ChargingConnectorPreference.ANY }
                    ?.let(::setOf)
                    .orEmpty()
            },
            chargingMaxDistanceMeters = j.optInt("chargingMaxDistanceMeters", defaults.chargingMaxDistanceMeters).coerceIn(100, 10_000),'''
)
replace_once(
    "app/src/main/java/de/drivetime/notifier/security/PasswordBackup.kt",
    '    private fun JSONObject.stringSet(name: String): Set<String> =\n        stringList(name).toSet()',
    '''    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_BACKUP_BYTES) { "Backup file is too large." }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun JSONObject.stringSet(name: String): Set<String> =
        stringList(name).toSet()'''
)

# ---------------------------------------------------------------------------
# Manual planner and settings UI
# ---------------------------------------------------------------------------
replace_once(
    "app/src/main/java/de/drivetime/notifier/MainActivity.kt",
    'import de.drivetime.notifier.core.DrivePlanner\n',
    'import de.drivetime.notifier.core.DrivePlanner\nimport de.drivetime.notifier.core.DynamicArrivalBuffer\n'
)
replace_once(
    "app/src/main/java/de/drivetime/notifier/MainActivity.kt",
    'import de.drivetime.notifier.ui.*\n',
    'import de.drivetime.notifier.ui.*\nimport de.drivetime.notifier.update.*\n'
)
replace_once(
    "app/src/main/java/de/drivetime/notifier/MainActivity.kt",
    '''            val plan = DrivePlanner.plan(
                enriched.effectiveDestinationStartMillis,
                enriched.route.durationSeconds,
                settings.bufferMinutes,
                previousEndMillis
            )
            Pair(enriched, plan)''',
    '''            val effectiveBufferMinutes = DynamicArrivalBuffer.totalMinutes(
                settings,
                enriched.route.durationSeconds,
                enriched.route.trafficDelaySeconds
            )
            val plan = DrivePlanner.plan(
                enriched.effectiveDestinationStartMillis,
                enriched.route.durationSeconds,
                effectiveBufferMinutes,
                previousEndMillis
            )
            Triple(enriched, plan, effectiveBufferMinutes)'''
)
replace_once(
    "app/src/main/java/de/drivetime/notifier/MainActivity.kt",
    '        result.onSuccess { (enriched, plan) ->',
    '        result.onSuccess { (enriched, plan, effectiveBufferMinutes) ->'
)
replace_once(
    "app/src/main/java/de/drivetime/notifier/MainActivity.kt",
    '                planWarningText(settings.language, plan, settings.bufferMinutes),',
    '                planWarningText(settings.language, plan, effectiveBufferMinutes),'
)
replace_once(
    "app/src/main/java/de/drivetime/notifier/MainActivity.kt",
    '        var photonDraft by remember { mutableStateOf(settings.photonBaseUrl) }\n        val latestSettings by rememberUpdatedState(settings)',
    '''        var photonDraft by remember { mutableStateOf(settings.photonBaseUrl) }
        val operatorCatalog = remember { ChargingOperatorCatalog(context) }
        var operatorOptions by remember { mutableStateOf(operatorCatalog.operators()) }
        var operatorRefreshing by remember { mutableStateOf(false) }
        var showOperatorPicker by remember { mutableStateOf(false) }
        var updateChecking by remember { mutableStateOf(false) }
        var updateResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
        val latestSettings by rememberUpdatedState(settings)'''
)

# Planning card: dynamic buffer controls
replace_once(
    "app/src/main/java/de/drivetime/notifier/MainActivity.kt",
    '''                NumberDraftField(
                    initialValue = settings.bufferMinutes,
                    label = tr(settings.language, "Arrival buffer (minutes)", "Ankunftspuffer (Minuten)"),
                    onValid = { onChange(settings.copy(bufferMinutes = it.coerceIn(0, 180))) }
                )
                NumberDraftField(
                    initialValue = settings.reminderLeadMinutes,''',
    '''                NumberDraftField(
                    initialValue = settings.bufferMinutes,
                    label = tr(settings.language, "Arrival buffer (minutes)", "Ankunftspuffer (Minuten)"),
                    onValid = { onChange(settings.copy(bufferMinutes = it.coerceIn(0, 180))) }
                )
                SettingSwitch(
                    tr(settings.language, "Add dynamic reserve for longer trips", "Dynamischen Zusatzpuffer für längere Fahrten verwenden"),
                    settings.dynamicBufferEnabled
                ) { onChange(settings.copy(dynamicBufferEnabled = it)) }
                if (settings.dynamicBufferEnabled) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        DynamicBufferLevel.entries.forEach { level ->
                            FilterChip(
                                selected = settings.dynamicBufferLevel == level,
                                onClick = { onChange(settings.copy(dynamicBufferLevel = level)) },
                                label = {
                                    Text(
                                        when (level) {
                                            DynamicBufferLevel.LOW -> tr(settings.language, "Low", "Gering")
                                            DynamicBufferLevel.BALANCED -> tr(settings.language, "Balanced", "Ausgewogen")
                                            DynamicBufferLevel.CAUTIOUS -> tr(settings.language, "Cautious", "Vorsichtig")
                                        }
                                    )
                                }
                            )
                        }
                    }
                    Text(
                        tr(
                            settings.language,
                            "The extra reserve grows with trip duration. Providers without live traffic get a slightly larger uncertainty reserve. Road-type weighting is not guessed when a provider does not expose reliable road-class data.",
                            "Der Zusatzpuffer wächst mit der Fahrtdauer. Dienste ohne Live-Verkehr erhalten eine etwas größere Unsicherheitsreserve. Eine Autobahn-/Stadt-Gewichtung wird nicht geraten, wenn der Anbieter keine verlässlichen Straßenklassen liefert."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                NumberDraftField(
                    initialValue = settings.reminderLeadMinutes,'''
)

# Multi-select connector chips
pattern = re.compile(
    r'''                            Text\(tr\(settings\.language, "Connector", "Steckertyp"\), style = MaterialTheme\.typography\.labelLarge\)\n                            Row\(\n                                Modifier\.fillMaxWidth\(\)\.horizontalScroll\(rememberScrollState\(\)\),\n                                horizontalArrangement = Arrangement\.spacedBy\(6\.dp\)\n                            \) \{\n                                ChargingConnectorPreference\.entries\.forEach \{ connector ->.*?\n                            \}\n                            NumberDraftField\(''',
    re.S,
)
main = read("app/src/main/java/de/drivetime/notifier/MainActivity.kt")
replacement = '''                            Text(tr(settings.language, "Connector types", "Steckertypen"), style = MaterialTheme.typography.labelLarge)
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilterChip(
                                    selected = settings.chargingConnectors.isEmpty(),
                                    onClick = { onChange(settings.copy(chargingConnectors = emptySet(), chargingConnector = ChargingConnectorPreference.ANY)) },
                                    label = { Text(tr(settings.language, "No restriction", "Keine Einschränkung")) }
                                )
                                listOf(
                                    ChargingConnectorPreference.CCS,
                                    ChargingConnectorPreference.TYPE2,
                                    ChargingConnectorPreference.CHADEMO
                                ).forEach { connector ->
                                    FilterChip(
                                        selected = connector in settings.chargingConnectors,
                                        onClick = {
                                            val updated = if (connector in settings.chargingConnectors) {
                                                settings.chargingConnectors - connector
                                            } else settings.chargingConnectors + connector
                                            onChange(settings.copy(chargingConnectors = updated, chargingConnector = ChargingConnectorPreference.ANY))
                                        },
                                        label = {
                                            Text(
                                                when (connector) {
                                                    ChargingConnectorPreference.CCS -> "CCS / Combo 2"
                                                    ChargingConnectorPreference.TYPE2 -> tr(settings.language, "Type 2", "Typ 2")
                                                    ChargingConnectorPreference.CHADEMO -> "CHAdeMO"
                                                    ChargingConnectorPreference.ANY -> tr(settings.language, "Any", "Beliebig")
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                            Text(tr(settings.language, "Maximum straight-line distance from destination", "Maximale Luftlinien-Entfernung vom Ziel"), style = MaterialTheme.typography.labelLarge)
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(250, 500, 750, 1000, 1500, 2000).forEach { meters ->
                                    FilterChip(
                                        selected = settings.chargingMaxDistanceMeters == meters,
                                        onClick = { onChange(settings.copy(chargingMaxDistanceMeters = meters)) },
                                        label = { Text(if (meters < 1000) "$meters m" else "${meters / 1000.0}".replace(".0", "") + " km") }
                                    )
                                }
                            }
                            NumberDraftField('''
if 'Text(tr(settings.language, "Connector types", "Steckertypen")' not in main:
    main, count = pattern.subn(replacement, main, count=1)
    if count != 1:
        raise RuntimeError("Could not replace charging connector UI")
    p("app/src/main/java/de/drivetime/notifier/MainActivity.kt").write_text(main, encoding="utf-8")

# Rename custom distance field label and update speed labels
main = read("app/src/main/java/de/drivetime/notifier/MainActivity.kt")
main = main.replace(
    'label = tr(settings.language, "Maximum distance from destination (m)", "Maximale Entfernung vom Ziel (m)"),',
    'label = tr(settings.language, "Custom distance (m)", "Benutzerdefinierte Entfernung (m)"),',
    1
)
main = main.replace('ChargingSpeedPreference.SLOW -> tr(settings.language, "Slow ≤22 kW", "Langsam ≤22 kW")', 'ChargingSpeedPreference.SLOW -> tr(settings.language, "Slow ≤11 kW", "Langsam ≤11 kW")')
main = main.replace('ChargingSpeedPreference.MEDIUM -> tr(settings.language, "Medium 23–99 kW", "Mittel 23–99 kW")', 'ChargingSpeedPreference.MEDIUM -> tr(settings.language, "Normal >11–22 kW", "Normal >11–22 kW")')
main = main.replace('ChargingSpeedPreference.FAST -> tr(settings.language, "Fast 100–149 kW", "Schnell 100–149 kW")', 'ChargingSpeedPreference.FAST -> tr(settings.language, "Fast >22–100 kW", "Schnell >22–100 kW")')
main = main.replace('ChargingSpeedPreference.HPC -> "HPC ≥150 kW"', 'ChargingSpeedPreference.HPC -> "HPC >100 kW"')
p("app/src/main/java/de/drivetime/notifier/MainActivity.kt").write_text(main, encoding="utf-8")

# Replace direct operator text entry with catalog picker and refresh
replace_once(
    "app/src/main/java/de/drivetime/notifier/MainActivity.kt",
    '''                            OutlinedTextField(
                                value = settings.chargingPreferredOperator,
                                onValueChange = { onChange(settings.copy(chargingPreferredOperator = it.take(80))) },
                                label = { Text(tr(settings.language, "Preferred network/operator (optional)", "Bevorzugter Netzbetreiber (optional)")) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )''',
    '''                            Text(tr(settings.language, "Preferred operator/network", "Bevorzugter Betreiber/Netzwerk"), style = MaterialTheme.typography.labelLarge)
                            SelectionButton(
                                text = settings.chargingPreferredOperator.ifBlank { tr(settings.language, "No preference", "Keine Präferenz") },
                                onClick = { showOperatorPicker = true },
                                leadingIcon = Icons.Outlined.EvStation
                            )
                            OutlinedButton(
                                onClick = {
                                    if (!operatorRefreshing) {
                                        operatorRefreshing = true
                                        scope.launch {
                                            operatorOptions = operatorCatalog.refresh()
                                            operatorRefreshing = false
                                        }
                                    }
                                },
                                enabled = !operatorRefreshing,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (operatorRefreshing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Outlined.Refresh, null)
                                Spacer(Modifier.width(8.dp))
                                Text(tr(settings.language, "Update operator list", "Betreiberliste aktualisieren"))
                            }'''
)

# Operator picker dialog
replace_once(
    "app/src/main/java/de/drivetime/notifier/MainActivity.kt",
    '        if (backupPasswordMode != null) {',
    '''        if (showOperatorPicker) {
            Dialog(onDismissRequest = { showOperatorPicker = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth(0.92f).heightIn(max = 650.dp)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(tr(settings.language, "Preferred operator/network", "Bevorzugter Betreiber/Netzwerk"), style = MaterialTheme.typography.titleLarge)
                        Text(
                            tr(settings.language, "Choose a normalized name instead of typing it manually. Matching remains case-insensitive.", "Wähle einen normalisierten Namen statt ihn manuell einzugeben. Der Abgleich bleibt unabhängig von Groß-/Kleinschreibung."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    onChange(settings.copy(chargingPreferredOperator = ""))
                                    showOperatorPicker = false
                                }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(settings.chargingPreferredOperator.isBlank(), onClick = null)
                                Spacer(Modifier.width(8.dp))
                                Text(tr(settings.language, "No preference", "Keine Präferenz"))
                            }
                            operatorOptions.forEach { operator ->
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        onChange(settings.copy(chargingPreferredOperator = operator.take(80)))
                                        showOperatorPicker = false
                                    }.padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(settings.chargingPreferredOperator.equals(operator, true), onClick = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(operator)
                                }
                            }
                        }
                        TextButton(onClick = { showOperatorPicker = false }, modifier = Modifier.align(Alignment.End)) {
                            Text(tr(settings.language, "Close", "Schließen"))
                        }
                    }
                }
            }
        }

        if (backupPasswordMode != null) {'''
)

# Version display and update checker next to GitHub area
old_footer = '''            Column(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Vibecoded with ❤️", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { uriHandler.openUri("https://github.com/3115a083/drive-time-notifier/") }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_github),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text("GitHub")
                }
            }'''
new_footer = '''            Column(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Vibecoded with ❤️", style = MaterialTheme.typography.bodyMedium)
                Text(
                    tr(settings.language, "Installed version: ${BuildConfig.VERSION_NAME}", "Installierte Version: ${BuildConfig.VERSION_NAME}"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { uriHandler.openUri("https://github.com/3115a083/drive-time-notifier/") }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_github),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(7.dp))
                        Text("GitHub")
                    }
                    OutlinedButton(
                        onClick = {
                            if (!updateChecking) {
                                updateChecking = true
                                scope.launch {
                                    updateResult = UpdateChecker().check(BuildConfig.VERSION_NAME, BuildConfig.DEBUG)
                                    updateChecking = false
                                }
                            }
                        },
                        enabled = !updateChecking
                    ) {
                        if (updateChecking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.SystemUpdate, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(tr(settings.language, "Check update", "Update prüfen"))
                    }
                }
                when (val result = updateResult) {
                    is UpdateCheckResult.UpToDate -> Text(
                        if (BuildConfig.DEBUG)
                            tr(settings.language, "Debug build. Latest official release: ${result.latestVersion}.", "Debug-Build. Neueste offizielle Version: ${result.latestVersion}.")
                        else tr(settings.language, "App is up to date (${result.latestVersion}).", "App ist aktuell (${result.latestVersion})."),
                        style = MaterialTheme.typography.bodySmall
                    )
                    is UpdateCheckResult.UpdateAvailable -> {
                        Text(
                            tr(settings.language, "New official version ${result.latestVersion} is available.", "Neue offizielle Version ${result.latestVersion} ist verfügbar."),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        TextButton(onClick = { uriHandler.openUri(result.releaseUrl) }) {
                            Text(tr(settings.language, "Open release", "Release öffnen"))
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Outlined.OpenInNew, null, modifier = Modifier.size(16.dp))
                        }
                    }
                    is UpdateCheckResult.Error -> Text(
                        tr(settings.language, "Update check failed: ${result.reason}", "Update-Prüfung fehlgeschlagen: ${result.reason}"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    null -> Unit
                }
            }'''
replace_once("app/src/main/java/de/drivetime/notifier/MainActivity.kt", old_footer, new_footer)

# Fix numeric text fields so DataStore updates do not move the cursor/reset partially typed values.
replace_once(
    "app/src/main/java/de/drivetime/notifier/MainActivity.kt",
    '''    private fun NumberDraftField(initialValue: Int, label: String, onValid: (Int) -> Unit) {
        var text by remember { mutableStateOf(initialValue.toString()) }
        val latestOnValid by rememberUpdatedState(onValid)
        val latestInitial by rememberUpdatedState(initialValue)
        LaunchedEffect(text) {
            delay(450)
            val parsed = text.toIntOrNull()
            if (parsed != null && parsed != latestInitial) latestOnValid(parsed)
        }
        LaunchedEffect(initialValue) {
            if (text.toIntOrNull() != initialValue) text = initialValue.toString()
        }
        OutlinedTextField(
            value = text,
            onValueChange = { if (it.all(Char::isDigit)) text = it },
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }''',
    '''    private fun NumberDraftField(initialValue: Int, label: String, onValid: (Int) -> Unit) {
        var text by remember(label) { mutableStateOf(initialValue.toString()) }
        var userEditing by remember(label) { mutableStateOf(false) }
        val latestOnValid by rememberUpdatedState(onValid)
        val latestInitial by rememberUpdatedState(initialValue)
        LaunchedEffect(text, userEditing) {
            if (!userEditing) return@LaunchedEffect
            delay(450)
            val submitted = text
            val parsed = submitted.toIntOrNull()
            if (parsed != null && parsed != latestInitial) latestOnValid(parsed)
            delay(120)
            if (text == submitted) userEditing = false
        }
        LaunchedEffect(initialValue, userEditing) {
            if (!userEditing && text.toIntOrNull() != initialValue) text = initialValue.toString()
        }
        OutlinedTextField(
            value = text,
            onValueChange = {
                if (it.all(Char::isDigit)) {
                    text = it
                    userEditing = true
                }
            },
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }'''
)

# ---------------------------------------------------------------------------
# Apply dynamic buffer in both automation workers
# ---------------------------------------------------------------------------
for rel in [
    "app/src/main/java/de/drivetime/notifier/automation/NextDayWorker.kt",
    "app/src/main/java/de/drivetime/notifier/automation/SingleEventWorker.kt",
]:
    content = read(rel)
    if "import de.drivetime.notifier.core.DynamicArrivalBuffer" not in content:
        content = content.replace(
            "import de.drivetime.notifier.core.DrivePlanner\n",
            "import de.drivetime.notifier.core.DrivePlanner\nimport de.drivetime.notifier.core.DynamicArrivalBuffer\n",
            1,
        )
    if rel.endswith("NextDayWorker.kt"):
        content = content.replace(
            '''                requestedBufferMinutes = settings.bufferMinutes,
                previousEventEndMillis = previousEnd''',
            '''                requestedBufferMinutes = DynamicArrivalBuffer.totalMinutes(
                    settings,
                    estimate.durationSeconds,
                    estimate.trafficDelaySeconds
                ),
                previousEventEndMillis = previousEnd''',
            1,
        )
    else:
        content = content.replace(
            '''                route.durationSeconds,
                settings.bufferMinutes,
                previousEnd''',
            '''                route.durationSeconds,
                DynamicArrivalBuffer.totalMinutes(settings, route.durationSeconds, route.trafficDelaySeconds),
                previousEnd''',
            1,
        )
    p(rel).write_text(content, encoding="utf-8")

# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------
write("app/src/test/java/de/drivetime/notifier/data/ChargingPreferencesTest.kt", r'''
package de.drivetime.notifier.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ChargingPreferencesTest {
    @Test
    fun connectorIdsRoundTrip() {
        ChargingConnectorPreference.entries.forEach { value ->
            assertEquals(value, ChargingConnectorPreference.fromId(value.id))
        }
    }

    @Test
    fun speedBandsMatchDocumentedBoundaries() {
        assertEquals(0, ChargingSpeedPreference.SLOW.penalty(11.0))
        assertEquals(0, ChargingSpeedPreference.MEDIUM.penalty(22.0))
        assertEquals(0, ChargingSpeedPreference.FAST.penalty(100.0))
        assertEquals(0, ChargingSpeedPreference.HPC.penalty(101.0))
    }

    @Test
    fun hpcPenalizesSlowCharging() {
        assertEquals(3, ChargingSpeedPreference.HPC.penalty(11.0))
    }
}
''')

write("app/src/test/java/de/drivetime/notifier/core/DynamicArrivalBufferTest.kt", r'''
package de.drivetime.notifier.core

import de.drivetime.notifier.data.AppSettings
import de.drivetime.notifier.data.DynamicBufferLevel
import de.drivetime.notifier.data.RoutingProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicArrivalBufferTest {
    @Test
    fun disabledMeansNoExtraBuffer() {
        val settings = AppSettings(bufferMinutes = 15, dynamicBufferEnabled = false)
        assertEquals(15, DynamicArrivalBuffer.totalMinutes(settings, 3 * 60 * 60L))
    }

    @Test
    fun longerTripsReceiveMoreReserve() {
        val settings = AppSettings(dynamicBufferEnabled = true, dynamicBufferLevel = DynamicBufferLevel.BALANCED, routingProvider = RoutingProvider.TOMTOM)
        val short = DynamicArrivalBuffer.extraMinutes(settings, 20 * 60L)
        val long = DynamicArrivalBuffer.extraMinutes(settings, 180 * 60L)
        assertTrue(long > short)
    }

    @Test
    fun staticProviderGetsSmallAdditionalUncertaintyReserve() {
        val traffic = AppSettings(dynamicBufferEnabled = true, routingProvider = RoutingProvider.TOMTOM)
        val static = AppSettings(dynamicBufferEnabled = true, routingProvider = RoutingProvider.OSRM)
        assertTrue(
            DynamicArrivalBuffer.extraMinutes(static, 90 * 60L) >=
                DynamicArrivalBuffer.extraMinutes(traffic, 90 * 60L)
        )
    }
}
''')

write("app/src/test/java/de/drivetime/notifier/routing/ChargingStationSelectorTest.kt", r'''
package de.drivetime.notifier.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingStationSelectorTest {
    @Test
    fun longStayRanksAheadOfShortStay() {
        assertTrue(ChargingStationSelector.stayPenalty("4 h") < ChargingStationSelector.stayPenalty("30 min"))
    }

    @Test
    fun parsesHourAndMinuteDurations() {
        assertEquals(120, ChargingStationSelector.parseMaxStayMinutes("2 h"))
        assertEquals(90, ChargingStationSelector.parseMaxStayMinutes("1:30"))
    }
}
''')

write("app/src/test/java/de/drivetime/notifier/update/UpdateCheckerTest.kt", r'''
package de.drivetime.notifier.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    private val checker = UpdateChecker()

    @Test
    fun semanticVersionComparisonWorks() {
        assertTrue(UpdateChecker.compareVersions("1.2.0", "1.1.9") > 0)
        assertEquals(0, UpdateChecker.compareVersions("1.1", "1.1.0"))
    }

    @Test
    fun debugSuffixIsIgnoredForComparison() {
        assertEquals("1.1.0", UpdateChecker.normalize("1.1.0-debug"))
    }

    @Test
    fun stableReleaseCanBeReportedAsNewer() {
        val result = checker.parseReleaseJson(
            "1.1.0-debug",
            true,
            """{\"tag_name\":\"v1.2.0\",\"html_url\":\"https://github.com/3115a083/drive-time-notifier/releases/tag/v1.2.0\",\"draft\":false,\"prerelease\":false}"""
        )
        assertTrue(result is UpdateCheckResult.UpdateAvailable)
    }

    @Test
    fun invalidPayloadFailsClosed() {
        assertTrue(checker.parseReleaseJson("1.1.0", false, "not-json") is UpdateCheckResult.Error)
    }
}
''')

print("Full debug feature patch applied successfully.")
