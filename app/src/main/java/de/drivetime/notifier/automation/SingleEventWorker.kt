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

class SingleEventWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        val settings = SettingsStore(applicationContext).flow.first()
        val destination = inputData.getString("destination").orEmpty().trim()
        val arrival = inputData.getLong("arrival_millis", -1L)
        val previousEnd = inputData.getLong("previous_end_millis", -1L).takeIf { it > 0 }
        val origin = inputData.getString("origin").orEmpty().trim().ifBlank { settings.homeAddress }
        require(origin.isNotBlank() && destination.isNotBlank() && arrival > 0) { "Ungültige Triggerdaten." }

        val route = RoutingServiceFactory.create(applicationContext, settings)
            .route(RouteRequest(origin, destination, arrival))
        val plan = DrivePlanner.plan(arrival, route.durationSeconds, settings.bufferMinutes, previousEnd)
        val pois = if (settings.showSpeedCameras || settings.showParking) {
            val points = PolylineDecoder.decode(route.encodedPolyline)
            OsmEnrichmentClient().query(points, settings.showSpeedCameras, settings.showParking)
        } else emptyList()
        val description = DriveEventDescriptionBuilder.build(
            settings.language,
            settings.routingProvider,
            origin,
            destination,
            route,
            pois
        )
        if (settings.outputIcs) {
            IcsExporter(applicationContext).saveToDownloads(origin, destination, plan.departureMillis, plan.arrivalMillis)
        } else {
            CalendarRepository(applicationContext).insertDrive(
                settings.targetCalendarId,
                origin,
                destination,
                plan.departureMillis,
                plan.arrivalMillis,
                settings.reminderLeadMinutes,
                description
            )
        }
        Result.success()
    }.getOrElse { Result.failure() }
}
