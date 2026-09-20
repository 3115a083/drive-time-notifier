package de.drivetime.notifier.export

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsExporterTest {
    @Test
    fun escapesLineBreaksAndCalendarSeparators() {
        val escaped = IcsExporter.escapeIcsText("Title\r\nInjected:yes,maybe;test\\end")

        assertFalse(escaped.contains('\r'))
        assertFalse(escaped.contains("\nInjected"))
        assertTrue(escaped.contains("\\nInjected:yes"))
        assertTrue(escaped.contains("\\,maybe"))
        assertTrue(escaped.contains("\\;test"))
        assertTrue(escaped.contains("\\\\end"))
    }

    @Test
    fun stripsOtherControlCharacters() {
        val escaped = IcsExporter.escapeIcsText("safe\u0000\u0007text")

        assertTrue(escaped == "safetext")
    }
}
