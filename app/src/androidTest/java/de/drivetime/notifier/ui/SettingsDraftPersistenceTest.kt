package de.drivetime.notifier.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.drivetime.notifier.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsDraftPersistenceTest {
    @Test fun concurrentFieldSavesPreserveBothValuesInTheRealDataStore() = runBlocking {
        val store = SettingsStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = store.flow.first()
        val source = original.copy(bufferMinutes = 15, reminderLeadMinutes = 0)
        try {
            store.update(source)
            coroutineScope {
                listOf(
                    async { store.update(source.copy(bufferMinutes = 30), previous = source) },
                    async { store.update(source.copy(reminderLeadMinutes = 45), previous = source) }
                ).awaitAll()
            }
            val saved = store.flow.first()
            assertEquals(30, saved.bufferMinutes)
            assertEquals(45, saved.reminderLeadMinutes)
        } finally { store.update(original) }
    }

    @Test fun staleUnrelatedSettingsSavesCannotEraseANewerDefaultLocation() = runBlocking {
        val store=SettingsStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val original=store.flow.first()
        val stale=original.copy(homeAddress="",bufferMinutes=15)
        try {
            store.update(stale)
            store.update(stale.copy(homeAddress="Saved default"),previous=stale)
            store.update(stale.copy(bufferMinutes=30),previous=stale)
            val saved=store.flow.first()
            assertEquals("Saved default",saved.homeAddress)
            assertEquals(30,saved.bufferMinutes)
        } finally {store.update(original)}
    }
}
