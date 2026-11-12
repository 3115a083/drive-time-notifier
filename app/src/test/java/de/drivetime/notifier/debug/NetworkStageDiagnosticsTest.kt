package de.drivetime.notifier.debug

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class NetworkStageDiagnosticsTest {
    @Before fun setup() = RequestDebugLog.clear()
    @After fun cleanup() = RequestDebugLog.clear()

    @Test fun inFlightAndTimedOutRequestsAreExportedWithoutPrivateExceptionText() {
        val diagnostics = NetworkStageDiagnostics("OSRM", "route calculation", 14000, 8000)
        assertEquals("started", RequestDebugLog.entries.value.single().result)
        diagnostics.failure(SocketTimeoutException("secret-key private-address https://private.example/route"))
        val output = RequestDebugLog.format()
        assertEquals("timeout", RequestDebugLog.entries.value.last().result)
        assertTrue(output.contains("callTimeout=14000ms"))
        assertTrue(output.contains("connectTimeout=8000ms"))
        for (privateText in listOf("secret-key", "private-address", "private.example")) {
            assertFalse(output.contains(privateText))
        }
    }

    @Test fun wrappedTimeoutIsRecognizedButCancellationIsNotATimeout() {
        assertTrue(NetworkStageDiagnostics.isTimeout(IOException("wrapped", SocketTimeoutException())))
        val cancellation = CancellationException("cancelled").apply { initCause(SocketTimeoutException()) }
        assertFalse(NetworkStageDiagnostics.isTimeout(cancellation))
        NetworkStageDiagnostics("Photon", "address lookup", 8000, 5000).failure(cancellation)
        assertEquals("cancelled", RequestDebugLog.entries.value.last().result)
    }

    @Test fun successAndOtherFailuresHaveDistinctResults() {
        NetworkStageDiagnostics("Photon", "address lookup", 8000, 5000).success()
        assertEquals("success", RequestDebugLog.entries.value.last().result)
        NetworkStageDiagnostics("Photon", "address lookup", 8000, 5000).failure(IOException("private response"))
        assertEquals("failed", RequestDebugLog.entries.value.last().result)
        assertFalse(RequestDebugLog.format().contains("private response"))
    }
}
