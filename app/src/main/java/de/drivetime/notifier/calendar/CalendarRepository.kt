package de.drivetime.notifier.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import de.drivetime.notifier.model.CalendarEventRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

data class CalendarInfo(val id: Long, val name: String, val accountName: String)

class CalendarRepository(private val context: Context) {
    suspend fun calendars(): List<CalendarInfo> = withContext(Dispatchers.IO) {
        val out = mutableListOf<CalendarInfo>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME
        )
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.VISIBLE}=1",
            null,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " COLLATE NOCASE"
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val name = c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val account = c.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
            while (c.moveToNext()) {
                out += CalendarInfo(c.getLong(id), c.getString(name).orEmpty(), c.getString(account).orEmpty())
            }
        }
        out
    }

    suspend fun events(from: Long, to: Long, calendarIds: Set<Long> = emptySet()): List<CalendarEventRef> =
        withContext(Dispatchers.IO) {
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, from)
            ContentUris.appendId(builder, to)
            val projection = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END
            )
            val out = mutableListOf<CalendarEventRef>()
            context.contentResolver.query(builder.build(), projection, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                while (c.moveToNext()) {
                    val calendarId = c.getLong(1)
                    if (calendarIds.isNotEmpty() && calendarId !in calendarIds) continue
                    val location = c.getString(3).orEmpty().trim()
                    if (location.isBlank()) continue
                    out += CalendarEventRef(
                        id = c.getLong(0),
                        calendarId = calendarId,
                        title = c.getString(2).orEmpty(),
                        location = location,
                        startMillis = c.getLong(4),
                        endMillis = c.getLong(5)
                    )
                }
            }
            out.distinctBy { it.id to it.startMillis }
        }

    suspend fun insertDrive(
        calendarId: Long,
        origin: String,
        destination: String,
        departureMillis: Long,
        arrivalMillis: Long,
        reminderLeadMinutes: Int
    ): Long = withContext(Dispatchers.IO) {
        require(calendarId >= 0) { "Kein Zielkalender gewählt." }
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, "Fahrt zum Termin")
            put(CalendarContract.Events.EVENT_LOCATION, destination)
            put(CalendarContract.Events.DESCRIPTION, "Automatisch berechnete Fahrt. Start: $origin")
            put(CalendarContract.Events.DTSTART, departureMillis)
            put(CalendarContract.Events.DTEND, arrivalMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            put(CalendarContract.Events.HAS_ALARM, 1)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: error("Kalendereintrag konnte nicht gespeichert werden.")
        val eventId = ContentUris.parseId(uri)
        runCatching {
            context.contentResolver.insert(
                CalendarContract.Reminders.CONTENT_URI,
                ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, reminderLeadMinutes.coerceAtLeast(0))
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }
            )
        }
        eventId
    }
}
