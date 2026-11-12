package de.drivetime.notifier.routing

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class OverpassResponseCacheTest {
    @Test fun byteLimitLruAndExpiryAreEnforcedTogether() {
        var time=0L
        val cache=OverpassResponseCache(maxEntries=2,maxBytes=10,ttlMillis=100,now={time})
        cache.put("a",ByteArray(3));cache.put("b",ByteArray(3))
        assertNotNull(cache.get("a"))
        cache.put("c",ByteArray(3))
        assertNull(cache.get("b"));assertNotNull(cache.get("a"));assertEquals(2 to 8,cache.usage())
        cache.put("oversized",ByteArray(11));assertNull(cache.get("oversized"))
        time=100
        assertNull(cache.get("a"));assertEquals(0 to 0,cache.usage())
    }
    @Test fun concurrentWritesNeverExceedEitherLimit()=runBlocking {
        val cache=OverpassResponseCache(maxEntries=16,maxBytes=1024)
        coroutineScope {(0..200).map {i->launch(Dispatchers.Default){cache.put("key-$i",ByteArray(100));cache.get("key-$i")}}.joinAll()}
        val (count,bytes)=cache.usage();assertTrue(count<=16);assertTrue(bytes<=1024)
    }
    @Test fun rawDataIsNotMutableThroughTheCaller() {
        val cache=OverpassResponseCache();val bytes=byteArrayOf(1,2)
        cache.put("key",bytes);bytes[0]=9
        val first=cache.get("key")!!;first[1]=9
        assertArrayEquals(byteArrayOf(1,2),cache.get("key"))
    }
    @Test fun oneConsumerCancellingDoesNotCancelTheSharedRequest()=runBlocking {
        val cache=OverpassResponseCache();val calls=AtomicInteger();val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val first=async {cache.getOrLoad("shared"){calls.incrementAndGet();started.complete(Unit);release.await();byteArrayOf(7)}}
        started.await()
        val second=async(start=CoroutineStart.UNDISPATCHED){cache.getOrLoad("shared"){error("Duplicate HTTP request")}}
        first.cancelAndJoin();release.complete(Unit)
        assertArrayEquals(byteArrayOf(7),second.await());assertEquals(1,calls.get())
        assertArrayEquals(byteArrayOf(7),cache.getOrLoad("shared"){error("Cache miss")})
    }
    @Test fun lastConsumerCancelsWorkAndFailuresAreNotCached()=runBlocking {
        val cache=OverpassResponseCache();val started=CompletableDeferred<Unit>();val stopped=CompletableDeferred<Unit>()
        val request=async {cache.getOrLoad("cancel"){try{started.complete(Unit);awaitCancellation()}finally{stopped.complete(Unit)}}}
        started.await();request.cancelAndJoin();withTimeout(5_000){stopped.await()}
        assertNull(cache.get("cancel"))
        assertNull(cache.getOrLoad("failure"){null})
        assertArrayEquals(byteArrayOf(4),cache.getOrLoad("failure"){byteArrayOf(4)})
    }
    @Test fun timeoutReturnsNoResultAndDoesNotCache()=runBlocking {
        val cache=OverpassResponseCache(timeoutMillis=50)
        assertNull(withTimeout(5_000){cache.getOrLoad("slow"){awaitCancellation()}})
        assertNull(cache.get("slow"))
    }
    @Test fun endpointCooldownAndBudgetUseMonotonicTime() {
        var time=0L;val policy=OverpassEndpointPolicy {time}
        policy.failed("a");assertFalse(policy.available("a"));time=15_000;assertTrue(policy.available("a"))
        policy.failed("a",retryAfterSeconds=90,rateLimited=true);time+=60_000;assertFalse(policy.available("a"))
        time+=30_000;assertTrue(policy.available("a"));policy.success("a")
        val budget=OverpassRequestBudget {time};assertEquals(45_000L,budget.claim()!!);time+=45_000
        assertEquals(15_000L,budget.claim()!!);assertNull(budget.claim()!!);time+=15_000;assertEquals(0L,budget.remaining())
    }
}
