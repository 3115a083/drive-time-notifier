package de.drivetime.notifier.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.drivetime.notifier.calendar.CalendarRepository
import de.drivetime.notifier.calendar.DriveEntryIdentity
import de.drivetime.notifier.calendar.DriveEventDescriptionBuilder
import de.drivetime.notifier.core.DrivePlanner
import de.drivetime.notifier.data.SettingsStore
import de.drivetime.notifier.export.IcsExporter
import de.drivetime.notifier.model.RouteEstimate
import de.drivetime.notifier.model.RouteRequest
import de.drivetime.notifier.routing.OsmEnrichmentClient
import de.drivetime.notifier.routing.PolylineDecoder
import de.drivetime.notifier.routing.RoutingService
import de.drivetime.notifier.routing.RoutingServiceFactory
import de.drivetime.notifier.ui.resolvedDriveEventTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

class SingleEventWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val settings = SettingsStore(applicationContext).flow.first()
        val destination = inputData.getString("destination").orEmpty().trim()
        val arrival = inputData.getLong("arrival_millis", -1L)
        val previousEnd = inputData.getLong("previous_end_millis", -1L).takeIf { it > 0 }
        val origin = inputData.getString("origin").orEmpty().trim().ifBlank { settings.homeAddress }
        val allowDuplicate = inputData.getBoolean("allow_duplicate", false)
        if (origin.isBlank() || destination.isBlank() || arrival <= 0) return Result.failure()

        setForeground(AutomationNotifier.processingForegroundInfo(applicationContext, settings.language, id))
        val identityKey = inputData.getString("identity_key").orEmpty()
            .ifBlank { DriveEntryIdentity.key(destination, arrival) }
        val calendar = CalendarRepository(applicationContext)

        if (!allowDuplicate && !settings.outputIcs) {
            val duplicate = runCatching {
                calendar.findExistingDrive(
                    settings.targetCalendarId,
                    destination,
                    arrival,
                    identityKey
                )
            }.getOrNull()
            if (duplicate != null) {
                AutomationNotifier.notifyDuplicateDecision(
                    applicationContext,
                    settings.language,
                    origin,
                    destination,
                    arrival,
                    previousEnd,
                    identityKey
                )
                return Result.success()
            }
        }

        val routes = RoutingServiceFactory.create(applicationContext, settings, automated = true)
        val route = routeWithRetry(routes, RouteRequest(origin, destination, arrival))
        if (route == null) {
            AutomationNotifier.notifyRoutingFailure(
                applicationContext,
                settings.language,
                origin,
                destination,
                arrival,
                previousEnd
            )
            return Result.failure()
        }

        return runCatching {
            val plan = DrivePlanner.plan(arrival, route.durationSeconds, settings.bufferMinutes, previousEnd)
            val pois = if (settings.showSpeedCameras || settings.showParking) {
                val points = PolylineDecoder.decode(route.encodedPolyline)
                runCatching {
                    OsmEnrichmentClient().query(points, settings.showSpeedCameras, settings.showParking)
                }.getOrDefault(emptyList())
            } else emptyList()
            val description = DriveEntryIdentity.attach(
                DriveEventDescriptionBuilder.build(
                    settings.language,
                    settings.routingProvider,
                    origin,
                    destination,
                    route,
                    pois
                ),
                identityKey
            )
            val title = resolvedDriveEventTitle(settings)
            val overlapsPrevious = previousEnd != null && plan.departureMillis < previousEnd
            if (settings.outputIcs) {
                IcsExporter(applicationContext).saveToDownloads(
                    origin,
                    destination,
                    plan.departureMillis,
                    plan.arrivalMillis,
                    title,
                    description
                )
            } else {
                calendar.insertDrive(
                    settings.targetCalendarId,
                    origin,
                    destination,
                    plan.departureMillis,
                    plan.arrivalMillis,
                    settings.reminderLeadMinutes,
                    title,
                    description
                )
            }
            if (overlapsPrevious) {
                AutomationNotifier.notifyConflict(
                    applicationContext,
                    settings.language,
                    destination,
                    plan.departureMillis
                )
            }
            Result.success()
        }.getOrElse {
            AutomationNotifier.notifyRoutingFailure(
                applicationContext,
                settings.language,
                origin,
                destination,
                arrival,
                previousEnd
            )
            Result.failure()
        }
    }

    private suspend fun routeWithRetry(routes: RoutingService, request: RouteRequest): RouteEstimate? {
        repeat(2) { attempt ->
            try {
                return routes.route(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (attempt == 0) delay(2_000)
            }
        }
        return null
    }
}
