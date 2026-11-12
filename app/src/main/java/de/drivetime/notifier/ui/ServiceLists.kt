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
import de.drivetime.notifier.network.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import de.drivetime.notifier.routing.*
import de.drivetime.notifier.security.SecureApiKeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.abs

/** Drag by the handle; commit one persistent order when dropped. Keys retain each card's draft. */
@Composable
fun OrderedServiceList(ids: List<String>, language: AppLanguage, title: (String) -> String,
    onOrder: (List<String>) -> Unit, badge: @Composable (String) -> Unit = {},
    status: @Composable (String) -> Unit = {}, content: @Composable (String) -> Unit) {
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text("${index+1}.", Modifier.width(28.dp).padding(top=24.dp), style=MaterialTheme.typography.labelLarge)
            Card(Modifier.weight(1f).zIndex(if (dragging == id) 1f else 0f)
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
                        badge(id)
                        Spacer(Modifier.width(8.dp))
                        Text(title(id), Modifier.weight(1f), style=MaterialTheme.typography.titleSmall, maxLines=2, overflow=TextOverflow.Ellipsis)
                        status(id)
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
        {order->onChange(settings.withRoutingOrder(order.map { id->RoutingProvider.entries.first {it.id==id} }))},
        badge={ServiceImage(it)}, status={id ->
            val revision by ServiceMonitor.changes.collectAsState()
            val provider=RoutingProvider.entries.first {it.id==id}
            val key=remember(id,revision){keyStore.read(provider).orEmpty()}
            ServiceHealth(id, ProviderConnectivityChecker.providerFingerprint(provider, settings, key), provider.keyRequired && key.isBlank(), language)
        }) { id->
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
        TextButton(onClick={keyStore.save(provider,key.text.trim()); ServiceMonitor.invalidate(provider.id)}) {Text(tr(language,"Save API key","API-Key speichern"))}
    }
    if(endpoint!=null) {
        OutlinedTextField(address.text,address::edit,label={Text(tr(language,"HTTPS endpoint","HTTPS-Endpunkt"))},
            singleLine=true,isError=HttpsEndpoint.normalize(address.text)==null,modifier=Modifier.fillMaxWidth())
        TextButton(enabled=HttpsEndpoint.normalize(address.text)!=null,onClick={
            ServiceMonitor.invalidate(provider.id)
            onChange(if(provider==RoutingProvider.OSRM) settings.copy(osrmBaseUrl=address.text) else settings.copy(valhallaBaseUrl=address.text))
        }) {Text(tr(language,"Save endpoint","Endpunkt speichern"))}
    }
    if(endpoint!=null) EndpointAuthEditor(endpoint,language)
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
    OrderedServiceList(endpoints,language,{endpoint->endpoint.toHttpUrlOrNull()?.host?:kind},onOrder,
        badge={ServiceImage(kind.lowercase())}, status={endpoint ->
            val revision by ServiceMonitor.changes.collectAsState()
            ServiceHealth(ServiceMonitor.endpointId(kind,endpoint), remember(endpoint,revision){EndpointSecurity.fingerprint(endpoint)}, false, language)
        }) { endpoint->
        val draft=rememberTextDraft(endpoint,endpoint)
        var testing by remember {mutableStateOf(false)}
        var result by remember {mutableStateOf<InterfaceCheckResult?>(null)}
        OutlinedTextField(draft.text,{draft.edit(it);result=null},label={Text(tr(language,"HTTPS endpoint","HTTPS-Endpunkt"))},singleLine=true,
            isError=HttpsEndpoint.normalize(draft.text)==null,modifier=Modifier.fillMaxWidth())
        TextButton(enabled=HttpsEndpoint.normalize(draft.text)!=null,onClick={
            val normalized=HttpsEndpoint.normalize(draft.text)!!
            ServiceMonitor.invalidate(ServiceMonitor.endpointId(kind,endpoint))
            ServiceMonitor.invalidate(ServiceMonitor.endpointId(kind,normalized))
            onOrder(endpoints.map {if(it==endpoint)normalized else it}.distinct())
        }) {Text(tr(language,"Save endpoint","Endpunkt speichern"))}
        EndpointAuthEditor(endpoint,language)
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

@Composable
private fun ServiceHealth(id:String,fingerprint:String,missingKey:Boolean,language:AppLanguage) {
    val revision by ServiceMonitor.changes.collectAsState()
    val observation=remember(id,fingerprint,revision){ServiceMonitor.read(id,fingerprint)}
    val label=when {
        missingKey -> tr(language,"API key missing","API-Key fehlt")
        observation==null -> tr(language,"Not tested","Noch nicht getestet")
        observation.valid -> tr(language,"API reachable","API erreichbar")
        else -> tr(language,"API error or unreachable","API-Fehler oder nicht erreichbar")
    }
    val icon=when {
        missingKey -> Icons.Outlined.KeyOff
        observation==null -> Icons.Outlined.HelpOutline
        observation.valid -> Icons.Outlined.CheckCircle
        else -> Icons.Outlined.Cancel
    }
    val tint=when {
        missingKey -> MaterialTheme.colorScheme.onSurfaceVariant
        observation==null -> Color(0xFFB07800)
        observation.valid -> Color(0xFF208344)
        else -> MaterialTheme.colorScheme.error
    }
    if(observation!=null) Text(if(observation.durationMillis<1000) "${observation.durationMillis} ms" else
        java.lang.String.format(java.util.Locale.ROOT,"%.1f s",observation.durationMillis/1000.0),
        Modifier.padding(horizontal=4.dp).semantics { contentDescription=tr(language,"Last response time","Letzte Antwortzeit") + ": ${observation.durationMillis} ms" },
        style=MaterialTheme.typography.labelSmall)
    Icon(icon,label,tint=tint,modifier=Modifier.size(22.dp))
}

@Composable
private fun EndpointAuthEditor(endpoint:String,language:AppLanguage) {
    val existing=remember(endpoint){EndpointSecurity.header(endpoint)}
    val name=rememberTextDraft(existing?.name?:"Authorization",endpoint+"header-name")
    val value=rememberTextDraft(existing?.value.orEmpty(),endpoint+"header-value")
    var enabled by rememberSaveable(endpoint){mutableStateOf(existing!=null)}
    var saved by remember {mutableStateOf(false)}
    Row(verticalAlignment=Alignment.CenterVertically) {
        Text(tr(language,"Header authentication","Header-Authentifizierung"),Modifier.weight(1f))
        Switch(enabled,{enabled=it;saved=false})
    }
    if(enabled) {
        OutlinedTextField(name.text,{name.edit(it);saved=false},label={Text(tr(language,"Header name","Header-Name"))},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(value.text,{value.edit(it);saved=false},label={Text(tr(language,"Header value (e.g. Bearer …)","Header-Wert (z. B. Bearer …)"))},
            singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
    }
    TextButton(enabled= !enabled || EndpointHeaderPolicy.valid(name.text,value.text),onClick={
        EndpointSecurity.save(endpoint,if(enabled)EndpointHeader(name.text,value.text) else null);saved=true
    }) {Text(tr(language,"Save authentication","Authentifizierung speichern"))}
    if(saved) Text(tr(language,"Saved. Test the endpoint again.","Gespeichert. Endpunkt erneut testen."),style=MaterialTheme.typography.bodySmall)
    Text(tr(language,"Stored encrypted on this device. Not included in backups; configure again after restoring.",
        "Auf diesem Gerät verschlüsselt gespeichert. Nicht im Backup enthalten; nach Wiederherstellung erneut einrichten."),style=MaterialTheme.typography.bodySmall)
}

@Composable
private fun ServiceImage(id:String) {
    val drawable=when(id) {
        "tomtom" -> de.drivetime.notifier.R.drawable.service_tomtom
        "valhalla" -> de.drivetime.notifier.R.drawable.service_valhalla
        "openrouteservice" -> de.drivetime.notifier.R.drawable.service_openrouteservice
        "osrm" -> de.drivetime.notifier.R.drawable.service_osrm
        "graphhopper" -> de.drivetime.notifier.R.drawable.service_graphhopper
        "google" -> de.drivetime.notifier.R.drawable.service_google
        "here" -> de.drivetime.notifier.R.drawable.service_here
        "photon" -> de.drivetime.notifier.R.drawable.service_photon
        else -> de.drivetime.notifier.R.drawable.service_overpass
    }
    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(drawable),null,Modifier.size(28.dp))
}
