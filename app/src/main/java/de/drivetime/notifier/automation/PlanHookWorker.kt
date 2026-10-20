package de.drivetime.notifier.automation

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.drivetime.notifier.security.PlanHookStore
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

class PlanHookWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val store = PlanHookStore(applicationContext)
        val config = store.read()
        val transport = inputData.getString("transport") ?: return Result.failure()
        if (config.fingerprint() != inputData.getString("configuration") ||
            transport == "intent" && !config.intentEnabled || transport == "webhook" && !config.webhookEnabled) return Result.success()
        val latest = applicationContext.getSharedPreferences("plan-hook-revisions", Context.MODE_PRIVATE)
            .getLong(inputData.getString("plan_key"), -1)
        if (latest != inputData.getLong("revision", -2) ||
            System.currentTimeMillis() - inputData.getLong("queued_at", 0) > 2 * 3_600_000L) {
            record(store, transport, "skipped-stale")
            return Result.success()
        }
        val payload = inputData.getString("payload") ?: return Result.failure()
        return try {
            if (transport == "intent") {
                val intent = Intent(config.action).setPackage(config.targetPackage)
                intent.putExtra("payload", payload)
                val json = JSONObject(payload)
                json.keys().forEach { name ->
                    when (val value = json.get(name)) {
                        is Number -> intent.putExtra(name, value.toLong())
                        is Boolean -> intent.putExtra(name, value)
                        else -> intent.putExtra(name, value.toString())
                    }
                }
                applicationContext.sendBroadcast(intent)
                record(store, transport, "broadcast-sent") // Broadcasts do not acknowledge receiver execution.
                Result.success()
            } else if (transport == "webhook") {
                val status = PlanWebhookClient().post(config.webhookUrl, config.bearerToken, payload,
                    requireNotNull(inputData.getString("delivery_id")))
                if (status in 200..299) { record(store, transport, "http-$status"); Result.success() }
                else if ((status == 408 || status == 429 || status >= 500) && runAttemptCount < 2) {
                    record(store, transport, "retry-http-$status"); Result.retry()
                } else { record(store, transport, "failed-http-$status"); Result.failure() }
            } else Result.failure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val retry = transport == "webhook" && runAttemptCount < 2
            record(store, transport, if (retry) "retry-network" else "failed")
            if (retry) Result.retry() else Result.failure()
        }
    }

    private fun record(store: PlanHookStore, transport: String, result: String) {
        store.status("${java.time.Instant.now()} $transport $result")
        AutomationRunLog.add(applicationContext, "plan-forward-$transport", result)
    }
}
