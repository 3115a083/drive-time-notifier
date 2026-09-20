package de.drivetime.notifier.routing

import android.content.Context
import de.drivetime.notifier.network.readBytesLimited
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
                    val bytes = body.readBytesLimited(MAX_BYTES)
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

    private fun clean(value: String?): String? = value
        ?.filterNot { it.isISOControl() }
        ?.trim()
        ?.take(MAX_OPERATOR_LENGTH)
        ?.takeIf { it.isNotEmpty() && !it.equals("null", true) }

    companion object {
        private const val KEY = "operators"
        private const val MAX_BYTES = 1_000_000L
        private const val MAX_OPERATOR_LENGTH = 80
        private val BUILT_IN = setOf(
            "EnBW", "IONITY", "Tesla", "Aral pulse", "Allego", "E.ON Drive", "Shell Recharge",
            "Mer", "Pfalzwerke", "Westfalen Weser", "Stadtwerke München", "Stadtwerke Düsseldorf",
            "Stadtwerke Dortmund", "Stadtwerke Unna"
        )
    }
}
