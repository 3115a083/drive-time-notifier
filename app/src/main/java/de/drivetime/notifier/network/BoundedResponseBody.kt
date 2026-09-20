package de.drivetime.notifier.network

import okhttp3.ResponseBody
import okio.Buffer

/** Reads up to [maxBytes] without requiring the response to have that exact size. */
internal fun ResponseBody.readBytesLimited(maxBytes: Long): ByteArray {
    require(maxBytes > 0L) { "Maximum response size must be positive" }
    val declaredLength = contentLength()
    check(declaredLength < 0L || declaredLength <= maxBytes) { "Response too large" }

    val buffer = Buffer()
    val input = source()
    while (buffer.size <= maxBytes) {
        val remaining = maxBytes + 1L - buffer.size
        if (remaining <= 0L) break
        val read = input.read(buffer, minOf(8_192L, remaining))
        if (read == -1L) break
    }
    check(buffer.size <= maxBytes) { "Response too large" }
    return buffer.readByteArray()
}

/** Prevents an endpoint from exhausting app memory with an unexpectedly large text response. */
internal fun ResponseBody.readStringLimited(maxBytes: Long = 4L * 1024L * 1024L): String =
    String(readBytesLimited(maxBytes), Charsets.UTF_8)
