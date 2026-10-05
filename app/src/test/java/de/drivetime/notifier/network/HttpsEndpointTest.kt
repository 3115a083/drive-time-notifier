package de.drivetime.notifier.network

import org.junit.Assert.*
import org.junit.Test

class HttpsEndpointTest {
    @Test fun requiresARealHttpsHostWithoutEmbeddedCredentials() {
        assertEquals("https://example.org/api", HttpsEndpoint.normalize(" https://example.org/api/ "))
        assertEquals("https://example.org/custom-key/api", HttpsEndpoint.normalize("https://example.org/custom-key/api"))
        for (value in listOf("http://example.org", "https:///path", "https://user:secret@example.org", "https://example.org/#secret", "https://example.org:99999", "https://example.org/\nheader")) assertNull(value, HttpsEndpoint.normalize(value))
    }
}
