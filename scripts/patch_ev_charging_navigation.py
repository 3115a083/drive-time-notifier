from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"Missing patch target: {label}")
    return text.replace(old, new, 1)


# Shared charging route planner. It keeps the original appointment destination,
# but when requested it reroutes the drive to the best matching charger and
# reserves walking time from the charger to the appointment.
write(
    "app/src/main/java/de/drivetime/notifier/routing/ChargingRoutePlanner.kt",
    r'''package de.drivetime.notifier.routing

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
'''
)

# Calendar description: pass the actual navigation result, not just the setting.
path = "app/src/main/java/de/drivetime/notifier/calendar/DriveEventDescriptionBuilder.kt"
text = read(path)
text = replace_once(
    text,
    "import de.drivetime.notifier.routing.RoutePoi\n",
    "import de.drivetime.notifier.routing.ChargingNavigation\nimport de.drivetime.notifier.routing.RoutePoi\n",
    "description import"
)
text = replace_once(
    text,
    "        pois: List<RoutePoi>,\n        navigateViaChargingStation: Boolean = false\n",
    "        pois: List<RoutePoi>,\n        chargingNavigation: ChargingNavigation? = null\n",
    "description signature"
)
text = replace_once(
    text,
    "        val navigationStation = charging.firstOrNull().takeIf { navigateViaChargingStation }\n",
    "        val navigationStation = chargingNavigation?.station\n",
    "description selected station"
)
text = replace_once(
    text,
    "                val walkingDestination = if (lat != null && lon != null) \"$lat,$lon\" else encodedDestination\n                val walking = \"https://www.google.com/maps/dir/?api=1&origin=$pLat,$pLon&destination=$walkingDestination&travelmode=walking\"\n                appendLine(\"${tr(language, \"Navigation target\", \"Navigationsziel\")}: ${navigationStation.name ?: tr(language, \"Charging station\", \"Ladestation\")}\")\n                appendLine(\"${tr(language, \"Then walk to the appointment destination\", \"Danach zu Fuß zum Terminziel\")}: $walking\")\n",
    "                val walking = \"https://www.google.com/maps/dir/?api=1&origin=$pLat,$pLon&destination=$encodedDestination&travelmode=walking\"\n                appendLine(\"${tr(language, \"Navigation target\", \"Navigationsziel\")}: ${navigationStation.name ?: tr(language, \"Charging station\", \"Ladestation\")}\")\n                chargingNavigation?.let { nav ->\n                    appendLine(\"${tr(language, \"Approximate walk to appointment\", \"Ungefährer Fußweg zum Termin\")}: ~${nav.walkingDistanceMeters} m / ${formatDuration(nav.walkingDurationSeconds, language)}\")\n                }\n                appendLine(\"${tr(language, \"Then walk to the appointment destination\", \"Danach zu Fuß zum Terminziel\")}: $walking\")\n",
    "description walking link"
)
write(path, text)

