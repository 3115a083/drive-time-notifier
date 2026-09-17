package de.drivetime.notifier.core

import de.drivetime.notifier.data.AppSettings
import de.drivetime.notifier.data.DynamicBufferLevel
import de.drivetime.notifier.data.RoutingProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicArrivalBufferTest {
    @Test
    fun disabledMeansNoExtraBuffer() {
        val settings = AppSettings(bufferMinutes = 15, dynamicBufferEnabled = false)
        assertEquals(15, DynamicArrivalBuffer.totalMinutes(settings, 3 * 60 * 60L))
    }

    @Test
    fun longerTripsReceiveMoreReserve() {
        val settings = AppSettings(dynamicBufferEnabled = true, dynamicBufferLevel = DynamicBufferLevel.BALANCED, routingProvider = RoutingProvider.TOMTOM)
        val short = DynamicArrivalBuffer.extraMinutes(settings, 20 * 60L)
        val long = DynamicArrivalBuffer.extraMinutes(settings, 180 * 60L)
        assertTrue(long > short)
    }

    @Test
    fun staticProviderGetsSmallAdditionalUncertaintyReserve() {
        val traffic = AppSettings(dynamicBufferEnabled = true, routingProvider = RoutingProvider.TOMTOM)
        val static = AppSettings(dynamicBufferEnabled = true, routingProvider = RoutingProvider.OSRM)
        assertTrue(
            DynamicArrivalBuffer.extraMinutes(static, 90 * 60L) >=
                DynamicArrivalBuffer.extraMinutes(traffic, 90 * 60L)
        )
    }
}
