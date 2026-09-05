package de.drivetime.notifier.ui

import de.drivetime.notifier.data.AppLanguage
import de.drivetime.notifier.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class DriveEventTitleTest {
    @Test
    fun englishDefaultTitleIsLocalized() {
        assertEquals(
            "Your drive starts",
            resolvedDriveEventTitle(AppSettings(language = AppLanguage.ENGLISH, calendarEventTitle = ""))
        )
    }

    @Test
    fun germanDefaultTitleIsLocalized() {
        assertEquals(
            "Deine Fahrt beginnt",
            resolvedDriveEventTitle(AppSettings(language = AppLanguage.GERMAN, calendarEventTitle = ""))
        )
    }

    @Test
    fun customTitleWinsInBothLanguages() {
        assertEquals(
            "Leave now",
            resolvedDriveEventTitle(
                AppSettings(language = AppLanguage.GERMAN, calendarEventTitle = "Leave now")
            )
        )
    }
}
