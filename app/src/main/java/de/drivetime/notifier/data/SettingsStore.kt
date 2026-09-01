package de.drivetime.notifier.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

enum class RoutingProvider(val id: String, val label: String) {
    GOOGLE("google", "Google Routes (Verkehrsprognose)"),
    OSRM("osrm", "OSRM + Nominatim (Open Source)");

    companion object {
        fun fromId(id: String?): RoutingProvider = entries.firstOrNull { it.id == id } ?: GOOGLE
    }
}

data class AppSettings(
    val homeAddress: String = "",
    val savedPlaces: Set<String> = emptySet(),
    val bufferMinutes: Int = 15,
    val reminderLeadMinutes: Int = 0,
    val automaticEnabled: Boolean = false,
    val autoHour: Int = 21,
    val outputIcs: Boolean = false,
    val showSpeedCameras: Boolean = false,
    val showParking: Boolean = false,
    val targetCalendarId: Long = -1L,
    val sourceCalendarIds: Set<String> = emptySet(),
    val routingProvider: RoutingProvider = RoutingProvider.GOOGLE,
    val osrmBaseUrl: String = "https://router.project-osrm.org",
    val nominatimBaseUrl: String = "https://nominatim.openstreetmap.org"
)

class SettingsStore(private val context: Context) {
    private object K {
        val HOME = stringPreferencesKey("home_address")
        val PLACES = stringSetPreferencesKey("saved_places")
        val BUFFER = intPreferencesKey("buffer_minutes")
        val REMINDER = intPreferencesKey("reminder_lead")
        val AUTO = booleanPreferencesKey("automatic_enabled")
        val HOUR = intPreferencesKey("auto_hour")
        val ICS = booleanPreferencesKey("output_ics")
        val CAMERAS = booleanPreferencesKey("show_speed_cameras")
        val PARKING = booleanPreferencesKey("show_parking")
        val TARGET = longPreferencesKey("target_calendar_id")
        val SOURCES = stringSetPreferencesKey("source_calendar_ids")
        val ROUTING = stringPreferencesKey("routing_provider")
        val OSRM = stringPreferencesKey("osrm_base_url")
        val NOMINATIM = stringPreferencesKey("nominatim_base_url")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            homeAddress = p[K.HOME].orEmpty(),
            savedPlaces = p[K.PLACES] ?: emptySet(),
            bufferMinutes = p[K.BUFFER] ?: 15,
            reminderLeadMinutes = p[K.REMINDER] ?: 0,
            automaticEnabled = p[K.AUTO] ?: false,
            autoHour = p[K.HOUR] ?: 21,
            outputIcs = p[K.ICS] ?: false,
            showSpeedCameras = p[K.CAMERAS] ?: false,
            showParking = p[K.PARKING] ?: false,
            targetCalendarId = p[K.TARGET] ?: -1,
            sourceCalendarIds = p[K.SOURCES] ?: emptySet(),
            routingProvider = RoutingProvider.fromId(p[K.ROUTING]),
            osrmBaseUrl = p[K.OSRM] ?: "https://router.project-osrm.org",
            nominatimBaseUrl = p[K.NOMINATIM] ?: "https://nominatim.openstreetmap.org"
        )
    }

    suspend fun update(s: AppSettings) = context.dataStore.edit { p ->
        p[K.HOME] = s.homeAddress
        p[K.PLACES] = s.savedPlaces
        p[K.BUFFER] = s.bufferMinutes.coerceIn(0, 180)
        p[K.REMINDER] = s.reminderLeadMinutes.coerceIn(0, 180)
        p[K.AUTO] = s.automaticEnabled
        p[K.HOUR] = s.autoHour.coerceIn(0, 23)
        p[K.ICS] = s.outputIcs
        p[K.CAMERAS] = s.showSpeedCameras
        p[K.PARKING] = s.showParking
        p[K.TARGET] = s.targetCalendarId
        p[K.SOURCES] = s.sourceCalendarIds
        p[K.ROUTING] = s.routingProvider.id
        p[K.OSRM] = sanitizeHttpsBaseUrl(s.osrmBaseUrl, "https://router.project-osrm.org")
        p[K.NOMINATIM] = sanitizeHttpsBaseUrl(s.nominatimBaseUrl, "https://nominatim.openstreetmap.org")
    }

    private fun sanitizeHttpsBaseUrl(value: String, fallback: String): String {
        val clean = value.trim().removeSuffix("/")
        return if (clean.startsWith("https://") && clean.length <= 200) clean else fallback
    }
}
