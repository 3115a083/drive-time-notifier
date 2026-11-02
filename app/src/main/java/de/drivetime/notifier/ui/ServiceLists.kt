package de.drivetime.notifier.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import de.drivetime.notifier.data.*
import de.drivetime.notifier.debug.RequestDebugLog
import de.drivetime.notifier.network.HttpsEndpoint
import de.drivetime.notifier.routing.*
import de.drivetime.notifier.security.SecureApiKeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.abs

/** Drag by the handle; commit one persistent order when dropped. Keys retain each card's draft. */
@Composable
fun OrderedServiceList(ids: List<String>, language: AppLanguage, title: (String) -> String,
    onOrder: (List<String>) -> Unit, content: @Composable (String) -> Unit) {
    val bounds = remember { mutableMapOf<String, Rect>() }
    val drafts = rememberSaveableStateHolder()
    var dragging by remember { mutableStateOf<String?>(null) }
    var dy by remember { mutableFloatStateOf(0f) }
    var startY by remember { mutableFloatStateOf(0f) }
    val latestIds by rememberUpdatedState(ids)
    val applyOrder by rememberUpdatedState(onOrder)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ids.forEach { id -> key(id) {
            var expanded by rememberSaveable { mutableStateOf(false) }
            val index = ids.indexOf(id)
            fun move(to: Int) { applyOrder(moveService(latestIds, latestIds.indexOf(id), to)) }
            Card(Modifier.fillMaxWidth().zIndex(if (dragging == id) 1f else 0f)
                .graphicsLayer { if (dragging == id) { translationY = dy; alpha = .9f } }) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth().onGloballyPositioned { if (dragging != id) bounds[id] = it.boundsInRoot() },
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.DragHandle,
                            tr(language, "Move ", "Verschieben: ") + title(id),
                            modifier = Modifier.size(40.dp).semantics {
                                customActions = listOf(
                                    CustomAccessibilityAction(tr(language,"Move up","Nach oben")) { if(index>0) move(index-1); index>0 },
                                    CustomAccessibilityAction(tr(language,"Move down","Nach unten")) { if(index<ids.lastIndex) move(index+1); index<ids.lastIndex }
                                )
                            }.pointerInput(id, ids) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { dragging = id; dy = 0f; startY = bounds[id]?.center?.y ?: 0f },
                                    onDragCancel = { dragging = null; dy = 0f },
                                    onDragEnd = {
                                        val target = latestIds.minByOrNull { abs((bounds[it]?.center?.y ?: Float.MAX_VALUE) - (startY + dy)) }
                                        val order = moveService(latestIds, latestIds.indexOf(id), latestIds.indexOf(target ?: id))
                                        dragging = null; dy = 0f
                                        if(order != latestIds) applyOrder(order)
                                    },
                                    onDrag = { change, amount -> change.consume(); dy += amount.y }
                                )
                            })
                        Text("${index+1}. ${title(id)}", Modifier.weight(1f), style=MaterialTheme.typography.titleSmall)
                        IconButton(onClick = { expanded = !expanded }) {
                            Icon(if(expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                tr(language, "Settings for ", "Einstellungen für ") + title(id))
                        }
                    }
                    if(expanded) {
                        drafts.SaveableStateProvider(id) {
                            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) { content(id) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.End) {
                            IconButton(enabled=index>0,onClick={move(index-1)}) { Icon(Icons.Outlined.ArrowUpward,tr(language,"Move up","Nach oben")) }
                            IconButton(enabled=index<ids.lastIndex,onClick={move(index+1)}) { Icon(Icons.Outlined.ArrowDownward,tr(language,"Move down","Nach unten")) }
                            IconButton(enabled=ids.size>1,onClick={drafts.removeState(id);applyOrder(latestIds.filterNot { it==id })}) { Icon(Icons.Outlined.DeleteOutline,tr(language,"Remove service","Dienst entfernen")) }
                        }
                    }
                }
            }
        } }
    }
}

