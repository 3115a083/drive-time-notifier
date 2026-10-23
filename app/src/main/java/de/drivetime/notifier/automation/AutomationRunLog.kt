package de.drivetime.notifier.automation

import android.content.Context
import java.time.ZonedDateTime

/** Bounded metadata only. Never include event titles, addresses or credentials. */
object AutomationRunLog {
    @Synchronized
    fun add(context: Context, trigger: String, result: String, detail: String = "") {
        val preferences = context.getSharedPreferences("automation-run-log", Context.MODE_PRIVATE)
        val line = "${ZonedDateTime.now()} | $trigger | $result | $detail".replace('\n', ' ').take(500)
        val lines = (preferences.getString("entries", "").orEmpty().lines().filter { it.isNotBlank() } + line).takeLast(100)
        preferences.edit().putString("entries", lines.joinToString("\n")).apply()
    }
    fun format(context: Context): String = "Automation execution history\n" +
        context.getSharedPreferences("automation-run-log", Context.MODE_PRIVATE).getString("entries", "").orEmpty()
    fun clear(context: Context) { context.getSharedPreferences("automation-run-log", Context.MODE_PRIVATE).edit().clear().apply() }
}
