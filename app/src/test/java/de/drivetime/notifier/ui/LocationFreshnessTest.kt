package de.drivetime.notifier.ui

import org.junit.Assert.*
import org.junit.Test

class LocationFreshnessTest {
    @Test fun rejectsStaleFutureAndInvalidPositions() {
        assertFalse(LocationFreshness.usable(52.5, 13.4, 20.0, 120_001, true))
        assertFalse(LocationFreshness.usable(52.5, 13.4, 20.0, -1, true))
        assertFalse(LocationFreshness.usable(Double.NaN, 13.4, 20.0, 0, true))
        assertFalse(LocationFreshness.usable(91.0, 13.4, 20.0, 0, true))
    }
    @Test fun permitsRecentApproximateLocationWithoutClaimingPrecision() {
        assertTrue(LocationFreshness.usable(52.5, 13.4, 3000.0, 120_000, false))
        assertFalse(LocationFreshness.usable(52.5, 13.4, 3000.0, 120_000, true))
        assertTrue(LocationFreshness.usable(52.5, 13.4, 30.0, 500, true))
        assertFalse(LocationFreshness.usable(52.5, 13.4, 5001.0, 0, false))
    }
}
