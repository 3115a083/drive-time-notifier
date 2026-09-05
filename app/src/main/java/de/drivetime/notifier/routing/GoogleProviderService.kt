package de.drivetime.notifier.routing

import de.drivetime.notifier.data.LimitPeriod
import de.drivetime.notifier.data.RoutingProvider
import de.drivetime.notifier.model.AddressSuggestion
import de.drivetime.notifier.model.RouteEstimate
import de.drivetime.notifier.model.RouteRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GoogleProviderService(
    private val apiKey: String,
    private val budget: RequestBudgetStore,
    private val dailyCap: Int,
    private val limitPeriod: LimitPeriod = LimitPeriod.DAILY,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(16, TimeUnit.SECONDS)
        .build()
) : RoutingService, AddressSearchService {
    override suspend fun route(request: RouteRequest): RouteEstimate {
        require(apiKey.isNotBlank()) { "Google API key is missing." }
        budget.consume(RoutingProvider.GOOGLE, dailyCap, limitPeriod, 4)
        return GoogleRoutesClient(client).route(request, apiKey)
    }

    override suspend fun suggest(query: String, language: String): List<AddressSuggestion> = withContext(Dispatchers.IO) {
        if (query.trim().length < 3 || apiKey.isBlank()) return@withContext emptyList()
        budget.consume(RoutingProvider.GOOGLE, dailyCap, limitPeriod)
        val body = JSONObject().apply {
            put("input", query.trim())
            put("languageCode", language)
        }.toString()
        val request = Request.Builder()
            .url("https://places.googleapis.com/v1/places:autocomplete")
            .header("X-Goog-Api-Key", apiKey)
            .header("X-Goog-FieldMask", "suggestions.placePrediction.text.text")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val suggestions = JSONObject(response.body?.string().orEmpty()).optJSONArray("suggestions")
                ?: return@withContext emptyList()
            buildList {
                for (i in 0 until suggestions.length()) {
                    val prediction = suggestions.optJSONObject(i)?.optJSONObject("placePrediction") ?: continue
                    val text = prediction.optJSONObject("text")?.optString("text").orEmpty()
                    if (text.isNotBlank()) add(AddressSuggestion(text))
                }
            }.distinctBy { it.label }.take(6)
        }
    }
}
