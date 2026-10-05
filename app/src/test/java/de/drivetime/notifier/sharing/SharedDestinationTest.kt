package de.drivetime.notifier.sharing

import org.junit.Assert.*
import org.junit.Test

class SharedDestinationTest {
    @Test fun acceptsPlainAddressAndGeoTargets() {
        assertEquals("Berlin, Alexanderplatz 1", SharedDestination.parse("Berlin, Alexanderplatz 1"))
        assertEquals("52.52,13.405", SharedDestination.parse("geo:52.52,13.405"))
        assertEquals("52.52,13.405", SharedDestination.parse("geo:0,0?q=52.52,13.405(Berlin)"))
        assertEquals("Alexanderplatz Berlin", SharedDestination.parse("google.navigation:q=Alexanderplatz%20Berlin"))
    }
    @Test fun extractsActualDestinationRatherThanMapViewport() {
        assertEquals("Berlin", SharedDestination.parse("https://www.google.com/maps/dir/?api=1&origin=Hamburg&destination=Berlin"))
        assertEquals("52.52,13.405", SharedDestination.parse("https://www.google.com/maps/place/Berlin/@50.0,10.0,10z/data=!3d52.52!4d13.405"))
        assertEquals("52.52,13.405", SharedDestination.parse("https://www.openstreetmap.org/?mlat=52.52&mlon=13.405#map=10/50/10"))
        assertEquals("Berlin", SharedDestination.parse("Visit this place\nhttps://maps.apple.com/?q=Berlin"))
    }
    @Test fun rejectsUntrustedAndOversizedLinks() {
        assertNull(SharedDestination.parse("https://evil.example/?q=Berlin"))
        assertNull(SharedDestination.parse("https://www.google.com.evil.example/maps?q=Berlin"))
        assertNull(SharedDestination.parse("https://user@www.google.com/maps?q=Berlin"))
        assertNull(SharedDestination.parse("http://www.google.com/maps?q=Berlin"))
        assertNull(SharedDestination.parse("https://www.google.com:444/maps?q=Berlin"))
        assertNull(SharedDestination.parse("a".repeat(8193)))
        assertNull(SharedDestination.parse("geo:999,999"))
        assertNull(SharedDestination.coordinates("NaN,13"))
        assertNull(SharedDestination.coordinates("52,181"))
        assertNull(SharedDestination.coordinates("52,13,5"))
    }
}