# Manual planner.
path = "app/src/main/java/de/drivetime/notifier/MainActivity.kt"
text = read(path)
text = replace_once(
    text,
    "        var pois by remember { mutableStateOf<List<RoutePoi>>(emptyList()) }\n",
    "        var pois by remember { mutableStateOf<List<RoutePoi>>(emptyList()) }\n        var chargingNavigation by remember { mutableStateOf<ChargingNavigation?>(null) }\n",
    "planner charging state"
)
text = replace_once(
    text,
    "            pois = emptyList()\n            scope.launch {\n",
    "            pois = emptyList()\n            chargingNavigation = null\n            scope.launch {\n",
    "planner reset"
)
old_calc = '''            val route = RoutingServiceFactory.create(context, settings)
                .route(RouteRequest(origin.trim(), destination.trim(), target))
            val plan = DrivePlanner.plan(target, route.durationSeconds, settings.bufferMinutes, previousEndMillis)
            val points = runCatching { PolylineDecoder.decode(route.encodedPolyline) }.getOrDefault(emptyList())
            val routePois = if ((settings.showSpeedCameras || settings.showParking || settings.showChargingStations) && points.size >= 2) {
                withTimeoutOrNull(22_000) {
                    OsmEnrichmentClient().query(
                        points,
                        settings.showSpeedCameras,
                        settings.showParking,
                        ChargingSearchOptions.from(settings)
                    )
                }.orEmpty()
            } else emptyList()
            Triple(route, plan, routePois)
        }
        result.onSuccess { (route, plan, routePois) ->
            estimate = route
            plannedStart = plan.departureMillis
            plannedEnd = plan.arrivalMillis
            pois = routePois
            val previousEnd = previousEndMillis
            planConflict = previousEnd != null && plan.departureMillis < previousEnd
            planWarning = listOfNotNull(
                planWarningText(settings.language, plan, settings.bufferMinutes),
                routeWarningText(settings.language, settings.routingProvider, route.warning)
            ).joinToString(" ").ifBlank { null }
'''
new_calc = '''            val request = RouteRequest(origin.trim(), destination.trim(), target)
            val initialRoute = RoutingServiceFactory.create(context, settings).route(request)
            val enriched = ChargingRoutePlanner.prepare(
                context = context,
                settings = settings,
                request = request,
                initialRoute = initialRoute,
                automated = false
            )
            val plan = DrivePlanner.plan(
                enriched.effectiveDestinationStartMillis,
                enriched.route.durationSeconds,
                settings.bufferMinutes,
                previousEndMillis
            )
            Pair(enriched, plan)
        }
        result.onSuccess { (enriched, plan) ->
            val route = enriched.route
            estimate = route
            plannedStart = plan.departureMillis
            plannedEnd = plan.arrivalMillis + enriched.walkingDurationSeconds * 1_000L
            pois = enriched.pois
            chargingNavigation = enriched.navigation
            val previousEnd = previousEndMillis
            planConflict = previousEnd != null && plan.departureMillis < previousEnd
            planWarning = listOfNotNull(
                planWarningText(settings.language, plan, settings.bufferMinutes),
                routeWarningText(settings.language, settings.routingProvider, route.warning)
            ).joinToString(" ").ifBlank { null }
'''
text = replace_once(text, old_calc, new_calc, "manual charging reroute")
# All three manual description calls currently end in settings.chargingNavigateViaStation.
text, count = re.subn(r"(\bpois,\s*)settings\.chargingNavigateViaStation", r"\1chargingNavigation", text)
if count != 3:
    raise SystemExit(f"Expected 3 manual description-call replacements, got {count}")
write(path, text)

# Single-event worker.
path = "app/src/main/java/de/drivetime/notifier/automation/SingleEventWorker.kt"
text = read(path)
old = '''        val routes = RoutingServiceFactory.create(applicationContext, settings, automated = true)
        val route = routeWithRetry(routes, RouteRequest(origin, destination, arrival))
        if (route == null) {
'''
new = '''        val routes = RoutingServiceFactory.create(applicationContext, settings, automated = true)
        val request = RouteRequest(origin, destination, arrival)
        val initialRoute = routeWithRetry(routes, request)
        if (initialRoute == null) {
'''
text = replace_once(text, old, new, "single initial route")
old = '''        return runCatching {
            val plan = DrivePlanner.plan(arrival, route.durationSeconds, settings.bufferMinutes, previousEnd)
            val pois = if (settings.showSpeedCameras || settings.showParking || settings.showChargingStations) {
                val points = PolylineDecoder.decode(route.encodedPolyline)
                runCatching {
                    OsmEnrichmentClient().query(
                        points,
                        settings.showSpeedCameras,
                        settings.showParking,
                        ChargingSearchOptions.from(settings)
                    )
                }.getOrDefault(emptyList())
            } else emptyList()
            val description = DriveEntryIdentity.attach(
                DriveEventDescriptionBuilder.build(
                    settings.language,
                    settings.routingProvider,
                    origin,
                    destination,
                    route,
                    pois,
                    settings.chargingNavigateViaStation
                ),
'''
new = '''        return runCatching {
            val enriched = ChargingRoutePlanner.prepare(
                context = applicationContext,
                settings = settings,
                request = request,
                initialRoute = initialRoute,
                automated = true
            )
            val route = enriched.route
            val plan = DrivePlanner.plan(
                enriched.effectiveDestinationStartMillis,
                route.durationSeconds,
                settings.bufferMinutes,
                previousEnd
            )
            val pois = enriched.pois
            val description = DriveEntryIdentity.attach(
                DriveEventDescriptionBuilder.build(
                    settings.language,
                    settings.routingProvider,
                    origin,
                    destination,
                    route,
                    pois,
                    enriched.navigation
                ),
'''
text = replace_once(text, old, new, "single enrich")
text = text.replace("                    plan.arrivalMillis,\n                    title,", "                    plan.arrivalMillis + enriched.walkingDurationSeconds * 1_000L,\n                    title,")
text = text.replace("                    plan.arrivalMillis,\n                    settings.reminderLeadMinutes,", "                    plan.arrivalMillis + enriched.walkingDurationSeconds * 1_000L,\n                    settings.reminderLeadMinutes,")
write(path, text)

