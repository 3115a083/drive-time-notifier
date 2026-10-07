package de.drivetime.notifier.network

import kotlinx.coroutines.*
import okhttp3.*
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AwaitResponseTest {
    @Test fun cancellingAnAbandonedRequestCancelsItsSocketCall() = runBlocking {
        val call = PendingCall()
        val request = launch(start = CoroutineStart.UNDISPATCHED) { call.awaitResponse().close() }
        assertTrue(call.isExecuted())
        request.cancelAndJoin()
        assertTrue(call.isCanceled())
        // A cancellation-triggered IO failure must not revive the cancelled coroutine.
        call.callback!!.onFailure(call, IOException("cancelled"))
        assertTrue(request.isCancelled)
    }
    private class PendingCall : Call {
        var callback: Callback? = null
        private var cancelled = false
        override fun request(): Request = Request.Builder().url("https://example.com/").build()
        override fun execute(): Response = error("Not a synchronous test")
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = callback != null
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout()
        override fun clone(): Call = PendingCall()
    }
}
