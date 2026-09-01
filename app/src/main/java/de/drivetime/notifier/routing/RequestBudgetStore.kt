package de.drivetime.notifier.routing

import android.content.Context
import de.drivetime.notifier.data.RoutingProvider
import java.time.LocalDate

class RequestBudgetStore(context: Context) {
    private val prefs = context.getSharedPreferences("provider_request_budget", Context.MODE_PRIVATE)

    @Synchronized
    fun consume(provider: RoutingProvider, dailyCap: Int, count: Int = 1) {
        val date = LocalDate.now().toString()
        val dateKey = "${provider.id}_date"
        val countKey = "${provider.id}_count"
        val storedDate = prefs.getString(dateKey, null)
        val used = if (storedDate == date) prefs.getInt(countKey, 0) else 0
        if (used + count > dailyCap) {
            error("Daily request cap reached for ${provider.displayName} ($dailyCap). Increase the cap in Settings if intended.")
        }
        prefs.edit().putString(dateKey, date).putInt(countKey, used + count).apply()
    }

    fun usedToday(provider: RoutingProvider): Int {
        val date = LocalDate.now().toString()
        return if (prefs.getString("${provider.id}_date", null) == date) {
            prefs.getInt("${provider.id}_count", 0)
        } else 0
    }
}
