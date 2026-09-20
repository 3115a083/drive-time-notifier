package de.drivetime.notifier.routing

import de.drivetime.notifier.data.ChargingConnectorPreference
import de.drivetime.notifier.debug.RequestDebugLog
import de.drivetime.notifier.network.readBytesLimited
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
    enum class Kind { PARKING, CHARGING_STATION }
}

data class OsmEnrichmentResult(
    val pois: List<RoutePoi>,
    val unavailable: Boolean,
    val registryUnavailable: Boolean = false
)

private data class OverpassBatch(
    val elements: List<JSONObject>,
    val available: Boolean
)

internal enum class OsmQueryKind(val label: String) {
    CHARGING("charging stations"),
    PARKING("parking")
}

internal data class PrioritizedOsmQuery(val kind: OsmQueryKind, val query: String)

class OsmEnrichmentClient(
    preferredEndpoint: String = DEFAULT_OVERPASS_ENDPOINT,
    configuredEndpoints: List<String> = emptyList(),
    private val splitRequests: Boolean = false,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(9, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()
) {
    private val endpoints = if (configuredEndpoints.isEmpty()) {
        endpointOrder(preferredEndpoint)
    } else {
        normalizeConfiguredEndpoints(configuredEndpoints)
    }
    private var lastSuccessfulEndpoint: String? = null
    private val failedEndpoints = mutableSetOf<String>()

    suspend fun query(
        points: List<GeoPoint>,
        parking: Boolean,
        charging: ChargingSearchOptions? = null
    ): OsmEnrichmentResult = withContext(Dispatchers.IO) {
        if (points.isEmpty() || (!parking && charging == null)) {
            return@withContext OsmEnrichmentResult(emptyList(), unavailable = false)
        }
        val destination = points.last()
        // Requests are deliberately sequential. Charging has priority over parking.
        val registryDeferred = charging?.takeIf { it.useBNetzA }?.let { options ->
            async { runCatching { BNetzAChargingClient().query(destination, options.maxDistanceMeters) } }
        }
        val batches = mutableListOf<Pair<OsmQueryKind, OverpassBatch>>()
        prioritizedQueries(points, parking, charging).forEach { request ->
            batches += request.kind to fetchElements(request.query, request.kind)
        }
        val registryResult = registryDeferred?.await()
        val osmResults = buildList {
            for (e in batches.flatMap { it.second.elements }) {
                val tags = e.optJSONObject("tags") ?: continue
                val center = e.optJSONObject("center")
                val lat = if (e.has("lat")) e.optDouble("lat") else center?.optDouble("lat") ?: continue
                val lon = if (e.has("lon")) e.optDouble("lon") else center?.optDouble("lon") ?: continue
                if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
                val point = GeoPoint(lat, lon)
                when {
                    tags.optString("amenity") == "parking" -> {
                        add(RoutePoi(
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
                        ))
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
                        add(RoutePoi(
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
                        ))
                    }
                }
            }
        }

        val parkingOut = osmResults.filter { it.kind == RoutePoi.Kind.PARKING }
            .distinctBy(::key)
            .sortedBy { it.distanceFromDestinationMeters ?: Int.MAX_VALUE }
            .take(30)
        val chargingOut = if (charging != null) {
            val osmCharging = osmResults.filter { it.kind == RoutePoi.Kind.CHARGING_STATION }
                .distinctBy(::key)
            val registry = registryResult?.getOrDefault(emptyList()).orEmpty()
            ChargingStationSelector.mergeAndRank(osmCharging, registry, charging)
        } else emptyList()

        OsmEnrichmentResult(
            pois = chargingOut + parkingOut,
            unavailable = batches.any { !it.second.available },
            registryUnavailable = registryResult?.isFailure == true
        )
    }

    internal fun prioritizedQueries(
        points: List<GeoPoint>,
        parking: Boolean,
        charging: ChargingSearchOptions?
    ): List<PrioritizedOsmQuery> = buildList {
        charging?.let {
            add(PrioritizedOsmQuery(OsmQueryKind.CHARGING, wrapQuery(chargingQuery(points.last(), it.maxDistanceMeters), 200)))
        }
        if (parking) add(PrioritizedOsmQuery(OsmQueryKind.PARKING, wrapQuery(parkingQuery(points.last()), 100)))
    }

    private fun wrapQuery(body: String, limit: Int) =
        "[out:json][timeout:7][maxsize:8388608];($body);out center $limit;"

    private fun parkingQuery(destination: GeoPoint): String {
        val box = boundingBox(destination, 1_400)
        return "node($box)[\"amenity\"=\"parking\"];" +
            "way($box)[\"amenity\"=\"parking\"];"
    }

    private fun chargingQuery(destination: GeoPoint, requestedRadius: Int): String {
        val radius = requestedRadius.coerceIn(100, 10_000)
        val box = boundingBox(destination, radius)
        return "node($box)[\"amenity\"=\"charging_station\"];" +
            "way($box)[\"amenity\"=\"charging_station\"];" +
            "node($box)[\"amenity\"=\"fuel\"][\"fuel:electricity\"=\"yes\"];"
    }

    private fun boundingBox(center: GeoPoint, radiusMeters: Int): String {
        val latDelta = radiusMeters / 111_320.0
        val longitudeScale = (111_320.0 * cos(Math.toRadians(center.latitude))).coerceAtLeast(1.0)
        val lonDelta = radiusMeters / longitudeScale
        return "%.6f,%.6f,%.6f,%.6f".format(
            Locale.ROOT,
            (center.latitude - latDelta).coerceAtLeast(-90.0),
            (center.longitude - lonDelta).coerceAtLeast(-180.0),
            (center.latitude + latDelta).coerceAtMost(90.0),
            (center.longitude + lonDelta).coerceAtMost(180.0)
        )
    }

    private fun fetchElements(query: String, kind: OsmQueryKind): OverpassBatch {
        val preferred = lastSuccessfulEndpoint
        val splitOrder = requestEndpointOrder(endpoints, kind, splitRequests)
        val preferredOrder = if (splitRequests || preferred == null || preferred !in splitOrder) splitOrder else {
            listOf(preferred) + endpoints.filterNot { it == preferred }
        }
        val now = System.currentTimeMillis()
        val orderedEndpoints = preferredOrder.filterNot {
            it in failedEndpoints || (rateLimitCooldownUntil[it] ?: 0L) > now
        }
        if (orderedEndpoints.isEmpty()) {
            RequestDebugLog.add(
                "Overpass",
                kind.label,
                0L,
                "skipped",
                "All configured endpoints already failed during this loading pass. Use Retry to start a new pass."
            )
            return OverpassBatch(emptyList(), available = false)
        }
        for (endpoint in orderedEndpoints) {
            val started = System.nanoTime()
            var rateLimited = false
            val attempt = runCatching {
                val request = Request.Builder()
                    .url(endpoint)
                    .header("User-Agent", "DriveTimeNotifier/1.1 (+https://github.com/3115a083/drive-time-notifier)")
                    .header("Accept-Encoding", "identity")
                    .header("Connection", "close")
                    .post(FormBody.Builder().add("data", query).build())
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        rateLimited = response.code == 429
                        val detail = response.body?.string().orEmpty().replace(Regex("\\s+"), " ").take(1_200)
                        error("HTTP ${response.code}${if (detail.isBlank()) "" else ": $detail"}")
                    }
                    val body = response.body ?: error("Overpass returned no body")
                    check(body.contentLength() <= MAX_RESPONSE_BYTES) { "Overpass response too large" }
                    val bytes = body.readBytesLimited(MAX_RESPONSE_BYTES)
                    val array = JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("elements")
                        ?: error("Overpass response contains no elements array")
                    val elements = buildList {
                        for (i in 0 until array.length()) array.optJSONObject(i)?.let(::add)
                    }
                    elements to "HTTP ${response.code}, ${bytes.size} bytes, ${elements.size} elements"
                }
            }
            val duration = (System.nanoTime() - started) / 1_000_000L
            val value = attempt.getOrNull()
            if (value != null) {
                val (elements, detail) = value
                RequestDebugLog.add("Overpass", "${kind.label} via $endpoint", duration, "success", detail)
                lastSuccessfulEndpoint = endpoint
                return OverpassBatch(elements, available = true)
            }
            RequestDebugLog.add(
                "Overpass",
                "${kind.label} via $endpoint",
                duration,
                "failed",
                attempt.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" } ?: "unknown error"
            )
            failedEndpoints += endpoint
            if (rateLimited) {
                rateLimitCooldownUntil[endpoint] = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MILLIS
                RequestDebugLog.add(
                    "Overpass cooldown",
                    endpoint,
                    0L,
                    "active",
                    "HTTP 429: endpoint paused for ${RATE_LIMIT_COOLDOWN_MILLIS / 1_000L} seconds"
                )
            }
        }
        return OverpassBatch(emptyList(), available = false)
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

    private fun clean(value: String?): String? = value
        ?.filterNot { it.isISOControl() }
        ?.trim()
        ?.take(MAX_REMOTE_TEXT_LENGTH)
        ?.takeIf { it.isNotEmpty() && !it.equals("null", true) && !it.equals("none", true) }

    private fun key(poi: RoutePoi) = "%.5f,%.5f".format(Locale.ROOT, poi.point.latitude, poi.point.longitude)

    private fun haversineMeters(a: GeoPoint, b: GeoPoint): Double =
        ChargingStationSelector.haversineMeters(a, b)

    companion object {
        private const val MAX_RESPONSE_BYTES = 4_000_000L
        private const val MAX_REMOTE_TEXT_LENGTH = 240
        private const val RATE_LIMIT_COOLDOWN_MILLIS = 60_000L
        private val rateLimitCooldownUntil = ConcurrentHashMap<String, Long>()
        const val DEFAULT_OVERPASS_ENDPOINT = "https://overpass-api.de/api/interpreter"
        private val FALLBACK_OVERPASS_ENDPOINTS = listOf(
            "https://overpass.private.coffee/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
        )
        internal fun endpointOrder(preferredEndpoint: String): List<String> {
            val preferred = preferredEndpoint.trim().removeSuffix("/")
                .takeIf { it.startsWith("https://") && it.length <= 240 }
                ?: DEFAULT_OVERPASS_ENDPOINT
            return (listOf(preferred, DEFAULT_OVERPASS_ENDPOINT) + FALLBACK_OVERPASS_ENDPOINTS).distinct()
        }

        internal fun normalizeConfiguredEndpoints(endpoints: List<String>): List<String> = endpoints
            .map { it.trim().removeSuffix("/") }
            .filter { it.startsWith("https://") && it.length <= 240 }
            .distinct()
            .take(8)
            .ifEmpty { listOf(DEFAULT_OVERPASS_ENDPOINT) }

        internal fun requestEndpointOrder(
            endpoints: List<String>,
            kind: OsmQueryKind,
            splitRequests: Boolean
        ): List<String> {
            if (!splitRequests || endpoints.size < 2) return endpoints
            val startIndex = if (kind == OsmQueryKind.CHARGING) 0 else 1
            return endpoints.drop(startIndex) + endpoints.take(startIndex)
        }
    }
}
