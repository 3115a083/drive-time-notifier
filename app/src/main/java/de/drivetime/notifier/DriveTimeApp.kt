package de.drivetime.notifier

import android.app.Application
import de.drivetime.notifier.automation.AutomationScheduler
import de.drivetime.notifier.data.SettingsStore
import de.drivetime.notifier.data.RouteHistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DriveTimeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { RouteHistoryStore(this@DriveTimeApp).prune() }
        }
        val preferences = getSharedPreferences("automation-schedule-migration", MODE_PRIVATE)
        if (!preferences.getBoolean("wall-clock-v2", false)) {
            CoroutineScope(Dispatchers.IO).launch {
                val settings = SettingsStore(this@DriveTimeApp).flow.first()
                AutomationScheduler.configure(this@DriveTimeApp, settings.automaticEnabled, settings.autoHour, settings.autoMinute)
                preferences.edit().putBoolean("wall-clock-v2", true).apply()
            }
        }
    }
}
