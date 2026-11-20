package de.drivetime.notifier.network

import de.drivetime.notifier.debug.*
import okhttp3.*
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException

class NetworkPhaseTraceTest {
    @Test fun timeoutExportIdentifiesThePhaseWithoutHostOrAddressData() {
        val trace=NetworkPhaseTrace()
        val call=OkHttpClient().newCall(Request.Builder().url("https://fixture.invalid").build())
        RequestDebugLog.clear()
        try {
            val diagnostics=NetworkStageDiagnostics("Photon","address lookup",8000,5000,trace)
            trace.dnsStart(call,"private-host.example")
            assertEquals("dns",trace.phase)
            trace.secureConnectStart(call)
            assertEquals("tls",trace.phase)
            diagnostics.failure(SocketTimeoutException("private failure"))
            assertTrue(RequestDebugLog.format().contains("phase=tls"))
            assertFalse(RequestDebugLog.format().contains("private-host"))
            assertFalse(RequestDebugLog.format().contains("private failure"))
        } finally {RequestDebugLog.clear()}
    }
}
