package de.drivetime.notifier.routing

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** The Android application supplies elapsedRealtime; JVM tests keep an SDK-free monotonic clock. */
internal object OverpassClock {
    @Volatile var elapsedMillis:()->Long={System.nanoTime()/1_000_000}
}

/** Raw successful responses only. Every cache mutation, LRU touch and byte count shares one lock. */
internal class OverpassResponseCache(
    private val maxEntries: Int = 16,
    private val maxBytes: Int = 8 * 1024 * 1024,
    private val ttlMillis: Long = 300_000,
    private val now: () -> Long = { OverpassClock.elapsedMillis() },
    private val timeoutMillis: Long = 60_000,
    private val maxFlights: Int = 8
) {
    init { require(maxEntries>0 && maxBytes>0 && ttlMillis>0 && timeoutMillis>0 && maxFlights>0) }
    private data class Entry(val bytes: ByteArray, val created: Long, val weight: Int)
    private class Flight(val work: Deferred<ByteArray?>, var users: Int = 0)
    private val lock=Any()
    private val entries=LinkedHashMap<String,Entry>(16,.75f,true)
    private val flights=mutableMapOf<String,Flight>()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val slots=Semaphore(4)
    private var bytes=0

    private fun prune() {
        val time=now()
        val iterator=entries.entries.iterator()
        while(iterator.hasNext()) {
            val entry=iterator.next().value
            if(time-entry.created>=ttlMillis) {bytes-=entry.weight;iterator.remove()}
        }
    }
    fun get(key:String):ByteArray? = synchronized(lock) {prune();entries[key]?.bytes?.copyOf()}
    fun put(key:String,value:ByteArray) = synchronized(lock) {
        prune()
        entries.remove(key)?.let {bytes-=it.weight}
        val weight=key.toByteArray(Charsets.UTF_8).size.toLong()+value.size
        if(weight<=maxBytes) {
            while(entries.isNotEmpty() && (entries.size>=maxEntries || bytes.toLong()+weight>maxBytes)) {
                val iterator=entries.entries.iterator();bytes-=iterator.next().value.weight;iterator.remove()
            }
            entries[key]=Entry(value.copyOf(),now(),weight.toInt());bytes+=weight.toInt()
        }
    }
    internal fun usage():Pair<Int,Int> = synchronized(lock) {prune();entries.size to bytes}

    /** A consumer's cancellation only stops the HTTP request when no consumer remains. */
    suspend fun getOrLoad(key:String,loader:suspend ()->ByteArray?):ByteArray? {
        get(key)?.let {return it}
        val flight=synchronized(lock) {
            // A previous flight may have finished between the first lookup and this lock.
            entries[key]?.takeIf {now()-it.created<ttlMillis}?.let {return it.bytes.copyOf()}
            val selected=flights[key] ?: run {
                if(flights.size>=maxFlights) return null
                val work=scope.async(start=CoroutineStart.LAZY) {
                    withTimeoutOrNull(timeoutMillis) {
                        slots.withPermit {
                            val result=loader()
                            currentCoroutineContext().ensureActive()
                            if(result!=null) synchronized(lock) {if((flights[key]?.users?:0)>0)put(key,result)}
                            result
                        }
                    }
                }
                Flight(work).also {new ->
                    flights[key]=new
                    work.invokeOnCompletion {synchronized(lock) {if(flights[key]===new)flights.remove(key)}}
                }
            }
            selected.users++
            selected
        }
        try {
            val result=flight.work.await() ?: return null
            return result.copyOf()
        } finally {
            synchronized(lock) {
                flight.users--
                if(flight.users==0 && !flight.work.isCompleted) {
                    if(flights[key]===flight)flights.remove(key)
                    flight.work.cancel()
                }
            }
        }
    }
}

internal class OverpassEndpointPolicy(private val now:()->Long={OverpassClock.elapsedMillis()}) {
    private data class Failure(val until:Long,val count:Int)
    private val failures=LinkedHashMap<String,Failure>(16,.75f,true)
    @Synchronized fun available(endpoint:String):Boolean=(failures[endpoint]?.until?:0)<=now()
    @Synchronized fun success(endpoint:String){failures.remove(endpoint)}
    @Synchronized fun failed(endpoint:String,retryAfterSeconds:Long?=null,rateLimited:Boolean=false) {
        val count=((failures[endpoint]?.count?:0)+1).coerceAtMost(5)
        val delay=if(rateLimited) maxOf(60_000L,(retryAfterSeconds?:0).coerceIn(0,900)*1000) else minOf(300_000L,15_000L*(1L shl (count-1)))
        if(failures.size>=64 && endpoint !in failures)failures.remove(failures.keys.first())
        failures[endpoint]=Failure(now()+delay,count)
    }
}


internal class OverpassRequestBudget(private val now:()->Long={OverpassClock.elapsedMillis()}) {
    private val deadline=now()+60_000
    private var attempts=0
    fun remaining():Long=(deadline-now()).coerceAtLeast(0)
    @Synchronized fun claim():Long? {
        val left=remaining()
        if(attempts>=2 || left==0L)return null
        attempts++
        return minOf(45_000L,left)
    }
}
