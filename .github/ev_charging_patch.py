from pathlib import Path
import re


def replace_once(path: str, old: str, new: str, expected: int = 1):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != expected:
        raise SystemExit(f"{path}: expected {expected} occurrences, found {count}: {old[:80]!r}")
    p.write_text(text.replace(old, new))


# Settings storage
settings = "app/src/main/java/de/drivetime/notifier/data/SettingsStore.kt"
replace_once(settings,
'''    val showSpeedCameras: Boolean = false,
    val showParking: Boolean = false,
    val targetCalendarId: Long = -1L,''',
'''    val showSpeedCameras: Boolean = false,
    val showParking: Boolean = false,
    val showChargingStations: Boolean = false,
    val chargingConnector: ChargingConnectorPreference = ChargingConnectorPreference.ANY,
    val chargingMaxDistanceMeters: Int = 1_500,
    val chargingSpeedPreference: ChargingSpeedPreference = ChargingSpeedPreference.ANY,
    val chargingPreferredOperator: String = "",
    val chargingShowOtherOperators: Boolean = true,
    val chargingUseBNetzA: Boolean = false,
    val chargingNavigateViaStation: Boolean = false,
    val targetCalendarId: Long = -1L,''')
replace_once(settings,
'''        val CAMERAS = booleanPreferencesKey("show_speed_cameras")
        val PARKING = booleanPreferencesKey("show_parking")
        val TARGET = longPreferencesKey("target_calendar_id")''',
'''        val CAMERAS = booleanPreferencesKey("show_speed_cameras")
        val PARKING = booleanPreferencesKey("show_parking")
        val CHARGING = booleanPreferencesKey("show_charging_stations")
        val CHARGING_CONNECTOR = stringPreferencesKey("charging_connector")
        val CHARGING_MAX_DISTANCE = intPreferencesKey("charging_max_distance_meters")
        val CHARGING_SPEED = stringPreferencesKey("charging_speed_preference")
        val CHARGING_OPERATOR = stringPreferencesKey("charging_preferred_operator")
        val CHARGING_OTHER_OPERATORS = booleanPreferencesKey("charging_show_other_operators")
        val CHARGING_BNETZA = booleanPreferencesKey("charging_use_bnetza")
        val CHARGING_NAVIGATE = booleanPreferencesKey("charging_navigate_via_station")
        val TARGET = longPreferencesKey("target_calendar_id")''')
replace_once(settings,
'''            showSpeedCameras = p[K.CAMERAS] ?: false,
            showParking = p[K.PARKING] ?: false,
            targetCalendarId = p[K.TARGET] ?: -1L,''',
'''            showSpeedCameras = p[K.CAMERAS] ?: false,
            showParking = p[K.PARKING] ?: false,
            showChargingStations = p[K.CHARGING] ?: false,
            chargingConnector = ChargingConnectorPreference.fromId(p[K.CHARGING_CONNECTOR]),
            chargingMaxDistanceMeters = (p[K.CHARGING_MAX_DISTANCE] ?: 1_500).coerceIn(100, 10_000),
            chargingSpeedPreference = ChargingSpeedPreference.fromId(p[K.CHARGING_SPEED]),
            chargingPreferredOperator = p[K.CHARGING_OPERATOR].orEmpty(),
            chargingShowOtherOperators = p[K.CHARGING_OTHER_OPERATORS] ?: true,
            chargingUseBNetzA = p[K.CHARGING_BNETZA] ?: false,
            chargingNavigateViaStation = p[K.CHARGING_NAVIGATE] ?: false,
            targetCalendarId = p[K.TARGET] ?: -1L,''')
replace_once(settings,
'''        p[K.CAMERAS] = s.showSpeedCameras
        p[K.PARKING] = s.showParking
        p[K.TARGET] = s.targetCalendarId''',
'''        p[K.CAMERAS] = s.showSpeedCameras
        p[K.PARKING] = s.showParking
        p[K.CHARGING] = s.showChargingStations
        p[K.CHARGING_CONNECTOR] = s.chargingConnector.id
        p[K.CHARGING_MAX_DISTANCE] = s.chargingMaxDistanceMeters.coerceIn(100, 10_000)
        p[K.CHARGING_SPEED] = s.chargingSpeedPreference.id
        p[K.CHARGING_OPERATOR] = s.chargingPreferredOperator.trim().take(80)
        p[K.CHARGING_OTHER_OPERATORS] = s.chargingShowOtherOperators
        p[K.CHARGING_BNETZA] = s.chargingUseBNetzA
        p[K.CHARGING_NAVIGATE] = s.chargingNavigateViaStation
        p[K.TARGET] = s.targetCalendarId''')

