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
            compose.setContent { MaterialTheme { RoutingServiceList(settings,SecureApiKeyStore(context),{}) } }
            compose.onNodeWithText("1.").assertExists()
            compose.onNodeWithText("1.2 s").assertExists()
            compose.onNodeWithContentDescription("API erreichbar").assertExists()
            compose.onNodeWithContentDescription("Einstellungen für OSRM").performClick()
            compose.onNodeWithContentDescription("Einstellungen für OSRM").performClick()
            compose.onNodeWithContentDescription("API erreichbar").assertExists()
            compose.runOnIdle { ServiceMonitor.record("osrm",fp,false,2500) }
            compose.onNodeWithContentDescription("API-Fehler oder nicht erreichbar").assertExists()
            compose.onNodeWithText("2.5 s").assertExists()
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
}
