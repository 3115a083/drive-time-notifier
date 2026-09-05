package de.drivetime.notifier.ui

import de.drivetime.notifier.data.AppLanguage
import de.drivetime.notifier.data.AppSettings
import de.drivetime.notifier.data.LimitPeriod
import de.drivetime.notifier.data.RoutingProvider
import de.drivetime.notifier.model.DrivePlan
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun resolvedDriveEventTitle(settings: AppSettings): String =
    settings.calendarEventTitle.trim().ifBlank {
        tr(settings.language, "Your drive starts", "Deine Fahrt beginnt")
    }

fun formatDuration(seconds: Long, language: AppLanguage): String {
    val totalMinutes = (seconds.coerceAtLeast(0) + 30) / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) {
        if (minutes > 0) {
            tr(language, "$hours h $minutes min", "$hours Std. $minutes Min.")
        } else {
            tr(language, "$hours h", "$hours Std.")
        }
    } else {
        tr(language, "$minutes min", "$minutes Min.")
    }
}

fun formatClock(millis: Long?): String {
    if (millis == null) return "–"
    return Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))
}

fun planWarningText(language: AppLanguage, plan: DrivePlan, requestedBufferMinutes: Int): String? {
    if (plan.warning == null) return null
    return if (plan.appliedBufferMinutes == 0) {
        tr(
            language,
            "The appointments are unlikely to be reachable on time one after another. The drive is still planned for an on-time arrival and overlaps the previous appointment.",
            "Die Termine sind voraussichtlich nicht rechtzeitig nacheinander erreichbar. Die Fahrt wird trotzdem für eine pünktliche Ankunft geplant und überschneidet sich mit dem vorherigen Termin."
        )
    } else {
        tr(
            language,
            "The requested $requestedBufferMinutes-minute buffer was reduced to ${plan.appliedBufferMinutes} minutes so the drive can start directly after the previous appointment.",
            "Der gewünschte Puffer von $requestedBufferMinutes Minuten wurde auf ${plan.appliedBufferMinutes} Minuten verkürzt, damit die Fahrt direkt nach dem vorherigen Termin beginnen kann."
        )
    }
}

fun limitPeriodLabel(language: AppLanguage, period: LimitPeriod): String = when (period) {
    LimitPeriod.DAILY -> tr(language, "Daily", "Täglich")
    LimitPeriod.WEEKLY -> tr(language, "Weekly", "Wöchentlich")
    LimitPeriod.MONTHLY -> tr(language, "Monthly", "Monatlich")
}


fun routeWarningText(language: AppLanguage, provider: RoutingProvider, rawWarning: String?): String? {
    if (rawWarning.isNullOrBlank()) return null
    return when (provider) {
        RoutingProvider.VALHALLA -> tr(
            language,
            "Valhalla uses a free public fair-use service and does not guarantee live-traffic data.",
            "Valhalla nutzt einen kostenlosen öffentlichen Fair-Use-Dienst und garantiert keine Live-Verkehrsdaten."
        )
        RoutingProvider.OPENROUTESERVICE -> tr(
            language,
            "openrouteservice does not provide predictive live traffic in this integration.",
            "openrouteservice liefert in dieser Integration keine prognostischen Live-Verkehrsdaten."
        )
        RoutingProvider.OSRM -> tr(
            language,
            "OSRM provides a static OpenStreetMap-based travel-time estimate without predictive live traffic.",
            "OSRM liefert eine statische OpenStreetMap-basierte Fahrzeitschätzung ohne prognostische Live-Verkehrsdaten."
        )
        RoutingProvider.GRAPHHOPPER -> tr(
            language,
            "GraphHopper does not use predictive live traffic in this integration.",
            "GraphHopper verwendet in dieser Integration keine prognostischen Live-Verkehrsdaten."
        )
        else -> rawWarning
    }
}
