package de.drivetime.notifier.sharing

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import de.drivetime.notifier.MainActivity
import de.drivetime.notifier.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlanningOverlaySmokeTest {
    @Test fun actualOverlayOpensInBothThemesAndCloses() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName)), 15_000))
        try {
            for (appearance in listOf(AppAppearance.LIGHT, AppAppearance.DARK)) {
                SettingsStore(context).update(AppSettings(sharePopupEnabled = true, language = AppLanguage.GERMAN,
                    appearance = appearance, palette = ColorPalette.OCEAN, homeAddress = "52.52,13.40"))
                instrumentation.runOnMainSync {
                    context.startForegroundService(Intent(context, PlanningOverlayService::class.java).putExtra("destination", "52.50,13.42"))
                }
                assertTrue("Overlay did not render", device.wait(Until.hasObject(By.text("Fahrt planen")), 15_000))
                assertTrue(device.hasObject(By.text("Aktueller Standort")))
                device.waitForIdle()
                val output = File(context.getExternalFilesDir(null), "overlay-${appearance.id}.png")
                assertTrue(device.takeScreenshot(output))
                // Gradle removes the app and its external files after instrumentation.
                device.executeShellCommand("cp ${output.absolutePath} /data/local/tmp/${output.name}")
                device.findObject(By.desc("Schließen")).click()
                assertTrue("Overlay did not close", device.wait(Until.gone(By.text("Fahrt planen")), 10_000))
            }
        } finally {
            context.stopService(Intent(context, PlanningOverlayService::class.java))
        }
    }
}
