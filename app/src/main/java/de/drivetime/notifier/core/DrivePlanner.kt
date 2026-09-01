package de.drivetime.notifier.core

import de.drivetime.notifier.model.DrivePlan

object DrivePlanner {
    fun plan(
        destinationStartMillis: Long,
        routeDurationSeconds: Long,
        requestedBufferMinutes: Int,
        previousEventEndMillis: Long? = null
    ): DrivePlan {
        val durationMs = routeDurationSeconds * 1000L
        val requestedBufferMs = requestedBufferMinutes.coerceAtLeast(0) * 60_000L
        val idealDeparture = destinationStartMillis - durationMs - requestedBufferMs

        if (previousEventEndMillis == null || idealDeparture >= previousEventEndMillis) {
            return DrivePlan(
                departureMillis = idealDeparture,
                arrivalMillis = destinationStartMillis - requestedBufferMs,
                appliedBufferMinutes = requestedBufferMinutes.coerceAtLeast(0),
                warning = null
            )
        }

        val latestDepartureForOnTime = destinationStartMillis - durationMs
        if (latestDepartureForOnTime >= previousEventEndMillis) {
            val possibleBufferMs = destinationStartMillis - durationMs - previousEventEndMillis
            val applied = (possibleBufferMs / 60_000L).toInt().coerceAtLeast(0)
            return DrivePlan(
                departureMillis = previousEventEndMillis,
                arrivalMillis = previousEventEndMillis + durationMs,
                appliedBufferMinutes = applied,
                warning = "Der gewünschte Puffer wurde auf $applied Minuten verkürzt, damit die Fahrt direkt nach dem vorherigen Termin beginnt."
            )
        }

        return DrivePlan(
            departureMillis = latestDepartureForOnTime,
            arrivalMillis = destinationStartMillis,
            appliedBufferMinutes = 0,
            warning = "Die Termine sind voraussichtlich nicht rechtzeitig nacheinander erreichbar. Die Fahrt wird für eine pünktliche Ankunft geplant und überschneidet sich mit dem vorherigen Termin."
        )
    }
}
