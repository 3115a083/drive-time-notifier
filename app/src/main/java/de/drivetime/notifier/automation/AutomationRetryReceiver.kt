package de.drivetime.notifier.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

class AutomationRetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val data = workDataOf(
            "origin" to intent.getStringExtra("origin"),
            "destination" to intent.getStringExtra("destination"),
            "arrival_millis" to intent.getLongExtra("arrival_millis", -1L),
            "previous_end_millis" to intent.getLongExtra("previous_end_millis", -1L)
        )
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<SingleEventWorker>()
                .setInputData(data)
                .build()
        )
    }
}
