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
            if(!device.hasObject(By.text("Regression Home"))) scroll.scrollUntil(Direction.DOWN,Until.findObject(By.text("Regression Home")))
            val defaultField=find(By.text("Regression Home"))
            defaultField.text=""
            Thread.sleep(900) // Exceeds the former destructive 500 ms autosave.
            assertEquals("Regression Home",store.flow.first().homeAddress)
            defaultField.text="Replacement Home"
            Thread.sleep(900)
            assertEquals("Regression Home",store.flow.first().homeAddress)
            scroll.scrollUntil(Direction.DOWN,Until.findObject(By.text("Standard-Standort speichern")))
            find(By.text("Standard-Standort speichern")).click()
            kotlinx.coroutines.withTimeout(5_000) {store.flow.first {it.homeAddress=="Replacement Home"}}
            assertEquals("Replacement Home",store.flow.first().homeAddress)
        } finally {
            device.pressBack()
            store.update(original)
        }
    }
}
