package de.drivetime.notifier.debug

import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException

/** Pass fixed stage names only. Never include URLs, addresses or exception messages. */
internal class NetworkStageDiagnostics(
    private val service: String,
    private val stage: String,
    timeoutMillis: Int,
    connectTimeoutMillis: Int,
    private val trace: de.drivetime.notifier.network.NetworkPhaseTrace? = null
) {
    private val started = System.nanoTime()
    private val limits = "callTimeout=${timeoutMillis}ms; connectTimeout=${connectTimeoutMillis}ms"

    init { record("started", limits) }

    fun success() = record("success", limits)

    fun failure(error: Throwable) {
        val httpCode = Regex("\\bHTTP ([1-5][0-9]{2})\\b").find(error.message.orEmpty())?.groupValues?.get(1)
        val result = when {
            error is CancellationException -> "cancelled"
            isTimeout(error) -> "timeout"
            httpCode != null -> "HTTP $httpCode"
            else -> "failed"
        }
        record(result, "$limits; phase=${trace?.phase ?: "unknown"}; exception=${error.javaClass.simpleName}")
    }

    private fun record(result: String, detail: String) = RequestDebugLog.add(
        service, stage, (System.nanoTime() - started) / 1_000_000, result, detail
    )

    companion object {
        fun isTimeout(error: Throwable): Boolean {
            if (error is CancellationException) return false
            return generateSequence(error) { it.cause }.take(16).any {
                it is SocketTimeoutException ||
                    (it is InterruptedIOException && it.message == "timeout")
            }
        }
    }
}
