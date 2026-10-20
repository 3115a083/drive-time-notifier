package de.drivetime.notifier.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.drivetime.notifier.calendar.CalendarRepository
import de.drivetime.notifier.calendar.DriveEntryIdentity
import de.drivetime.notifier.calendar.DriveEventDescriptionBuilder
import de.drivetime.notifier.core.DrivePlanner
import de.drivetime.notifier.core.DynamicArrivalBuffer
import de.drivetime.notifier.data.*
import de.drivetime.notifier.export.IcsExporter
import de.drivetime.notifier.model.RouteEstimate
import de.drivetime.notifier.model.RouteRequest
import de.drivetime.notifier.routing.ChargingRoutePlanner
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
    private var historyEntry: RouteHistoryEntry? = null
    override suspend fun doWork(): Result = try {
        process()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        RouteHistoryStore(applicationContext).markSafely(applicationContext, historyEntry, HistorySaveState.FAILED)
        val settings = SettingsStore(applicationContext).flow.first()
        AutomationRunLog.add(applicationContext, "single-event", "failed", error.javaClass.simpleName)
        AutomationNotifier.notifyBackgroundActionProblem(applicationContext, settings.language,
            "The drive could not be processed or saved. Check calendar access and the diagnostic log.",
            "Die Fahrt konnte nicht verarbeitet oder gespeichert werden. Prüfe Kalenderzugriff und Diagnoseprotokoll.")
        Result.failure()
    }

    private suspend fun process(): Result {
        val calculationRevision = System.currentTimeMillis()
        val settings = SettingsStore(applicationContext).flow.first()
        val destination = inputData.getString("destination").orEmpty().trim()
        val arrival = inputData.getLong("arrival_millis", -1L)
        val previousEnd = inputData.getLong("previous_end_millis", -1L).takeIf { it > 0 }
        val origin = inputData.getString("origin").orEmpty().trim().ifBlank { settings.homeAddress }
        if (origin.isBlank() || destination.isBlank() || arrival <= 0) return Result.failure()

        setForeground(AutomationNotifier.processingForegroundInfo(applicationContext, settings.language, id))
        val identityKey = DriveEntryIdentity.key(destination, arrival)
        AutomationRunLog.add(applicationContext, "single-event", "started", identityKey)
        val calendar = CalendarRepository(applicationContext)

        val routes = RoutingServiceFactory.create(applicationContext, settings, automated = true)
        val request = RouteRequest(origin, destination, arrival)
        val initialRoute = routeWithRetry(routes, request)
        if (initialRoute == null) {
            AutomationRunLog.add(applicationContext, "single-event", "routing-failed", identityKey)
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

        return run {
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
                DynamicArrivalBuffer.totalMinutes(settings, route.durationSeconds, route.trafficDelaySeconds),
                previousEnd
            )
            historyEntry = RouteHistoryStore(applicationContext).recordSafely(applicationContext) {
                RouteHistoryEntry.create(
                    inputData.getString("history_id") ?: id.toString(),
                    inputData.getLong("requested_at", calculationRevision),
                    HistoryTrigger.APP_ACTION, request, route, plan, enriched.walkingDurationSeconds
                )
            }
            val pois = enriched.pois
            val description = DriveEntryIdentity.attach(
                DriveEventDescriptionBuilder.build(
                    settings.language,
                    settings.routingProvider,
                    origin,
                    destination,
                    route,
                    pois,
                    enriched.navigation,
                    DynamicArrivalBuffer.extraMinutes(settings, route.durationSeconds, route.trafficDelaySeconds)
                        .takeIf { settings.dynamicBufferEnabled }
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
                    plan.arrivalMillis + enriched.walkingDurationSeconds * 1_000L,
                    title,
                    description,
                    appointmentMillis = arrival,
                    calculatedAtMillis = calculationRevision
                )
            } else {
                calendar.insertDrive(
                    settings.targetCalendarId,
                    origin,
                    destination,
                    plan.departureMillis,
                    plan.arrivalMillis + enriched.walkingDurationSeconds * 1_000L,
                    settings.reminderLeadMinutes,
                    title,
                    description,
                    destinationStartMillis = arrival,
                    calculatedAtMillis = calculationRevision
                )
            }
            RouteHistoryStore(applicationContext).markSafely(applicationContext, historyEntry,
                if (settings.outputIcs) HistorySaveState.ICS else HistorySaveState.CALENDAR)
            if (overlapsPrevious) {
                AutomationNotifier.notifyConflict(
                    applicationContext,
                    settings.language,
                    destination,
                    plan.departureMillis
                )
            }
            AutomationRunLog.add(applicationContext, "single-event", "saved-or-newer-plan-preserved", identityKey)
            Result.success()
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
