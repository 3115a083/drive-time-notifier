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
        chargingNavigation: ChargingNavigation? = null
    ): String {
        val lat = route.destinationLatitude
        val lon = route.destinationLongitude
        val encodedDestination = URLEncoder.encode(destination, StandardCharsets.UTF_8.toString())
        val charging = pois.filter { it.kind == RoutePoi.Kind.CHARGING_STATION }
            .sortedBy { it.distanceFromDestinationMeters ?: Int.MAX_VALUE }
            .take(5)
        val navigationStation = chargingNavigation?.station
        val navLat = navigationStation?.point?.latitude ?: lat
        val navLon = navigationStation?.point?.longitude ?: lon
        val googleMaps = if (navLat != null && navLon != null) {
            "https://www.google.com/maps/dir/?api=1&destination=$navLat,$navLon&travelmode=driving"
        } else {
            "https://www.google.com/maps/dir/?api=1&destination=$encodedDestination&travelmode=driving"
        }
        val geo = if (navLat != null && navLon != null) "geo:$navLat,$navLon?q=$navLat,$navLon" else "geo:0,0?q=$encodedDestination"
        val cameras = pois.filter { it.kind == RoutePoi.Kind.SPEED_CAMERA }
        val parking = pois.filter { it.kind == RoutePoi.Kind.PARKING }
            .sortedBy { it.distanceFromDestinationMeters ?: Int.MAX_VALUE }
            .take(5)

        return buildString {
            appendLine(tr(language, "Drive automatically planned by Drive Time Notifier.", "Fahrt automatisch durch Drive Time Notifier geplant."))
            appendLine()
            appendLine("${tr(language, "From", "Von")}: $origin")
            appendLine("${tr(language, "To", "Nach")}: $destination")
            appendLine("${tr(language, "Routing provider", "Routingdienst")}: ${provider.displayName}")
            appendLine("${tr(language, "Estimated drive time", "Geschätzte Fahrzeit")}: ${formatDuration(route.durationSeconds, language)}")
            appendLine("${tr(language, "Distance", "Distanz")}: ${"%.1f".format(route.distanceMeters / 1000.0)} km")
            appendLine()
            appendLine("${tr(language, "Start navigation", "Navigation starten")}:")
            appendLine("Google Maps: $googleMaps")
            appendLine("${tr(language, "Installed navigation app", "Installierte Navigations-App")}: $geo")

            if (navigationStation != null) {
                val pLat = navigationStation.point.latitude
                val pLon = navigationStation.point.longitude
                val walking = "https://www.google.com/maps/dir/?api=1&origin=$pLat,$pLon&destination=$encodedDestination&travelmode=walking"
                appendLine("${tr(language, "Navigation target", "Navigationsziel")}: ${navigationStation.name ?: tr(language, "Charging station", "Ladestation")}")
                chargingNavigation?.let { nav ->
                    appendLine("${tr(language, "Approximate walk to appointment", "Ungefährer Fußweg zum Termin")}: ~${nav.walkingDistanceMeters} m / ${formatDuration(nav.walkingDurationSeconds, language)}")
                }
                appendLine("${tr(language, "Then walk to the appointment destination", "Danach zu Fuß zum Terminziel")}: $walking")
            }

            if (charging.isNotEmpty()) {
                appendLine()
                appendLine(tr(language, "Nearby public charging stations:", "Öffentliche Ladesäulen in Zielnähe:"))
                charging.forEachIndexed { index, poi ->
                    val distance = poi.distanceFromDestinationMeters ?: 0
                    val pLat = poi.point.latitude
                    val pLon = poi.point.longitude
                    val link = "https://www.google.com/maps/dir/?api=1&destination=$pLat,$pLon&travelmode=driving"
                    val details = buildList {
                        poi.operator?.takeIf { it.isNotBlank() }?.let(::add)
                        if (poi.connectorTypes.isNotEmpty()) {
                            add(poi.connectorTypes.joinToString("/") { connectorLabel(language, it) })
                        }
                        poi.maxPowerKw?.let { power ->
                            val formatted = if (power % 1.0 == 0.0) power.toInt().toString() else "%.1f".format(power)
                            add("$formatted kW")
                        }
                        poi.openingHours?.takeIf { it.isNotBlank() }?.let(::add)
                        add("~$distance m ${tr(language, "from destination", "vom Ziel")}")
                    }
                    appendLine("${index + 1}. ${poi.name ?: tr(language, "Charging station", "Ladestation")} · ${details.joinToString(" · ")}: $link")
                }
                val hasOsm = charging.any { RoutePoiSource.OSM in it.sources }
                val hasBNetzA = charging.any { RoutePoiSource.BUNDESNETZAGENTUR in it.sources }
                if (hasOsm) appendLine(tr(language, "Charging data: OpenStreetMap amenity=charging_station via Overpass; restricted access tags are excluded.", "Ladedaten: OpenStreetMap amenity=charging_station über Overpass; als eingeschränkt markierte Zugänge werden ausgeschlossen."))
                if (hasBNetzA) appendLine(tr(language, "Registry enrichment: Bundesnetzagentur.de data, CC BY 4.0, accessed through the public Esri feature service.", "Register-Anreicherung: Daten von Bundesnetzagentur.de, CC BY 4.0, abgerufen über den öffentlichen Esri-Feature-Service."))
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
                    appendLine("${index + 1}. ${poi.name ?: tr(language, "Parking", "Parkplatz")} (~$walk m ${tr(language, "walk", "Fußweg")}): $link")
                }
            }

            if (cameras.isNotEmpty()) {
                appendLine()
                appendLine("${tr(language, "Speed cameras on the selected route", "Blitzer auf der gewählten Strecke")}: ${cameras.size}")
                cameras.forEachIndexed { index, poi ->
                    appendLine("${index + 1}. ${"%.5f".format(poi.point.latitude)}, ${"%.5f".format(poi.point.longitude)}")
                }
                appendLine(tr(language, "Source: OpenStreetMap highway=speed_camera via Overpass. Community data may be incomplete.", "Quelle: OpenStreetMap highway=speed_camera über Overpass. Community-Daten können unvollständig sein."))
            }
        }.trim()
    }

    private fun connectorLabel(language: AppLanguage, connector: ChargingConnectorPreference): String = when (connector) {
        ChargingConnectorPreference.CCS -> "CCS"
        ChargingConnectorPreference.TYPE2 -> tr(language, "Type 2", "Typ 2")
        ChargingConnectorPreference.CHADEMO -> "CHAdeMO"
        ChargingConnectorPreference.ANY -> tr(language, "Any connector", "Beliebiger Stecker")
    }
}
