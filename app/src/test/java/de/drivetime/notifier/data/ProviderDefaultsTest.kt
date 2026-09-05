package de.drivetime.notifier.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderDefaultsTest {
    @Test
    fun freeTierDefaultsAreConservativeAndEditableModelValues() {
        val caps = ProviderCaps()
        assertEquals(2_500, caps.tomTom)
        assertEquals(2_000, caps.openRouteService)
        assertEquals(500, caps.graphHopper)
        assertEquals(5_000, caps.google)
        assertEquals(1_000, caps.here)
    }

    @Test
    fun providerPeriodsMatchPublishedQuotaPeriods() {
        val periods = ProviderLimitPeriods()
        assertEquals(LimitPeriod.DAILY, periods.tomTom)
        assertEquals(LimitPeriod.DAILY, periods.openRouteService)
        assertEquals(LimitPeriod.DAILY, periods.graphHopper)
        assertEquals(LimitPeriod.MONTHLY, periods.google)
        assertEquals(LimitPeriod.DAILY, periods.here)
    }
}
