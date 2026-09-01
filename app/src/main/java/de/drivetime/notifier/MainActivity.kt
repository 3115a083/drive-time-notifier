package de.drivetime.notifier

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import de.drivetime.notifier.automation.AutomationReceiver
import de.drivetime.notifier.automation.AutomationScheduler
import de.drivetime.notifier.calendar.CalendarInfo
import de.drivetime.notifier.calendar.CalendarRepository
import de.drivetime.notifier.core.DrivePlanner
import de.drivetime.notifier.data.AppSettings
import de.drivetime.notifier.data.SettingsStore
import de.drivetime.notifier.export.IcsExporter
import de.drivetime.notifier.model.CalendarEventRef
import de.drivetime.notifier.model.RouteEstimate
import de.drivetime.notifier.model.RouteRequest
import de.drivetime.notifier.routing.*
import de.drivetime.notifier.security.AutomationTokenStore
import de.drivetime.notifier.security.SecureApiKeyStore
import de.drivetime.notifier.ui.DriveTimeTheme
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        if (intent.action == AutomationReceiver.ACTION_PROCESS_NEXT_DAY) AutomationScheduler.runNow(this)
        setContent { DriveTimeTheme { DriveTimeScreen(intent) } }
    }

    @Composable
    private fun DriveTimeScreen(initialIntent: Intent) {
        val context = LocalContext.current
        val settingsStore = remember { SettingsStore(context) }
        val calendarRepo = remember { CalendarRepository(context) }
        val keyStore = remember { SecureApiKeyStore(context) }
        val settings by settingsStore.flow.collectAsState(initial = AppSettings())
        var showSettings by remember { mutableStateOf(false) }
        var origin by remember { mutableStateOf(initialIntent.getStringExtra("origin").orEmpty()) }
        var destination by remember { mutableStateOf(initialIntent.getStringExtra("destination").orEmpty()) }
        var dateTime by remember {
            mutableStateOf(initialIntent.getStringExtra("datetime")
                ?: LocalDateTime.now().plusHours(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
        }
        var previousEndMillis by remember { mutableStateOf<Long?>(initialIntent.getLongExtra("previous_end_millis", -1L).takeIf { it > 0 }) }
        var estimate by remember { mutableStateOf<RouteEstimate?>(null) }
        var pois by remember { mutableStateOf<List<RoutePoi>>(emptyList()) }
        var planWarning by remember { mutableStateOf<String?>(null) }
        var plannedStart by remember { mutableStateOf<Long?>(null) }
        var plannedEnd by remember { mutableStateOf<Long?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var loading by remember { mutableStateOf(false) }
        var events by remember { mutableStateOf<List<CalendarEventRef>>(emptyList()) }
        var showEvents by remember { mutableStateOf(false) }
        var pickingStart by remember { mutableStateOf(false) }

        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

        LaunchedEffect(settings.homeAddress) {
            if (origin.isBlank() && settings.homeAddress.isNotBlank()) origin = settings.homeAddress
        }

        fun hasCalendarPermission() =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

        fun openEventPicker(forStart: Boolean) {
            if (!hasCalendarPermission()) {
                permissionLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                return
            }
            pickingStart = forStart
            lifecycleScope.launch {
                val now = System.currentTimeMillis()
                events = calendarRepo.events(now - 24L * 60 * 60 * 1000, now + 14L * 24 * 60 * 60 * 1000)
                showEvents = true
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Drive Time Notifier") },
                    actions = {
                        IconButton(onClick = { showSettings = !showSettings }) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Einstellungen")
                        }
                    }
                )
            }
        ) { padding ->
            if (showSettings) {
                SettingsPane(
                    Modifier.padding(padding),
                    settings,
                    keyStore,
                    calendarRepo,
                    onSave = {
                        lifecycleScope.launch {
                            settingsStore.update(it)
                            AutomationScheduler.configure(context, it.automaticEnabled, it.autoHour)
                        }
                        showSettings = false
                    },
                    onRequestPermissions = {
                        permissionLauncher.launch(arrayOf(
                            Manifest.permission.READ_CALENDAR,
                            Manifest.permission.WRITE_CALENDAR,
                            Manifest.permission.POST_NOTIFICATIONS
                        ))
                    }
                )
            } else {
                Column(
                    Modifier.padding(padding).padding(horizontal = 18.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    HeroCard(estimate, loading)

                    Card {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Fahrt planen", style = MaterialTheme.typography.titleLarge)
                            OutlinedTextField(
                                value = origin, onValueChange = { origin = it },
                                modifier = Modifier.fillMaxWidth(), label = { Text("Start") },
                                leadingIcon = { Icon(Icons.Outlined.MyLocation, null) }, singleLine = true
                            )
                            if (settings.savedPlaces.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Gespeicherte Startpunkte", style = MaterialTheme.typography.labelLarge)
                                    settings.savedPlaces.sorted().forEach { raw ->
                                        val parts = raw.split("|", limit = 2)
                                        val name = parts.firstOrNull()?.trim().orEmpty()
                                        val address = parts.getOrNull(1)?.trim().orEmpty()
                                        if (address.isNotBlank()) {
                                            AssistChip(
                                                onClick = { origin = address; previousEndMillis = null },
                                                label = { Text(name.ifBlank { address }) },
                                                leadingIcon = { Icon(Icons.Outlined.HomeWork, null) }
                                            )
                                        }
                                    }
                                }
                            }
                            FilledTonalButton(onClick = { openEventPicker(true) }) {
                                Icon(Icons.Outlined.EventRepeat, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Start aus Kalenderevent")
                            }
                            OutlinedTextField(
                                value = destination, onValueChange = { destination = it },
                                modifier = Modifier.fillMaxWidth(), label = { Text("Ziel") },
                                leadingIcon = { Icon(Icons.Outlined.Place, null) }, singleLine = true
                            )
                            OutlinedTextField(
                                value = dateTime, onValueChange = { dateTime = it },
                                modifier = Modifier.fillMaxWidth(), label = { Text("Terminzeit, yyyy-MM-dd HH:mm") },
                                leadingIcon = { Icon(Icons.Outlined.Schedule, null) }, singleLine = true
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FilledTonalButton(onClick = { openEventPicker(false) }) {
                                    Icon(Icons.Outlined.Event, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Zieltermin")
                                }
                                Button(
                                    onClick = {
                                        error = null
                                        loading = true
                                        lifecycleScope.launch {
                                            runCatching {
                                                val target = LocalDateTime.parse(dateTime, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                                                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                                val route = RoutingServiceFactory.create(context, settings).route(
                                                    RouteRequest(origin.trim(), destination.trim(), target)
                                                )
                                                val p = DrivePlanner.plan(target, route.durationSeconds, settings.bufferMinutes, previousEndMillis)
                                                val points = PolylineDecoder.decode(route.encodedPolyline)
                                                pois = OsmEnrichmentClient().query(points, settings.showSpeedCameras, settings.showParking)
                                                estimate = route
                                                plannedStart = p.departureMillis
                                                plannedEnd = p.arrivalMillis
                                                planWarning = listOfNotNull(p.warning, route.warning).joinToString(" ") .ifBlank { null }
                                            }.onFailure { error = it.message ?: "Berechnung fehlgeschlagen." }
                                            loading = false
                                        }
                                    },
                                    enabled = origin.isNotBlank() && destination.isNotBlank() && !loading
                                ) {
                                    Icon(Icons.Outlined.Route, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Berechnen")
                                }
                            }
                        }
                    }

                    estimate?.let { route ->
                        RouteMap(route, pois)
                        SummaryCard(route, pois, planWarning)
                        Button(
                            onClick = {
                                val start = plannedStart ?: return@Button
                                val end = plannedEnd ?: return@Button
                                lifecycleScope.launch {
                                    runCatching {
                                        if (settings.outputIcs) {
                                            val uri = IcsExporter(context).create(origin, destination, start, end)
                                            context.startActivity(Intent.createChooser(
                                                Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/calendar"
                                                    putExtra(Intent.EXTRA_STREAM, uri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }, "ICS exportieren"
                                            ))
                                        } else {
                                            calendarRepo.insertDrive(
                                                settings.targetCalendarId, origin, destination, start, end, settings.reminderLeadMinutes
                                            )
                                        }
                                    }.onFailure { error = it.message }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(if (settings.outputIcs) Icons.Outlined.Download else Icons.Outlined.EventAvailable, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (settings.outputIcs) "ICS exportieren" else "Fahrt im Kalender speichern")
                        }
                    }

                    planWarning?.let {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                            Text(it, Modifier.padding(16.dp))
                        }
                    }
                    error?.let {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        if (showEvents) {
            AlertDialog(
                onDismissRequest = { showEvents = false },
                confirmButton = {},
                title = { Text(if (pickingStart) "Starttermin wählen" else "Zieltermin wählen") },
                text = {
                    Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                        events.forEach { event ->
                            TextButton(
                                onClick = {
                                    if (pickingStart) {
                                        origin = event.location
                                        previousEndMillis = event.endMillis
                                    } else {
                                        destination = event.location
                                        dateTime = LocalDateTime.ofInstant(
                                            java.time.Instant.ofEpochMilli(event.startMillis), ZoneId.systemDefault()
                                        ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                                    }
                                    showEvents = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(event.title.ifBlank { "Termin" })
                                    Text(event.location, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            )
        }
    }

    @Composable
    private fun HeroCard(estimate: RouteEstimate?, loading: Boolean) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Fahrtstatus", style = MaterialTheme.typography.labelLarge)
                Text(
                    when {
                        loading -> "Route wird berechnet"
                        estimate != null -> "Plan bereit"
                        else -> "Noch keine Route"
                    },
                    style = MaterialTheme.typography.headlineMedium
                )
                estimate?.let { Text("ca. ${it.durationSeconds / 60} min · ${"%.1f".format(it.distanceMeters / 1000.0)} km") }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }

    @Composable
    private fun SummaryCard(route: RouteEstimate, pois: List<RoutePoi>, warning: String?) {
        Card {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Zusammenfassung", style = MaterialTheme.typography.titleLarge)
                Text("Voraussichtliche Fahrzeit: ${route.durationSeconds / 60} Minuten")
                Text("Normale Fahrzeit: ${route.staticDurationSeconds / 60} Minuten")
                Text("Verkehrsaufschlag: ${route.trafficDelaySeconds / 60} Minuten")
                Text("Stauindikator: ${route.trafficProbabilityPercent} %")
                if (pois.any { it.kind == RoutePoi.Kind.SPEED_CAMERA }) {
                    Text("Blitzerhinweise: ${pois.count { it.kind == RoutePoi.Kind.SPEED_CAMERA }} OSM-Punkte")
                }
                if (pois.any { it.kind == RoutePoi.Kind.PARKING }) {
                    Text("Parkmöglichkeiten: ${pois.count { it.kind == RoutePoi.Kind.PARKING }} OSM-Einträge im Routenausschnitt")
                }
                warning?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
            }
        }
    }

    @Composable
    private fun RouteMap(route: RouteEstimate, pois: List<RoutePoi>) {
        val points = remember(route.encodedPolyline) { PolylineDecoder.decode(route.encodedPolyline) }
        if (points.isEmpty()) return
        Card(Modifier.fillMaxWidth()) {
            androidx.compose.ui.viewinterop.AndroidView(
                modifier = Modifier.fillMaxWidth().height(260.dp),
                factory = { ctx -> MapView(ctx).apply { setMultiTouchControls(true); controller.setZoom(12.0) } },
                update = { map ->
                    map.overlays.clear()
                    map.overlays.add(Polyline().apply { setPoints(points); outlinePaint.strokeWidth = 10f })
                    pois.forEach { poi ->
                        map.overlays.add(Marker(map).apply {
                            position = poi.point
                            title = if (poi.kind == RoutePoi.Kind.SPEED_CAMERA) "Blitzerhinweis" else "Parkplatz"
                        })
                    }
                    map.zoomToBoundingBox(org.osmdroid.util.BoundingBox.fromGeoPoints(points), true, 70)
                    map.invalidate()
                }
            )
        }
    }

    @Composable
    private fun SettingsPane(
        modifier: Modifier,
        initial: AppSettings,
        keyStore: SecureApiKeyStore,
        calendarRepo: CalendarRepository,
        onSave: (AppSettings) -> Unit,
        onRequestPermissions: () -> Unit
    ) {
        var s by remember(initial) { mutableStateOf(initial) }
        var apiKey by remember { mutableStateOf(keyStore.read().orEmpty()) }
        var calendars by remember { mutableStateOf<List<CalendarInfo>>(emptyList()) }
        var savedPlacesText by remember(initial.savedPlaces) { mutableStateOf(initial.savedPlaces.sorted().joinToString("\n")) }
        val token = remember { AutomationTokenStore(this).token() }

        LaunchedEffect(Unit) { runCatching { calendars = calendarRepo.calendars() } }

        Column(
            modifier.padding(horizontal = 18.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Einstellungen", style = MaterialTheme.typography.headlineSmall)
            SettingsCard("Start & Planung") {
                OutlinedTextField(s.homeAddress, { s = s.copy(homeAddress = it) }, label = { Text("Standard-Startort") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    savedPlacesText,
                    { savedPlacesText = it },
                    label = { Text("Weitere Startpunkte, Name | Adresse") },
                    supportingText = { Text("Eine Zeile pro Startpunkt, z. B. Büro | Musterstraße 1, Berlin") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                OutlinedTextField(s.bufferMinutes.toString(), { s = s.copy(bufferMinutes = it.toIntOrNull() ?: 0) }, label = { Text("Puffer in Minuten") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(s.reminderLeadMinutes.toString(), { s = s.copy(reminderLeadMinutes = it.toIntOrNull() ?: 0) }, label = { Text("Erinnerung vor Abfahrt in Minuten") }, modifier = Modifier.fillMaxWidth())
            }
            SettingsCard("Kalender") {
                Text("Zielkalender")
                calendars.forEach { cal ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${cal.name} · ${cal.accountName}", modifier = Modifier.weight(1f))
                        RadioButton(s.targetCalendarId == cal.id, onClick = { s = s.copy(targetCalendarId = cal.id) })
                    }
                }
                Text("Quellkalender für Automatik")
                calendars.forEach { cal ->
                    val checked = cal.id.toString() in s.sourceCalendarIds
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(cal.name, modifier = Modifier.weight(1f))
                        Checkbox(checked, onCheckedChange = {
                            s = s.copy(sourceCalendarIds = if (it) s.sourceCalendarIds + cal.id.toString() else s.sourceCalendarIds - cal.id.toString())
                        })
                    }
                }
                TextButton(onClick = onRequestPermissions) { Text("Kalenderberechtigungen anfordern") }
            }
            SettingsCard("Automatik") {
                SwitchRow("Automatisch nächsten Tag verarbeiten", s.automaticEnabled) { s = s.copy(automaticEnabled = it) }
                OutlinedTextField(s.autoHour.toString(), { s = s.copy(autoHour = it.toIntOrNull()?.coerceIn(0,23) ?: 21) }, label = { Text("Startstunde, Standard 21") }, modifier = Modifier.fillMaxWidth())
                SwitchRow("Statt Kalendereintrag ICS erzeugen", s.outputIcs) { s = s.copy(outputIcs = it) }
                SwitchRow("Blitzer anzeigen", s.showSpeedCameras) { s = s.copy(showSpeedCameras = it) }
                SwitchRow("Parkplätze anzeigen", s.showParking) { s = s.copy(showParking = it) }
            }
            SettingsCard("Routing & Sicherheit") {
                Text("Routingdienst", style = MaterialTheme.typography.labelLarge)
                de.drivetime.notifier.data.RoutingProvider.entries.forEach { provider ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(provider.label)
                            if (provider == de.drivetime.notifier.data.RoutingProvider.OSRM) {
                                Text("Open Source. Keine prognostizierten Staus im öffentlichen Standarddienst.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        RadioButton(
                            selected = s.routingProvider == provider,
                            onClick = { s = s.copy(routingProvider = provider) }
                        )
                    }
                }

                if (s.routingProvider == de.drivetime.notifier.data.RoutingProvider.GOOGLE) {
                    OutlinedTextField(
                        apiKey,
                        { apiKey = it },
                        label = { Text("Google Routes/Geocoding API-Key") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Der Schlüssel wird mit Android Keystore verschlüsselt gespeichert.", style = MaterialTheme.typography.bodySmall)
                } else {
                    OutlinedTextField(
                        s.osrmBaseUrl,
                        { s = s.copy(osrmBaseUrl = it) },
                        label = { Text("OSRM HTTPS-Endpunkt") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        s.nominatimBaseUrl,
                        { s = s.copy(nominatimBaseUrl = it) },
                        label = { Text("Nominatim HTTPS-Endpunkt") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Standardmäßig werden die öffentlichen OSRM- und Nominatim-Dienste verwendet. Eigene Instanzen können eingetragen werden.", style = MaterialTheme.typography.bodySmall)
                }

                Text("Tasker-Token", style = MaterialTheme.typography.labelLarge)
                Text(token, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = {
                keyStore.save(apiKey.trim())
                val places = savedPlacesText.lineSequence()
                    .map { it.trim() }
                    .filter { it.contains("|") && it.substringAfter("|").isNotBlank() }
                    .toSet()
                onSave(s.copy(savedPlaces = places))
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Einstellungen speichern")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    @Composable
    private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
        Card { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        } }
    }

    @Composable
    private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, Modifier.weight(1f))
            Switch(checked, onCheckedChange = onChange)
        }
    }
}
