package de.drivetime.notifier.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import de.drivetime.notifier.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class RouteHistoryStoreTest {
    private fun entry(id:String,time:Long)=RouteHistoryEntry(id,time,time,HistoryTrigger.MANUAL,
        RouteRequest("Start","Ziel",time+3_600_000),
        RouteEstimate(600,550,1000,"??_ibE_ibE",providerId="osrm"),time+2_000_000,time+2_600_000,15)

    private fun testStore(block:suspend(RouteHistoryStore,AtomicLong,androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>)->Unit)=runBlocking {
        val job=SupervisorJob()
        val scope=CoroutineScope(Dispatchers.IO+job)
        val folder=java.nio.file.Files.createTempDirectory("route-history-test").toFile()
        val data=PreferenceDataStoreFactory.create(scope=scope,produceFile={File(folder,"test.preferences_pb")})
        val clock=AtomicLong(System.currentTimeMillis())
        val store=RouteHistoryStore(data){clock.get()}
        try {block(store,clock,data)}
        finally {job.cancelAndJoin();folder.deleteRecursively()}
    }

    @Test fun disabledHistoryNeverRecords()=testStore {store,clock,_->
        assertNull(store.record{entry("a",clock.get())})
        assertTrue(store.flow.first().entries.isEmpty())
    }
    @Test fun concurrentResultsRespectConfiguredLimit()=testStore {store,clock,_->
        store.configure(true,7)
        coroutineScope {
            (1..40).map {index->async(Dispatchers.Default) {
                store.record{entry(index.toString(),clock.get()+index)}
            }}.awaitAll()
        }
        val state=store.flow.first()
        assertEquals(7,state.entries.size)
        assertEquals("40",state.entries.first().id)
        assertEquals("34",state.entries.last().id)
    }
    @Test fun sameRequestUpdatesOneEntryAndKeepsOrder()=testStore {store,clock,_->
        store.configure(true,50)
        val first=entry("a",clock.get())
        store.record{first}
        store.record{first.copy(route=first.route.copy(durationSeconds=700))}
        assertEquals(1,store.flow.first().entries.size)
        assertEquals(700L,store.flow.first().entries.single().route.durationSeconds)
    }
    @Test fun oldSaveCannotMarkAChangedResultAsSaved()=testStore {store,clock,_->
        store.configure(true,50)
        val first=entry("a",clock.get())
        store.record{first}
        store.record{first.copy(route=first.route.copy(durationSeconds=700))}
        store.mark("a",first.snapshotKey(),HistorySaveState.CALENDAR)
        assertEquals(HistorySaveState.CALCULATED,store.flow.first().entries.single().saveState)
    }
    @Test fun disablingAndReenablingDoesNotRestorePendingOldResults()=testStore {store,clock,_->
        store.configure(true,50)
        val old=entry("old",clock.get())
        store.record{old}
        clock.addAndGet(5000)
        store.configure(false,50)
        store.configure(true,50)
        assertNull(store.record{old})
        assertTrue(store.flow.first().entries.isEmpty())
    }
    @Test fun deletingAllRejectsLateUpdatesFromAnOldSearch()=testStore {store,clock,_->
        store.configure(true,50)
        val old=entry("old",clock.get())
        clock.addAndGet(5000)
        store.clear()
        assertNull(store.record{old})
        assertNotNull(store.record{entry("new",clock.incrementAndGet())})
    }
    @Test fun brokenHistoryIsVisibleAndRequiresExplicitDeletion()=testStore {store,_,data->
        store.configure(true,50)
        data.edit{it[stringPreferencesKey("entries")]="{broken"}
        assertNotNull(store.flow.first().error)
        store.clear()
        assertNull(store.flow.first().error)
    }
    @Test fun oldEntriesExpireAndReducingLimitIsImmediate()=testStore {store,clock,_->
        store.configure(true,50)
        store.record{entry("old",clock.get()-RouteHistoryPolicy.MAX_AGE_MILLIS-1)}
        store.record{entry("a",clock.get())}
        store.record{entry("b",clock.get()+1)}
        store.configure(true,1)
        assertEquals(listOf("b"),store.flow.first().entries.map{it.id})
    }
    @Test fun codecRoundTripRetainsProviderAndPlan() {
        val expected=entry("id",System.currentTimeMillis())
        assertEquals(listOf(expected),RouteHistoryCodec.decode(RouteHistoryCodec.encode(listOf(expected))))
    }
}
