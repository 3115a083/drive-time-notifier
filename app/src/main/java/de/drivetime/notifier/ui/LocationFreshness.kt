package de.drivetime.notifier.ui

/** Bounds reuse to two minutes; approximate-location users retain their chosen privacy level. */
internal object LocationFreshness {
    fun usable(latitude: Double, longitude: Double, accuracyMeters: Double, ageMillis: Long, precise: Boolean): Boolean =
        latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            accuracyMeters.isFinite() && accuracyMeters in 0.0..(if (precise) 500.0 else 5_000.0) && ageMillis in 0..120_000
}