@Composable
fun RoutingServiceList(settings: AppSettings, keyStore: SecureApiKeyStore, onChange: (AppSettings)->Unit) {
    val language=settings.language
    val providers=settings.routingOrder()
    var adding by remember { mutableStateOf(false) }
    Text(tr(language,"Drag a handle to change the query order. The first available service supplies the route.",
        "Griff gedrückt halten und ziehen, um die Abfragereihenfolge zu ändern. Der erste verfügbare Dienst liefert die Route."),style=MaterialTheme.typography.bodySmall)
    OrderedServiceList(providers.map { it.id },language,{ id-> RoutingProvider.entries.first {it.id==id}.displayName },
        {order->onChange(settings.withRoutingOrder(order.map { id->RoutingProvider.entries.first {it.id==id} }))}) { id->
        val provider=RoutingProvider.entries.first {it.id==id}
        RoutingServiceEditor(provider,settings,keyStore,onChange)
    }
    OutlinedButton(onClick={adding=true},enabled=providers.size<RoutingProvider.entries.size,modifier=Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.Add,null); Spacer(Modifier.width(8.dp));Text(tr(language,"Add routing service","Routingdienst hinzufügen"))
    }
    if(adding) AlertDialog(onDismissRequest={adding=false},title={Text(tr(language,"Add routing service","Routingdienst hinzufügen"))},
        text={Column { RoutingProvider.entries.filterNot {it in providers}.forEach { provider->
            TextButton(onClick={onChange(settings.withRoutingOrder(providers+provider));adding=false}) {
                Text(provider.displayName + if(provider.keyRequired) tr(language," · API key required"," · API-Key erforderlich") else tr(language," · no key"," · ohne Schlüssel"))
            }
        } }},confirmButton={},dismissButton={TextButton(onClick={adding=false}) {Text(tr(language,"Cancel","Abbrechen"))}})
}

@Composable
private fun RoutingServiceEditor(provider: RoutingProvider,settings: AppSettings,keyStore: SecureApiKeyStore,onChange:(AppSettings)->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val language=settings.language
    val key=rememberTextDraft(remember(provider){keyStore.read(provider).orEmpty()},provider.id)
    val endpoint=when(provider){RoutingProvider.OSRM->settings.osrmBaseUrl;RoutingProvider.VALHALLA->settings.valhallaBaseUrl;else->null}
    val address=rememberTextDraft(endpoint.orEmpty(),provider.id)
    var testing by remember {mutableStateOf(false)}
    var result by remember {mutableStateOf<InterfaceCheckResult?>(null)}
    if(provider.keyRequired) {
        OutlinedTextField(key.text,{key.edit(it);result=null},label={Text("API key")},singleLine=true,
            visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
        TextButton(onClick={keyStore.save(provider,key.text.trim())}) {Text(tr(language,"Save API key","API-Key speichern"))}
    }
    if(endpoint!=null) {
        OutlinedTextField(address.text,address::edit,label={Text(tr(language,"HTTPS endpoint","HTTPS-Endpunkt"))},
            singleLine=true,isError=HttpsEndpoint.normalize(address.text)==null,modifier=Modifier.fillMaxWidth())
        TextButton(enabled=HttpsEndpoint.normalize(address.text)!=null,onClick={
            onChange(if(provider==RoutingProvider.OSRM) settings.copy(osrmBaseUrl=address.text) else settings.copy(valhallaBaseUrl=address.text))
        }) {Text(tr(language,"Save endpoint","Endpunkt speichern"))}
    }
    Button(enabled=!testing&&(!provider.keyRequired||key.text.isNotBlank())&&(endpoint==null||HttpsEndpoint.normalize(address.text)!=null),onClick={
        testing=true
        scope.launch {
            try {
                val draft=when(provider){RoutingProvider.OSRM->settings.copy(osrmBaseUrl=address.text);RoutingProvider.VALHALLA->settings.copy(valhallaBaseUrl=address.text);else->settings}
                result=ProviderConnectivityChecker(context,draft,keyStore).checkProvider(provider,key.text)
            } catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){result=InterfaceCheckResult(InterfaceCheckState.INVALID,RequestDebugLog.redact(error.message.orEmpty()))}
            finally {testing=false}
        }
    },modifier=Modifier.fillMaxWidth()) {Text(tr(language,if(testing)"Testing…" else "Test this service",if(testing)"Wird geprüft…" else "Diesen Dienst testen"))}
    result?.let {Text(RequestDebugLog.redact(it.message),color=if(it.state==InterfaceCheckState.INVALID)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)}
    Text(tr(language,if(provider.trafficAware)"Traffic-aware routing" else "Routing without live traffic",if(provider.trafficAware)"Routing mit Verkehrsdaten" else "Routing ohne Live-Verkehr"),style=MaterialTheme.typography.bodySmall)
    NumberDraftField(settings.providerCaps.forProvider(provider),tr(language,"Daily request limit","Tägliches Anfrage-Limit"),1,1_000_000,
        onValid={onChange(settings.copy(providerCaps=settings.providerCaps.withProvider(provider,it)))})
    NumberDraftField(settings.providerTimeoutSeconds.forProvider(provider),tr(language,"Timeout in seconds","Timeout in Sekunden"),1,300,
        onValid={onChange(settings.copy(providerTimeoutSeconds=settings.providerTimeoutSeconds.withProvider(provider,it)))})
}

