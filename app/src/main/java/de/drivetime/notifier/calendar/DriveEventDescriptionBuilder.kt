package de.drivetime.notifier.calendar

import de.drivetime.notifier.data.AppLanguage
import de.drivetime.notifier.data.RoutingProvider
import de.drivetime.notifier.model.RouteEstimate
import de.drivetime.notifier.routing.RoutePoi
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
        pois: List<RoutePoi>
    ): String {
        val lat = route.destinationLatitude
        val lon = route.destinationLongitude
        val encodedDestination = URLEncoder.encode(destination, StandardCharsets.UTF_8.toString())
        val googleMaps = if (lat != null && lon != null) {
            "https://www.google.com/maps/dir/?api=1&destination=$lat,$lon&travelmode=driving"
        } else {
            "https://www.google.com/maps/dir/?api=1&destination=$encodedDestination&travelmode=driving"
        }
        val geo = if (lat != null && lon != null) "geo:$lat,$lon?q=$lat,$lon" else "geo:0,0?q=$encodedDestination"

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
            appendLine("${tr(language, "Estimated drive time", "Geschätzte Fahrzeit")}: ${route.durationSeconds / 60} min")
            appendLine("${tr(language, "Distance", "Distanz")}: ${"%.1f".format(route.distanceMeters / 1000.0)} km")
            appendLine()
            appendLine("${tr(language, "Start navigation", "Navigation starten")}:")
            appendLine("Google Maps: $googleMaps")
            appendLine("${tr(language, "Installed navigation app", "Installierte Navigations-App")}: $geo")

            if (parking.isNotEmpty()) {
                appendLine()
                appendLine(tr(language, "Nearby parking, sorted by approximate walking distance:", "Nahegelegene Parkplätze, sortiert nach ungefährer Laufentfernung:"))
                parking.forEachIndexed { index, poi ->
                    val straight = poi.distanceFromDestinationMeters ?: 0
                    val walk = (straight * 1.25).roundToInt()
                    val pLat = poi.point.latitude
                    val pLon = poi.point.longitude
                    val link = "https://www.google.com/maps/dir/?api=1&destination=$pLat,$pLon&travelmode=driving"
                    appendLine("${index + 1}. ${poi.name ?: "Parking"} (~$walk m ${tr(language, "walk", "Fußweg")}): $link")
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
}
