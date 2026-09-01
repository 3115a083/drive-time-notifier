package de.drivetime.notifier.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

private val Context.dataStore by preferencesDataStore("settings")

enum class RoutingProvider(
    val id: String,
    val displayName: String,
    val reliabilityScore: Int,
    val trafficAware: Boolean,
    val keyRequired: Boolean,
    val costRisk: Boolean
) {
    VALHALLA("valhalla", "Valhalla", 4, false, false, false),
    OPENROUTESERVICE("openrouteservice", "openrouteservice", 4, false, true, false),
    OSRM("osrm", "OSRM", 3, false, false, false),
    GRAPHHOPPER("graphhopper", "GraphHopper", 4, false, true, false),
    GOOGLE("google", "Google Routes", 5, true, true, true),
    HERE("here", "HERE Routing", 5, true, true, true),
    TOMTOM("tomtom", "TomTom Routing", 5, true, true, true);

    companion object {
        fun fromId(id: String?): RoutingProvider = entries.firstOrNull { it.id == id } ?: VALHALLA
    }
}

enum class AppLanguage(val id: String) {
    ENGLISH("en"), GERMAN("de");
    companion object {
        fun fromId(id: String?): AppLanguage = entries.firstOrNull { it.id == id }
            ?: if (Locale.getDefault().language.equals("de", true)) GERMAN else ENGLISH
    }
}

