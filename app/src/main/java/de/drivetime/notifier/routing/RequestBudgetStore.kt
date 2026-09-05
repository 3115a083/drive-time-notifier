package de.drivetime.notifier.routing

import android.content.Context
import de.drivetime.notifier.data.LimitPeriod
import de.drivetime.notifier.data.RoutingProvider
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

class RequestBudgetStore(context: Context) {
    private val prefs = context.getSharedPreferences("provider_request_budget", Context.MODE_PRIVATE)

    @Synchronized
    fun consume(provider: RoutingProvider, cap: Int, period: LimitPeriod, count: Int = 1) {
        val bucket = bucket(period)
        val prefix = "${provider.id}_${period.id}"
        val bucketKey = "${prefix}_bucket"
        val countKey = "${prefix}_count"
        val storedBucket = prefs.getString(bucketKey, null)
        val used = if (storedBucket == bucket) prefs.getInt(countKey, 0) else 0
        if (used + count > cap) {
            error("Request cap reached for ${provider.displayName}: $cap ${period.id}.")
        }
        prefs.edit().putString(bucketKey, bucket).putInt(countKey, used + count).apply()
    }

    fun used(provider: RoutingProvider, period: LimitPeriod): Int {
        val bucket = bucket(period)
        val prefix = "${provider.id}_${period.id}"
        return if (prefs.getString("${prefix}_bucket", null) == bucket) {
            prefs.getInt("${prefix}_count", 0)
        } else 0
    }

    private fun bucket(period: LimitPeriod): String = when (period) {
        LimitPeriod.DAILY -> LocalDate.now().toString()
        LimitPeriod.WEEKLY -> LocalDate.now()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .toString()
        LimitPeriod.MONTHLY -> YearMonth.now().toString()
    }
}
