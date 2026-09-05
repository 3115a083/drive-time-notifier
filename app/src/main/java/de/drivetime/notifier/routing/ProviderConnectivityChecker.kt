package de.drivetime.notifier.routing

import android.content.Context
import de.drivetime.notifier.data.AppSettings
import de.drivetime.notifier.data.RoutingProvider
import de.drivetime.notifier.security.SecureApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

enum class InterfaceCheckState { UNKNOWN, VALID, INVALID }

data class InterfaceCheckResult(
    val state: InterfaceCheckState,
    val message: String,
    val checkedAt: Long = System.currentTimeMillis()
)

class InterfaceHealthStore(context: Context) {
    private val prefs = context.getSharedPreferences("interface_health", Context.MODE_PRIVATE)

    fun read(id: String, fingerprint: String): InterfaceCheckResult? {
        if (prefs.getString("${id}_fingerprint", null) != fingerprint) return null
        val state = runCatching {
            InterfaceCheckState.valueOf(prefs.getString("${id}_state", InterfaceCheckState.UNKNOWN.name)!!)
        }.getOrDefault(InterfaceCheckState.UNKNOWN)
        return InterfaceCheckResult(
            state = state,
            message = prefs.getString("${id}_message", "").orEmpty(),
            checkedAt = prefs.getLong("${id}_checked_at", 0L)
        )
    }

    fun save(id: String, fingerprint: String, result: InterfaceCheckResult) {
        prefs.edit()
            .putString("${id}_fingerprint", fingerprint)
            .putString("${id}_state", result.state.name)
            .putString("${id}_message", result.message.take(240))
            .putLong("${id}_checked_at", result.checkedAt)
            .apply()
    }
}

