package de.drivetime.notifier.routing

import android.content.Context
import de.drivetime.notifier.data.AppSettings
import de.drivetime.notifier.data.RoutingProvider
import de.drivetime.notifier.model.RouteEstimate
import de.drivetime.notifier.model.RouteRequest
import de.drivetime.notifier.security.SecureApiKeyStore

interface RoutingService {
    suspend fun route(request: RouteRequest): RouteEstimate
}

object RoutingServiceFactory {
    fun create(context: Context, settings: AppSettings): RoutingService =
        when (settings.routingProvider) {
            RoutingProvider.GOOGLE -> GoogleRoutingService(
                GoogleRoutesClient(),
                SecureApiKeyStore(context).read().orEmpty()
            )
            RoutingProvider.OSRM -> OsrmRoutingService(
                osrmBaseUrl = settings.osrmBaseUrl,
                nominatimBaseUrl = settings.nominatimBaseUrl,
                userAgent = context.packageName
            )
        }
}

private class GoogleRoutingService(
    private val client: GoogleRoutesClient,
    private val apiKey: String
) : RoutingService {
    override suspend fun route(request: RouteRequest): RouteEstimate = client.route(request, apiKey)
}
