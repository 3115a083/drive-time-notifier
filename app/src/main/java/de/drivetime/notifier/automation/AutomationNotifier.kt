package de.drivetime.notifier.automation

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import de.drivetime.notifier.MainActivity
import de.drivetime.notifier.R
import de.drivetime.notifier.data.AppLanguage
import de.drivetime.notifier.ui.tr
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.absoluteValue

object AutomationNotifier {
    private const val CHANNEL_ID = "drive_conflicts"
    private const val FAILURE_CHANNEL_ID = "drive_failures"
    private const val PROCESSING_CHANNEL_ID = "drive_processing"

    fun processingForegroundInfo(context: Context, language: AppLanguage, workId: UUID): ForegroundInfo {
        createChannel(
            context,
            PROCESSING_CHANNEL_ID,
            tr(language, "Route processing", "Streckenverarbeitung"),
            tr(
                language,
                "Silent status while automatic routes are processed in the background.",
                "Stummer Status, während automatische Strecken im Hintergrund verarbeitet werden."
            ),
            NotificationManager.IMPORTANCE_LOW,
            silent = true
        )
        val notificationId = processingNotificationId(workId)
        val cancelIntent = Intent(context, AutomationCancelReceiver::class.java).apply {
            action = AutomationCancelReceiver.ACTION_CANCEL_WORK
            putExtra(AutomationCancelReceiver.EXTRA_WORK_ID, workId.toString())
            putExtra(AutomationCancelReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val cancelPending = PendingIntent.getBroadcast(
            context,
            notificationId,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, PROCESSING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(language, "Routes are being processed", "Strecken werden verarbeitet"))
            .setContentText(tr(language, "Drive Time Notifier is working in the background.", "Drive Time Notifier arbeitet im Hintergrund."))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setOngoing(false)
            .addAction(0, tr(language, "Cancel", "Abbrechen"), cancelPending)
            .build()

        return ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    @SuppressLint("MissingPermission")
    fun notifyConflict(context: Context, language: AppLanguage, destination: String, departureMillis: Long) {
        if (!canNotify(context)) return
        createChannel(
            context,
            CHANNEL_ID,
            tr(language, "Schedule conflicts", "Terminüberschneidungen"),
            tr(
                language,
                "Warnings when an automatically planned drive overlaps another appointment.",
                "Warnungen, wenn eine automatisch geplante Fahrt einen anderen Termin überschneidet."
            )
        )

        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = tr(
            language,
            "The drive to $destination overlaps the previous appointment. It was still planned and saved for an on-time arrival.",
            "Die Fahrt nach $destination überschneidet sich mit dem vorherigen Termin. Sie wurde trotzdem für eine pünktliche Ankunft geplant und gespeichert."
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(language, "Drive overlaps appointment", "Fahrt überschneidet sich mit Termin"))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()

        NotificationManagerCompat.from(context)
            .notify((departureMillis xor destination.hashCode().toLong()).toInt().absoluteValue, notification)
    }

    @SuppressLint("MissingPermission")
    fun notifyRoutingFailure(
        context: Context,
        language: AppLanguage,
        origin: String,
        destination: String,
        arrivalMillis: Long,
        previousEndMillis: Long?,
        trigger: String = "single-event"
    ) {
        if (!canNotify(context)) return
        createChannel(
            context,
            FAILURE_CHANNEL_ID,
            tr(language, "Automatic routing failures", "Fehler bei automatischer Routenplanung"),
            tr(
                language,
                "Notifications when automatic routing still fails after a retry.",
                "Benachrichtigungen, wenn automatische Routenplanung auch nach einem Wiederholungsversuch fehlschlägt."
            )
        )

        val dateTime = Instant.ofEpochMilli(arrivalMillis)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

        val open = Intent(context, MainActivity::class.java).apply {
            action = AutomationReceiver.ACTION_OPEN_PREFILLED
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("origin", origin)
            putExtra("destination", destination)
            putExtra("datetime", dateTime)
            putExtra("previous_end_millis", previousEndMillis ?: -1L)
        }
        val openPending = PendingIntent.getActivity(
            context,
            arrivalMillis.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val retry = Intent(context, AutomationRetryReceiver::class.java).apply {
            putExtra("origin", origin)
            putExtra("destination", destination)
            putExtra("arrival_millis", arrivalMillis)
            putExtra("previous_end_millis", previousEndMillis ?: -1L)
        }
        val retryPending = PendingIntent.getBroadcast(
            context,
            (arrivalMillis xor destination.hashCode().toLong()).toInt(),
            retry,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerText = when (trigger) {
            "scheduled" -> tr(language, "Started by the daily schedule.", "Durch den täglichen Zeitplan gestartet.")
            "manual-next-day" -> tr(language, "Started by a manual next-day search.", "Durch die manuelle Suche nach morgigen Terminen gestartet.")
            else -> tr(language, "Started by an individual drive action or retry.", "Durch eine Einzelaktion oder einen Wiederholungsversuch gestartet.")
        }
        val text = tr(
            language,
            "The route to $destination could not be calculated after two attempts.",
            "Die Route nach $destination konnte nach zwei Versuchen nicht berechnet werden."
        ) + " " + triggerText
        val notification = NotificationCompat.Builder(context, FAILURE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(language, "Drive calculation failed", "Fahrtberechnung fehlgeschlagen"))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .addAction(0, tr(language, "Open drive", "Fahrt öffnen"), openPending)
            .addAction(0, tr(language, "Retry", "Wiederholen"), retryPending)
            .build()

        NotificationManagerCompat.from(context)
            .notify((arrivalMillis xor destination.hashCode().toLong()).toInt().absoluteValue, notification)
    }

    @SuppressLint("MissingPermission")
    fun notifyBackgroundActionProblem(
        context: Context,
        language: AppLanguage,
        english: String,
        german: String
    ) {
        if (!canNotify(context)) return
        createChannel(
            context,
            FAILURE_CHANNEL_ID,
            tr(language, "Background action", "Hintergrundaktion"),
            tr(language, "Problems while running a background app action.", "Probleme beim Ausführen einer Hintergrundaktion.")
        )
        val text = tr(language, english, german)
        val openPending = PendingIntent.getActivity(
            context,
            text.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        NotificationManagerCompat.from(context).notify(
            70_000 + text.hashCode().absoluteValue % 10_000,
            NotificationCompat.Builder(context, FAILURE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(tr(language, "Background action could not run", "Hintergrundaktion konnte nicht ausgeführt werden"))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(openPending)
                .build()
        )
    }

    fun cancelNotification(context: Context, notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    private fun processingNotificationId(workId: UUID): Int = 40_000 + workId.hashCode().absoluteValue % 10_000

    private fun createChannel(
        context: Context,
        id: String,
        name: String,
        description: String,
        importance: Int = NotificationManager.IMPORTANCE_HIGH,
        silent: Boolean = false
    ) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(id, name, importance).apply {
                this.description = description
                if (silent) {
                    setSound(null, null)
                    enableVibration(false)
                }
            }
        )
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