# Backup persistence
backup = "app/src/main/java/de/drivetime/notifier/security/PasswordBackup.kt"
replace_once(backup,
'''        put("showSpeedCameras", s.showSpeedCameras)
        put("showParking", s.showParking)
        put("targetCalendarId", s.targetCalendarId)''',
'''        put("showSpeedCameras", s.showSpeedCameras)
        put("showParking", s.showParking)
        put("showChargingStations", s.showChargingStations)
        put("chargingConnector", s.chargingConnector.id)
        put("chargingMaxDistanceMeters", s.chargingMaxDistanceMeters)
        put("chargingSpeedPreference", s.chargingSpeedPreference.id)
        put("chargingPreferredOperator", s.chargingPreferredOperator)
        put("chargingShowOtherOperators", s.chargingShowOtherOperators)
        put("chargingUseBNetzA", s.chargingUseBNetzA)
        put("chargingNavigateViaStation", s.chargingNavigateViaStation)
        put("targetCalendarId", s.targetCalendarId)''')
replace_once(backup,
'''            showSpeedCameras = j.optBoolean("showSpeedCameras", defaults.showSpeedCameras),
            showParking = j.optBoolean("showParking", defaults.showParking),
            targetCalendarId = j.optLong("targetCalendarId", defaults.targetCalendarId),''',
'''            showSpeedCameras = j.optBoolean("showSpeedCameras", defaults.showSpeedCameras),
            showParking = j.optBoolean("showParking", defaults.showParking),
            showChargingStations = j.optBoolean("showChargingStations", defaults.showChargingStations),
            chargingConnector = ChargingConnectorPreference.fromId(j.optString("chargingConnector", defaults.chargingConnector.id)),
            chargingMaxDistanceMeters = j.optInt("chargingMaxDistanceMeters", defaults.chargingMaxDistanceMeters).coerceIn(100, 10_000),
            chargingSpeedPreference = ChargingSpeedPreference.fromId(j.optString("chargingSpeedPreference", defaults.chargingSpeedPreference.id)),
            chargingPreferredOperator = j.optString("chargingPreferredOperator", defaults.chargingPreferredOperator),
            chargingShowOtherOperators = j.optBoolean("chargingShowOtherOperators", defaults.chargingShowOtherOperators),
            chargingUseBNetzA = j.optBoolean("chargingUseBNetzA", defaults.chargingUseBNetzA),
            chargingNavigateViaStation = j.optBoolean("chargingNavigateViaStation", defaults.chargingNavigateViaStation),
            targetCalendarId = j.optLong("targetCalendarId", defaults.targetCalendarId),''')

# Rewrite OSM enrichment with charging support.
Path("app/src/main/java/de/drivetime/notifier/routing/OsmEnrichmentClient.kt").write_text(r'''package de.drivetime.notifier.routing

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
''')