# Next-day worker.
path = "app/src/main/java/de/drivetime/notifier/automation/NextDayWorker.kt"
text = read(path)
old = '''            val estimate = routeWithRetry(routes, RouteRequest(origin, event.location, event.startMillis))
            if (estimate == null) {
'''
new = '''            val request = RouteRequest(origin, event.location, event.startMillis)
            val initialEstimate = routeWithRetry(routes, request)
            if (initialEstimate == null) {
'''
text = replace_once(text, old, new, "nextday initial route")
old = '''            val plan = DrivePlanner.plan(
                destinationStartMillis = event.startMillis,
                routeDurationSeconds = estimate.durationSeconds,
                requestedBufferMinutes = settings.bufferMinutes,
                previousEventEndMillis = previousEnd
            )
            val pois = if (settings.showSpeedCameras || settings.showParking || settings.showChargingStations) {
                val points = PolylineDecoder.decode(estimate.encodedPolyline)
                runCatching {
                    OsmEnrichmentClient().query(
                        points,
                        settings.showSpeedCameras,
                        settings.showParking,
                        ChargingSearchOptions.from(settings)
                    )
                }.getOrDefault(emptyList())
            } else emptyList()
            val description = DriveEntryIdentity.attach(
                DriveEventDescriptionBuilder.build(
                    settings.language,
                    settings.routingProvider,
                    origin,
                    event.location,
                    estimate,
                    pois,
                    settings.chargingNavigateViaStation
                ),
'''
new = '''            val enriched = ChargingRoutePlanner.prepare(
                context = applicationContext,
                settings = settings,
                request = request,
                initialRoute = initialEstimate,
                automated = true
            )
            val estimate = enriched.route
            val plan = DrivePlanner.plan(
                destinationStartMillis = enriched.effectiveDestinationStartMillis,
                routeDurationSeconds = estimate.durationSeconds,
                requestedBufferMinutes = settings.bufferMinutes,
                previousEventEndMillis = previousEnd
            )
            val pois = enriched.pois
            val description = DriveEntryIdentity.attach(
                DriveEventDescriptionBuilder.build(
                    settings.language,
                    settings.routingProvider,
                    origin,
                    event.location,
                    estimate,
                    pois,
                    enriched.navigation
                ),
'''
text = replace_once(text, old, new, "nextday enrich")
text = text.replace("                        plan.arrivalMillis,\n                        title,", "                        plan.arrivalMillis + enriched.walkingDurationSeconds * 1_000L,\n                        title,")
text = text.replace("                        plan.arrivalMillis,\n                        settings.reminderLeadMinutes,", "                        plan.arrivalMillis + enriched.walkingDurationSeconds * 1_000L,\n                        settings.reminderLeadMinutes,")
write(path, text)

# Unit test for walking-time semantics and speed preferences remains network-free.
write(
    "app/src/test/java/de/drivetime/notifier/routing/ChargingRoutePlannerTest.kt",
    r'''package de.drivetime.notifier.routing

import de.drivetime.notifier.data.ChargingSpeedPreference
import org.junit.Assert.assertEquals
import org.junit.Test

class ChargingRoutePlannerTest {
    @Test
    fun slowPreferencePrefersSlowChargers() {
        assertEquals(0, ChargingSpeedPreference.SLOW.penalty(11.0))
        assertEquals(3, ChargingSpeedPreference.SLOW.penalty(150.0))
    }

    @Test
    fun hpcPreferencePrefersHighPower() {
        assertEquals(0, ChargingSpeedPreference.HPC.penalty(300.0))
        assertEquals(3, ChargingSpeedPreference.HPC.penalty(22.0))
    }
}
'''
)

print("EV charging navigation patch applied")
