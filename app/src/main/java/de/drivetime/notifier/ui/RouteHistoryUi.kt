package de.drivetime.notifier.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.drivetime.notifier.data.*
import de.drivetime.notifier.routing.PolylineDecoder
import kotlinx.coroutines.*
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

data class HistoryPlanningRequest(val entry:RouteHistoryEntry,val recalculate:Boolean,val nonce:String=UUID.randomUUID().toString())

private fun stamp(value:Long):String=runCatching {
    Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
}.getOrDefault("?")

@Composable
fun HistoryPreferencesCard(language:AppLanguage) {
    val context=LocalContext.current
    val store=remember {RouteHistoryStore(context)}
    val state by store.flow.collectAsState(initial=RouteHistoryState())
    val scope=rememberCoroutineScope()
    var limit by remember {mutableStateOf("50")}
    var message by remember {mutableStateOf<String?>(null)}
    var busy by remember {mutableStateOf(false)}
    var disableConfirm by remember {mutableStateOf(false)}
    LaunchedEffect(state.limit) {limit=state.limit.toString()}

    fun change(enabled:Boolean,count:Int) {
        busy=true
        scope.launch {
            try {store.configure(enabled,count);message=null}
            catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){message=tr(language,"History settings could not be saved.","Historieneinstellungen konnten nicht gespeichert werden.")}
            finally {busy=false}
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(tr(language,"Search history","Suchhistorie"),style=MaterialTheme.typography.titleMedium,
                modifier=Modifier.semantics {heading()})
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(tr(language,"Keep search history","Suchhistorie führen"),Modifier.weight(1f))
                Switch(modifier=Modifier.semantics {contentDescription=tr(language,"Keep search history","Suchhistorie führen")},checked=state.enabled,enabled=state.ready&&!busy&&state.error==null,onCheckedChange={enabled->
                    if(!enabled&&state.entries.isNotEmpty())disableConfirm=true
                    else change(enabled,state.limit)
                })
            }
            Text(tr(language,"Stored locally for up to 30 days. Turning it off deletes existing entries.",
                "Lokal für höchstens 30 Tage speichern. Ausschalten löscht vorhandene Einträge."),
                style=MaterialTheme.typography.bodySmall)
            val count=limit.toIntOrNull()
            val valid=count!=null&&count in 1..RouteHistoryPolicy.MAX_ENTRIES
            OutlinedTextField(value=limit,onValueChange={limit=it.take(4).filter(Char::isDigit)},
                label={Text(tr(language,"Maximum entries","Maximale Eintragszahl"))},
                supportingText={Text(tr(language,"1 to 500 entries","1 bis 500 Einträge"))},
                isError=!valid,singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),
                modifier=Modifier.fillMaxWidth(),enabled=state.ready&&!busy)
            OutlinedButton(enabled=state.ready&&!busy&&valid&&count!=state.limit,
                onClick={change(state.enabled,count!!)},modifier=Modifier.fillMaxWidth()) {
                Text(tr(language,"Apply limit","Eintragsgrenze übernehmen"))
            }
            state.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            message?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }
    }
    if(disableConfirm) AlertDialog(onDismissRequest={disableConfirm=false},
        title={Text(tr(language,"Turn off and delete history?","Historie ausschalten und löschen?"))},
        text={Text(tr(language,"Saved route results will be removed. Calendar entries remain available.",
            "Gespeicherte Suchergebnisse werden entfernt. Kalendereinträge bleiben erhalten."))},
        confirmButton={TextButton(onClick={change(false,state.limit);disableConfirm=false}) {
            Text(tr(language,"Turn off and delete","Ausschalten und löschen"))}},
        dismissButton={TextButton(onClick={disableConfirm=false}) {Text(tr(language,"Cancel","Abbrechen"))}})
}

