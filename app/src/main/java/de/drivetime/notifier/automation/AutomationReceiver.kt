package de.drivetime.notifier.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*
import de.drivetime.notifier.MainActivity
import de.drivetime.notifier.security.AutomationTokenStore

class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val expected = AutomationTokenStore(context).token()
        val supplied = intent.getStringExtra(EXTRA_TOKEN)
        if (supplied == null || !constantTimeEquals(expected, supplied)) return

        when (intent.action) {
            ACTION_PROCESS_NEXT_DAY -> AutomationScheduler.runNow(context)
            ACTION_PROCESS_EVENT -> {
                val mode = intent.getStringExtra("mode") ?: "process"
                if (mode == "open") {
                    context.startActivity(
                        Intent(context, MainActivity::class.java).apply {
                            action = ACTION_OPEN_PREFILLED
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            putExtra("origin", intent.getStringExtra("origin"))
                            putExtra("destination", intent.getStringExtra("destination"))
                            putExtra("datetime", intent.getStringExtra("datetime"))
                            putExtra("previous_end_millis", intent.getLongExtra("previous_end_millis", -1L))
                        }
                    )
                } else {
                    val data = workDataOf(
                        "origin" to intent.getStringExtra("origin"),
                        "destination" to intent.getStringExtra("destination"),
                        "arrival_millis" to intent.getLongExtra("arrival_millis", -1L),
                        "previous_end_millis" to intent.getLongExtra("previous_end_millis", -1L)
                    )
                    WorkManager.getInstance(context).enqueue(
                        OneTimeWorkRequestBuilder<SingleEventWorker>().setInputData(data).build()
                    )
                }
            }
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val aa = a.toByteArray()
        val bb = b.toByteArray()
        if (aa.size != bb.size) return false
        var diff = 0
        for (i in aa.indices) diff = diff or (aa[i].toInt() xor bb[i].toInt())
        return diff == 0
    }

    companion object {
        const val ACTION_PROCESS_NEXT_DAY = "de.drivetime.notifier.ACTION_PROCESS_NEXT_DAY"
        const val ACTION_PROCESS_EVENT = "de.drivetime.notifier.ACTION_PROCESS_EVENT"
        const val ACTION_OPEN_PREFILLED = "de.drivetime.notifier.ACTION_OPEN_PREFILLED"
        const val EXTRA_TOKEN = "token"
    }
}
