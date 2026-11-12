package de.drivetime.notifier.routing

import de.drivetime.notifier.data.ChargingConnectorPreference
import de.drivetime.notifier.debug.RequestDebugLog
import de.drivetime.notifier.network.*
import de.drivetime.notifier.network.readBytesLimited
import de.drivetime.notifier.network.awaitResponse
import de.drivetime.notifier.network.readStringLimited
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
    val registryUnavailable: Boolean = false,
    val chargingUnavailable: Boolean? = null,
    val parkingUnavailable: Boolean? = null
)

data class ParkingSearchOptions(
    val resultLimit: Int = 10,
    val maxDistanceMeters: Int = 1_500,
    val freeOnly: Boolean = false
)

private data class OverpassBatch(
    val elements: List<JSONObject>,
    val available: Boolean
)

internal enum class OsmQueryKind(val label: String) {
    CHARGING("charging stations"),
    PARKING("parking"),
    COMBINED("parking and charging stations")
}

internal data class PrioritizedOsmQuery(val kind: OsmQueryKind, val query: String)

class OsmEnrichmentClient(
    preferredEndpoint: String = DEFAULT_OVERPASS_ENDPOINT,
    configuredEndpoints: List<String> = emptyList(),
    private val splitRequests: Boolean = false,
    private val client: OkHttpClient = OverpassProtocol.client
) {
    private val endpoints = if (configuredEndpoints.isEmpty()) {
        endpointOrder(preferredEndpoint)
    } else {
        normalizeConfiguredEndpoints(configuredEndpoints)
    }



    suspend fun query(
        points: List<GeoPoint>,
        parking: ParkingSearchOptions? = null,
        charging: ChargingSearchOptions? = null
    ): OsmEnrichmentResult = withContext(Dispatchers.IO) {
        if (points.isEmpty() || (parking == null && charging == null)) {
            return@withContext OsmEnrichmentResult(emptyList(), unavailable = false)
        }
        val destination = points.last()
        // Requests are deliberately sequential. Charging has priority over parking.
        val registryDeferred = charging?.takeIf { it.useBNetzA }?.let { options ->
            async { runCatching { BNetzAChargingClient().query(destination, options.maxDistanceMeters) } }
        }
        val batches = mutableListOf<Pair<OsmQueryKind, OverpassBatch>>()
        val requestBudget=OverpassRequestBudget {android.os.SystemClock.elapsedRealtime()}
        plannedQueries(points, parking, charging).forEach { request ->
            batches += request.kind to fetchElements(request.query, request.kind, requestBudget)
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
                        val distance = haversineMeters(point, destination).roundToInt()
                        if (parking == null || distance > parking.maxDistanceMeters) continue
                        val fee = clean(tags.optString("fee"))
                        if (parking.freeOnly && !isExplicitlyFree(fee)) continue
                        add(RoutePoi(
                            point = point,
                            kind = RoutePoi.Kind.PARKING,
                            name = clean(tags.optString("name")) ?: "Parking",
                            distanceFromDestinationMeters = distance,
                            access = clean(tags.optString("access")),
                            fee = fee,
                            openingHours = clean(tags.optString("opening_hours")),
                            maxStay = clean(tags.optString("maxstay")) ?: clean(tags.optString("parking:maxstay")),
                            capacity = firstInt(tags, "capacity", "capacity:car"),
                            parkingType = clean(tags.optString("parking")) ?: clean(tags.optString("parking:condition")),
                            address = address(tags)
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
            .take(parking?.resultLimit?.coerceIn(1, 50) ?: 0)
        val chargingOut = if (charging != null) {
            val osmCharging = osmResults.filter { it.kind == RoutePoi.Kind.CHARGING_STATION }
                .distinctBy(::key)
            val registry = registryResult?.getOrDefault(emptyList()).orEmpty()
            ChargingStationSelector.mergeAndRank(osmCharging, registry, charging)
        } else emptyList()

        OsmEnrichmentResult(
            pois = chargingOut + parkingOut,
            unavailable = batches.any { !it.second.available },
            registryUnavailable = registryResult?.isFailure == true,
            chargingUnavailable = batches.filter {it.first!=OsmQueryKind.PARKING}.any {!it.second.available},
            parkingUnavailable = batches.filter {it.first!=OsmQueryKind.CHARGING}.any {!it.second.available}
        )
    }

    internal fun prioritizedQueries(
        points: List<GeoPoint>,
        parking: ParkingSearchOptions?,
        charging: ChargingSearchOptions?
    ): List<PrioritizedOsmQuery> = buildList {
        charging?.let {
            val limit = (it.resultLimit.coerceIn(1, 50) * 8).coerceIn(40, 200)
            add(PrioritizedOsmQuery(OsmQueryKind.CHARGING, wrapQuery(chargingQuery(points.last(), it.maxDistanceMeters), limit)))
        }
        parking?.let {
            val limit = (it.resultLimit.coerceIn(1, 50) * if (it.freeOnly) 8 else 4).coerceIn(30, 200)
            add(PrioritizedOsmQuery(OsmQueryKind.PARKING, wrapQuery(parkingQuery(points.last(), it.maxDistanceMeters), limit)))
        }
    }

    internal fun plannedQueries(points:List<GeoPoint>,parking:ParkingSearchOptions?,charging:ChargingSearchOptions?):List<PrioritizedOsmQuery> {
        val queries=prioritizedQueries(points,parking,charging)
        if(splitRequests || queries.size<2)return queries
        val prefix="[out:json][timeout:20][maxsize:33554432];"
        return listOf(PrioritizedOsmQuery(OsmQueryKind.COMBINED, prefix+queries.joinToString("") {it.query.removePrefix(prefix)}))
    }

    private fun wrapQuery(body: String, limit: Int) =
        "[out:json][timeout:20][maxsize:33554432];($body);out center $limit;"

    private fun parkingQuery(destination: GeoPoint, requestedRadius: Int): String {
        val box = boundingBox(destination, requestedRadius.coerceIn(100, 10_000))
        return "node($box)[\"amenity\"=\"parking\"];" +
            "way($box)[\"amenity\"=\"parking\"];" +
            "relation($box)[\"amenity\"=\"parking\"];"
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

    private suspend fun fetchElements(query:String,kind:OsmQueryKind,requestBudget:OverpassRequestBudget):OverpassBatch {
        val cacheKey=endpoints.joinToString("|") {EndpointSecurity.fingerprint(it)}+"|split=$splitRequests|$kind|"+query
        val cached=responseCache.get(cacheKey)
        if(cached!=null) {
            RequestDebugLog.add("Overpass",kind.label,0,"cache","Using a recent bounded response.")
            return batch(cached)
        }
        val remaining=requestBudget.remaining()
        if(remaining==0L)return OverpassBatch(emptyList(),false)
        val bytes=kotlinx.coroutines.withTimeoutOrNull(remaining) {
            responseCache.getOrLoad(cacheKey) { fetchResponse(query,kind,requestBudget) }
        }
        return if(bytes==null) OverpassBatch(emptyList(),false) else batch(bytes)
    }

    private fun batch(bytes:ByteArray):OverpassBatch {
        val array=OverpassProtocol.parse(String(bytes,Charsets.UTF_8)).getJSONArray("elements")
        return OverpassBatch(buildList {for(i in 0 until array.length())array.optJSONObject(i)?.let(::add)},true)
    }

    private suspend fun fetchResponse(query:String,kind:OsmQueryKind,requestBudget:OverpassRequestBudget):ByteArray? {
        // Preserve the explicit list order. Never probe or fan out to the other servers.
        val ordered=requestEndpointOrder(endpoints,kind,splitRequests)
        for(endpoint in ordered) {
            val fingerprint=EndpointSecurity.fingerprint(endpoint)
            if(!endpointPolicy.available(fingerprint))continue
            val timeout=requestBudget.claim() ?: break
            val started=System.nanoTime()
            val at=System.currentTimeMillis()
            var rateLimited=false
            var retryAfter:Long?=null
            val attempt=runCatching {
                val request=OverpassProtocol.request(endpoint,query)
                val boundedClient=client.newBuilder().callTimeout(timeout,TimeUnit.MILLISECONDS).build()
                boundedClient.newCall(request).awaitResponse().use {response ->
                    if(!response.isSuccessful) {
                        rateLimited=response.code in listOf(429,406)
                        retryAfter=response.header("Retry-After")?.toLongOrNull()
                        // A server can reflect encoded credentials. Keep all response bodies out of error logs.
                        error("HTTP ${response.code}")
                    }
                    val bytes=response.body.readBytesLimited(MAX_RESPONSE_BYTES)
                    check(requestBudget.remaining()>0) { "Overpass budget elapsed" }
                    OverpassProtocol.parse(String(bytes,Charsets.UTF_8))
                    bytes
                }
            }
            attempt.exceptionOrNull()?.let {if(it is kotlinx.coroutines.CancellationException)throw it}
            val duration=(System.nanoTime()-started)/1_000_000
            val value=attempt.getOrNull()
            ServiceMonitor.record(ServiceMonitor.endpointId("Overpass",endpoint),fingerprint,value!=null,duration,at)
            if(value!=null) {
                endpointPolicy.success(fingerprint)
                RequestDebugLog.add("Overpass","${kind.label} via $endpoint",duration,"success","${value.size} bytes")
                return value
            }
            endpointPolicy.failed(fingerprint,retryAfter,rateLimited)
            RequestDebugLog.add("Overpass","${kind.label} via $endpoint",duration,"failed",
                attempt.exceptionOrNull()?.let {if(EndpointSecurity.configured(endpoint))it.javaClass.simpleName else "${it.javaClass.simpleName}: ${it.message.orEmpty()}"}.orEmpty())
        }
        return null
    }

    private fun isCharging(tags: JSONObject): Boolean =
        tags.optString("amenity") == "charging_station" ||
            (tags.optString("amenity") == "fuel" && tags.optString("fuel:electricity").equals("yes", true))

    private fun isExplicitlyFree(raw: String?): Boolean = raw?.trim()?.lowercase() in
        setOf("no", "free", "0", "none", "nein", "kostenlos")

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
        private val responseCache = OverpassResponseCache(now={android.os.SystemClock.elapsedRealtime()})
        private val endpointPolicy = OverpassEndpointPolicy {android.os.SystemClock.elapsedRealtime()}
        const val DEFAULT_OVERPASS_ENDPOINT = "https://overpass-api.de/api/interpreter"
        private val FALLBACK_OVERPASS_ENDPOINTS = listOf(
            "https://overpass.private.coffee/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
        )
        internal fun endpointOrder(preferredEndpoint: String): List<String> {
            val preferred = de.drivetime.notifier.network.HttpsEndpoint.normalize(preferredEndpoint)
                ?: DEFAULT_OVERPASS_ENDPOINT
            return (listOf(preferred, DEFAULT_OVERPASS_ENDPOINT) + FALLBACK_OVERPASS_ENDPOINTS).distinct()
        }

        internal fun normalizeConfiguredEndpoints(endpoints: List<String>): List<String> = endpoints
            .mapNotNull(de.drivetime.notifier.network.HttpsEndpoint::normalize)
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
