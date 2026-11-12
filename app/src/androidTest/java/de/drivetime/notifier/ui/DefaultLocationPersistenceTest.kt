package de.drivetime.notifier.ui

import android.content.Intent
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.drivetime.notifier.MainActivity
import de.drivetime.notifier.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DefaultLocationPersistenceTest {
    @get:Rule val compose=createEmptyComposeRule()

    @Test fun clearingTheSettingsDraftAndTypingAManualPlannerAddressNeverEraseTheSavedDefault()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=SettingsStore(context)
        val original=store.flow.first()
        store.update(AppSettings(language=AppLanguage.GERMAN,homeAddress="Regression Home",homeName="Regression default"))
        val scenario=ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            compose.onNode(hasSetTextAction() and hasText("Regression Home")).performTextReplacement("Manual planner address")
            assertEquals("Regression Home",store.flow.first().homeAddress)
            compose.onNodeWithContentDescription("Einstellungen").performClick()
            val defaultField=compose.onNode(hasSetTextAction() and hasText("Standard-Startort"))
            defaultField.performScrollTo().performTextClearance()
            Thread.sleep(900) // Exceeds the former destructive 500 ms autosave.
            assertEquals("Regression Home",store.flow.first().homeAddress)
            defaultField.performTextInput("Replacement Home")
            Thread.sleep(900)
            assertEquals("Regression Home",store.flow.first().homeAddress)
            // Hide the actual IME without a Back event that could leave Settings.
            scenario.onActivity { activity ->
                activity.getSystemService(InputMethodManager::class.java)
                    .hideSoftInputFromWindow(activity.window.decorView.windowToken,0)
            }
            compose.waitForIdle()
            compose.onNodeWithText("Standard-Standort speichern").performScrollTo().performClick()
            kotlinx.coroutines.withTimeout(5_000) {store.flow.first {it.homeAddress=="Replacement Home"}}
            assertEquals("Replacement Home",store.flow.first().homeAddress)
        } catch(error:Throwable) {
            val device=androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val tree=java.io.File(context.getExternalFilesDir(null),"default-location-failure.xml")
            val image=java.io.File(context.getExternalFilesDir(null),"default-location-failure.png")
            device.dumpWindowHierarchy(tree);device.takeScreenshot(image)
            device.executeShellCommand("cp ${tree.absolutePath} /data/local/tmp/${tree.name}")
            device.executeShellCommand("cp ${image.absolutePath} /data/local/tmp/${image.name}")
            throw error
        } finally {
            scenario.close()
            store.update(original)
        }
    }
}
