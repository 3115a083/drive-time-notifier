package de.drivetime.notifier.automation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.drivetime.notifier.calendar.CalendarRepository
import de.drivetime.notifier.calendar.DriveEntryIdentity
import de.drivetime.notifier.data.SettingsStore
import de.drivetime.notifier.data.excludesLocation
import de.drivetime.notifier.data.startLocationForCalendar
import kotlinx.coroutines.flow.first

class NextDriveWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val settings = SettingsStore(applicationContext).flow.first()
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            AutomationNotifier.notifyBackgroundActionProblem(
                applicationContext,
                settings.language,
                "Calendar access is required for the next-drive action.",
                "Für die Aktion „Nächste Fahrt“ wird Kalenderzugriff benötigt."
            )
            return Result.failure()
        }

        val sourceIds = settings.sourceCalendarIds.mapNotNull { it.toLongOrNull() }.toSet()
        if (sourceIds.isEmpty()) {
            AutomationNotifier.notifyBackgroundActionProblem(
                applicationContext,
                settings.language,
                "Select at least one source calendar in Settings first.",
                "Wähle zuerst mindestens einen Quellkalender in den Einstellungen."
            )
            return Result.failure()
        }

        val now = System.currentTimeMillis()
        val calendar = CalendarRepository(applicationContext)
        val events = runCatching {
            calendar.events(
                now - 24L * 60 * 60 * 1000,
                now + 21L * 24 * 60 * 60 * 1000,
                sourceIds
            )
        }.getOrElse {
            AutomationNotifier.notifyBackgroundActionProblem(
                applicationContext,
                settings.language,
                "The selected calendars could not be read.",
                "Die ausgewählten Kalender konnten nicht gelesen werden."
            )
            return Result.retry()
        }

        val next = events.firstOrNull {
            it.startMillis > now && !settings.excludesLocation(it.location)
        }
        if (next == null) {
            AutomationNotifier.notifyBackgroundActionProblem(
                applicationContext,
                settings.language,
                "No upcoming appointment with a routable location was found.",
                "Kein kommender Termin mit routbarem Ort gefunden."
            )
            return Result.success()
        }

        val origin = settings.startLocationForCalendar(next.calendarId)
        if (origin.isBlank()) {
            AutomationNotifier.notifyBackgroundActionProblem(
                applicationContext,
                settings.language,
                "No start location is configured for the next appointment.",
                "Für den nächsten Termin ist kein Startort konfiguriert."
            )
            return Result.failure()
        }

        val previousEnd = events
            .asSequence()
            .filter { it.id != next.id && it.endMillis <= next.startMillis }
            .maxOfOrNull { it.endMillis }
        val identityKey = DriveEntryIdentity.key(next.location, next.startMillis)
        val request = OneTimeWorkRequestBuilder<SingleEventWorker>()
            .setInputData(
                workDataOf(
                    "origin" to origin,
                    "destination" to next.location,
                    "arrival_millis" to next.startMillis,
                    "previous_end_millis" to (previousEnd ?: -1L),
                    "identity_key" to identityKey
                )
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "shortcut-next-drive-route",
            ExistingWorkPolicy.REPLACE,
            request
        )
        return Result.success()
    }
}
