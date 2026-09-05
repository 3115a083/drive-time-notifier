package de.drivetime.notifier.routing

import android.content.Context
import de.drivetime.notifier.data.AppSettings
import de.drivetime.notifier.data.RoutingProvider
import de.drivetime.notifier.model.AddressSuggestion
import de.drivetime.notifier.model.RouteEstimate
import de.drivetime.notifier.model.RouteRequest
import de.drivetime.notifier.security.SecureApiKeyStore

interface RoutingService {
    suspend fun route(request: RouteRequest): RouteEstimate
}

interface AddressSearchService {
    suspend fun suggest(query: String, language: String): List<AddressSuggestion>
}

private class FallbackRoutingService(
    private val context: Context,
    private val settings: AppSettings,
    private val automated: Boolean
) : RoutingService {
    override suspend fun route(request: RouteRequest): RouteEstimate {
        val providers = buildList {
            add(settings.routingProvider)
            settings.fallbackProviderIds
                .mapNotNull { id -> RoutingProvider.entries.firstOrNull { it.id == id } }
                .filter { it != settings.routingProvider }
                .forEach { if (it !in this) add(it) }
        }

        val failures = mutableListOf<String>()
        val keyStore = SecureApiKeyStore(context)
        for (provider in providers) {
            if (provider.keyRequired && keyStore.read(provider).isNullOrBlank()) {
                failures += "${provider.displayName}: API key missing"
                continue
            }
            val service = UnifiedRoutingService(
                context = context,
                settings = settings.copy(routingProvider = provider),
                keyStore = keyStore,
                budget = RequestBudgetStore(context),
                automated = automated
            )
            val result = runCatching { service.route(request) }
            result.getOrNull()?.let { return it }
            val message = result.exceptionOrNull()?.message.orEmpty().ifBlank { "unknown error" }
            failures += "${provider.displayName}: $message"
        }

        error(
            if (failures.isEmpty()) "No routing provider is available."
            else "All configured routing providers failed. " + failures.joinToString(" | ")
        )
    }
}


object RoutingServiceFactory {
    fun create(context: Context, settings: AppSettings, automated: Boolean = false): RoutingService =
        FallbackRoutingService(context, settings, automated)

    fun addressSearch(context: Context, settings: AppSettings): AddressSearchService =
        object : AddressSearchService {
            override suspend fun suggest(query: String, language: String): List<AddressSuggestion> =
                PhotonSearchService(settings.photonBaseUrl, context.packageName).suggest(query, language)
        }
}
