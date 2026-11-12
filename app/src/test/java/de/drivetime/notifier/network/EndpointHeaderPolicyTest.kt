package de.drivetime.notifier.network

import org.junit.Assert.*
import org.junit.Test

class EndpointHeaderPolicyTest {
    @Test fun rejectsHeaderInjectionAndTransportOverrides() {
        assertTrue(EndpointHeaderPolicy.valid("Authorization","Bearer test-token"))
        assertTrue(EndpointHeaderPolicy.valid("X-API-Key","test-token"))
        assertFalse(EndpointHeaderPolicy.valid("X-Key\r\nHost","test"))
        assertFalse(EndpointHeaderPolicy.valid("Authorization","test\r\nX-Other: value"))
        assertFalse(EndpointHeaderPolicy.valid("Host","attacker.invalid"))
        assertFalse(EndpointHeaderPolicy.valid("Content-Length","0"))
        assertFalse(EndpointHeaderPolicy.valid("Cookie","session=x"))
    }
    @Test fun credentialsStayOnTheConfiguredHttpsOriginAndPath() {
        val base="https://example.test/private"
        assertTrue(EndpointHeaderPolicy.applies(base,"https://example.test/private/route?q=1"))
        assertTrue(EndpointHeaderPolicy.applies(base,"https://example.test/private"))
        assertFalse(EndpointHeaderPolicy.applies(base,"https://example.test/private-other/route"))
        assertFalse(EndpointHeaderPolicy.applies(base,"https://attacker.invalid/private"))
        assertFalse(EndpointHeaderPolicy.applies(base,"https://example.test:444/private"))
        assertFalse(EndpointHeaderPolicy.applies(base,"http://example.test/private"))
        assertFalse(EndpointHeaderPolicy.applies(base,"https://example.test/private/../public"))
    }
}
