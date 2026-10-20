package de.drivetime.notifier.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.ContentProviderOperation
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

    suspend fun events(from: Long, to: Long, calendarIds: Set<Long>, includeWithoutLocation: Boolean = false): List<CalendarEventRef> =
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
                CalendarContract.Instances.END,
                CalendarContract.Instances.DESCRIPTION
            )
            val out = mutableListOf<CalendarEventRef>()
            val cursorResult = context.contentResolver.query(builder.build(), projection, null, null, CalendarContract.Instances.BEGIN + " ASC")
                ?: error("Calendar appointments could not be read.")
            cursorResult.use { cursor ->
                while (cursor.moveToNext()) {
                    val calendarId = cursor.getLong(1)
                    if (calendarId !in calendarIds) continue
                    if (DriveEntryIdentity.isOwnDescription(cursor.getString(6).orEmpty())) continue
                    val location = cursor.getString(3).orEmpty().trim()
                    if (!includeWithoutLocation && location.isBlank()) continue
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
        identityKey: String = DriveEntryIdentity.key(destination, destinationStartMillis),
        origin: String? = null
    ): ExistingDriveEntry? = matchingDrives(calendarId, destination, destinationStartMillis, identityKey, origin)
        .maxWithOrNull(compareBy<ExistingDriveEntry> { DriveEntryIdentity.revision(it.description) }.thenBy { it.eventId })

    private suspend fun matchingDrives(
        calendarId: Long,
        destination: String,
        destinationStartMillis: Long,
        identityKey: String = DriveEntryIdentity.key(destination, destinationStartMillis),
        origin: String? = null
    ): List<ExistingDriveEntry> = withContext(Dispatchers.IO) {
        if (calendarId < 0 || destination.isBlank() || destinationStartMillis <= 0) return@withContext emptyList()

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND
        )
        val from = destinationStartMillis - 24L * 60 * 60 * 1000
        val to = destinationStartMillis + 5L * 60 * 1000
        val selection = "${CalendarContract.Events.CALENDAR_ID}=? AND " +
            "${CalendarContract.Events.DELETED}=0 AND (" +
            "${CalendarContract.Events.DESCRIPTION} LIKE ? OR " +
            "(${CalendarContract.Events.DTSTART}>=? AND ${CalendarContract.Events.DTSTART}<=?))"
        require(identityKey.matches(Regex("[a-f0-9]{24}"))) { "Invalid drive identity." }
        val args = arrayOf(calendarId.toString(), "%${DriveEntryIdentity.marker(identityKey)}%", from.toString(), to.toString())
        val normalizedDestination = DriveEntryIdentity.normalizeLocation(destination)
        val legacyTolerance = 185L * 60 * 1000

        val cursorResult = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            args,
            CalendarContract.Events.DTSTART + " ASC"
        ) ?: error("Calendar duplicate check failed.")
        val matches = mutableListOf<ExistingDriveEntry>()
        cursorResult.use { cursor ->
            while (cursor.moveToNext()) {
                val eventId = cursor.getLong(0)
                val location = cursor.getString(1).orEmpty()
                val description = cursor.getString(2).orEmpty()
                val startMillis = cursor.getLong(3)
                val endMillis = cursor.getLong(4)

                if (origin != null && !DriveEntryIdentity.matchesOrigin(description, origin)) continue
                if (DriveEntryIdentity.normalizeLocation(location) != normalizedDestination) continue
                if (DriveEntryIdentity.hasMarker(description, identityKey)) {
                    matches += ExistingDriveEntry(eventId, startMillis, endMillis, description)
                    continue
                }

                val legacyMatch = !DriveEntryIdentity.hasAnyMarker(description) &&
                    DriveEntryIdentity.isOwnDescription(description) &&
                    DriveEntryIdentity.normalizeLocation(location) == normalizedDestination &&
                    endMillis <= destinationStartMillis + 5L * 60 * 1000 &&
                    abs(destinationStartMillis - endMillis) <= legacyTolerance
                if (legacyMatch) {
                    matches += ExistingDriveEntry(eventId, startMillis, endMillis, description)
                }
            }
        }
        matches
    }

    suspend fun insertDrive(
        calendarId: Long,
        origin: String,
        destination: String,
        departureMillis: Long,
        arrivalMillis: Long,
        reminderLeadMinutes: Int,
        title: String,
        description: String,
        destinationStartMillis: Long? = null,
        calculatedAtMillis: Long = System.currentTimeMillis()
    ): Long = withContext(Dispatchers.IO) {
        require(calendarId >= 0) { "No target calendar selected." }
        val identityKey = DriveEntryIdentity.extractKey(description)
        if (identityKey != null) {
            require(destinationStartMillis != null) { "Appointment time is required for drive updates." }
            require(identityKey == DriveEntryIdentity.key(destination, destinationStartMillis)) { "Drive identity does not match the appointment." }
        }
        DriveWriteGuard.locked {
            val matches = if (identityKey != null) matchingDrives(calendarId, destination,
                requireNotNull(destinationStartMillis), identityKey, origin) else emptyList()
            val existing = matches.maxWithOrNull(compareBy<ExistingDriveEntry> { DriveEntryIdentity.revision(it.description) }.thenBy { it.eventId })
            // A slow older request must not overwrite a newer calculation that already finished.
            if (existing != null && DriveEntryIdentity.revision(existing.description) > calculatedAtMillis) {
                return@locked existing.eventId
            }
            val saveRevision = maxOf(System.currentTimeMillis(), DriveEntryIdentity.savedRevision(existing?.description.orEmpty()) + 1)
            val finalDescription = if (identityKey != null)
                DriveEntryIdentity.attachPlanningMetadata(description, origin, calculatedAtMillis) + "\n[DriveTimeNotifier-Saved:$saveRevision]"
            else description
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title.take(120))
                put(CalendarContract.Events.EVENT_LOCATION, destination)
                put(CalendarContract.Events.DESCRIPTION, finalDescription)
                put(CalendarContract.Events.DTSTART, departureMillis)
                put(CalendarContract.Events.DTEND, arrivalMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
                put(CalendarContract.Events.HAS_ALARM, 1)
            }
            val operations = arrayListOf<ContentProviderOperation>()
            if (existing == null) {
                operations += ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI).withValues(values).build()
            } else {
                operations += ContentProviderOperation.newUpdate(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existing.eventId))
                    .withValues(values).withExpectedCount(1).build()
                operations += ContentProviderOperation.newDelete(CalendarContract.Reminders.CONTENT_URI)
                    .withSelection("${CalendarContract.Reminders.EVENT_ID}=? AND ${CalendarContract.Reminders.METHOD}=?",
                        arrayOf(existing.eventId.toString(), CalendarContract.Reminders.METHOD_ALERT.toString())).build()
                // Remove only confirmed app-generated duplicates for this exact appointment and route.
                matches.filter { it.eventId != existing.eventId && identityKey != null && DriveEntryIdentity.hasMarker(it.description, identityKey) }
                    .forEach { duplicate ->
                        operations += ContentProviderOperation.newDelete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, duplicate.eventId)).build()
                    }
            }
            val reminder = ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                .withValue(CalendarContract.Reminders.MINUTES, reminderLeadMinutes.coerceAtLeast(0))
                .withValue(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            if (existing == null) reminder.withValueBackReference(CalendarContract.Reminders.EVENT_ID, 0)
            else reminder.withValue(CalendarContract.Reminders.EVENT_ID, existing.eventId)
            operations += reminder.build()
            val results = context.contentResolver.applyBatch(CalendarContract.AUTHORITY, operations)
            val eventId = existing?.eventId ?: ContentUris.parseId(requireNotNull(results[0].uri))
            eventId
        }
    }
}
