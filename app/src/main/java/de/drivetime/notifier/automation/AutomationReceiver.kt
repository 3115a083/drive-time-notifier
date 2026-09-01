package de.drivetime.notifier.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.drivetime.notifier.security.AutomationTokenStore

class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val expected = AutomationTokenStore(context).token()
        val supplied = intent.getStringExtra(EXTRA_TOKEN)
        if (supplied == null || !constantTimeEquals(expected, supplied)) return

        when (intent.action) {
            ACTION_PROCESS_NEXT_DAY -> AutomationScheduler.runNow(context)
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
        const val EXTRA_TOKEN = "token"
    }
}