@Composable
fun RouteHistoryDialog(settings:AppSettings,onDismiss:()->Unit,onSettings:()->Unit,
    onPlan:(HistoryPlanningRequest)->Unit) {
    val context=LocalContext.current
    val store=remember {RouteHistoryStore(context)}
    val state by store.flow.collectAsState(initial=RouteHistoryState())
    val scope=rememberCoroutineScope()
    var selected by remember {mutableStateOf<String?>(null)}
    var clearConfirm by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf<String?>(null)}
    val entry=state.entries.firstOrNull {it.id==selected}
    val language=settings.language
    LaunchedEffect(Unit) {try{store.prune()}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){}}
    fun remove(id:String) {
        scope.launch {
            try {store.remove(id);if(selected==id)selected=null}
            catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){message=tr(language,"Entry could not be deleted.","Eintrag konnte nicht gelöscht werden.")}
        }
    }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(modifier=Modifier.widthIn(max=760.dp).fillMaxWidth().fillMaxHeight(.94f),shape=MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(tr(language,"Search history","Suchhistorie"),style=MaterialTheme.typography.titleLarge,
                        modifier=Modifier.weight(1f).semantics {heading()})
                    IconButton(onClick=onDismiss) {
                        Icon(Icons.Outlined.Close,tr(language,"Close history","Historie schließen"))
                    }
                }
                if(entry==null) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        TextButton(onClick=onSettings) {Text(tr(language,"History settings","Historieneinstellungen"))}
                        TextButton(enabled=state.entries.isNotEmpty()||state.error!=null,onClick={clearConfirm=true}) {
                            Text(tr(language,"Delete all","Alle löschen"))
                        }
                    }
                    if(!state.ready)LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                    if(state.ready&&state.entries.isEmpty()&&state.error==null) {
                        Text(if(state.enabled)tr(language,"No saved searches yet.","Noch keine gespeicherten Suchen.")
                            else tr(language,"History is turned off.","Die Historie ist ausgeschaltet."))
                    }
                    LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        items(state.entries,key={it.id}) {item->
                            Card(Modifier.fillMaxWidth().clickable {selected=item.id}) {
                                Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(item.request.origin+" → "+item.request.destination,
                                            style=MaterialTheme.typography.titleSmall)
                                        Text(stamp(item.calculatedAt)+" · "+((item.route.durationSeconds+59)/60)+" min")
                                        Text(if(item.trigger==HistoryTrigger.MANUAL)tr(language,"Manual","Manuell")
                                            else tr(language,"App action / requested processing","App-Aktion / angeforderte Verarbeitung"),
                                            style=MaterialTheme.typography.bodySmall)
                                    }
                                    IconButton(onClick={remove(item.id)}) {
                                        Icon(Icons.Outlined.Delete,tr(language,"Delete search to ","Suche löschen nach ")+item.request.destination)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        item {
                            TextButton(onClick={selected=null}) {Text(tr(language,"Back to history","Zurück zur Historie"))}
                            SelectionContainer {Text(entry.request.origin+" → "+entry.request.destination,
                                style=MaterialTheme.typography.titleMedium)}
                            Text(tr(language,"Calculated: ","Berechnet: ")+stamp(entry.calculatedAt))
                            Text(tr(language,"Saved result. Traffic conditions may have changed.",
                                "Gespeichertes Ergebnis. Die Verkehrslage kann sich inzwischen geändert haben."),
                                style=MaterialTheme.typography.bodySmall)
                        }
                        item {OfflineHistoryMap(entry,language)}
                        item {
                            Text(tr(language,"Appointment: ","Termin: ")+stamp(entry.request.arrivalMillis))
                            Text(tr(language,"Departure: ","Abfahrt: ")+stamp(entry.departureMillis))
                            Text(tr(language,"Arrival: ","Ankunft: ")+stamp(entry.arrivalMillis))
                            Text(tr(language,"Duration: ","Fahrtdauer: ")+((entry.route.durationSeconds+59)/60)+" min")
                            Text(tr(language,"Distance: ","Entfernung: ")+"%.1f km".format(entry.route.distanceMeters/1000.0))
                            Text(tr(language,"Applied buffer: ","Enthaltener Puffer: ")+entry.bufferMinutes+" min")
                            val provider=RoutingProvider.entries.firstOrNull {it.id==entry.route.providerId}?.displayName
                                ?: tr(language,"Unknown","Unbekannt")
                            Text(tr(language,"Routing provider: ","Routinganbieter: ")+provider)
                            Text(when(entry.saveState) {
                                HistorySaveState.CALCULATED -> tr(language,"Calculated; no confirmed calendar output","Berechnet; keine bestätigte Kalenderausgabe")
                                HistorySaveState.CALENDAR -> tr(language,"Saved to calendar","Im Kalender gespeichert")
                                HistorySaveState.ICS -> tr(language,"ICS file created","ICS-Datei erstellt")
                                HistorySaveState.FAILED -> tr(language,"Output failed; calculated result preserved","Ausgabe fehlgeschlagen; Ergebnis erhalten")
                            })
                            entry.route.warning?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
                        }
                    }
                    Button(onClick={onPlan(HistoryPlanningRequest(entry,true))},modifier=Modifier.fillMaxWidth()) {
                        Text(tr(language,"Recalculate","Neu berechnen"))
                    }
                    OutlinedButton(onClick={onPlan(HistoryPlanningRequest(entry,false))},modifier=Modifier.fillMaxWidth()) {
                        Text(tr(language,"Use as template","Als Vorlage verwenden"))
                    }
                }
                message?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            }
        }
    }
    if(clearConfirm) AlertDialog(onDismissRequest={clearConfirm=false},
        title={Text(tr(language,"Delete history?","Historie löschen?"))},
        text={Text(tr(language,"Remove saved search results? Calendar events stay available.",
            "Gespeicherte Suchergebnisse entfernen? Kalendereinträge bleiben erhalten."))},
        confirmButton={TextButton(onClick={
            scope.launch {
                try{store.clear();message=null;selected=null}
                catch(cancelled:CancellationException){throw cancelled}
                catch(_:Exception){message=tr(language,"History could not be deleted.","Historie konnte nicht gelöscht werden.")}
            };clearConfirm=false
        }){Text(tr(language,"Delete","Löschen"))}},
        dismissButton={TextButton(onClick={clearConfirm=false}){Text(tr(language,"Cancel","Abbrechen"))}})
}

