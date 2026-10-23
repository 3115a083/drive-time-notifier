package de.drivetime.notifier.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomationCalendarTest {
    @Test fun concurrentPlanningUpdatesOneDriveAndExcludesGeneratedEvents() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_CALENDAR)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.WRITE_CALENDAR)
        val resolver = context.contentResolver
        val account = "automation-test-${System.nanoTime()}"
        val calendarsUri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        val calendarUri = requireNotNull(resolver.insert(calendarsUri, ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, account)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, account)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Automation test")
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xff0088ff.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, account)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }))
        val calendar = ContentUris.parseId(calendarUri)
        val repo = CalendarRepository(context)
        val arrival = (System.currentTimeMillis() / 60_000 + 24 * 60) * 60_000
        val destination = "Test Street 1"
        val key = DriveEntryIdentity.key(destination, arrival)
        val description = DriveEntryIdentity.attach("Drive", key)
        val revision = System.currentTimeMillis()
        try {
            val meeting = repo.insertDrive(calendar, "Home", destination, arrival, arrival + 3_600_000, 0, "Appointment", "Source appointment")
            val saves = (1..20).map { index -> async(Dispatchers.Default) {
                repo.insertDrive(calendar, "Home", destination, arrival - 3_600_000 + index * 1_000, arrival - 600_000 + index * 1_000,
                    0, "Drive", description, destinationStartMillis = arrival, calculatedAtMillis = revision + index)
            } }.awaitAll()
            assertEquals(1, saves.distinct().size)
            val newest = requireNotNull(repo.findExistingDrive(calendar, destination, arrival, origin = "Home"))
            assertEquals(arrival - 600_000 + 20_000, newest.endMillis)
            assertEquals(revision + 20, DriveEntryIdentity.revision(newest.description))
            assertEquals(listOf(meeting), repo.events(arrival - 7_200_000, arrival + 7_200_000, setOf(calendar)).map { it.id })
            assertNull(repo.findExistingDrive(calendar, destination, arrival + 3_600_000))
            val distant = arrival + 7 * 86_400_000L
            val longDrive = repo.insertDrive(calendar, "Home", destination, distant - 2 * 86_400_000L, distant - 600_000,
                0, "Long drive", DriveEntryIdentity.attach("Drive", DriveEntryIdentity.key(destination, distant)), destinationStartMillis = distant)
            assertEquals(longDrive, repo.findExistingDrive(calendar, destination, distant)?.eventId)
            val differentOrigin = repo.insertDrive(calendar, "Other home", destination, arrival - 3_600_000, arrival - 600_000,
                0, "Drive", description, destinationStartMillis = arrival, calculatedAtMillis = revision + 100)
            assertNotEquals(saves.first(), differentOrigin)
            val updated = repo.insertDrive(calendar, "Home", destination, arrival - 2_700_000, arrival - 900_000,
                5, "Updated", description, destinationStartMillis = arrival, calculatedAtMillis = revision + 200)
            assertEquals(saves.first(), updated)
            repo.insertDrive(calendar, "Home", destination, arrival - 3_600_000, arrival - 600_000,
                0, "Outdated", description, destinationStartMillis = arrival, calculatedAtMillis = revision + 150)
            assertEquals(arrival - 900_000, repo.findExistingDrive(calendar, destination, arrival, origin = "Home")?.endMillis)
            resolver.query(CalendarContract.Reminders.CONTENT_URI, arrayOf(CalendarContract.Reminders.MINUTES),
                "${CalendarContract.Reminders.EVENT_ID}=?", arrayOf(updated.toString()), null)?.use {
                assertEquals(1, it.count); assertTrue(it.moveToFirst()); assertEquals(5, it.getInt(0))
            } ?: fail("Missing reminders")
            val duplicate = requireNotNull(resolver.insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendar)
                put(CalendarContract.Events.TITLE, "Old duplicate")
                put(CalendarContract.Events.EVENT_LOCATION, destination)
                put(CalendarContract.Events.DESCRIPTION, "From: Home\n$description")
                put(CalendarContract.Events.DTSTART, arrival - 3_600_000)
                put(CalendarContract.Events.DTEND, arrival - 600_000)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            }))
            repo.insertDrive(calendar, "Home", destination, arrival - 2_700_000, arrival - 900_000,
                5, "Updated", description, destinationStartMillis = arrival, calculatedAtMillis = revision + 300)
            resolver.query(duplicate, arrayOf(CalendarContract.Events.DELETED), null, null, null)?.use {
                assertTrue(!it.moveToFirst() || it.getInt(0) == 1)
            } ?: fail("Could not inspect duplicate")
        } finally {
            resolver.delete(calendarsUri, "${CalendarContract.Calendars._ID}=?", arrayOf(calendar.toString()))
        }
    }
}
