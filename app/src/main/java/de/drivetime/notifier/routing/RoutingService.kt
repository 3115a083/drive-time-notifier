package de.drivetime.notifier.routing

import android.content.Context
import de.drivetime.notifier.calendar.CalendarRepository
import de.drivetime.notifier.calendar.DriveEntryIdentity
import de.drivetime.notifier.data.AppSettings
import de.drivetime.notifier.data.RoutingProvider
import de.drivetime.notifier.model.AddressSuggestion
import de.drivetime.notifier.model.RouteEstimate
import de.drivetime.notifier.model.RouteRequest
import de.drivetime.notifier.security.SecureApiKeyStore
import de.drivetime.notifier.ui.tr

interface RoutingService {
    suspend fun route(request: RouteRequest): RouteEstimate
}

interface CancelableRoutingService : RoutingService {
    fun cancelActiveCalls()
}

interface AddressSearchService {
    suspend fun suggest(query: String, language: String): List<AddressSuggestion>
}

data class ResolvedRoutePoints(
    val originLatitude: Double,
    val originLongitude: Double,
    val destinationLatitude: Double,
    val destinationLongitude: Double
)

private class FallbackRoutingService(
    private val context: Context,
    private val settings: AppSettings,
    private val automated: Boolean
) : CancelableRoutingService {
    @Volatile
    private var activeService: UnifiedRoutingService? = null

    override suspend fun route(request: RouteRequest): RouteEstimate {
        if (!automated && !settings.outputIcs && settings.targetCalendarId >= 0) {
            val identityKey = DriveEntryIdentity.key(request.destination, request.arrivalMillis)
            val duplicate = runCatching {
                CalendarRepository(context).findExistingDrive(
                    settings.targetCalendarId,
                    request.destination,
                    request.arrivalMillis,
                    identityKey
                )
            }.getOrNull()
            if (duplicate != null) {
                error(
                    tr(
                        settings.language,
                        "A Drive Time Notifier entry for this destination and appointment time already exists. Delete the existing drive or change the appointment time before calculating it again.",
                        "Für dieses Ziel und diese Terminzeit existiert bereits ein Drive-Time-Notifier-Eintrag. Lösche die vorhandene Fahrt oder ändere die Terminzeit, bevor du sie erneut berechnest."
                    )
                )
            }
        }

        val geocoder = PhotonSearchService(settings.photonBaseUrl, context.packageName)
        val origin = geocoder.geocode(request.origin)
        val destination = geocoder.geocode(request.destination)
        val points = ResolvedRoutePoints(
            originLatitude = origin.latitude ?: error("Origin latitude missing."),
            originLongitude = origin.longitude ?: error("Origin longitude missing."),
            destinationLatitude = destination.latitude ?: error("Destination latitude missing."),
            destinationLongitude = destination.longitude ?: error("Destination longitude missing.")
        )

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
            activeService = service
            val result = runCatching { service.routeResolved(request, points) }
            activeService = null
            result.getOrNull()?.let { return it }
            val message = result.exceptionOrNull()?.message.orEmpty().ifBlank { "unknown error" }
            failures += "${provider.displayName}: $message"
        }

        error(
            if (failures.isEmpty()) "No routing provider is available."
            else "All configured routing providers failed. " + failures.joinToString(" | ")
        )
    }

    override fun cancelActiveCalls() {
        activeService?.cancelActiveCalls()
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