@Composable
private fun OfflineHistoryMap(entry:RouteHistoryEntry,language:AppLanguage) {
    val context=LocalContext.current
    val points=remember(entry.route.encodedPolyline){
        runCatching{PolylineDecoder.decode(entry.route.encodedPolyline)}.getOrDefault(emptyList())
    }
    if(points.size<2) {
        Text(tr(language,"No saved route geometry available.","Kein gespeicherter Streckenverlauf verfügbar."))
        return
    }
    val map=remember(entry.id,entry.route.encodedPolyline){
        MapView(context).apply{
            setTileSource(TileSourceFactory.MAPNIK)
            setUseDataConnection(false)
            setMultiTouchControls(true)
            overlays.add(Polyline().apply{setPoints(points);outlinePaint.strokeWidth=8f})
            listOf(points.first() to entry.request.origin,points.last() to entry.request.destination).forEach{(point,label)->
                overlays.add(Marker(this).apply{
                    position=point;title=label;setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_BOTTOM)
                })
            }
            post {runCatching{zoomToBoundingBox(org.osmdroid.util.BoundingBox.fromGeoPoints(points),false,40)}}
        }
    }
    DisposableEffect(map){map.onResume();onDispose{map.onPause();map.onDetach()}}
    AndroidView(factory={map},modifier=Modifier.fillMaxWidth().height(220.dp).semantics{
        contentDescription=tr(language,"Saved route, offline map","Gespeicherte Route, Offline-Karte")
    })
    Text(tr(language,"Offline: saved route and cached map tiles only.",
        "Offline: gespeicherter Verlauf und bereits vorhandene Kartenkacheln."),
        style=MaterialTheme.typography.bodySmall)
}
