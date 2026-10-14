package de.drivetime.notifier.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.drivetime.notifier.security.PlanHookStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanHookDeliveryTest {
    @Test fun savedPlanProducesARestrictedBroadcastAndEncryptedSettings() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = PlanHookStore(context)
        val previous = store.read()
        val action = "de.drivetime.notifier.TEST_PLAN_SAVED"
        val received = Channel<Intent>(Channel.UNLIMITED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { received.trySend(intent) }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_EXPORTED)
        try {
            val config = PlanHookConfig(intentEnabled = true, action = action, targetPackage = context.packageName,
                webhookUrl = "https://ha.example/api/webhook/private-secret", bearerToken = "private-token",
                fields = setOf("start_time", "plan_id", "revision_ms"))
            store.save(config)
            assertEquals(config, store.read())
            val cipher = requireNotNull(context.getSharedPreferences("plan-hooks", Context.MODE_PRIVATE).getString("cipher", null))
            assertFalse(cipher.contains("private-secret"))
            assertFalse(cipher.contains("private-token"))
            val revision = System.currentTimeMillis()
            val plan = SavedPlan("Never send home", "Never send destination", revision + 3_600_000,
                revision + 7_200_000, revision + 8_100_000, 42, 7, revision, "updated", null, "Never send title")
            PlanHookPublisher.onSaved(context, plan)
            val intent = withTimeout(30_000) { received.receive() }
            assertEquals(action, intent.action)
            assertEquals(context.packageName, intent.`package`)
            assertNull(intent.component) // Compatible with Tasker's dynamic Intent Received filter.
            assertEquals(plan.departureMillis, intent.getLongExtra("start_time_ms", -1))
            assertEquals(plan.planId, intent.getStringExtra("plan_id"))
            assertFalse(intent.hasExtra("start_address"))
            assertFalse(intent.hasExtra("destination_address"))
            assertFalse(intent.hasExtra("event_title"))
            val json = JSONObject(requireNotNull(intent.getStringExtra("payload")))
            assertEquals(setOf("schema_version", "delivery_id", "start_time", "start_time_ms", "plan_id", "revision_ms"), json.keys().asSequence().toSet())
            assertFalse(json.toString().contains("Never send"))
        } finally {
            store.save(previous)
            context.unregisterReceiver(receiver)
            received.close()
        }
    }
}
