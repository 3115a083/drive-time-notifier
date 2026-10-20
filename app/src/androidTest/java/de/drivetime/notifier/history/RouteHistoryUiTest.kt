package de.drivetime.notifier.history

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import de.drivetime.notifier.MainActivity
import de.drivetime.notifier.data.*
import de.drivetime.notifier.model.*
import de.drivetime.notifier.routing.PolylineEncoder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint

@RunWith(AndroidJUnit4::class)
class RouteHistoryUiTest {
    @Test fun iconIsLeftOfSettingsAndHistoryCanBeViewedWithoutRecalculation()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val device=UiDevice.getInstance(instrumentation)
        val store=RouteHistoryStore(context)
        SettingsStore(context).update(AppSettings(language=AppLanguage.GERMAN))
        store.configure(true,50)
        val now=System.currentTimeMillis()
        val route=RouteEstimate(600,600,1200,
            PolylineEncoder.encode(listOf(GeoPoint(52.52,13.40),GeoPoint(52.50,13.42))),providerId="osrm")
        store.record {RouteHistoryEntry("ui-test",now,now,HistoryTrigger.APP_ACTION,
            RouteRequest("Teststart","Testziel",now+3_600_000),route,now+2_000_000,now+2_600_000,15)}
        try {
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(device.wait(Until.hasObject(By.desc("Suchhistorie")),15_000))
            val history=device.findObject(By.desc("Suchhistorie"))
            val settings=device.findObject(By.desc("Einstellungen"))
            assertTrue(history.visibleBounds.centerX()<settings.visibleBounds.centerX())
            history.click()
            assertTrue(device.wait(Until.hasObject(By.text("Teststart → Testziel")),10_000))
            device.findObject(By.text("Teststart → Testziel")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Neu berechnen")),10_000))
            assertTrue(device.hasObject(By.text("Als Vorlage verwenden")))
            assertTrue(device.hasObject(By.textContains("Gespeichertes Ergebnis.")))
            // Reading history must leave the stored calculation unchanged.
            assertEquals(now,store.flow.first().entries.single().calculatedAt)
            device.findObject(By.desc("Historie schließen")).click()
            device.findObject(By.desc("Suchhistorie")).click()
            device.findObject(By.text("Historieneinstellungen")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Maximale Eintragszahl")),10_000))
            val input=device.findObject(By.clazz("android.widget.EditText").text("50"))
            input.click()
            input.text="2"
            assertTrue("Entry-limit edit was not reflected in the field",
                device.wait(Until.hasObject(By.clazz("android.widget.EditText").text("2")),5000))
            // Close the IME before clicking the action below the input. With the
            // keyboard open its accessible node can exist outside the usable viewport.
            device.pressBack()
            device.waitForIdle()
            assertTrue("Settings closed instead of dismissing the keyboard",
                device.hasObject(By.text("Maximale Eintragszahl")))
            val apply=device.findObject(By.text("Eintragsgrenze übernehmen"))
            assertTrue("Limit action must be enabled after changing the number",apply.isEnabled)
            apply.click()
            try {
                withTimeout(10_000){store.flow.first {it.limit==2}}
            } catch(error:TimeoutCancellationException) {
                val state=store.flow.first()
                throw AssertionError("Limit was not saved: limit=\u0024{state.limit}, error=\u0024{state.error}, actionEnabled=\u0024{apply.isEnabled}",error)
            }
            assertEquals(2,store.flow.first().limit)
        } finally {
            store.configure(false,50)
        }
    }
}
