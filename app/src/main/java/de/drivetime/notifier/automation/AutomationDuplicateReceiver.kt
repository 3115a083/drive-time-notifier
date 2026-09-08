package de.drivetime.notifier.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

class AutomationDuplicateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notificationId = intent.getIntExtra("notification_id", -1)
        if (notificationId >= 0) AutomationNotifier.cancelNotification(context, notificationId)

        if (intent.action != ACTION_SAVE_ANYWAY) return
        val destination = intent.getStringExtra("destination").orEmpty().trim()
        val arrival = intent.getLongExtra("arrival_millis", -1L)
        if (destination.isBlank() || arrival <= 0) return

        val identityKey = intent.getStringExtra("identity_key").orEmpty()
        val request = OneTimeWorkRequestBuilder<SingleEventWorker>()
            .setInputData(
                workDataOf(
                    "origin" to intent.getStringExtra("origin"),
                    "destination" to destination,
                    "arrival_millis" to arrival,
                    "previous_end_millis" to intent.getLongExtra("previous_end_millis", -1L),
                    "allow_duplicate" to true,
                    "identity_key" to identityKey
                )
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "duplicate-drive-${identityKey.ifBlank { arrival.toString() }}",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    companion object {
        const val ACTION_SAVE_ANYWAY = "de.drivetime.notifier.action.SAVE_DUPLICATE_ANYWAY"
        const val ACTION_CANCEL = "de.drivetime.notifier.action.CANCEL_DUPLICATE"
    }
}
