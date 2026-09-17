package de.drivetime.notifier.core

import de.drivetime.notifier.data.AppSettings
import de.drivetime.notifier.data.DynamicBufferLevel
import kotlin.math.ceil

/**
 * Adds a transparent uncertainty reserve on top of the user's fixed arrival buffer.
 * It deliberately does not pretend to know motorway/city shares because the common
 * RouteEstimate model does not expose reliable road-class composition for every provider.
 * Providers without traffic awareness receive a slightly larger uncertainty allowance.
 */
object DynamicArrivalBuffer {
    fun totalMinutes(
        settings: AppSettings,
        routeDurationSeconds: Long,
        trafficDelaySeconds: Long = 0L
    ): Int = (settings.bufferMinutes + extraMinutes(settings, routeDurationSeconds, trafficDelaySeconds))
        .coerceIn(0, 180)

    fun extraMinutes(
        settings: AppSettings,
        routeDurationSeconds: Long,
        trafficDelaySeconds: Long = 0L
    ): Int {
        if (!settings.dynamicBufferEnabled) return 0
        val minutes = (routeDurationSeconds.coerceAtLeast(0L) / 60.0)
        val base = when (settings.dynamicBufferLevel) {
            DynamicBufferLevel.LOW -> when {
                minutes < 30 -> 0
                minutes < 60 -> 2
                minutes < 120 -> 4
                minutes < 180 -> 7
                else -> 10
            }
            DynamicBufferLevel.BALANCED -> when {
                minutes < 20 -> 0
                minutes < 45 -> 3
                minutes < 90 -> 6
                minutes < 150 -> 10
                minutes < 240 -> 15
                else -> 20
            }
            DynamicBufferLevel.CAUTIOUS -> when {
                minutes < 20 -> 2
                minutes < 45 -> 5
                minutes < 90 -> 10
                minutes < 150 -> 15
                minutes < 240 -> 22
                else -> 30
            }
        }

        // Static-routing providers cannot account for current congestion. Add a small,
        // bounded uncertainty reserve rather than pretending to know the road mix.
        val nonTrafficAware = if (!settings.routingProvider.trafficAware) {
            ceil(minutes * 0.03).toInt().coerceAtMost(10)
        } else 0

        // Traffic-aware providers already include the known delay in the ETA. Only a
        // small volatility reserve is added, avoiding double counting of congestion.
        val trafficVolatility = if (settings.routingProvider.trafficAware && trafficDelaySeconds > 0) {
            ceil((trafficDelaySeconds / 60.0) * 0.10).toInt().coerceAtMost(5)
        } else 0

        return (base + nonTrafficAware + trafficVolatility).coerceIn(0, 45)
    }
}
