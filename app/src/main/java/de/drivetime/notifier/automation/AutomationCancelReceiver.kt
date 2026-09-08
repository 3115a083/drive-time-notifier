package de.drivetime.notifier.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import java.util.UUID

class AutomationCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL_WORK) return
        val workId = intent.getStringExtra(EXTRA_WORK_ID)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return
        WorkManager.getInstance(context).cancelWorkById(workId)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (notificationId >= 0) AutomationNotifier.cancelNotification(context, notificationId)
    }

    companion object {
        const val ACTION_CANCEL_WORK = "de.drivetime.notifier.action.CANCEL_AUTOMATION_WORK"
        const val EXTRA_WORK_ID = "work_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
