package de.drivetime.notifier.calendar

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DriveWriteGuardTest {
    @Test fun simultaneousManualAndScheduledWritesCreateOneEntry() = runBlocking {
        var entry: Long? = null
        var inserts = 0
        val ids = (1..40).map {
            async(Dispatchers.Default) {
                DriveWriteGuard.locked {
                    delay(1)
                    entry ?: run { delay(1); inserts++; entry = 42L; 42L }
                }
            }
        }.awaitAll()
        assertEquals(1, inserts)
        assertTrue(ids.all { it == 42L })
    }

    @Test fun failedChecksPreventWritesAndReleaseTheLock() = runBlocking {
        var inserts = 0
        suspend fun lookup(): Long? { error("Calendar access denied") }
        try {
            DriveWriteGuard.locked<Long> {
                lookup() ?: run { inserts++; 1L }
            }
            fail("The calendar check must fail closed")
        } catch (_: IllegalStateException) { }
        assertEquals(0, inserts)
        assertEquals(2L, DriveWriteGuard.locked { 2L })
    }
}
