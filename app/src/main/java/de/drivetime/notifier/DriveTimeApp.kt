package de.drivetime.notifier

import android.app.Application
import de.drivetime.notifier.automation.AutomationScheduler

class DriveTimeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AutomationScheduler.schedule(this)
    }
}