enum class AppAppearance(val id: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");
    companion object {
        fun fromId(id: String?): AppAppearance = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

enum class ColorPalette(val id: String, val label: String) {
    MATERIAL_YOU("material_you", "Material You"),
    VIOLET("violet", "Violet"),
    OCEAN("ocean", "Ocean"),
    FOREST("forest", "Forest"),
    SUNSET("sunset", "Sunset"),
    ROSE("rose", "Rose"),
    GRAPHITE("graphite", "Graphite");

    companion object {
        fun fromId(id: String?): ColorPalette = when (id) {
            "proton", "aurora" -> VIOLET
            else -> entries.firstOrNull { it.id == id } ?: MATERIAL_YOU
        }
    }
}

data class ProviderCaps(
    val valhalla: Int = 100,
    val openRouteService: Int = 250,
    val osrm: Int = 100,
    val graphHopper: Int = 400,
    val google: Int = 100,
    val here: Int = 100,
    val tomTom: Int = 100
) {
    fun forProvider(provider: RoutingProvider): Int = when (provider) {
        RoutingProvider.VALHALLA -> valhalla
        RoutingProvider.OPENROUTESERVICE -> openRouteService
        RoutingProvider.OSRM -> osrm
        RoutingProvider.GRAPHHOPPER -> graphHopper
        RoutingProvider.GOOGLE -> google
        RoutingProvider.HERE -> here
        RoutingProvider.TOMTOM -> tomTom
    }

    fun withProvider(provider: RoutingProvider, value: Int): ProviderCaps = when (provider) {
        RoutingProvider.VALHALLA -> copy(valhalla = value)
        RoutingProvider.OPENROUTESERVICE -> copy(openRouteService = value)
        RoutingProvider.OSRM -> copy(osrm = value)
        RoutingProvider.GRAPHHOPPER -> copy(graphHopper = value)
        RoutingProvider.GOOGLE -> copy(google = value)
        RoutingProvider.HERE -> copy(here = value)
        RoutingProvider.TOMTOM -> copy(tomTom = value)
    }
}

data class AppSettings(
    val homeAddress: String = "",
    val savedPlaces: Set<String> = emptySet(),
    val bufferMinutes: Int = 15,
    val reminderLeadMinutes: Int = 0,
    val automaticEnabled: Boolean = false,
    val autoHour: Int = 21,
    val autoMinute: Int = 0,
    val outputIcs: Boolean = false,
    val showSpeedCameras: Boolean = false,
    val showParking: Boolean = false,
    val targetCalendarId: Long = -1L,
    val sourceCalendarIds: Set<String> = emptySet(),
    val routingProvider: RoutingProvider = RoutingProvider.VALHALLA,
    val osrmBaseUrl: String = "https://router.project-osrm.org",
    val valhallaBaseUrl: String = "https://valhalla1.openstreetmap.de",
    val photonBaseUrl: String = "https://photon.komoot.io",
    val language: AppLanguage = if (Locale.getDefault().language.equals("de", true)) AppLanguage.GERMAN else AppLanguage.ENGLISH,
    val appearance: AppAppearance = AppAppearance.SYSTEM,
    val palette: ColorPalette = ColorPalette.MATERIAL_YOU,
    val providerCaps: ProviderCaps = ProviderCaps()
)

class SettingsStore(private val context: Context) {
    private object K {
        val HOME = stringPreferencesKey("home_address")
        val PLACES = stringSetPreferencesKey("saved_places")
        val BUFFER = intPreferencesKey("buffer_minutes")
        val REMINDER = intPreferencesKey("reminder_lead")
        val AUTO = booleanPreferencesKey("automatic_enabled")
        val HOUR = intPreferencesKey("auto_hour")
        val MINUTE = intPreferencesKey("auto_minute")
        val ICS = booleanPreferencesKey("output_ics")
        val CAMERAS = booleanPreferencesKey("show_speed_cameras")
        val PARKING = booleanPreferencesKey("show_parking")
        val TARGET = longPreferencesKey("target_calendar_id")
        val SOURCES = stringSetPreferencesKey("source_calendar_ids")
        val ROUTING = stringPreferencesKey("routing_provider")
        val OSRM = stringPreferencesKey("osrm_base_url")
        val VALHALLA = stringPreferencesKey("valhalla_base_url")
        val PHOTON = stringPreferencesKey("photon_base_url")
        val LEGACY_NOMINATIM = stringPreferencesKey("nominatim_base_url")
        val LANGUAGE = stringPreferencesKey("app_language")
        val APPEARANCE = stringPreferencesKey("appearance")
        val PALETTE = stringPreferencesKey("palette")
        val CAP_VALHALLA = intPreferencesKey("cap_valhalla")
        val CAP_ORS = intPreferencesKey("cap_openrouteservice")
        val CAP_GOOGLE = intPreferencesKey("cap_google")
        val CAP_HERE = intPreferencesKey("cap_here")
        val CAP_GRAPHHOPPER = intPreferencesKey("cap_graphhopper")
        val CAP_OSRM = intPreferencesKey("cap_osrm")
        val CAP_TOMTOM = intPreferencesKey("cap_tomtom")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            homeAddress = p[K.HOME].orEmpty(),
            savedPlaces = p[K.PLACES] ?: emptySet(),
            bufferMinutes = p[K.BUFFER] ?: 15,
            reminderLeadMinutes = p[K.REMINDER] ?: 0,
            automaticEnabled = p[K.AUTO] ?: false,
            autoHour = p[K.HOUR] ?: 21,
            autoMinute = p[K.MINUTE] ?: 0,
            outputIcs = p[K.ICS] ?: false,
            showSpeedCameras = p[K.CAMERAS] ?: false,
            showParking = p[K.PARKING] ?: false,
            targetCalendarId = p[K.TARGET] ?: -1L,
            sourceCalendarIds = p[K.SOURCES] ?: emptySet(),
            routingProvider = RoutingProvider.fromId(p[K.ROUTING]),
            osrmBaseUrl = p[K.OSRM] ?: "https://router.project-osrm.org",
            valhallaBaseUrl = p[K.VALHALLA] ?: "https://valhalla1.openstreetmap.de",
            photonBaseUrl = p[K.PHOTON] ?: "https://photon.komoot.io",
            language = AppLanguage.fromId(p[K.LANGUAGE]),
            appearance = AppAppearance.fromId(p[K.APPEARANCE]),
            palette = ColorPalette.fromId(p[K.PALETTE]),
            providerCaps = ProviderCaps(
                valhalla = p[K.CAP_VALHALLA] ?: 100,
                openRouteService = p[K.CAP_ORS] ?: 250,
                osrm = p[K.CAP_OSRM] ?: 100,
                graphHopper = p[K.CAP_GRAPHHOPPER] ?: 400,
                google = p[K.CAP_GOOGLE] ?: 100,
                here = p[K.CAP_HERE] ?: 100,
                tomTom = p[K.CAP_TOMTOM] ?: 100
            )
        )
    }

    suspend fun update(s: AppSettings) = context.dataStore.edit { p ->
        p[K.HOME] = s.homeAddress
        p[K.PLACES] = s.savedPlaces
        p[K.BUFFER] = s.bufferMinutes.coerceIn(0, 180)
        p[K.REMINDER] = s.reminderLeadMinutes.coerceIn(0, 180)
        p[K.AUTO] = s.automaticEnabled
        p[K.HOUR] = s.autoHour.coerceIn(0, 23)
        p[K.MINUTE] = s.autoMinute.coerceIn(0, 59)
        p[K.ICS] = s.outputIcs
        p[K.CAMERAS] = s.showSpeedCameras
        p[K.PARKING] = s.showParking
        p[K.TARGET] = s.targetCalendarId
        p[K.SOURCES] = s.sourceCalendarIds
        p[K.ROUTING] = s.routingProvider.id
        p[K.OSRM] = sanitizeHttpsBaseUrl(s.osrmBaseUrl, "https://router.project-osrm.org")
        p[K.VALHALLA] = sanitizeHttpsBaseUrl(s.valhallaBaseUrl, "https://valhalla1.openstreetmap.de")
        p[K.PHOTON] = sanitizeHttpsBaseUrl(s.photonBaseUrl, "https://photon.komoot.io")
        p[K.LANGUAGE] = s.language.id
        p[K.APPEARANCE] = s.appearance.id
        p[K.PALETTE] = s.palette.id
        p[K.CAP_VALHALLA] = sanitizeCap(s.providerCaps.valhalla)
        p[K.CAP_ORS] = sanitizeCap(s.providerCaps.openRouteService)
        p[K.CAP_GOOGLE] = sanitizeCap(s.providerCaps.google)
        p[K.CAP_HERE] = sanitizeCap(s.providerCaps.here)
        p[K.CAP_GRAPHHOPPER] = sanitizeCap(s.providerCaps.graphHopper)
        p[K.CAP_OSRM] = sanitizeCap(s.providerCaps.osrm)
        p[K.CAP_TOMTOM] = sanitizeCap(s.providerCaps.tomTom)
        p.remove(K.LEGACY_NOMINATIM)
    }

    private fun sanitizeCap(value: Int) = value.coerceIn(1, 100_000)

    private fun sanitizeHttpsBaseUrl(value: String, fallback: String): String {
        val clean = value.trim().removeSuffix("/")
        return if (clean.startsWith("https://") && clean.length <= 240) clean else fallback
    }
}
