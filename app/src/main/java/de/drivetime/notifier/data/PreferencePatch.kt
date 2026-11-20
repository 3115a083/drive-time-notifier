package de.drivetime.notifier.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences

/** Apply only the user's changes, atomically preserving unrelated concurrent updates. */
internal fun MutablePreferences.applyChangedValues(values: Preferences, previous: Preferences?) {
    val baseline = previous?.asMap()
    for ((key, value) in values.asMap()) {
        if (baseline == null || baseline[key] != value) {
            @Suppress("UNCHECKED_CAST")
            this[key as Preferences.Key<Any>] = value
        }
    }
}
