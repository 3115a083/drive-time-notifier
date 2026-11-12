package de.drivetime.notifier.ui

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import de.drivetime.notifier.MainActivity
import de.drivetime.notifier.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DefaultLocationPersistenceTest {
    @Test fun clearingTheSettingsDraftAndTypingAManualPlannerAddressNeverEraseTheSavedDefault()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val store=SettingsStore(context)
        val original=store.flow.first()
        val device=UiDevice.getInstance(instrumentation)
        fun find(selector:BySelector):UiObject2 = device.wait(Until.findObject(selector),10_000)
            ?: throw AssertionError("Missing UI element: $selector")
        try {
            store.update(AppSettings(language=AppLanguage.GERMAN,homeAddress="Regression Home",homeName="Regression default"))
            device.wakeUp();device.pressHome()
            instrumentation.runOnMainSync {
                context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
            find(By.text("Regression Home")).text="Manual planner address"
            assertEquals("Regression Home",store.flow.first().homeAddress)
            find(By.desc("Einstellungen")).click()
            val scroll=find(By.scrollable(true))
            scroll.scrollUntil(Direction.DOWN,Until.findObject(By.text("Regression Home")))
            find(By.text("Regression Home")).text=""
            Thread.sleep(900) // Exceeds the former destructive 500 ms autosave.
            assertEquals("Regression Home",store.flow.first().homeAddress)
            assertTrue(device.hasObject(By.text("Standard-Standort speichern")))
        } finally {
            device.pressBack()
            store.update(original)
        }
    }
}
