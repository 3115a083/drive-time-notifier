package de.drivetime.notifier.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import de.drivetime.notifier.data.*
import de.drivetime.notifier.network.*
import de.drivetime.notifier.routing.ProviderConnectivityChecker
import de.drivetime.notifier.security.SecureApiKeyStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ServiceHealthUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun statusAndDurationSurviveCollapseAndUpdateAfterRuntimeError() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val settings=AppSettings(language=AppLanguage.GERMAN,routingProvider=RoutingProvider.OSRM,routingServicesConfigured=true)
        val fp=ProviderConnectivityChecker.providerFingerprint(RoutingProvider.OSRM,settings,"")
        ServiceMonitor.record("osrm",fp,true,1234)
        try {
            compose.setContent { MaterialTheme { androidx.compose.foundation.layout.Column { RoutingServiceList(settings,SecureApiKeyStore(context),{}) } } }
            compose.onNodeWithText("1.").assertExists()
            compose.onNodeWithText("1.2 s").assertExists()
            compose.onNodeWithContentDescription("API erreichbar").assertExists()
            compose.onNodeWithContentDescription("Einstellungen für OSRM").performClick()
            compose.onNodeWithContentDescription("Einstellungen für OSRM").performClick()
            compose.onNodeWithContentDescription("API erreichbar").assertExists()
            compose.runOnIdle { ServiceMonitor.record("osrm",fp,false,2500) }
            compose.onNodeWithContentDescription("API-Fehler oder nicht erreichbar").assertExists()
            compose.onNodeWithText("2.5 s").assertExists()
            val device=androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val image=java.io.File(context.getExternalFilesDir(null),"service-list.png")
            assertTrue(device.takeScreenshot(image))
            device.executeShellCommand("cp ${image.absolutePath} /data/local/tmp/${image.name}")
            assertFalse(ServiceMonitor.read("osrm",fp)!!.valid)
            assertNull(ServiceMonitor.read("osrm","changed-config"))
        } finally { ServiceMonitor.invalidate("osrm") }
    }
    @Test fun authIsEncryptedAndChangingItInvalidatesHealthFingerprint() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val endpoint="https://example.test/private"
        val before=EndpointSecurity.fingerprint(endpoint)
        try {
            EndpointSecurity.save(endpoint,EndpointHeader("X-Test-Key","fixture-secret-123"))
            assertEquals(EndpointHeader("X-Test-Key","fixture-secret-123"),EndpointSecurity.header(endpoint))
            assertNotEquals(before,EndpointSecurity.fingerprint(endpoint))
            val stored=context.getSharedPreferences("endpoint_headers",0).all.toString()
            assertFalse(stored.contains("fixture-secret-123"))
            assertFalse(stored.contains("example.test"))
            assertEquals("[redacted]",EndpointSecurity.redact("fixture-secret-123"))
        } finally { EndpointSecurity.save(endpoint,null) }
    }

    @Test fun interceptorAttachesAuthOnlyWithinItsExplicitScopeAndNeverFollowsRedirects() {
        val endpoint="https://example.test/private"
        EndpointSecurity.save(endpoint,EndpointHeader("X-Test-Key","synthetic-test-secret"))
        try {
            var captured:String?=null
            val client=okhttp3.OkHttpClient.Builder().endpointSecurity().addInterceptor { chain ->
                captured=chain.request().header("X-Test-Key")
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("OK").body(okhttp3.ResponseBody.create(null,"{}")).build()
            }.build()
            assertFalse(client.followRedirects)
            client.newCall(okhttp3.Request.Builder().endpointScope(endpoint).url(endpoint+"/route").build()).execute().close()
            assertEquals("synthetic-test-secret",captured)
            captured=null
            try {
                client.newCall(okhttp3.Request.Builder().endpointScope(endpoint).url("https://attacker.invalid/private").build()).execute().close()
                fail("Scope mismatch must fail before any request is sent")
            } catch(expected:java.io.IOException) {assertEquals("Endpoint scope mismatch.",expected.message)}
            assertNull(captured)
        } finally {EndpointSecurity.save(endpoint,null)}
    }

    @Test fun authenticatedEndpointErrorBodiesCannotLeakEscapedCredentialsIntoDiagnostics() = kotlinx.coroutines.runBlocking {
        val endpoint="https://example.test/overpass"
        EndpointSecurity.save(endpoint,EndpointHeader("X-Test-Key","sensitive/test-key"))
        de.drivetime.notifier.debug.RequestDebugLog.clear()
        try {
            val client=okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(500).message("Failure").body(okhttp3.ResponseBody.create(null,"sensitive"+"\\/test-key")).build()
            }.build()
            val result=de.drivetime.notifier.routing.OsmEnrichmentClient(endpoint,listOf(endpoint),client=client)
                .query(listOf(org.osmdroid.util.GeoPoint(52.52,13.40)),parking=de.drivetime.notifier.routing.ParkingSearchOptions())
            assertTrue(result.unavailable)
            assertFalse(de.drivetime.notifier.debug.RequestDebugLog.format().contains("sensitive"))
        } finally {
            EndpointSecurity.save(endpoint,null)
            de.drivetime.notifier.debug.RequestDebugLog.clear()
        }
    }
}