class ProviderConnectivityChecker(
    private val context: Context,
    private val settings: AppSettings,
    private val keyStore: SecureApiKeyStore = SecureApiKeyStore(context),
    private val store: InterfaceHealthStore = InterfaceHealthStore(context),
    private val budget: RequestBudgetStore = RequestBudgetStore(context)
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun checkProvider(provider: RoutingProvider): InterfaceCheckResult = withContext(Dispatchers.IO) {
        val key = keyStore.read(provider).orEmpty()
        val fingerprint = providerFingerprint(provider, settings, key)
        if (provider.keyRequired && key.isBlank()) {
            return@withContext InterfaceCheckResult(
                InterfaceCheckState.INVALID,
                "API key missing."
            ).also { store.save(provider.id, fingerprint, it) }
        }

        val result = runCatching { executeProviderCheck(provider, key) }
            .fold(
                onSuccess = { InterfaceCheckResult(InterfaceCheckState.VALID, "Interface reachable and credentials accepted.") },
                onFailure = { InterfaceCheckResult(InterfaceCheckState.INVALID, it.message ?: "Interface check failed.") }
            )
        store.save(provider.id, fingerprint, result)
        result
    }

    suspend fun checkPhoton(): InterfaceCheckResult = withContext(Dispatchers.IO) {
        val fingerprint = photonFingerprint(settings)
        val result = runCatching {
            val base = settings.photonBaseUrl.trimEnd('/')
            val url = "$base/api".toHttpUrl().newBuilder()
                .addQueryParameter("q", "Berlin")
                .addQueryParameter("limit", "1")
                .build()
            execute(Request.Builder().url(url).header("User-Agent", context.packageName).get().build(), "Photon")
        }.fold(
            onSuccess = { InterfaceCheckResult(InterfaceCheckState.VALID, "Photon endpoint reachable.") },
            onFailure = { InterfaceCheckResult(InterfaceCheckState.INVALID, it.message ?: "Photon check failed.") }
        )
        store.save(PHOTON_ID, fingerprint, result)
        result
    }

    private fun executeProviderCheck(provider: RoutingProvider, key: String) {
        budget.consume(
            provider,
            settings.providerCaps.forProvider(provider),
            settings.providerLimitPeriods.forProvider(provider)
        )

        val oLat = 52.5200
        val oLon = 13.4050
        val dLat = 52.5163
        val dLon = 13.3777
        val testArrival = System.currentTimeMillis() + 60 * 60_000L

        val request = when (provider) {
            RoutingProvider.VALHALLA -> {
                val body = JSONObject().apply {
                    put("locations", JSONArray().apply {
                        put(JSONObject().put("lat", oLat).put("lon", oLon))
                        put(JSONObject().put("lat", dLat).put("lon", dLon))
                    })
                    put("costing", "auto")
                    put("units", "kilometers")
                    put("date_time", JSONObject().put("type", 2).put(
                        "value",
                        Instant.ofEpochMilli(testArrival).atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
                    ))
                }.toString()
                val url = settings.valhallaBaseUrl.toHttpUrl().newBuilder().addPathSegment("route").build()
                Request.Builder().url(url)
                    .header("X-Client-Id", context.packageName)
                    .header("User-Agent", context.packageName)
                    .header("Accept", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType())).build()
            }
            RoutingProvider.OPENROUTESERVICE -> {
                val body = JSONObject().put("coordinates", JSONArray().apply {
                    put(JSONArray().put(oLon).put(oLat))
                    put(JSONArray().put(dLon).put(dLat))
                }).toString()
                Request.Builder()
                    .url("https://api.heigit.org/openrouteservice/v2/directions/driving-car/geojson")
                    .header("Authorization", key)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            }
            RoutingProvider.OSRM -> {
                val base = settings.osrmBaseUrl.trimEnd('/')
                val url = "$base/route/v1/driving/$oLon,$oLat;$dLon,$dLat".toHttpUrl().newBuilder()
                    .addQueryParameter("overview", "false")
                    .build()
                Request.Builder().url(url).header("User-Agent", context.packageName).get().build()
            }
            RoutingProvider.GRAPHHOPPER -> {
                val url = okhttp3.HttpUrl.Builder().scheme("https").host("graphhopper.com").addPathSegments("api/1/route")
                    .addQueryParameter("point", "$oLat,$oLon")
                    .addQueryParameter("point", "$dLat,$dLon")
                    .addQueryParameter("profile", "car")
                    .addQueryParameter("points_encoded", "false")
                    .addQueryParameter("instructions", "false")
                    .addQueryParameter("key", key)
                    .build()
                Request.Builder().url(url).get().build()
            }
            RoutingProvider.GOOGLE -> {
                val waypoint = { lat: Double, lon: Double ->
                    JSONObject().put("location", JSONObject().put("latLng", JSONObject()
                        .put("latitude", lat).put("longitude", lon)))
                }
                val body = JSONObject()
                    .put("origin", waypoint(oLat, oLon))
                    .put("destination", waypoint(dLat, dLon))
                    .put("travelMode", "DRIVE")
                    .toString()
                Request.Builder()
                    .url("https://routes.googleapis.com/directions/v2:computeRoutes")
                    .header("X-Goog-Api-Key", key)
                    .header("X-Goog-FieldMask", "routes.duration")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            }
            RoutingProvider.HERE -> {
                val url = okhttp3.HttpUrl.Builder().scheme("https").host("router.hereapi.com").addPathSegments("v8/routes")
                    .addQueryParameter("transportMode", "car")
                    .addQueryParameter("origin", "$oLat,$oLon")
                    .addQueryParameter("destination", "$dLat,$dLon")
                    .addQueryParameter("return", "summary")
                    .addQueryParameter("apiKey", key)
                    .build()
                Request.Builder().url(url).get().build()
            }
            RoutingProvider.TOMTOM -> {
                val locations = "$oLat,$oLon:$dLat,$dLon"
                val url = okhttp3.HttpUrl.Builder().scheme("https").host("api.tomtom.com")
                    .addPathSegments("routing/1/calculateRoute/$locations/json")
                    .addQueryParameter("key", key)
                    .addQueryParameter("traffic", "true")
                    .addQueryParameter("computeTravelTimeFor", "all")
                    .addQueryParameter("arriveAt", Instant.ofEpochMilli(testArrival).toString())
                    .addQueryParameter("routeRepresentation", "polyline")
                    .build()
                Request.Builder().url(url).get().build()
            }
        }
        execute(request, provider.displayName)
    }

    private fun execute(request: Request, label: String) {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = response.body?.string().orEmpty().replace(Regex("\\s+"), " ").take(120)
                error("$label: HTTP ${response.code}${if (detail.isBlank()) "" else " · $detail"}")
            }
        }
    }

    companion object {
        const val PHOTON_ID = "photon"

        fun providerFingerprint(provider: RoutingProvider, settings: AppSettings, key: String): String {
            val endpoint = when (provider) {
                RoutingProvider.VALHALLA -> settings.valhallaBaseUrl
                RoutingProvider.OSRM -> settings.osrmBaseUrl
                RoutingProvider.OPENROUTESERVICE -> "https://api.heigit.org/openrouteservice"
                RoutingProvider.GRAPHHOPPER -> "https://graphhopper.com"
                RoutingProvider.GOOGLE -> "https://routes.googleapis.com"
                RoutingProvider.HERE -> "https://router.hereapi.com"
                RoutingProvider.TOMTOM -> "https://api.tomtom.com"
            }
            return sha256("${provider.id}|$endpoint|$key")
        }

        fun photonFingerprint(settings: AppSettings): String = sha256("photon|${settings.photonBaseUrl}")

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
