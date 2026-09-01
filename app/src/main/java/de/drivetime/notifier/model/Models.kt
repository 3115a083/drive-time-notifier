package de.drivetime.notifier.model

data class SavedPlace(val id: String, val name: String, val address: String)

data class CalendarEventRef(
    val id: Long,
    val calendarId: Long,
    val title: String,
    val location: String,
    val startMillis: Long,
    val endMillis: Long
)

data class RouteRequest(val origin: String, val destination: String, val arrivalMillis: Long)

data class RouteEstimate(
    val durationSeconds: Long,
    val staticDurationSeconds: Long,
    val distanceMeters: Long,
    val encodedPolyline: String,
    val warning: String? = null,
    val originLatitude: Double? = null,
    val originLongitude: Double? = null,
    val destinationLatitude: Double? = null,
    val destinationLongitude: Double? = null
) {
    val trafficDelaySeconds: Long get() = (durationSeconds - staticDurationSeconds).coerceAtLeast(0)
    val trafficProbabilityPercent: Int
        get() = if (staticDurationSeconds <= 0) 0
        else ((trafficDelaySeconds.toDouble() / staticDurationSeconds) * 100.0).toInt().coerceIn(0, 100)
}

data class DrivePlan(
    val departureMillis: Long,
    val arrivalMillis: Long,
    val appliedBufferMinutes: Int,
    val warning: String?
)

data class AddressSuggestion(
    val label: String,
    val latitude: Double? = null,
    val longitude: Double? = null
)