# Rewrite calendar description with charging list before parking and optional navigation via charger.
Path("app/src/main/java/de/drivetime/notifier/calendar/DriveEventDescriptionBuilder.kt").write_text(r'''package de.drivetime.notifier.calendar

import de.drivetime.notifier.data.AppLanguage
import de.drivetime.notifier.data.ChargingConnectorPreference
import de.drivetime.notifier.data.RoutingProvider
import de.drivetime.notifier.model.RouteEstimate
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
        navigateViaChargingStation: Boolean = false
    ): String {
        val lat = route.destinationLatitude
        val lon = route.destinationLongitude
        val encodedDestination = URLEncoder.encode(destination, StandardCharsets.UTF_8.toString())
        val charging = pois.filter { it.kind == RoutePoi.Kind.CHARGING_STATION }
            .sortedBy { it.distanceFromDestinationMeters ?: Int.MAX_VALUE }
            .take(5)
        val navigationStation = charging.firstOrNull().takeIf { navigateViaChargingStation }
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
                val walkingDestination = if (lat != null && lon != null) "$lat,$lon" else encodedDestination
                val walking = "https://www.google.com/maps/dir/?api=1&origin=$pLat,$pLon&destination=$walkingDestination&travelmode=walking"
                appendLine("${tr(language, "Navigation target", "Navigationsziel")}: ${navigationStation.name ?: tr(language, "Charging station", "Ladestation")}")
                appendLine("${tr(language, "Then walk to the appointment destination", "Danach zu Fuß zum Terminziel")}: $walking")
            }

            if (charging.isNotEmpty()) {
                appendLine()
                appendLine(tr(language, "Nearby public charging stations:", "Öffentliche Ladesäulen in Zielnähe:"))
                charging.forEachIndexed { index, poi ->
                    val distance = poi.distanceFromDestinationMeters ?: 0
                    val pLat = poi.point.latitude
                    val pLon = poi.point.longitude
                    val link = "https://www.google.com/maps/dir/?api=1&destination=$pLat,$pLon&travelmode=driving"
                    val details = buildList {
                        poi.operator?.takeIf { it.isNotBlank() }?.let(::add)
                        if (poi.connectorTypes.isNotEmpty()) {
                            add(poi.connectorTypes.joinToString("/") { connectorLabel(language, it) })
                        }
                        poi.maxPowerKw?.let { power ->
                            val formatted = if (power % 1.0 == 0.0) power.toInt().toString() else "%.1f".format(power)
                            add("$formatted kW")
                        }
                        poi.openingHours?.takeIf { it.isNotBlank() }?.let(::add)
                        add("~$distance m ${tr(language, "from destination", "vom Ziel")}")
                    }
                    appendLine("${index + 1}. ${poi.name ?: tr(language, "Charging station", "Ladestation")} · ${details.joinToString(" · ")}: $link")
                }
                val hasOsm = charging.any { RoutePoiSource.OSM in it.sources }
                val hasBNetzA = charging.any { RoutePoiSource.BUNDESNETZAGENTUR in it.sources }
                if (hasOsm) appendLine(tr(language, "Charging data: OpenStreetMap amenity=charging_station via Overpass; restricted access tags are excluded.", "Ladedaten: OpenStreetMap amenity=charging_station über Overpass; als eingeschränkt markierte Zugänge werden ausgeschlossen."))
                if (hasBNetzA) appendLine(tr(language, "Registry enrichment: Bundesnetzagentur.de data, CC BY 4.0, accessed through the public Esri feature service.", "Register-Anreicherung: Daten von Bundesnetzagentur.de, CC BY 4.0, abgerufen über den öffentlichen Esri-Feature-Service."))
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
                    appendLine("${index + 1}. ${poi.name ?: tr(language, "Parking", "Parkplatz")} (~$walk m ${tr(language, "walk", "Fußweg")}): $link")
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

    private fun connectorLabel(language: AppLanguage, connector: ChargingConnectorPreference): String = when (connector) {
        ChargingConnectorPreference.CCS -> "CCS"
        ChargingConnectorPreference.TYPE2 -> tr(language, "Type 2", "Typ 2")
        ChargingConnectorPreference.CHADEMO -> "CHAdeMO"
        ChargingConnectorPreference.ANY -> tr(language, "Any connector", "Beliebiger Stecker")
    }
}
''')

# Automation workers
for worker in [
    "app/src/main/java/de/drivetime/notifier/automation/SingleEventWorker.kt",
    "app/src/main/java/de/drivetime/notifier/automation/NextDayWorker.kt",
]:
    p = Path(worker)
    text = p.read_text()
    if "import de.drivetime.notifier.routing.ChargingSearchOptions" not in text:
        text = text.replace(
            "import de.drivetime.notifier.routing.OsmEnrichmentClient\n",
            "import de.drivetime.notifier.routing.ChargingSearchOptions\nimport de.drivetime.notifier.routing.OsmEnrichmentClient\n"
        )
    text = text.replace(
        "settings.showSpeedCameras || settings.showParking",
        "settings.showSpeedCameras || settings.showParking || settings.showChargingStations"
    )
    text = text.replace(
        "OsmEnrichmentClient().query(points, settings.showSpeedCameras, settings.showParking)",
        "OsmEnrichmentClient().query(points, settings.showSpeedCameras, settings.showParking, ChargingSearchOptions.from(settings))"
    )
    if worker.endswith("SingleEventWorker.kt"):
        old = """                    route,\n                    pois\n                ),"""
        new = """                    route,\n                    pois,\n                    settings.chargingNavigateViaStation\n                ),"""
    else:
        old = """                    estimate,\n                    pois\n                ),"""
        new = """                    estimate,\n                    pois,\n                    settings.chargingNavigateViaStation\n                ),"""
    if old not in text:
        raise SystemExit(f"{worker}: description builder tail not found")
    text = text.replace(old, new, 1)
    p.write_text(text)

