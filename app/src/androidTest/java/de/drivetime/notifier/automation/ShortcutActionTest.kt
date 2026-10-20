package de.drivetime.notifier.automation

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import de.drivetime.notifier.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ShortcutActionTest {
    @Test fun registeredActionsQueueWorkWithoutOpeningMainActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = context.applicationContext as Application
        // Test constraints stay unmet. No calendar writes or provider requests are executed.
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor()).build())
        val manager = WorkManager.getInstance(context)
        val shortcuts = context.getSystemService(ShortcutManager::class.java).manifestShortcuts
        val actions = mapOf(
            "process_next_day" to ("manual-next-day" to AutomationReceiver.ACTION_PROCESS_NEXT_DAY),
            "next_drive" to ("manual-next-drive-locator" to MainActivity.ACTION_NEXT_DRIVE)
        )
        val mainCreates = AtomicInteger()
        var finished = CountDownLatch(1)
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity is MainActivity) mainCreates.incrementAndGet()
            }
            override fun onActivityDestroyed(activity: Activity) {
                if (activity is ShortcutActionActivity) finished.countDown()
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            for ((id, expected) in actions) {
                finished = CountDownLatch(1)
                val shortcut = shortcuts.single { it.id == id }
                val intent = requireNotNull(shortcut.intent)
                assertEquals(expected.second, intent.action)
                assertEquals(ComponentName(context, ShortcutActionActivity::class.java), intent.component)
                instrumentation.runOnMainSync {
                    context.startActivity(Intent(intent).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }
                assertTrue("Invisible shortcut activity did not finish", finished.await(15, TimeUnit.SECONDS))
                val work = manager.getWorkInfosForUniqueWork(expected.first).get(10, TimeUnit.SECONDS)
                assertEquals(1, work.size)
                assertEquals(WorkInfo.State.ENQUEUED, work.single().state)
                assertEquals("A shortcut opened the confirmation screen", 0, mainCreates.get())
            }
            finished = CountDownLatch(1)
            instrumentation.runOnMainSync {
                context.startActivity(Intent(context, ShortcutActionActivity::class.java)
                    .setAction("unknown-action").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            assertTrue(finished.await(15, TimeUnit.SECONDS))
            assertEquals(0, mainCreates.get())
            assertEquals(1, manager.getWorkInfosForUniqueWork("manual-next-day").get().size)
            assertEquals(1, manager.getWorkInfosForUniqueWork("manual-next-drive-locator").get().size)
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
            manager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
        }
    }

    @Test fun directExternalLaunchIsDeniedAndShortcutHasItsOwnTask() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val component = ComponentName(context, ShortcutActionActivity::class.java)
        @Suppress("DEPRECATION")
        val info = context.packageManager.getActivityInfo(component, 0)
        assertFalse("Hands-off entry must not accept arbitrary external intents", info.exported)
        assertEquals("", info.taskAffinity.orEmpty())
        val output = UiDevice.getInstance(instrumentation).executeShellCommand(
            "am start -W -n ${component.flattenToString()} -a ${MainActivity.ACTION_NEXT_DRIVE} 2>&1")
        assertTrue("Shell must not be able to trigger private shortcut work: $output",
            output.contains("Permission Denial") || output.contains("SecurityException"))
    }
}
