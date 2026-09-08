package de.drivetime.notifier.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveEntryIdentityTest {
    @Test
    fun keyIgnoresWhitespaceAndCase() {
        val time = 1_800_000L
        assertEquals(
            DriveEntryIdentity.key(" Main Street 1 ", time),
            DriveEntryIdentity.key("main   street 1", time + 20_000L)
        )
    }

    @Test
    fun keyChangesForAnotherAppointmentMinute() {
        val first = DriveEntryIdentity.key("Main Street 1", 1_800_000L)
        val second = DriveEntryIdentity.key("Main Street 1", 1_860_000L)
        assertFalse(first == second)
    }

    @Test
    fun markerCanBeAttachedAndDetected() {
        val key = DriveEntryIdentity.key("Main Street 1", 1_800_000L)
        val description = DriveEntryIdentity.attach("Drive", key)
        assertTrue(DriveEntryIdentity.hasMarker(description, key))
        assertTrue(DriveEntryIdentity.isOwnDescription(description))
    }

    @Test
    fun legacyDescriptionsAreRecognized() {
        assertTrue(DriveEntryIdentity.isOwnDescription("Drive automatically planned by Drive Time Notifier."))
        assertTrue(DriveEntryIdentity.isOwnDescription("Fahrt automatisch durch Drive Time Notifier geplant."))
        assertFalse(DriveEntryIdentity.isOwnDescription("Unrelated calendar entry"))
    }
}
