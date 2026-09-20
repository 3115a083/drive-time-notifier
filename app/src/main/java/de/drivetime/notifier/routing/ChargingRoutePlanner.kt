package de.drivetime.notifier.routing

import android.content.Context
import de.drivetime.notifier.data.AppSettings
import de.drivetime.notifier.data.RoutingProvider
import de.drivetime.notifier.model.RouteEstimate
import de.drivetime.notifier.model.RouteRequest
import de.drivetime.notifier.security.SecureApiKeyStore
import kotlin.math.ceil
import kotlin.math.roundToInt

data class ChargingNavigation(
    val station: RoutePoi,
    val walkingDistanceMeters: Int,
    val walkingDurationSeconds: Long
)

data class ChargingRouteOutcome(
    val route: RouteEstimate,
    val effectiveDestinationStartMillis: Long,
    val walkingDurationSeconds: Long,
    val pois: List<RoutePoi>,
    val navigation: ChargingNavigation?,
    val enrichmentUnavailable: Boolean = false,
    val chargingRegistryUnavailable: Boolean = false
)

object ChargingRoutePlanner {
    suspend fun prepare(
        context: Context,
        settings: AppSettings,
        request: RouteRequest,
        initialRoute: RouteEstimate,
        automated: Boolean
    ): ChargingRouteOutcome {
        val initialPoints = runCatching { PolylineDecoder.decode(initialRoute.encodedPolyline) }
            .getOrDefault(emptyList())
        val wantsEnrichment = settings.showParking || settings.showChargingStations
        val initialEnrichment = if (wantsEnrichment && initialPoints.size >= 2) {
            runCatching {
                OsmEnrichmentClient(
                    settings.overpassBaseUrl,
                    settings.overpassEndpoints,
                    settings.overpassSplitRequests
                ).query(
                    initialPoints,
                    settings.showParking,
                    ChargingSearchOptions.from(settings)
                )
            }.getOrElse { OsmEnrichmentResult(emptyList(), unavailable = true) }
        } else OsmEnrichmentResult(emptyList(), unavailable = false)
        val initialPois = initialEnrichment.pois

        val station = initialPois
            .firstOrNull { it.kind == RoutePoi.Kind.CHARGING_STATION }
            .takeIf { settings.showChargingStations && settings.chargingNavigateViaStation }
        if (station == null) {
            return ChargingRouteOutcome(
                route = initialRoute,
                effectiveDestinationStartMillis = request.arrivalMillis,
                walkingDurationSeconds = 0L,
                pois = initialPois,
                navigation = null,
                enrichmentUnavailable = initialEnrichment.unavailable,
                chargingRegistryUnavailable = initialEnrichment.registryUnavailable
            )
        }

        return rerouteViaStation(
            context = context,
            settings = settings,
            request = request,
            initialRoute = initialRoute,
            station = station,
            pois = initialPois,
            automated = automated,
            enrichmentUnavailable = initialEnrichment.unavailable,
            registryUnavailable = initialEnrichment.registryUnavailable
        )
    }

    suspend fun rerouteViaStation(
        context: Context,
        settings: AppSettings,
        request: RouteRequest,
        initialRoute: RouteEstimate,
        station: RoutePoi,
        pois: List<RoutePoi>,
        automated: Boolean,
        enrichmentUnavailable: Boolean = false,
        registryUnavailable: Boolean = false
    ): ChargingRouteOutcome {
        val originLat = initialRoute.originLatitude
        val originLon = initialRoute.originLongitude
        if (originLat == null || originLon == null) {
            return ChargingRouteOutcome(
                route = initialRoute,
                effectiveDestinationStartMillis = request.arrivalMillis,
                walkingDurationSeconds = 0L,
                pois = pois,
                navigation = null,
                enrichmentUnavailable = enrichmentUnavailable,
                chargingRegistryUnavailable = registryUnavailable
            )
        }

        val straightMeters = (station.distanceFromDestinationMeters ?: 0).coerceAtLeast(0)
        val walkingMeters = (straightMeters * 1.25).roundToInt().coerceAtLeast(straightMeters)
        val walkingSeconds = ceil(walkingMeters / 1.35).toLong().coerceAtLeast(0L)
        val effectiveArrival = (request.arrivalMillis - walkingSeconds * 1_000L).coerceAtLeast(1L)
        val resolved = ResolvedRoutePoints(
            originLatitude = originLat,
            originLongitude = originLon,
            destinationLatitude = station.point.latitude,
            destinationLongitude = station.point.longitude
        )

        val rerouted = runCatching {
            routeResolvedWithFallback(
                context = context,
                settings = settings,
                request = request.copy(arrivalMillis = effectiveArrival),
                points = resolved,
                automated = automated
            )
        }.getOrNull()

        if (rerouted == null) {
            return ChargingRouteOutcome(
                route = initialRoute,
                effectiveDestinationStartMillis = request.arrivalMillis,
                walkingDurationSeconds = 0L,
                pois = pois,
                navigation = null,
                enrichmentUnavailable = enrichmentUnavailable,
                chargingRegistryUnavailable = registryUnavailable
            )
        }

        return ChargingRouteOutcome(
            route = rerouted,
            effectiveDestinationStartMillis = effectiveArrival,
            walkingDurationSeconds = walkingSeconds,
            pois = pois,
            navigation = ChargingNavigation(station, walkingMeters, walkingSeconds),
            enrichmentUnavailable = enrichmentUnavailable,
            chargingRegistryUnavailable = registryUnavailable
        )
    }

    private suspend fun routeResolvedWithFallback(
        context: Context,
        settings: AppSettings,
        request: RouteRequest,
        points: ResolvedRoutePoints,
        automated: Boolean
    ): RouteEstimate {
        val providers = buildList {
            add(settings.routingProvider)
            settings.fallbackProviderIds
                .mapNotNull { id -> RoutingProvider.entries.firstOrNull { it.id == id } }
                .filter { it != settings.routingProvider }
                .forEach { if (it !in this) add(it) }
        }
        val keyStore = SecureApiKeyStore(context)
        val failures = mutableListOf<String>()
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
            val result = runCatching { service.routeResolved(request, points) }
            result.getOrNull()?.let { return it }
            failures += "${provider.displayName}: ${result.exceptionOrNull()?.message.orEmpty().ifBlank { "unknown error" }}"
        }
        error(
            if (failures.isEmpty()) "No routing provider is available."
            else "All configured routing providers failed. " + failures.joinToString(" | ")
        )
    }
}
