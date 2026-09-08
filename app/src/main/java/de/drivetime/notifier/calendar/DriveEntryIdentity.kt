package de.drivetime.notifier.calendar

import java.security.MessageDigest
import java.util.Locale

object DriveEntryIdentity {
    private const val MARKER_PREFIX = "DriveTimeNotifier-ID:"
    private const val ENGLISH_SIGNATURE = "Drive automatically planned by Drive Time Notifier."
    private const val GERMAN_SIGNATURE = "Fahrt automatisch durch Drive Time Notifier geplant."

    fun key(destination: String, destinationStartMillis: Long): String {
        val normalizedDestination = normalizeLocation(destination)
        val epochMinute = destinationStartMillis / 60_000L
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$normalizedDestination|$epochMinute".toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    fun marker(key: String): String = "[$MARKER_PREFIX$key]"

    fun attach(description: String, key: String): String {
        val marker = marker(key)
        return if (description.contains(marker)) description else "$description\n\n$marker"
    }

    fun hasMarker(description: String, key: String): Boolean = description.contains(marker(key))

    fun isOwnDescription(description: String): Boolean =
        description.contains(MARKER_PREFIX) ||
            description.contains(ENGLISH_SIGNATURE) ||
            description.contains(GERMAN_SIGNATURE)

    fun normalizeLocation(value: String): String = value
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")
}

data class ExistingDriveEntry(
    val eventId: Long,
    val startMillis: Long,
    val endMillis: Long
)
