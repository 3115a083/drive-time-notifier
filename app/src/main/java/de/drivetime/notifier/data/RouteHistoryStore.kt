package de.drivetime.notifier.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import de.drivetime.notifier.automation.AutomationRunLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*

private val Context.routeHistoryDataStore by preferencesDataStore("route_history")

/** Preferences and entries share one atomic DataStore transaction, including disabling/deletion. */
class RouteHistoryStore internal constructor(private val store:DataStore<Preferences>,private val now:()->Long=System::currentTimeMillis) {
    constructor(context:Context):this(context.applicationContext.routeHistoryDataStore)
    private val enabledKey=booleanPreferencesKey("enabled")
    private val limitKey=intPreferencesKey("limit")
    private val entriesKey=stringPreferencesKey("entries")
    private val clearedKey=longPreferencesKey("clearedAt")

    val flow:Flow<RouteHistoryState> = store.data.map { preferences ->
        val enabled=preferences[enabledKey]?:false
        val limit=(preferences[limitKey]?:50).coerceIn(1,RouteHistoryPolicy.MAX_ENTRIES)
        try {
            RouteHistoryState(enabled,limit,RouteHistoryPolicy.trim(
                RouteHistoryCodec.decode(preferences[entriesKey]),limit,now()),ready=true)
        }catch(error:Exception) {
            RouteHistoryState(enabled,limit,error="Historie konnte nicht gelesen werden. Du kannst sie gezielt löschen.",ready=true)
        }
    }.catch { error ->
        if(error is CancellationException)throw error
        emit(RouteHistoryState(error="Historie ist zurzeit nicht verfügbar.",ready=true))
    }

    suspend fun configure(enabled:Boolean,limit:Int) {
        require(limit in 1..RouteHistoryPolicy.MAX_ENTRIES)
        store.edit { preferences ->
            val entries=if(enabled)RouteHistoryPolicy.trim(RouteHistoryCodec.decode(preferences[entriesKey]),limit,now())
                else emptyList()
            if(!enabled)preferences[clearedKey]=now()
            preferences[enabledKey]=enabled
            preferences[limitKey]=limit
            preferences[entriesKey]=RouteHistoryCodec.encode(entries)
        }
    }
    suspend fun record(build:()->RouteHistoryEntry):RouteHistoryEntry? {
        var saved:RouteHistoryEntry?=null
        store.edit { preferences ->
            if(preferences[enabledKey]!=true)return@edit
            val entry=build()
            if(entry.createdAt<(preferences[clearedKey]?:0L))return@edit
            val previous=RouteHistoryCodec.decode(preferences[entriesKey])
            val old=previous.firstOrNull {it.id==entry.id}
            val next=entry.copy(createdAt=old?.createdAt?:entry.createdAt,
                saveState=if(old?.snapshotKey()==entry.snapshotKey())old.saveState else entry.saveState)
            val entries=RouteHistoryPolicy.trim(listOf(next)+previous.filterNot {it.id==next.id},
                preferences[limitKey]?:50,now())
            preferences[entriesKey]=RouteHistoryCodec.encode(entries)
            saved=next
        }
        return saved
    }
    suspend fun mark(id:String,snapshotKey:String,state:HistorySaveState) {
        store.edit { preferences ->
            if(preferences[enabledKey]!=true)return@edit
            val entries=RouteHistoryCodec.decode(preferences[entriesKey]).map {
                if(it.id==id&&it.snapshotKey()==snapshotKey)it.copy(saveState=state) else it
            }
            preferences[entriesKey]=RouteHistoryCodec.encode(entries)
        }
    }
    suspend fun remove(id:String) {
        store.edit { preferences ->
            preferences[entriesKey]=RouteHistoryCodec.encode(
                RouteHistoryCodec.decode(preferences[entriesKey]).filterNot {it.id==id})
        }
    }
    suspend fun clear() { store.edit {it.remove(entriesKey);it[clearedKey]=now()} }
    suspend fun prune() {
        store.edit { preferences ->
            preferences[entriesKey]=RouteHistoryCodec.encode(RouteHistoryPolicy.trim(
                RouteHistoryCodec.decode(preferences[entriesKey]),preferences[limitKey]?:50,now()))
        }
    }
}

suspend fun RouteHistoryStore.recordSafely(context:Context,build:()->RouteHistoryEntry):RouteHistoryEntry? =
    try { record(build) }
    catch(cancelled:CancellationException){throw cancelled}
    catch(error:Exception){
        AutomationRunLog.add(context,"history","write-failed",error.javaClass.simpleName)
        null
    }

suspend fun RouteHistoryStore.markSafely(context:Context,entry:RouteHistoryEntry?,state:HistorySaveState) {
    if(entry==null)return
    try { mark(entry.id,entry.snapshotKey(),state) }
    catch(cancelled:CancellationException){throw cancelled}
    catch(error:Exception){AutomationRunLog.add(context,"history","status-write-failed",error.javaClass.simpleName)}
}
