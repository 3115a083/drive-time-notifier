package de.drivetime.notifier.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class RequestDebugEntry(
    val timestampMillis: Long,
    val service: String,
    val request: String,
    val durationMillis: Long,
    val result: String,
    val detail: String
)

object RequestDebugLog {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val mutableEntries = MutableStateFlow<List<RequestDebugEntry>>(emptyList())
    val entries = mutableEntries.asStateFlow()

    @Synchronized
    fun add(
        service: String,
        request: String,
        durationMillis: Long,
        result: String,
        detail: String
    ) {
        val entry = RequestDebugEntry(
            timestampMillis = System.currentTimeMillis(),
            service = sanitize(service, 80),
            request = sanitize(request, 160),
            durationMillis = durationMillis.coerceAtLeast(0L),
            result = sanitize(result, 120),
            detail = sanitize(detail, 1_500)
        )
        mutableEntries.value = (mutableEntries.value + entry).takeLast(MAX_ENTRIES)
    }

    @Synchronized
    fun clear() {
        mutableEntries.value = emptyList()
    }

    fun format(entries: List<RequestDebugEntry> = mutableEntries.value): String = buildString {
        appendLine("Drive Time Notifier network debug")
        appendLine("Generated: ${Instant.now()}")
        appendLine("Entries: ${entries.size}")
        appendLine()
        entries.forEach { entry ->
            val time = Instant.ofEpochMilli(entry.timestampMillis)
                .atZone(ZoneId.systemDefault())
                .format(formatter)
            appendLine("[$time] ${entry.service}")
            appendLine("Request: ${entry.request}")
            appendLine("Duration: ${entry.durationMillis} ms")
            appendLine("Result: ${entry.result}")
            appendLine("Detail: ${entry.detail}")
            appendLine()
        }
    }.trimEnd()

    private fun sanitize(value: String, limit: Int): String = value
        .filter { it == '\n' || it == '\t' || !it.isISOControl() }
        .replace(Regex("[\\r\\n]+"), " ")
        .trim()
        .take(limit)

    private const val MAX_ENTRIES = 200
}
