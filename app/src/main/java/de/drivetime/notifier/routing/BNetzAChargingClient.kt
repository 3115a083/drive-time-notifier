package de.drivetime.notifier.routing

import de.drivetime.notifier.data.ChargingConnectorPreference
import de.drivetime.notifier.debug.RequestDebugLog
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

        val started = System.nanoTime()
        try {
            client.newCall(Request.Builder().url(url).header("User-Agent", "DriveTimeNotifier/1.1").get().build())
                .execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = response.body?.string().orEmpty().replace(Regex("\\s+"), " ").take(1_200)
                    RequestDebugLog.add("Bundesnetzagentur", "charging register", elapsedMillis(started), "HTTP ${response.code}", detail)
                    return@withContext emptyList()
                }
                val body = response.body ?: run {
                    RequestDebugLog.add("Bundesnetzagentur", "charging register", elapsedMillis(started), "failed", "empty response body")
                    return@withContext emptyList()
                }
                if (body.contentLength() > MAX_RESPONSE_BYTES) {
                    RequestDebugLog.add("Bundesnetzagentur", "charging register", elapsedMillis(started), "failed", "response too large: ${body.contentLength()} bytes")
                    return@withContext emptyList()
                }
                val bytes = body.source().readByteArray(MAX_RESPONSE_BYTES + 1L)
                if (bytes.size > MAX_RESPONSE_BYTES) {
                    RequestDebugLog.add("Bundesnetzagentur", "charging register", elapsedMillis(started), "failed", "response exceeded $MAX_RESPONSE_BYTES bytes")
                    return@withContext emptyList()
                }
                val root = JSONObject(String(bytes, Charsets.UTF_8))
                if (root.has("error")) {
                    RequestDebugLog.add("Bundesnetzagentur", "charging register", elapsedMillis(started), "API error", root.optJSONObject("error")?.toString().orEmpty())
                    return@withContext emptyList()
                }
                val features = root.optJSONArray("features") ?: run {
                    RequestDebugLog.add("Bundesnetzagentur", "charging register", elapsedMillis(started), "failed", "response contains no features array")
                    return@withContext emptyList()
                }
                val results = buildList {
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
                        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
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
                RequestDebugLog.add(
                    "Bundesnetzagentur",
                    "charging register",
                    elapsedMillis(started),
                    "success",
                    "HTTP ${response.code}, ${bytes.size} bytes, ${features.length()} features, ${results.size} usable"
                )
                results
                }
        } catch (error: Exception) {
            RequestDebugLog.add(
                "Bundesnetzagentur",
                "charging register",
                elapsedMillis(started),
                "failed",
                "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            )
            throw error
        }
    }

    private fun elapsedMillis(startedNanos: Long): Long =
        (System.nanoTime() - startedNanos) / 1_000_000L

    private fun first(attrs: JSONObject, vararg keys: String): String? =
        keys.asSequence().mapNotNull { clean(attrs.optString(it)) }.firstOrNull()

    private fun clean(raw: String?): String? = raw
        ?.filterNot { it.isISOControl() }
        ?.trim()
        ?.take(MAX_REMOTE_TEXT_LENGTH)
        ?.takeIf { it.isNotEmpty() && !it.equals("null", true) && !it.equals("none", true) }

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
        private const val MAX_REMOTE_TEXT_LENGTH = 240
    }
}