# Main activity routing enrichment.
main = "app/src/main/java/de/drivetime/notifier/MainActivity.kt"
replace_once(main,
'''            val routePois = if ((settings.showSpeedCameras || settings.showParking) && points.size >= 2) {
                withTimeoutOrNull(13_000) {
                    OsmEnrichmentClient().query(points, settings.showSpeedCameras, settings.showParking)
                }.orEmpty()
            } else emptyList()''',
'''            val routePois = if ((settings.showSpeedCameras || settings.showParking || settings.showChargingStations) && points.size >= 2) {
                withTimeoutOrNull(22_000) {
                    OsmEnrichmentClient().query(
                        points,
                        settings.showSpeedCameras,
                        settings.showParking,
                        ChargingSearchOptions.from(settings)
                    )
                }.orEmpty()
            } else emptyList()''')

# Main activity: pass charging-navigation preference to both description-builder calls.
p = Path(main)
text = p.read_text()
pattern = re.compile(r'''(DriveEventDescriptionBuilder\.build\(\s*settings\.language,\s*settings\.routingProvider,\s*origin,\s*destination,\s*route,\s*pois)(\s*\))''', re.S)
text, count = pattern.subn(r'''\1,\n                            settings.chargingNavigateViaStation\2''', text)
if count != 2:
    raise SystemExit(f"MainActivity: expected 2 description builder calls, patched {count}")
p.write_text(text)

# Summary count before parking.
replace_once(main,
'''            if (settings.showParking) {
                Text(
                    tr(
                        settings.language,
                        "Nearby parking: ${pois.count { it.kind == RoutePoi.Kind.PARKING }} results, sorted by approximate walking distance.",''',
'''            if (settings.showChargingStations) {
                Text(
                    tr(
                        settings.language,
                        "Nearby public charging stations: ${pois.count { it.kind == RoutePoi.Kind.CHARGING_STATION }} results.",
                        "Öffentliche Ladesäulen in Zielnähe: ${pois.count { it.kind == RoutePoi.Kind.CHARGING_STATION }} Treffer."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (settings.showParking) {
                Text(
                    tr(
                        settings.language,
                        "Nearby parking: ${pois.count { it.kind == RoutePoi.Kind.PARKING }} results, sorted by approximate walking distance.",''')

# Map marker titles and icons.
replace_once(main,
'''                                title = if (poi.kind == RoutePoi.Kind.SPEED_CAMERA) {
                                    tr(settings.language, "Speed camera", "Blitzer")
                                } else {
                                    poi.name ?: tr(settings.language, "Parking", "Parkplatz")
                                }
                                icon = ContextCompat.getDrawable(
                                    map.context,
                                    if (poi.kind == RoutePoi.Kind.SPEED_CAMERA) {
                                        R.drawable.ic_speed_camera_marker
                                    } else {
                                        R.drawable.ic_parking_marker
                                    }
                                )''',
'''                                title = when (poi.kind) {
                                    RoutePoi.Kind.SPEED_CAMERA -> tr(settings.language, "Speed camera", "Blitzer")
                                    RoutePoi.Kind.PARKING -> poi.name ?: tr(settings.language, "Parking", "Parkplatz")
                                    RoutePoi.Kind.CHARGING_STATION -> poi.name ?: tr(settings.language, "Charging station", "Ladestation")
                                }
                                icon = ContextCompat.getDrawable(
                                    map.context,
                                    when (poi.kind) {
                                        RoutePoi.Kind.SPEED_CAMERA -> R.drawable.ic_speed_camera_marker
                                        RoutePoi.Kind.PARKING -> R.drawable.ic_parking_marker
                                        RoutePoi.Kind.CHARGING_STATION -> R.drawable.ic_charging_marker
                                    }
                                )''')

