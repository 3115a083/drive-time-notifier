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

data class ProviderServices(
    val routing: RoutingService,
    val search: AddressSearchService
)

object RoutingServiceFactory {
    fun create(context: Context, settings: AppSettings): RoutingService =
        services(context, settings).routing

    fun addressSearch(context: Context, settings: AppSettings): AddressSearchService =
        services(context, settings).search

    private fun services(context: Context, settings: AppSettings): ProviderServices {
        val keys = SecureApiKeyStore(context)
        val budget = RequestBudgetStore(context)
        val cap = settings.providerCaps.forProvider(settings.routingProvider)
        return when (settings.routingProvider) {
            RoutingProvider.GOOGLE -> GoogleProviderService(
                apiKey = keys.read(RoutingProvider.GOOGLE).orEmpty(),
                budget = budget,
                dailyCap = cap
            )
            RoutingProvider.HERE -> HereProviderService(
                apiKey = keys.read(RoutingProvider.HERE).orEmpty(),
                budget = budget,
                dailyCap = cap
            )
            RoutingProvider.GRAPHHOPPER -> GraphHopperProviderService(
                apiKey = keys.read(RoutingProvider.GRAPHHOPPER).orEmpty(),
                budget = budget,
                dailyCap = cap
            )
            RoutingProvider.OSRM -> OsrmPhotonProviderService(
                osrmBaseUrl = settings.osrmBaseUrl,
                photonBaseUrl = settings.photonBaseUrl,
                userAgent = context.packageName,
                budget = budget,
                dailyCap = cap
            )
        }
    }
}
