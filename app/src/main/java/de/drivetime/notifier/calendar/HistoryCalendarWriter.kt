package de.drivetime.notifier.calendar

import android.content.Context
import de.drivetime.notifier.data.*
import de.drivetime.notifier.ui.resolvedDriveEventTitle

/** Save the recorded plan as-is, without geocoding, routing or recalculating its buffer. */
object HistoryCalendarWriter {
    suspend fun save(context: Context, settings: AppSettings, entry: RouteHistoryEntry): Long {
        require(settings.targetCalendarId >= 0) { "Select a destination calendar in Settings first. / Zuerst einen Zielkalender in den Einstellungen auswählen." }
        val provider = RoutingProvider.entries.firstOrNull { it.id == entry.route.providerId } ?: settings.routingProvider
        val description = DriveEntryIdentity.attach(
            DriveEventDescriptionBuilder.build(settings.language, provider, entry.request.origin, entry.request.destination,
                entry.route, emptyList()), DriveEntryIdentity.key(entry.request.destination, entry.request.arrivalMillis))
        val eventId = CalendarRepository(context).insertDrive(settings.targetCalendarId, entry.request.origin,
            entry.request.destination, entry.departureMillis, entry.arrivalMillis, settings.reminderLeadMinutes,
            resolvedDriveEventTitle(settings), description, destinationStartMillis = entry.request.arrivalMillis,
            calculatedAtMillis = entry.calculatedAt)
        RouteHistoryStore(context).markSafely(context, entry, HistorySaveState.CALENDAR)
        return eventId
    }
}
