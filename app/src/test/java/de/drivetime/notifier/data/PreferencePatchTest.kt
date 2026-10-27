package de.drivetime.notifier.data

import androidx.datastore.preferences.core.*
import org.junit.Assert.*
import org.junit.Test

class PreferencePatchTest {
    @Test fun overlappingEditsFromTheSameSnapshotCannotUndoAnUnrelatedField() {
        val buffer = intPreferencesKey("buffer")
        val reminder = intPreferencesKey("reminder")
        val source = mutablePreferencesOf(buffer to 15, reminder to 0)
        val current = source.toMutablePreferences()
        current.applyChangedValues(mutablePreferencesOf(buffer to 30, reminder to 0), source)
        current.applyChangedValues(mutablePreferencesOf(buffer to 15, reminder to 45), source)
        assertEquals(30, current[buffer])
        assertEquals(45, current[reminder])
    }
    @Test fun stringsAndUnrelatedBackgroundChangesSurviveAFieldSave() {
        val home = stringPreferencesKey("home")
        val theme = booleanPreferencesKey("theme")
        val baseline = mutablePreferencesOf(home to "Home", theme to false)
        val current = mutablePreferencesOf(home to "Home", theme to true)
        current.applyChangedValues(mutablePreferencesOf(home to "New home", theme to false), baseline)
        assertEquals("New home", current[home])
        assertEquals(true, current[theme])
    }
}
