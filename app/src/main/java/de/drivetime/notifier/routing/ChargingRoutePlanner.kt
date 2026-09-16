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
    val navigation: ChargingNavigation?
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
        val wantsEnrichment = settings.showSpeedCameras || settings.showParking || settings.showChargingStations
        val initialPois = if (wantsEnrichment && initialPoints.size >= 2) {
            runCatching {
                OsmEnrichmentClient().query(
                    initialPoints,
                    settings.showSpeedCameras,
                    settings.showParking,
                    ChargingSearchOptions.from(settings)
                )
            }.getOrDefault(emptyList())
        } else emptyList()

        val station = initialPois
            .firstOrNull { it.kind == RoutePoi.Kind.CHARGING_STATION }
            .takeIf { settings.showChargingStations && settings.chargingNavigateViaStation }

        val originLat = initialRoute.originLatitude
        val originLon = initialRoute.originLongitude
        if (station == null || originLat == null || originLon == null) {
            return ChargingRouteOutcome(
                route = initialRoute,
                effectiveDestinationStartMillis = request.arrivalMillis,
                walkingDurationSeconds = 0L,
                pois = initialPois,
                navigation = null
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
                pois = initialPois,
                navigation = null
            )
        }

        val destinationPois = initialPois.filter { it.kind != RoutePoi.Kind.SPEED_CAMERA }
        val cameraPois = if (settings.showSpeedCameras) {
            val reroutedPoints = runCatching { PolylineDecoder.decode(rerouted.encodedPolyline) }
                .getOrDefault(emptyList())
            if (reroutedPoints.size >= 2) {
                runCatching {
                    OsmEnrichmentClient().query(
                        reroutedPoints,
                        cameras = true,
                        parking = false,
                        charging = null
                    )
                }.getOrDefault(emptyList()).filter { it.kind == RoutePoi.Kind.SPEED_CAMERA }
            } else emptyList()
        } else emptyList()

        return ChargingRouteOutcome(
            route = rerouted,
            effectiveDestinationStartMillis = effectiveArrival,
            walkingDurationSeconds = walkingSeconds,
            pois = cameraPois + destinationPois,
            navigation = ChargingNavigation(station, walkingMeters, walkingSeconds)
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
