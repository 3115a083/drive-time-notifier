package de.drivetime.notifier.calendar

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Recheck and write share one lock across all entry points in this app process. */
object DriveWriteGuard {
    private val mutex = Mutex()
    suspend fun <T> locked(block: suspend () -> T): T = mutex.withLock { block() }
}
