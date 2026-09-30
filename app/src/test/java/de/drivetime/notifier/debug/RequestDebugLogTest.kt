package de.drivetime.notifier.debug

import org.junit.Assert.*
import org.junit.Test

class RequestDebugLogTest {
    @Test fun exportedDiagnosticsDoNotExposeEndpointCredentials() {
        RequestDebugLog.clear()
        RequestDebugLog.add("Overpass", "via https://user:password@example.org/private-key/api/interpreter?token=secret", 12, "failed", "apiKey=hidden https://example.org/?key=another")
        val output = RequestDebugLog.format()
        for (secret in listOf("password", "private-key", "secret", "hidden", "another")) assertFalse(output.contains(secret))
        assertTrue(output.contains("https://example.org"))
        assertEquals("https://overpass-api.de/api/interpreter", RequestDebugLog.redact("https://overpass-api.de/api/interpreter"))
        RequestDebugLog.clear()
    }
}
