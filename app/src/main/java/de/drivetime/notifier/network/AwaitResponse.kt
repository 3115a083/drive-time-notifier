package de.drivetime.notifier.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

/** Cancel abandoned autocomplete/share requests rather than leaving sockets running. */
internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response, onCancellation = { _, value, _ -> value.close() })
        }
    })
}
