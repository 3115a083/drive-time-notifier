package de.drivetime.notifier.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.drivetime.notifier.calendar.CalendarRepository
import de.drivetime.notifier.calendar.DriveEntryIdentity
import de.drivetime.notifier.calendar.DriveEventDescriptionBuilder
import de.drivetime.notifier.core.DrivePlanner
import de.drivetime.notifier.core.DynamicArrivalBuffer
import de.drivetime.notifier.data.SettingsStore
import de.drivetime.notifier.data.excludesLocation
import de.drivetime.notifier.data.startLocationForCalendar
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
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class NextDayWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val forced = inputData.getBoolean("force", false)
        try {
            return process(forced)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val settings = SettingsStore(applicationContext).flow.first()
            AutomationRunLog.add(applicationContext, if (forced) "manual-next-day" else "scheduled", "failed", error.javaClass.simpleName)
            AutomationNotifier.notifyBackgroundActionProblem(applicationContext, settings.language,
                "Calendar processing could not finish. Check calendar access and the diagnostic log.",
                "Die Kalenderverarbeitung konnte nicht abgeschlossen werden. Prüfe Kalenderzugriff und Diagnoseprotokoll.")
            return Result.failure()
        } finally {
            if (!forced) withContext(NonCancellable) {
                val settings = SettingsStore(applicationContext).flow.first()
                AutomationScheduler.configure(applicationContext, settings.automaticEnabled, settings.autoHour, settings.autoMinute)
            }
        }
    }

    private suspend fun process(forced: Boolean): Result {
        val calculationRevision = System.currentTimeMillis()
        val settings = SettingsStore(applicationContext).flow.first()
        val now = ZonedDateTime.now()
        val scheduled = inputData.getLong("scheduled_for", -1L)
        val trigger = if (forced) "manual-next-day" else "scheduled"
        if (!forced && (!settings.automaticEnabled || !AutomationTiming.mayRun(now, scheduled, settings.autoHour, settings.autoMinute))) {
            AutomationRunLog.add(applicationContext, trigger, "skipped-outside-window", "scheduled=$scheduled configured=${settings.autoHour}:${settings.autoMinute}")
            return Result.success()
        }
        if (forced && !AutomationTiming.mayRunManual(now.toInstant().toEpochMilli(), inputData.getLong("requested_at", -1L))) {
            AutomationRunLog.add(applicationContext, trigger, "skipped-stale-manual-request")
            return Result.success()
        }
        val zone = now.zone
        val day = if (forced) inputData.getString("planning_day")?.let(LocalDate::parse) ?: now.toLocalDate().plusDays(1)
            else AutomationTiming.planningDay(scheduled, now)
        AutomationRunLog.add(applicationContext, trigger, "started", "day=$day scheduled=$scheduled")
        setForeground(AutomationNotifier.processingForegroundInfo(applicationContext, settings.language, id))
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val sourceIds = settings.sourceCalendarIds.mapNotNull { it.toLongOrNull() }.toSet()

        val calendar = CalendarRepository(applicationContext)
        val events = calendar.events(from, to, sourceIds)
        if (events.isEmpty()) return Result.success()

        val routes = RoutingServiceFactory.create(applicationContext, settings, automated = true)
        var previousEnd: Long? = null

        for (event in events) {
            if (isStopped) return Result.failure()
            if (!forced) {
                val latest = SettingsStore(applicationContext).flow.first()
                if (!latest.automaticEnabled || !AutomationTiming.mayRun(ZonedDateTime.now(), scheduled, latest.autoHour, latest.autoMinute)) {
                    AutomationRunLog.add(applicationContext, trigger, "stopped-settings-or-window-changed")
                    return Result.success()
                }
            }
            if (settings.excludesLocation(event.location)) {
                previousEnd = event.endMillis
                continue
            }

            val origin = settings.startLocationForCalendar(event.calendarId)
            if (origin.isBlank()) {
                previousEnd = event.endMillis
                continue
            }

            val identityKey = DriveEntryIdentity.key(event.location, event.startMillis)
            val request = RouteRequest(origin, event.location, event.startMillis)
            val initialEstimate = routeWithRetry(routes, request)
            if (initialEstimate == null) {
                AutomationRunLog.add(applicationContext, trigger, "routing-failed", identityKey)
                AutomationNotifier.notifyRoutingFailure(
                    applicationContext,
                    settings.language,
                    origin,
                    event.location,
                    event.startMillis,
                    previousEnd,
                    trigger = trigger
                )
                previousEnd = event.endMillis
                continue
            }

            val enriched = ChargingRoutePlanner.prepare(
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
                requestedBufferMinutes = DynamicArrivalBuffer.totalMinutes(
                    settings,
                    estimate.durationSeconds,
                    estimate.trafficDelaySeconds
                ),
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
                    enriched.navigation,
                    DynamicArrivalBuffer.extraMinutes(settings, estimate.durationSeconds, estimate.trafficDelaySeconds)
                        .takeIf { settings.dynamicBufferEnabled }
                ),
                identityKey
            )

            val title = resolvedDriveEventTitle(settings)
            val overlapsPrevious = previousEnd != null && plan.departureMillis < previousEnd
            runCatching {
                if (settings.outputIcs) {
                    IcsExporter(applicationContext).saveToDownloads(
                        origin,
                        event.location,
                        plan.departureMillis,
                        plan.arrivalMillis + enriched.walkingDurationSeconds * 1_000L,
                        title,
                        description,
                        appointmentMillis = event.startMillis,
                        calculatedAtMillis = calculationRevision
                    )
                } else {
                    calendar.insertDrive(
                        settings.targetCalendarId,
                        origin,
                        event.location,
                        plan.departureMillis,
                        plan.arrivalMillis + enriched.walkingDurationSeconds * 1_000L,
                        settings.reminderLeadMinutes,
                        title,
                        description,
                        destinationStartMillis = event.startMillis,
                        calculatedAtMillis = calculationRevision
                    )
                }
                AutomationRunLog.add(applicationContext, trigger, "saved-or-newer-plan-preserved", identityKey)
            }.onFailure {
                if (it is CancellationException) throw it
                AutomationRunLog.add(applicationContext, trigger, "save-failed", it.javaClass.simpleName)
                AutomationNotifier.notifyBackgroundActionProblem(applicationContext, settings.language,
                    "The planned drive could not be saved. Check the destination calendar or ICS output.",
                    "Die geplante Fahrt konnte nicht gespeichert werden. Prüfe Zielkalender oder ICS-Ausgabe.")
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
        AutomationRunLog.add(applicationContext, trigger, "finished", "appointments=${events.size}")
        return Result.success()
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
