package de.drivetime.notifier.automation

import android.app.Activity
import android.os.Bundle
import de.drivetime.notifier.MainActivity

class ShortcutActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            AutomationReceiver.ACTION_PROCESS_NEXT_DAY -> AutomationScheduler.runNow(this)
            MainActivity.ACTION_NEXT_DRIVE -> AutomationScheduler.runNextDriveNow(this)
        }
        finish()
    }
}
