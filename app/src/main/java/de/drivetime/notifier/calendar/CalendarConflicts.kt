package de.drivetime.notifier.calendar

import de.drivetime.notifier.model.CalendarEventRef

object CalendarConflicts {
    /** Include overlapping appointments and appointments without locations. */
    fun previousEnd(events:List<CalendarEventRef>,target:CalendarEventRef):Long? =
        events.asSequence()
            .filterNot {it.id==target.id && it.startMillis==target.startMillis}
            .filter {it.startMillis<=target.startMillis}
            .maxOfOrNull {it.endMillis}
}
