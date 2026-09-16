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
        .retryOnConnectionFailure(true)
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

        client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", "DriveTimeNotifier/1.1")
                .get()
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val root = JSONObject(response.body?.string().orEmpty())
            if (root.has("error")) return@withContext emptyList()
            val features = root.optJSONArray("features") ?: return@withContext emptyList()
            buildList {
                for (i in 0 until features.length()) {
                    val feature = features.optJSONObject(i) ?: continue
                    val attrs = feature.optJSONObject("attributes") ?: continue
                    val status = attrs.optString("Status").lowercase()
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
                        for (index in 1..6) {
                            addAll(parseConnector(attrs.optString("Steckertypen$index")))
                        }
                    }
                    val powerValues = buildList {
                        attrs.optDouble("Nennleistung_Ladeeinrichtung__kW_", Double.NaN)
                            .takeIf { it.isFinite() && it > 0.0 }
                            ?.let(::add)
                        for (index in 1..6) {
                            parsePowerKw(attrs.optString("Nennleistung_Stecker$index"))?.let(::add)
                        }
                    }
                    val operator = attrs.optString("Betreiber").ifBlank { null }
                    val name = attrs.optString("Anzeigename__Karte_")
                        .ifBlank { attrs.optString("Standortbezeichnung") }
                        .ifBlank { operator.orEmpty() }
                        .ifBlank { "Ladestation" }
                    val openingHours = attrs.optString("Öffnungszeiten").ifBlank {
                        listOf(
                            attrs.optString("Öffnungszeiten__Wochentage"),
                            attrs.optString("Öffnungszeiten__Tageszeiten")
                        ).filter { it.isNotBlank() }.joinToString(" ")
                    }.ifBlank { null }
                    val address = listOf(
                        listOf(attrs.optString("Straße"), attrs.optString("Hausnummer"))
                            .filter { it.isNotBlank() }.joinToString(" "),
                        listOf(attrs.optString("Postleitzahl"), attrs.optString("Ort"))
                            .filter { it.isNotBlank() }.joinToString(" ")
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
                            openingHours = openingHours,
                            address = address,
                            sources = setOf(RoutePoiSource.BUNDESNETZAGENTUR)
                        )
                    )
                }
            }
        }
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

    private fun parsePowerKw(raw: String): Double? {
        if (raw.isBlank()) return null
        return NUMBER.findAll(raw.replace(',', '.'))
            .mapNotNull { it.value.toDoubleOrNull() }
            .filter { it > 0.0 }
            .maxOrNull()
    }

    companion object {
        // Public ArcGIS mirror of Bundesnetzagentur charging-register data, CC BY 4.0.
        private const val BASE_URL = "https://services2.arcgis.com/jUpNdisbWqRpMo35/arcgis/rest/services/Ladesaeulen_in_Deutschland/FeatureServer/0/query"
        private val NUMBER = Regex("[0-9]+(?:\\.[0-9]+)?")
    }
}
