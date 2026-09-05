package de.drivetime.notifier.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.drivetime.notifier.calendar.CalendarRepository
import de.drivetime.notifier.calendar.DriveEventDescriptionBuilder
import de.drivetime.notifier.core.DrivePlanner
import de.drivetime.notifier.data.SettingsStore
import de.drivetime.notifier.data.excludesLocation
import de.drivetime.notifier.data.startLocationForCalendar
import de.drivetime.notifier.export.IcsExporter
import de.drivetime.notifier.model.RouteEstimate
import de.drivetime.notifier.model.RouteRequest
import de.drivetime.notifier.routing.OsmEnrichmentClient
import de.drivetime.notifier.routing.PolylineDecoder
import de.drivetime.notifier.routing.RoutingServiceFactory
import de.drivetime.notifier.ui.resolvedDriveEventTitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.time.LocalDate
import java.time.ZoneId

class NextDayWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val settings = SettingsStore(applicationContext).flow.first()
        if (!settings.automaticEnabled && inputData.getBoolean("force", false).not()) return Result.success()

        val zone = ZoneId.systemDefault()
        val day = LocalDate.now(zone).plusDays(1)
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val sourceIds = settings.sourceCalendarIds.mapNotNull { it.toLongOrNull() }.toSet()

        val calendar = CalendarRepository(applicationContext)
        val events = runCatching { calendar.events(from, to, sourceIds) }.getOrElse { return Result.retry() }
        if (events.isEmpty()) return Result.success()

        val routes = RoutingServiceFactory.create(applicationContext, settings, automated = true)
        var previousEnd: Long? = null

        for (event in events) {
            if (settings.excludesLocation(event.location)) {
                previousEnd = event.endMillis
                continue
            }

            val origin = settings.startLocationForCalendar(event.calendarId)
            if (origin.isBlank()) {
                previousEnd = event.endMillis
                continue
            }

            val estimate = routeWithRetry(routes, RouteRequest(origin, event.location, event.startMillis))
            if (estimate == null) {
                AutomationNotifier.notifyRoutingFailure(
                    applicationContext,
                    settings.language,
                    origin,
                    event.location,
                    event.startMillis,
                    previousEnd
                )
                previousEnd = event.endMillis
                continue
            }

            val plan = DrivePlanner.plan(
                destinationStartMillis = event.startMillis,
                routeDurationSeconds = estimate.durationSeconds,
                requestedBufferMinutes = settings.bufferMinutes,
                previousEventEndMillis = previousEnd
            )
            val pois = if (settings.showSpeedCameras || settings.showParking) {
                val points = PolylineDecoder.decode(estimate.encodedPolyline)
                runCatching { OsmEnrichmentClient().query(points, settings.showSpeedCameras, settings.showParking) }.getOrDefault(emptyList())
            } else emptyList()
            val description = DriveEventDescriptionBuilder.build(
                settings.language,
                settings.routingProvider,
                origin,
                event.location,
                estimate,
                pois
            )

            val title = resolvedDriveEventTitle(settings)
            val overlapsPrevious = previousEnd != null && plan.departureMillis < previousEnd
            runCatching {
                if (settings.outputIcs) {
                    IcsExporter(applicationContext).saveToDownloads(
                        origin,
                        event.location,
                        plan.departureMillis,
                        plan.arrivalMillis,
                        title,
                        description
                    )
                } else {
                    calendar.insertDrive(
                        settings.targetCalendarId,
                        origin,
                        event.location,
                        plan.departureMillis,
                        plan.arrivalMillis,
                        settings.reminderLeadMinutes,
                        title,
                        description
                    )
                }
            }.onFailure {
                AutomationNotifier.notifyRoutingFailure(
                    applicationContext,
                    settings.language,
                    origin,
                    event.location,
                    event.startMillis,
                    previousEnd
                )
            }

            if (overlapsPrevious) {
                AutomationNotifier.notifyConflict(
                    applicationContext,
                    settings.language,
                    event.location,
                    plan.departureMillis
                )
            }
            previousEnd = event.endMillis
        }
        return Result.success()
    }

    private suspend fun routeWithRetry(
        routes: de.drivetime.notifier.routing.RoutingService,
        request: RouteRequest
    ): RouteEstimate? {
        repeat(2) { attempt ->
            val result = runCatching {
                withTimeout(55_000) { routes.route(request) }
            }.getOrNull()
            if (result != null) return result
            if (attempt == 0) delay(2_000)
        }
        return null
    }
}
