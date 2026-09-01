package de.drivetime.notifier.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.drivetime.notifier.calendar.CalendarRepository
import de.drivetime.notifier.calendar.DriveEventDescriptionBuilder
import de.drivetime.notifier.core.DrivePlanner
import de.drivetime.notifier.data.SettingsStore
import de.drivetime.notifier.export.IcsExporter
import de.drivetime.notifier.model.RouteRequest
import de.drivetime.notifier.routing.OsmEnrichmentClient
import de.drivetime.notifier.routing.PolylineDecoder
import de.drivetime.notifier.routing.RoutingServiceFactory
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId

class NextDayWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return runCatching {
            val settings = SettingsStore(applicationContext).flow.first()
            if (!settings.automaticEnabled && inputData.getBoolean("force", false).not()) return Result.success()

            val zone = ZoneId.systemDefault()
            val day = LocalDate.now(zone).plusDays(1)
            val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val sourceIds = settings.sourceCalendarIds.mapNotNull { it.toLongOrNull() }.toSet()

            val calendar = CalendarRepository(applicationContext)
            val events = calendar.events(from, to, sourceIds)
            if (events.isEmpty()) return Result.success()

            val routes = RoutingServiceFactory.create(applicationContext, settings)
            var origin = settings.homeAddress
            var previousEnd: Long? = null

            for (event in events) {
                if (origin.isBlank()) {
                    origin = event.location
                    previousEnd = event.endMillis
                    continue
                }
                val estimate = routes.route(RouteRequest(origin, event.location, event.startMillis))
                val plan = DrivePlanner.plan(
                    destinationStartMillis = event.startMillis,
                    routeDurationSeconds = estimate.durationSeconds,
                    requestedBufferMinutes = settings.bufferMinutes,
                    previousEventEndMillis = previousEnd
                )
                val pois = if (settings.showSpeedCameras || settings.showParking) {
                    val points = PolylineDecoder.decode(estimate.encodedPolyline)
                    OsmEnrichmentClient().query(points, settings.showSpeedCameras, settings.showParking)
                } else emptyList()
                val description = DriveEventDescriptionBuilder.build(
                    settings.language,
                    settings.routingProvider,
                    origin,
                    event.location,
                    estimate,
                    pois
                )

                if (settings.outputIcs) {
                    IcsExporter(applicationContext).saveToDownloads(
                        origin, event.location, plan.departureMillis, plan.arrivalMillis
                    )
                } else {
                    calendar.insertDrive(
                        settings.targetCalendarId,
                        origin,
                        event.location,
                        plan.departureMillis,
                        plan.arrivalMillis,
                        settings.reminderLeadMinutes,
                        description
                    )
                }
                origin = event.location
                previousEnd = event.endMillis
            }
            Result.success()
        }.getOrElse { Result.retry() }
    }
}
