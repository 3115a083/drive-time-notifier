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

object RoutingServiceFactory {
    fun create(context: Context, settings: AppSettings): RoutingService =
        UnifiedRoutingService(
            context = context,
            settings = settings,
            keyStore = SecureApiKeyStore(context),
            budget = RequestBudgetStore(context)
        )

    fun addressSearch(context: Context, settings: AppSettings): AddressSearchService =
        object : AddressSearchService {
            override suspend fun suggest(query: String, language: String): List<AddressSuggestion> =
                PhotonSearchService(settings.photonBaseUrl, context.packageName).suggest(query, language)
        }
}
