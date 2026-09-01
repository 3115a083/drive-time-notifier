package de.drivetime.notifier.routing

import de.drivetime.notifier.model.AddressSuggestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

class PhotonSearchService(
    private val baseUrl: String,
    private val userAgent: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
) {
    suspend fun suggest(query: String, language: String): List<AddressSuggestion> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.length < 3) return@withContext emptyList()

        val country = Locale.getDefault().country.trim().uppercase()
        val local = if (country.length == 2) search(clean, language, country, 6) else emptyList()
        if (local.size >= 6) return@withContext local.take(6)

        val global = search(clean, language, null, 8)
        (local + global).distinctBy { it.label.lowercase() }.take(6)
    }

    suspend fun geocode(query: String): AddressSuggestion = withContext(Dispatchers.IO) {
        val clean = query.trim()
        require(clean.isNotBlank()) { "Address is empty." }
        val country = Locale.getDefault().country.trim().uppercase()
        val local = if (country.length == 2) search(clean, "en", country, 3) else emptyList()
        (local + search(clean, "en", null, 3))
            .distinctBy { it.label.lowercase() }
            .firstOrNull()
            ?: error("Address not found: $clean")
    }

    private fun search(
        query: String,
        language: String,
        countryCode: String?,
        limit: Int
    ): List<AddressSuggestion> {
        val base = baseUrl.toHttpUrl()
        require(base.isHttps) { "Photon endpoint must use HTTPS." }
        val builder = base.newBuilder()
            .addPathSegment("api")
            .addQueryParameter("q", query)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("lang", language)
        if (!countryCode.isNullOrBlank()) builder.addQueryParameter("countrycode", countryCode)

        val request = Request.Builder()
            .url(builder.build())
            .header("User-Agent", userAgent)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val features = JSONObject(response.body?.string().orEmpty()).optJSONArray("features") ?: return emptyList()
            return buildList {
                for (i in 0 until features.length()) {
                    val feature = features.getJSONObject(i)
                    val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                    val p = feature.optJSONObject("properties") ?: continue
                    val label = listOf(
                        p.optString("name"),
                        p.optString("street"),
                        p.optString("housenumber"),
                        p.optString("postcode"),
                        p.optString("city"),
                        p.optString("state"),
                        p.optString("country")
                    ).filter { it.isNotBlank() }.distinct().joinToString(", ")
                    if (label.isNotBlank()) {
                        add(AddressSuggestion(label, coords.optDouble(1), coords.optDouble(0)))
                    }
                }
            }
        }
    }
}