@Composable
fun MapServiceLists(settings:AppSettings,onChange:(AppSettings)->Unit) {
    val language=settings.language
    EndpointServiceList("Photon",settings.photonOrder(),settings,{order->onChange(settings.copy(photonBaseUrl=order.first(),photonEndpoints=order))})
    Spacer(Modifier.height(12.dp))
    EndpointServiceList("Overpass",settings.overpassEndpoints,settings,{order->onChange(settings.copy(overpassBaseUrl=order.first(),overpassEndpoints=order))})
    Row(verticalAlignment=Alignment.CenterVertically) {
        Text(tr(language,"Distribute parking and charging queries","Parkplatz- und Ladeabfragen verteilen"),Modifier.weight(1f))
        Switch(checked=settings.overpassSplitRequests,onCheckedChange={onChange(settings.copy(overpassSplitRequests=it))})
    }
}

@Composable
private fun EndpointServiceList(kind:String,endpoints:List<String>,settings:AppSettings,onOrder:(List<String>)->Unit) {
    val context=LocalContext.current
    val language=settings.language
    val scope=rememberCoroutineScope()
    var adding by remember {mutableStateOf(false)}
    val newEndpoint=rememberTextDraft("")
    val defaults=if(kind=="Photon")listOf("https://photon.komoot.io") else listOf(OsmEnrichmentClient.DEFAULT_OVERPASS_ENDPOINT,
        "https://overpass.private.coffee/api/interpreter","https://maps.mail.ru/osm/tools/overpass/api/interpreter")
    Text(kind,style=MaterialTheme.typography.titleMedium)
    OrderedServiceList(endpoints,language,{endpoint->endpoint.toHttpUrlOrNull()?.host?:kind},onOrder) { endpoint->
        val draft=rememberTextDraft(endpoint,endpoint)
        var testing by remember {mutableStateOf(false)}
        var result by remember {mutableStateOf<InterfaceCheckResult?>(null)}
        OutlinedTextField(draft.text,{draft.edit(it);result=null},label={Text(tr(language,"HTTPS endpoint","HTTPS-Endpunkt"))},singleLine=true,
            isError=HttpsEndpoint.normalize(draft.text)==null,modifier=Modifier.fillMaxWidth())
        TextButton(enabled=HttpsEndpoint.normalize(draft.text)!=null,onClick={
            val normalized=HttpsEndpoint.normalize(draft.text)!!
            onOrder(endpoints.map {if(it==endpoint)normalized else it}.distinct())
        }) {Text(tr(language,"Save endpoint","Endpunkt speichern"))}
        Button(enabled=!testing&&HttpsEndpoint.normalize(draft.text)!=null,onClick={
            testing=true
            scope.launch {
                try {
                    val tested=if(kind=="Photon")settings.copy(photonBaseUrl=draft.text) else settings.copy(overpassBaseUrl=draft.text,overpassEndpoints=listOf(draft.text))
                    val checker=ProviderConnectivityChecker(context,tested)
                    result=if(kind=="Photon")checker.checkPhoton() else checker.checkOverpass()
                } catch(cancelled:CancellationException){throw cancelled}
                catch(error:Exception){result=InterfaceCheckResult(InterfaceCheckState.INVALID,RequestDebugLog.redact(error.message.orEmpty()))}
                finally {testing=false}
            }
        },modifier=Modifier.fillMaxWidth()) {Text(tr(language,if(testing)"Testing…" else "Test this endpoint",if(testing)"Wird geprüft…" else "Diesen Endpunkt testen"))}
        result?.let {Text(RequestDebugLog.redact(it.message),color=if(it.state==InterfaceCheckState.INVALID)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)}
    }
    OutlinedButton(enabled=endpoints.size<8,onClick={newEndpoint.reset("");adding=true},modifier=Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(8.dp));Text(tr(language,"Add $kind endpoint","$kind-Endpunkt hinzufügen"))
    }
    if(adding) AlertDialog(onDismissRequest={adding=false},title={Text(tr(language,"Add $kind endpoint","$kind-Endpunkt hinzufügen"))},
        text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            defaults.filterNot {it in endpoints}.forEach { endpoint->TextButton(onClick={onOrder(endpoints+endpoint);adding=false}) {Text(endpoint.toHttpUrlOrNull()?.host.orEmpty())} }
            OutlinedTextField(newEndpoint.text,newEndpoint::edit,label={Text(tr(language,"Custom HTTPS endpoint","Eigener HTTPS-Endpunkt"))},singleLine=true)
        }},confirmButton={TextButton(enabled=HttpsEndpoint.normalize(newEndpoint.text)!=null,onClick={
            onOrder((endpoints+HttpsEndpoint.normalize(newEndpoint.text)!!).distinct());adding=false
        }) {Text(tr(language,"Add","Hinzufügen"))}},dismissButton={TextButton(onClick={adding=false}) {Text(tr(language,"Cancel","Abbrechen"))}})
}
