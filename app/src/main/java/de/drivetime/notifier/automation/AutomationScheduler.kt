package de.drivetime.notifier.automation

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.work.*
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object AutomationScheduler {
    private const val NAME = "nightly-drive-planning"

    fun configure(context: Context, enabled: Boolean, hour: Int = 21, minute: Int = 0) {
        setRescheduleReceiverEnabled(context, enabled)
        if (!enabled) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
            return
        }
        schedule(context, hour, minute)
    }

    private fun schedule(context: Context, hour: Int, minute: Int) {
        val now = ZonedDateTime.now()
        var next = now.withHour(hour.coerceIn(0, 23)).withMinute(minute.coerceIn(0, 59)).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMillis()

        val request = PeriodicWorkRequestBuilder<NextDayWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<NextDayWorker>()
            .setInputData(workDataOf("force" to true))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "manual-next-day",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun setRescheduleReceiverEnabled(context: Context, enabled: Boolean) {
        val component = ComponentName(context, RescheduleReceiver::class.java)
        context.packageManager.setComponentEnabledSetting(
            component,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}
