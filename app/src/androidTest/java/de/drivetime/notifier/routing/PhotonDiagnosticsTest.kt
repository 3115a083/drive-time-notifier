package de.drivetime.notifier.routing

import androidx.test.ext.junit.runners.AndroidJUnit4
import de.drivetime.notifier.debug.RequestDebugLog
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.SocketTimeoutException

@RunWith(AndroidJUnit4::class)
class PhotonDiagnosticsTest {
    @Test fun actualLookupTimeoutAppearsInExportWithoutQueryOrExceptionSecrets() = runBlocking {
        RequestDebugLog.clear()
        val client = OkHttpClient.Builder().addInterceptor {
            throw SocketTimeoutException("private-response secret-credential")
        }.build()
        val service = PhotonSearchService("https://example.org", "diagnostic-test", client = client)
        try {
            service.testEndpoint()
            fail("Expected timeout")
        } catch (error: java.io.IOException) {
            assertTrue(error.message.orEmpty().contains("Photon: address lookup timed out"))
        }
        val entries = RequestDebugLog.entries.value
        assertEquals(listOf("started", "timeout"), entries.map { it.result })
        val exported = RequestDebugLog.format()
        for (secret in listOf("Berlin", "private-response", "secret-credential", "example.org")) {
            assertFalse(exported.contains(secret))
        }
        RequestDebugLog.clear()
    }
}
