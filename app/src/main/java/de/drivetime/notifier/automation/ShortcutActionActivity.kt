package de.drivetime.notifier.automation

import android.app.Activity
import android.os.Bundle
import de.drivetime.notifier.MainActivity

class ShortcutActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == AutomationReceiver.ACTION_PROCESS_NEXT_DAY || intent?.action == MainActivity.ACTION_NEXT_DRIVE) {
            startActivity(android.content.Intent(this, MainActivity::class.java).setAction(intent.action))
        }
        finish()
    }
}
