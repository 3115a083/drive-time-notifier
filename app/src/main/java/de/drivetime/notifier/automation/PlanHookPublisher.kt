package de.drivetime.notifier.automation

import android.content.Context
import androidx.work.*
import de.drivetime.notifier.security.PlanHookStore
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

object PlanHookPublisher {
    fun onSaved(context: Context, plan: SavedPlan) {
        try {
            val config = PlanHookStore(context).read()
            if (!config.intentEnabled && !config.webhookEnabled) return
            val deliveryId = UUID.randomUUID().toString()
            val payload = JSONObject(PlanPayload.values(plan, config.fields, deliveryId)).toString()
            require(payload.toByteArray().size <= 8_000) { "Forwarding payload is too large." }
            val planKey = MessageDigest.getInstance("SHA-256").digest(plan.planId.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val revisions = context.getSharedPreferences("plan-hook-revisions", Context.MODE_PRIVATE)
            val edit = revisions.edit().putLong(planKey, plan.revisionMillis)
            val oldKeys = revisions.all.filterValues { it is Long }.entries.sortedBy { it.value as Long }
            oldKeys.filter { it.key != planKey && (it.value as Long) < System.currentTimeMillis() - 7 * 86_400_000L }
                .forEach { edit.remove(it.key) }
            oldKeys.take((oldKeys.size - 511).coerceAtLeast(0)).filter { it.key != planKey }.forEach { edit.remove(it.key) }
            edit.apply()
            for (transport in listOf("intent", "webhook")) {
                if (transport == "intent" && !config.intentEnabled || transport == "webhook" && !config.webhookEnabled) continue
                val request = OneTimeWorkRequestBuilder<PlanHookWorker>()
                    .setInputData(workDataOf("payload" to payload, "delivery_id" to deliveryId,
                        "configuration" to config.fingerprint(), "plan_key" to planKey,
                        "revision" to plan.revisionMillis, "queued_at" to System.currentTimeMillis(), "transport" to transport))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                if (transport == "webhook") request.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                WorkManager.getInstance(context).enqueueUniqueWork("plan-forward-$transport-$planKey",
                    ExistingWorkPolicy.REPLACE, request.build())
            }
            PlanHookStore(context).status("${java.time.Instant.now()} queued")
        } catch (error: Exception) {
            PlanHookStore(context).status("${java.time.Instant.now()} queue-failed")
            AutomationRunLog.add(context, "plan-forward", "queue-failed", error.javaClass.simpleName)
        }
    }
}
