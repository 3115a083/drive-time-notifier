package de.drivetime.notifier.ui

import androidx.test.platform.app.InstrumentationRegistry
import de.drivetime.notifier.network.*
import okhttp3.*
import org.junit.Assert.*
import org.junit.Test

class MultiHeaderSecurityTest {
    @Test fun legacyHeadersMigrateAndPangolinPairPlusApiAuthAreEncryptedAndSentTogether() {
        val endpoint="https://headers-fixture.invalid/private"
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences("endpoint_headers",0)
        val storageKey=digest(HttpsEndpoint.normalize(endpoint)!!)
        try {
            prefs.edit().putString(storageKey,EndpointSecurity.encryptDraft("{\"name\":\"X-Legacy\",\"value\":\"legacy-secret\"}")).commit()
            assertEquals(listOf(EndpointHeader("X-Legacy","legacy-secret")),EndpointSecurity.headers(endpoint))
            val headers=listOf(EndpointHeader("P-Access-Token-Id","fixture-id"),EndpointHeader("P-Access-Token","fixture-token"),EndpointHeader("Authorization","Bearer fixture-api-key"))
            EndpointSecurity.saveHeaders(endpoint,headers)
            assertEquals(headers,EndpointSecurity.headers(endpoint))
            assertFalse(prefs.all.toString().contains("fixture-token"))
            assertFalse(EndpointSecurity.encryptDraft("draft-secret").contains("draft-secret"))
            var captured:Headers?=null
            val client=OkHttpClient.Builder().endpointSecurity().addInterceptor { chain ->
                captured=chain.request().headers
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(ResponseBody.create(null,"{}")).build()
            }.build()
            client.newCall(Request.Builder().endpointScope(endpoint).url(endpoint+"/route").build()).execute().close()
            headers.forEach { assertEquals(it.value,captured!![it.name]) }
            captured=null
            client.newCall(Request.Builder().url(endpoint+"/route").build()).execute().close()
            headers.forEach { assertNull(captured!![it.name]) }
            prefs.edit().putString(storageKey,"corrupt").commit()
            try { client.newCall(Request.Builder().endpointScope(endpoint).url(endpoint).build()).execute().close();fail("Must fail closed") }
            catch(expected:java.io.IOException) { assertTrue(expected.message.orEmpty().contains("credentials unavailable")) }
        } finally {EndpointSecurity.saveHeaders(endpoint,emptyList())}
    }
}
