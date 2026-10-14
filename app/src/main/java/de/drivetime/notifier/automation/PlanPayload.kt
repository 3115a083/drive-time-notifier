package de.drivetime.notifier.automation

import de.drivetime.notifier.calendar.DriveEntryIdentity
import java.time.Instant
import java.time.ZoneId

data class SavedPlan(
    val origin: String,
    val destination: String,
    val departureMillis: Long,
    val arrivalMillis: Long,
    val appointmentMillis: Long?,
    val eventId: Long?,
    val calendarId: Long?,
    val revisionMillis: Long,
    val operation: String,
    val identityKey: String?,
    val title: String = ""
) {
    val planId: String get() = if (eventId != null) "calendar:$calendarId:$eventId"
        else "ics:${identityKey ?: DriveEntryIdentity.key(destination, appointmentMillis ?: arrivalMillis)}:${DriveEntryIdentity.originKey(origin)}"
}

enum class PlanPayloadField(val id: String, val english: String, val german: String) {
    ORIGIN("start_address", "Start address", "Startadresse"),
    DESTINATION("destination_address", "Destination address", "Zieladresse"),
    DEPARTURE("start_time", "Departure time (ISO and milliseconds)", "Abfahrtszeit (ISO und Millisekunden)"),
    ARRIVAL("arrival_time", "Arrival time (ISO and milliseconds)", "Ankunftszeit (ISO und Millisekunden)"),
    APPOINTMENT("appointment_time", "Original appointment time", "Ursprüngliche Terminzeit"),
    ID("plan_id", "Stable plan ID", "Stabile Planungs-ID"),
    REVISION("revision_ms", "Saved plan revision", "Zeitpunkt der gespeicherten Planung"),
    OPERATION("operation", "Created / updated / exported", "Erstellt / aktualisiert / exportiert"),
    DURATION("planned_duration_seconds", "Calendar entry duration", "Dauer des Kalendereintrags"),
    BUFFER("buffer_minutes", "Applied arrival buffer", "Angewendeter Ankunftspuffer"),
    EVENT("calendar_event_id", "Calendar event ID", "Kalendertermin-ID"),
    CALENDAR("calendar_id", "Calendar ID", "Kalender-ID"),
    TITLE("event_title", "Calendar entry title", "Titel des Kalendereintrags"),
    ZONE("timezone", "Time zone", "Zeitzone");

    companion object {
        val defaults = setOf(ORIGIN.id, DESTINATION.id, DEPARTURE.id, ARRIVAL.id, ID.id, REVISION.id)
    }
}

object PlanPayload {
    fun values(plan: SavedPlan, selected: Set<String>, deliveryId: String): Map<String, Any> = buildMap {
        put("schema_version", 1)
        put("delivery_id", deliveryId)
        fun time(name: String, millis: Long) {
            put(name, Instant.ofEpochMilli(millis).toString())
            put("${name}_ms", millis)
        }
        PlanPayloadField.entries.filter { it.id in selected }.forEach { field ->
            when (field) {
                PlanPayloadField.ORIGIN -> put(field.id, plan.origin)
                PlanPayloadField.DESTINATION -> put(field.id, plan.destination)
                PlanPayloadField.DEPARTURE -> time(field.id, plan.departureMillis)
                PlanPayloadField.ARRIVAL -> time(field.id, plan.arrivalMillis)
                PlanPayloadField.APPOINTMENT -> plan.appointmentMillis?.let { time(field.id, it) }
                PlanPayloadField.ID -> put(field.id, plan.planId)
                PlanPayloadField.REVISION -> put(field.id, plan.revisionMillis)
                PlanPayloadField.OPERATION -> put(field.id, plan.operation)
                PlanPayloadField.DURATION -> put(field.id, (plan.arrivalMillis - plan.departureMillis).coerceAtLeast(0) / 1_000)
                PlanPayloadField.BUFFER -> plan.appointmentMillis?.let { put(field.id, ((it - plan.arrivalMillis) / 60_000).coerceAtLeast(0)) }
                PlanPayloadField.EVENT -> plan.eventId?.let { put(field.id, it) }
                PlanPayloadField.CALENDAR -> plan.calendarId?.let { put(field.id, it) }
                PlanPayloadField.TITLE -> put(field.id, plan.title)
                PlanPayloadField.ZONE -> put(field.id, ZoneId.systemDefault().id)
            }
        }
    }
}
