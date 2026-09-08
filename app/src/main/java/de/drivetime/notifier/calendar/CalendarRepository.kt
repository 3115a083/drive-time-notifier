package de.drivetime.notifier.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import de.drivetime.notifier.model.CalendarEventRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId
import kotlin.math.abs

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
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val name = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val account = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
            while (cursor.moveToNext()) {
                out += CalendarInfo(cursor.getLong(id), cursor.getString(name).orEmpty(), cursor.getString(account).orEmpty())
            }
        }
        out
    }

    suspend fun events(from: Long, to: Long, calendarIds: Set<Long>): List<CalendarEventRef> =
        withContext(Dispatchers.IO) {
            if (calendarIds.isEmpty()) return@withContext emptyList()
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
            context.contentResolver.query(builder.build(), projection, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    val calendarId = cursor.getLong(1)
                    if (calendarId !in calendarIds) continue
                    val location = cursor.getString(3).orEmpty().trim()
                    if (location.isBlank()) continue
                    out += CalendarEventRef(
                        id = cursor.getLong(0),
                        calendarId = calendarId,
                        title = cursor.getString(2).orEmpty(),
                        location = location,
                        startMillis = cursor.getLong(4),
                        endMillis = cursor.getLong(5)
                    )
                }
            }
            out.distinctBy { it.id to it.startMillis }
        }

    suspend fun findExistingDrive(
        calendarId: Long,
        destination: String,
        destinationStartMillis: Long,
        identityKey: String = DriveEntryIdentity.key(destination, destinationStartMillis)
    ): ExistingDriveEntry? = withContext(Dispatchers.IO) {
        if (calendarId < 0 || destination.isBlank() || destinationStartMillis <= 0) return@withContext null

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND
        )
        val from = destinationStartMillis - 24L * 60 * 60 * 1000
        val to = destinationStartMillis + 5L * 60 * 1000
        val selection = "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.DTSTART}>=? AND ${CalendarContract.Events.DTSTART}<=?"
        val args = arrayOf(calendarId.toString(), from.toString(), to.toString())
        val normalizedDestination = DriveEntryIdentity.normalizeLocation(destination)
        val legacyTolerance = 185L * 60 * 1000

        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            args,
            CalendarContract.Events.DTSTART + " ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val eventId = cursor.getLong(0)
                val location = cursor.getString(1).orEmpty()
                val description = cursor.getString(2).orEmpty()
                val startMillis = cursor.getLong(3)
                val endMillis = cursor.getLong(4)

                if (DriveEntryIdentity.hasMarker(description, identityKey)) {
                    return@withContext ExistingDriveEntry(eventId, startMillis, endMillis)
                }

                val legacyMatch = DriveEntryIdentity.isOwnDescription(description) &&
                    DriveEntryIdentity.normalizeLocation(location) == normalizedDestination &&
                    endMillis <= destinationStartMillis + 5L * 60 * 1000 &&
                    abs(destinationStartMillis - endMillis) <= legacyTolerance
                if (legacyMatch) {
                    return@withContext ExistingDriveEntry(eventId, startMillis, endMillis)
                }
            }
        }
        null
    }

    suspend fun insertDrive(
        calendarId: Long,
        origin: String,
        destination: String,
        departureMillis: Long,
        arrivalMillis: Long,
        reminderLeadMinutes: Int,
        title: String,
        description: String
    ): Long = withContext(Dispatchers.IO) {
        require(calendarId >= 0) { "No target calendar selected." }
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title.take(120))
            put(CalendarContract.Events.EVENT_LOCATION, destination)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.DTSTART, departureMillis)
            put(CalendarContract.Events.DTEND, arrivalMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            put(CalendarContract.Events.HAS_ALARM, 1)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: error("Calendar event could not be saved.")
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