# Settings UI below parking.
replace_once(main,
'''                SettingSwitch(
                    tr(settings.language, "Find parking near destination", "Parkplätze am Ziel suchen"),
                    settings.showParking
                ) { onChange(settings.copy(showParking = it)) }
                SettingSwitch(
                    tr(settings.language, "Export ICS instead of calendar event", "ICS statt Kalendereintrag erzeugen"),''',
'''                SettingSwitch(
                    tr(settings.language, "Find parking near destination", "Parkplätze am Ziel suchen"),
                    settings.showParking
                ) { onChange(settings.copy(showParking = it)) }
                SettingSwitch(
                    tr(settings.language, "Find charging stations near destination", "Ladesäulen am Ziel suchen"),
                    settings.showChargingStations
                ) { onChange(settings.copy(showChargingStations = it)) }
                if (settings.showChargingStations) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                tr(settings.language, "Charging-station search", "Ladesäulensuche"),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                tr(
                                    settings.language,
                                    "Only stations not marked as private or restricted are shown. Bundesnetzagentur registry entries are public charging infrastructure by definition.",
                                    "Es werden nur Stationen angezeigt, die nicht als privat oder eingeschränkt markiert sind. Einträge des Bundesnetzagentur-Registers sind per Definition öffentliche Ladeinfrastruktur."
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(tr(settings.language, "Connector", "Steckertyp"), style = MaterialTheme.typography.labelLarge)
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                ChargingConnectorPreference.entries.forEach { connector ->
                                    FilterChip(
                                        selected = settings.chargingConnector == connector,
                                        onClick = { onChange(settings.copy(chargingConnector = connector)) },
                                        label = {
                                            Text(
                                                when (connector) {
                                                    ChargingConnectorPreference.ANY -> tr(settings.language, "Any", "Beliebig")
                                                    ChargingConnectorPreference.CCS -> "CCS"
                                                    ChargingConnectorPreference.TYPE2 -> tr(settings.language, "Type 2", "Typ 2")
                                                    ChargingConnectorPreference.CHADEMO -> "CHAdeMO"
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                            NumberDraftField(
                                initialValue = settings.chargingMaxDistanceMeters,
                                label = tr(settings.language, "Maximum distance from destination (m)", "Maximale Entfernung vom Ziel (m)"),
                                onValid = { onChange(settings.copy(chargingMaxDistanceMeters = it.coerceIn(100, 10_000))) }
                            )
                            Text(tr(settings.language, "Preferred charging speed", "Bevorzugte Ladegeschwindigkeit"), style = MaterialTheme.typography.labelLarge)
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                ChargingSpeedPreference.entries.forEach { speed ->
                                    FilterChip(
                                        selected = settings.chargingSpeedPreference == speed,
                                        onClick = { onChange(settings.copy(chargingSpeedPreference = speed)) },
                                        label = {
                                            Text(
                                                when (speed) {
                                                    ChargingSpeedPreference.ANY -> tr(settings.language, "Any", "Beliebig")
                                                    ChargingSpeedPreference.SLOW -> tr(settings.language, "Slow ≤22 kW", "Langsam ≤22 kW")
                                                    ChargingSpeedPreference.MEDIUM -> tr(settings.language, "Medium 23–99 kW", "Mittel 23–99 kW")
                                                    ChargingSpeedPreference.FAST -> tr(settings.language, "Fast 100–149 kW", "Schnell 100–149 kW")
                                                    ChargingSpeedPreference.HPC -> "HPC ≥150 kW"
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = settings.chargingPreferredOperator,
                                onValueChange = { onChange(settings.copy(chargingPreferredOperator = it.take(80))) },
                                label = { Text(tr(settings.language, "Preferred network/operator (optional)", "Bevorzugter Netzbetreiber (optional)")) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            SettingSwitch(
                                tr(settings.language, "Show other providers as well", "Andere Anbieter ebenfalls anzeigen"),
                                settings.chargingShowOtherOperators
                            ) { onChange(settings.copy(chargingShowOtherOperators = it)) }
                            SettingSwitch(
                                tr(settings.language, "Enrich with Bundesnetzagentur registry data", "Mit Bundesnetzagentur-Daten abgleichen"),
                                settings.chargingUseBNetzA
                            ) { onChange(settings.copy(chargingUseBNetzA = it)) }
                            Text(
                                tr(
                                    settings.language,
                                    "Optional registry enrichment uses Bundesnetzagentur data under CC BY 4.0 through a public Esri feature service. No API key is required.",
                                    "Der optionale Register-Abgleich verwendet Daten der Bundesnetzagentur unter CC BY 4.0 über einen öffentlichen Esri-Feature-Service. Es ist kein API-Key nötig."
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            SettingSwitch(
                                tr(settings.language, "Navigate to the nearest matching charger, then walk to the appointment", "Zur nächsten passenden Ladestation navigieren und von dort zum Termin laufen"),
                                settings.chargingNavigateViaStation
                            ) { onChange(settings.copy(chargingNavigateViaStation = it)) }
                            Text(
                                tr(
                                    settings.language,
                                    "The calendar entry keeps the original appointment destination. Its navigation link points to the first matching charger and adds a walking link to the destination.",
                                    "Der Kalendereintrag behält das ursprüngliche Terminziel. Der Navigationslink führt zur ersten passenden Ladestation und ergänzt einen Fußweg-Link zum Ziel."
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                SettingSwitch(
                    tr(settings.language, "Export ICS instead of calendar event", "ICS statt Kalendereintrag erzeugen"),''')

print("EV charging patch applied")
