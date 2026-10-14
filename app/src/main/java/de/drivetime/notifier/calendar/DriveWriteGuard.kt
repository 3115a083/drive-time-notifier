package de.drivetime.notifier.calendar

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All app entry points share one process. Recheck and write must use the same lock. */
object DriveWriteGuard {
    private val mutex = Mutex()

    suspend fun <T> locked(block: suspend () -> T): T = mutex.withLock { block() }

}
