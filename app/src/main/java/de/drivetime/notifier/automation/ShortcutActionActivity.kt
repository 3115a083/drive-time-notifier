package de.drivetime.notifier.automation

import android.app.Activity
import android.os.Bundle
import de.drivetime.notifier.MainActivity

/**
 * Android launches registered app shortcuts as the publishing app, including private
 * activities. Keeping this entry point private permits hands-off execution without
 * allowing arbitrary explicit intents from other apps to consume quota or write events.
 * External automation continues to use the token-authenticated AutomationReceiver.
 */
class ShortcutActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            when (intent?.action) {
                AutomationReceiver.ACTION_PROCESS_NEXT_DAY -> AutomationScheduler.runNow(this)
                MainActivity.ACTION_NEXT_DRIVE -> AutomationScheduler.runNextDriveNow(this)
            }
        } finally {
            finish()
        }
    }
}
