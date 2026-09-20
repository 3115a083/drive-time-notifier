package de.drivetime.notifier.calendar

import de.drivetime.notifier.data.AppLanguage
import de.drivetime.notifier.data.ChargingConnectorPreference
import de.drivetime.notifier.data.RoutingProvider
import de.drivetime.notifier.model.RouteEstimate
import de.drivetime.notifier.routing.ChargingNavigation
import de.drivetime.notifier.routing.RoutePoi
import de.drivetime.notifier.routing.RoutePoiSource
import de.drivetime.notifier.ui.formatDuration
import de.drivetime.notifier.ui.tr
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

object DriveEventDescriptionBuilder {
    fun build(
        language: AppLanguage,
        provider: RoutingProvider,
        origin: String,
        destination: String,
        route: RouteEstimate,
        pois: List<RoutePoi>,
        chargingNavigation: ChargingNavigation? = null,
        dynamicBufferMinutes: Int? = null
    ): String {
        val lat = route.destinationLatitude
        val lon = route.destinationLongitude
        val encodedDestination = URLEncoder.encode(destination, StandardCharsets.UTF_8.toString())
        val charging = pois.filter { it.kind == RoutePoi.Kind.CHARGING_STATION }.take(5)
        val navigationStation = chargingNavigation?.station
        val navLat = navigationStation?.point?.latitude ?: lat
        val navLon = navigationStation?.point?.longitude ?: lon
        val googleMaps = if (navLat != null && navLon != null) {
            "https://www.google.com/maps/dir/?api=1&destination=$navLat,$navLon&travelmode=driving"
        } else {
            "https://www.google.com/maps/dir/?api=1&destination=$encodedDestination&travelmode=driving"
        }
        val geo = if (navLat != null && navLon != null) "geo:$navLat,$navLon?q=$navLat,$navLon" else "geo:0,0?q=$encodedDestination"
        val parking = pois.filter { it.kind == RoutePoi.Kind.PARKING }
            .sortedBy { it.distanceFromDestinationMeters ?: Int.MAX_VALUE }
            .take(5)

        return buildString {
            appendLine(tr(language, "Drive automatically planned by Drive Time Notifier.", "Fahrt automatisch durch Drive Time Notifier geplant."))
            appendLine()
            appendLine("${tr(language, "From", "Von")}: $origin")
            appendLine("${tr(language, "To", "Nach")}: $destination")
            appendLine("${tr(language, "Routing provider", "Routingdienst")}: ${provider.displayName}")
            val dynamicSuffix = dynamicBufferMinutes?.let {
                " (${tr(language, "+${formatDuration(it * 60L, language)} dynamic buffer", "+${formatDuration(it * 60L, language)} dynamischer Puffer")})"
            }.orEmpty()
            appendLine("${tr(language, "Estimated drive time", "Geschätzte Fahrzeit")}: ${formatDuration(route.durationSeconds, language)}$dynamicSuffix")
            appendLine("${tr(language, "Distance", "Distanz")}: ${"%.1f".format(route.distanceMeters / 1000.0)} km")
            appendLine()
            appendLine("${tr(language, "Start navigation", "Navigation starten")}:")
            appendLine("Google Maps: $googleMaps")
            appendLine("${tr(language, "Installed navigation app", "Installierte Navigations-App")}: $geo")

            if (navigationStation != null) {
                val pLat = navigationStation.point.latitude
                val pLon = navigationStation.point.longitude
                val walking = "https://www.google.com/maps/dir/?api=1&origin=$pLat,$pLon&destination=$encodedDestination&travelmode=walking"
                appendLine("${tr(language, "Navigation target", "Navigationsziel")}: ${clean(navigationStation.name) ?: tr(language, "Charging station", "Ladestation")}")
                chargingNavigation?.let { nav ->
                    appendLine("${tr(language, "Approximate walk to appointment", "Ungefährer Fußweg zum Termin")}: ~${nav.walkingDistanceMeters} m / ${formatDuration(nav.walkingDurationSeconds, language)}")
                }
                appendLine("${tr(language, "Then walk to the appointment destination", "Danach zu Fuß zum Terminziel")}: $walking")
            }

            if (charging.isNotEmpty()) {
                appendLine()
                appendLine(tr(language, "Nearby public charging stations:", "Öffentliche Ladesäulen in Zielnähe:"))
                charging.forEachIndexed { index, poi ->
                    val pLat = poi.point.latitude
                    val pLon = poi.point.longitude
                    val driving = "https://www.google.com/maps/dir/?api=1&destination=$pLat,$pLon&travelmode=driving"
                    val walking = "https://www.google.com/maps/dir/?api=1&origin=$pLat,$pLon&destination=$encodedDestination&travelmode=walking"
                    appendLine("${index + 1}. ${clean(poi.name) ?: tr(language, "Charging station", "Ladestation")}")
                    val compact = buildList {
                        if (poi.connectorTypes.isNotEmpty()) add(poi.connectorTypes.joinToString("/") { connectorLabel(language, it) })
                        poi.maxPowerKw?.takeIf { it > 0.0 }?.let { power ->
                            add((if (power % 1.0 == 0.0) power.toInt().toString() else "%.1f".format(power)) + " kW")
                        }
                        poi.distanceFromDestinationMeters?.let { add("~$it m ${tr(language, "from destination", "vom Ziel")}") }
                    }
                    if (compact.isNotEmpty()) appendLine("   ${compact.joinToString(" · ")}")
                    clean(poi.operator)?.let { appendLine("   ${tr(language, "Operator", "Betreiber")}: $it") }
                    clean(poi.network)?.takeIf { !it.equals(clean(poi.operator), true) }?.let {
                        appendLine("   ${tr(language, "Network", "Netzwerk")}: $it")
                    }
                    clean(poi.openingHours)?.let { appendLine("   ${tr(language, "Opening hours", "Öffnungszeiten")}: $it") }
                    clean(poi.maxStay)?.let { appendLine("   ${tr(language, "Maximum stay", "Maximale Standzeit")}: $it") }
                    clean(poi.fee)?.let { appendLine("   ${tr(language, "Fee information", "Gebührenhinweis")}: $it") }
                    poi.capacity?.takeIf { it > 0 }?.let { appendLine("   ${tr(language, "Charging points", "Ladepunkte")}: $it") }
                    clean(poi.address)?.let { appendLine("   ${tr(language, "Address", "Adresse")}: $it") }
                    appendLine("   ${tr(language, "Navigation", "Navigation")}: $driving")
                    appendLine("   ${tr(language, "Walk to destination", "Zum Ziel laufen")}: $walking")
                }
                val hasOsm = charging.any { RoutePoiSource.OSM in it.sources }
                val hasBNetzA = charging.any { RoutePoiSource.BUNDESNETZAGENTUR in it.sources }
                if (hasOsm) appendLine(tr(language, "Charging data: OpenStreetMap via Overpass. Private/member-only access is excluded; community data can be incomplete.", "Ladedaten: OpenStreetMap über Overpass. Private bzw. nur für Mitglieder zugängliche Stationen werden ausgeschlossen; Community-Daten können unvollständig sein."))
                if (hasBNetzA) appendLine(tr(language, "Registry enrichment: Bundesnetzagentur.de data, CC BY 4.0, through the configured public register service.", "Register-Anreicherung: Daten von Bundesnetzagentur.de, CC BY 4.0, über den konfigurierten öffentlichen Registerdienst."))
            }

            if (parking.isNotEmpty()) {
                appendLine()
                appendLine(tr(language, "Nearby parking, sorted by approximate walking distance:", "Nahegelegene Parkplätze, sortiert nach ungefährer Laufentfernung:"))
                parking.forEachIndexed { index, poi ->
                    val straight = poi.distanceFromDestinationMeters ?: 0
                    val walk = (straight * 1.25).roundToInt()
                    val pLat = poi.point.latitude
                    val pLon = poi.point.longitude
                    val link = "https://www.google.com/maps/dir/?api=1&destination=$pLat,$pLon&travelmode=driving"
                    appendLine("${index + 1}. ${clean(poi.name) ?: tr(language, "Parking", "Parkplatz")} (~$walk m ${tr(language, "walk", "Fußweg")}): $link")
                }
            }

        }.trim()
    }

    private fun clean(value: String?): String? = value?.trim()?.takeIf {
        it.isNotEmpty() && !it.equals("null", true) && !it.equals("none", true)
    }

    private fun connectorLabel(language: AppLanguage, connector: ChargingConnectorPreference): String = when (connector) {
        ChargingConnectorPreference.CCS -> "CCS / Combo 2"
        ChargingConnectorPreference.TYPE2 -> tr(language, "Type 2", "Typ 2")
        ChargingConnectorPreference.CHADEMO -> "CHAdeMO"
        ChargingConnectorPreference.ANY -> tr(language, "Any connector", "Beliebiger Stecker")
    }
}
