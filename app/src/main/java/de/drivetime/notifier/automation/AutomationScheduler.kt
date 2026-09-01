package de.drivetime.notifier.automation

import android.content.Context
import androidx.work.*
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object AutomationScheduler {
    private const val NAME = "nightly-drive-planning"

    fun schedule(context: Context, hour: Int = 21) {
        val now = ZonedDateTime.now()
        var next = now.withHour(hour.coerceIn(0,23)).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMillis()

        val request = PeriodicWorkRequestBuilder<NextDayWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<NextDayWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "manual-next-day",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
