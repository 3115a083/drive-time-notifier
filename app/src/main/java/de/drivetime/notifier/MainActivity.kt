package de.drivetime.notifier

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import de.drivetime.notifier.automation.AutomationReceiver
import de.drivetime.notifier.automation.AutomationScheduler
import de.drivetime.notifier.calendar.*
import de.drivetime.notifier.core.DrivePlanner
import de.drivetime.notifier.data.*
import de.drivetime.notifier.export.IcsExporter
import de.drivetime.notifier.model.CalendarEventRef
import de.drivetime.notifier.model.RouteEstimate
import de.drivetime.notifier.model.RouteRequest
import de.drivetime.notifier.routing.*
import de.drivetime.notifier.security.AutomationTokenStore
import de.drivetime.notifier.security.SecureApiKeyStore
import de.drivetime.notifier.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.osmdroid.config.Configuration
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.time.*
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Configuration.getInstance().userAgentValue = packageName
        if (intent.action == AutomationReceiver.ACTION_PROCESS_NEXT_DAY) AutomationScheduler.runNow(this)

        val settingsStore = SettingsStore(this)
        setContent {
            val settings by settingsStore.flow.collectAsState(initial = AppSettings())
            val dark = resolvedDarkMode(settings.appearance)
            val view = LocalView.current
            SideEffect {
                window.statusBarColor = AndroidColor.TRANSPARENT
                window.navigationBarColor = AndroidColor.TRANSPARENT
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            DriveTimeTheme(settings.appearance, settings.palette) {
                AppRoot(settingsStore, settings, intent)
            }
        }
    }

    @Composable
    private fun AppRoot(settingsStore: SettingsStore, settings: AppSettings, initialIntent: Intent) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val calendarRepo = remember { CalendarRepository(context) }
        val keyStore = remember { SecureApiKeyStore(context) }
        var showSettings by rememberSaveable { mutableStateOf(false) }
        var permissionTick by remember { mutableIntStateOf(0) }
        var lastBackPress by remember { mutableLongStateOf(0L) }

        BackHandler {
            if (showSettings) {
                showSettings = false
            } else {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackPress <= 1800L) {
                    this@MainActivity.finish()
                } else {
                    lastBackPress = now
                    Toast.makeText(
                        context,
                        tr(settings.language, "Press back again to exit", "Zum Beenden erneut Zurück drücken"),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissionTick++ }

        val hasCalendarPermission = remember(permissionTick) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
        }

        fun updateSettings(newSettings: AppSettings) {
            scope.launch {
                settingsStore.update(newSettings)
                if (newSettings.automaticEnabled != settings.automaticEnabled ||
                    newSettings.autoHour != settings.autoHour ||
                    newSettings.autoMinute != settings.autoMinute
                ) {
                    AutomationScheduler.configure(context, newSettings.automaticEnabled, newSettings.autoHour, newSettings.autoMinute)
                }
            }
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Drive Time", fontWeight = FontWeight.Bold)
                            Text(
                                if (showSettings) tr(settings.language, "Settings", "Einstellungen")
                                else tr(settings.language, "Plan your departure", "Abfahrt planen"),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSettings = !showSettings }) {
                            Icon(
                                if (showSettings) Icons.Outlined.Close else Icons.Outlined.Settings,
                                contentDescription = tr(settings.language, "Settings", "Einstellungen")
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            }
        ) { padding ->
            if (showSettings) {
                SettingsScreen(
                    modifier = Modifier.padding(padding),
                    settings = settings,
                    keyStore = keyStore,
                    calendarRepo = calendarRepo,
                    hasCalendarPermission = hasCalendarPermission,
                    onRequestCalendarPermission = {
                        permissionLauncher.launch(arrayOf(
                            Manifest.permission.READ_CALENDAR,
                            Manifest.permission.WRITE_CALENDAR,
                            Manifest.permission.POST_NOTIFICATIONS
                        ))
                    },
                    onChange = ::updateSettings
                )
            } else {
                PlannerScreen(
                    modifier = Modifier.padding(padding),
                    settings = settings,
                    keyStore = keyStore,
                    calendarRepo = calendarRepo,
                    initialIntent = initialIntent,
                    hasCalendarPermission = hasCalendarPermission,
                    onRequestCalendarPermission = {
                        permissionLauncher.launch(arrayOf(
                            Manifest.permission.READ_CALENDAR,
                            Manifest.permission.WRITE_CALENDAR
                        ))
                    }
                )
            }
        }
    }

    @Composable
    private fun PlannerScreen(
        modifier: Modifier,
        settings: AppSettings,
        keyStore: SecureApiKeyStore,
        calendarRepo: CalendarRepository,
        initialIntent: Intent,
        hasCalendarPermission: Boolean,
        onRequestCalendarPermission: () -> Unit
    ) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val defaultDateTime = remember {
            initialIntent.getStringExtra("datetime")?.let {
                runCatching { LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }.getOrNull()
            } ?: LocalDateTime.now().plusHours(1)
        }

        var origin by rememberSaveable { mutableStateOf(initialIntent.getStringExtra("origin").orEmpty()) }
        var originInitialized by rememberSaveable { mutableStateOf(origin.isNotBlank()) }
        var destination by rememberSaveable { mutableStateOf(initialIntent.getStringExtra("destination").orEmpty()) }
        var appointmentDate by remember { mutableStateOf(defaultDateTime.toLocalDate()) }
        var appointmentTime by remember { mutableStateOf(defaultDateTime.toLocalTime().withSecond(0).withNano(0)) }
        var previousEndMillis by rememberSaveable {
            mutableStateOf(initialIntent.getLongExtra("previous_end_millis", -1L).takeIf { it > 0 })
        }
        var estimate by remember { mutableStateOf<RouteEstimate?>(null) }
        var pois by remember { mutableStateOf<List<RoutePoi>>(emptyList()) }
        var planWarning by remember { mutableStateOf<String?>(null) }
        var plannedStart by remember { mutableStateOf<Long?>(null) }
        var plannedEnd by remember { mutableStateOf<Long?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var loading by remember { mutableStateOf(false) }
        var showDatePicker by remember { mutableStateOf(false) }
        var showTimePicker by remember { mutableStateOf(false) }
        var showEventPicker by remember { mutableStateOf(false) }
        var pickingStart by remember { mutableStateOf(false) }
        var events by remember { mutableStateOf<List<CalendarEventRef>>(emptyList()) }
        var calendarNames by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
        var nextDriveShortcutHandled by rememberSaveable { mutableStateOf(false) }
        var shortcutCalculatePending by remember { mutableStateOf(false) }

        LaunchedEffect(settings.homeAddress) {
            if (!originInitialized && origin.isBlank() && settings.homeAddress.isNotBlank()) {
                origin = settings.homeAddress
                originInitialized = true
            }
        }

        fun loadEvents(forStart: Boolean) {
            if (!hasCalendarPermission) {
                onRequestCalendarPermission()
                return
            }
            val selected = settings.sourceCalendarIds.mapNotNull { it.toLongOrNull() }.toSet()
            if (selected.isEmpty()) {
                error = tr(settings.language, "Select at least one source calendar in Settings first.", "Wähle zuerst mindestens einen Quellkalender in den Einstellungen.")
                return
            }
            pickingStart = forStart
            scope.launch {
                val now = System.currentTimeMillis()
                calendarNames = calendarRepo.calendars().associate { it.id to it.name }
                events = calendarRepo.events(
                    now - 24L * 60 * 60 * 1000,
                    now + 21L * 24 * 60 * 60 * 1000,
                    selected
                )
                showEventPicker = true
            }
        }

        fun calculateRoute() {
            if (origin.isBlank() || destination.isBlank() || loading) return
            error = null
            loading = true
            estimate = null
            pois = emptyList()
            scope.launch {
                val target = LocalDateTime.of(appointmentDate, appointmentTime)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val result = withTimeoutOrNull(28_000) {
                    runCatching {
                        val route = RoutingServiceFactory.create(context, settings)
                            .route(RouteRequest(origin.trim(), destination.trim(), target))
                        val plan = DrivePlanner.plan(target, route.durationSeconds, settings.bufferMinutes, previousEndMillis)
                        val points = runCatching { PolylineDecoder.decode(route.encodedPolyline) }.getOrDefault(emptyList())
                        val routePois = if ((settings.showSpeedCameras || settings.showParking) && points.size >= 2) {
                            withTimeoutOrNull(13_000) {
                                OsmEnrichmentClient().query(points, settings.showSpeedCameras, settings.showParking)
                            }.orEmpty()
                        } else emptyList()
                        Triple(route, plan, routePois)
                    }
                }
                if (result == null) {
                    error = tr(settings.language, "The routing service timed out. Try again or choose another provider.", "Der Routingdienst hat das Zeitlimit überschritten. Versuche es erneut oder wähle einen anderen Anbieter.")
                } else {
                    result.onSuccess { (route, plan, routePois) ->
                        estimate = route
                        plannedStart = plan.departureMillis
                        plannedEnd = plan.arrivalMillis
                        pois = routePois
                        planWarning = listOfNotNull(plan.warning, route.warning).joinToString(" ").ifBlank { null }
                    }.onFailure {
                        error = it.message ?: tr(settings.language, "Route calculation failed.", "Routenberechnung fehlgeschlagen.")
                    }
                }
                loading = false
            }
        }

        LaunchedEffect(initialIntent.action, hasCalendarPermission, settings.sourceCalendarIds, settings.homeAddress) {
            if (initialIntent.action != ACTION_NEXT_DRIVE || nextDriveShortcutHandled) return@LaunchedEffect
            if (!hasCalendarPermission) {
                onRequestCalendarPermission()
                return@LaunchedEffect
            }
            val selected = settings.sourceCalendarIds.mapNotNull { it.toLongOrNull() }.toSet()
            if (selected.isEmpty()) {
                error = tr(settings.language, "Select at least one source calendar in Settings first.", "Wähle zuerst mindestens einen Quellkalender in den Einstellungen.")
                nextDriveShortcutHandled = true
                return@LaunchedEffect
            }
            val now = System.currentTimeMillis()
            val next = runCatching {
                calendarRepo.events(now, now + 21L * 24 * 60 * 60 * 1000, selected)
                    .firstOrNull { it.startMillis > now }
            }.getOrNull()
            if (next == null) {
                error = tr(settings.language, "No upcoming appointment with a location was found.", "Kein kommender Termin mit Ort gefunden.")
                nextDriveShortcutHandled = true
                return@LaunchedEffect
            }
            if (origin.isBlank() && settings.homeAddress.isNotBlank()) {
                origin = settings.homeAddress
                originInitialized = true
            }
            destination = next.location
            val dt = Instant.ofEpochMilli(next.startMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()
            appointmentDate = dt.toLocalDate()
            appointmentTime = dt.toLocalTime().withSecond(0).withNano(0)
            previousEndMillis = null
            nextDriveShortcutHandled = true
            shortcutCalculatePending = true
        }

        LaunchedEffect(shortcutCalculatePending, origin, destination, appointmentDate, appointmentTime) {
            if (shortcutCalculatePending && origin.isNotBlank() && destination.isNotBlank()) {
                shortcutCalculatePending = false
                calculateRoute()
            }
        }

        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            HeroCard(settings, estimate, loading)

            AppCard {
                SectionHeader(
                    icon = Icons.Outlined.Route,
                    title = tr(settings.language, "Route", "Route"),
                    subtitle = tr(settings.language, "Choose start and appointment destination", "Start und Terminziel auswählen")
                )
                Spacer(Modifier.height(14.dp))

                AddressAutocompleteField(
                    settings = settings,
                    value = origin,
                    onValueChange = { origin = it; originInitialized = true; previousEndMillis = null },
                    label = tr(settings.language, "Start location", "Startort"),
                    leadingIcon = { Icon(Icons.Outlined.MyLocation, null) }
                )

                if (settings.homeAddress.isNotBlank() || settings.savedPlaces.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    QuickLocationChips(
                        settings = settings,
                        includeDefault = true,
                        onSelect = { address ->
                            origin = address
                            originInitialized = true
                            previousEndMillis = null
                        }
                    )
                }

                Spacer(Modifier.height(10.dp))
                FilledTonalButton(onClick = { loadEvents(true) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.EventRepeat, null)
                    Spacer(Modifier.width(8.dp))
                    Text(tr(settings.language, "Use previous calendar event as start", "Vorherigen Kalendereintrag als Start verwenden"))
                }

                Spacer(Modifier.height(14.dp))
                AddressAutocompleteField(
                    settings = settings,
                    value = destination,
                    onValueChange = { destination = it },
                    label = tr(settings.language, "Destination", "Ziel"),
                    leadingIcon = { Icon(Icons.Outlined.LocationOn, null) }
                )

                if (settings.homeAddress.isNotBlank() || settings.savedPlaces.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    QuickLocationChips(
                        settings = settings,
                        includeDefault = true,
                        onSelect = { destination = it }
                    )
                }

                Spacer(Modifier.height(10.dp))
                FilledTonalButton(onClick = { loadEvents(false) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.CalendarMonth, null)
                    Spacer(Modifier.width(8.dp))
                    Text(tr(settings.language, "Choose appointment from calendar", "Termin aus Kalender wählen"))
                }
            }

            AppCard {
                SectionHeader(
                    icon = Icons.Outlined.Schedule,
                    title = tr(settings.language, "Appointment time", "Terminzeit"),
                    subtitle = tr(settings.language, "Arrival is planned before this time", "Die Ankunft wird vor diesem Zeitpunkt geplant")
                )
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.DateRange, null)
                        Spacer(Modifier.width(8.dp))
                        Text(appointmentDate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")))
                    }
                    OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.AccessTime, null)
                        Spacer(Modifier.width(8.dp))
                        Text(appointmentTime.format(DateTimeFormatter.ofPattern("HH:mm")))
                    }
                }
            }

            Button(
                onClick = { calculateRoute() },
                enabled = origin.isNotBlank() && destination.isNotBlank() && !loading,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Outlined.Route, null)
                }
                Spacer(Modifier.width(10.dp))
                Text(tr(settings.language, "Calculate drive", "Fahrt berechnen"), fontWeight = FontWeight.SemiBold)
            }

            estimate?.let { route ->
                RouteMap(route, pois)
                SummaryCard(settings, route, pois, planWarning)
                Button(
                    onClick = {
                        val start = plannedStart ?: return@Button
                        val end = plannedEnd ?: return@Button
                        scope.launch {
                            runCatching {
                                val description = DriveEventDescriptionBuilder.build(
                                    settings.language, settings.routingProvider, origin, destination, route, pois
                                )
                                if (settings.outputIcs) {
                                    val uri = IcsExporter(context).create(origin, destination, start, end)
                                    context.startActivity(Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "text/calendar"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        },
                                        tr(settings.language, "Export ICS", "ICS exportieren")
                                    ))
                                } else {
                                    calendarRepo.insertDrive(
                                        settings.targetCalendarId,
                                        origin,
                                        destination,
                                        start,
                                        end,
                                        settings.reminderLeadMinutes,
                                        description
                                    )
                                }
                            }.onFailure { error = it.message }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(if (settings.outputIcs) Icons.Outlined.Download else Icons.Outlined.EventAvailable, null)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (settings.outputIcs) tr(settings.language, "Export ICS", "ICS exportieren")
                        else tr(settings.language, "Save drive to calendar", "Fahrt im Kalender speichern")
                    )
                }
            }

            error?.let {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(10.dp))
                        Text(it, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }

        if (showDatePicker) {
            AppDatePickerDialog(
                initialDate = appointmentDate,
                onDismiss = { showDatePicker = false },
                onConfirm = { appointmentDate = it; showDatePicker = false }
            )
        }
        if (showTimePicker) {
            AppTimePickerDialog(
                initialTime = appointmentTime,
                title = tr(settings.language, "Appointment time", "Terminzeit"),
                cancelLabel = tr(settings.language, "Cancel", "Abbrechen"),
                confirmLabel = tr(settings.language, "Apply", "Übernehmen"),
                onDismiss = { showTimePicker = false },
                onConfirm = { appointmentTime = it; showTimePicker = false }
            )
        }
        if (showEventPicker) {
            EventPickerDialog(
                settings = settings,
                events = events,
                calendarNames = calendarNames,
                pickingStart = pickingStart,
                onDismiss = { showEventPicker = false },
                onSelect = { event ->
                    if (pickingStart) {
                        origin = event.location
                        originInitialized = true
                        previousEndMillis = event.endMillis
                    } else {
                        destination = event.location
                        val dt = Instant.ofEpochMilli(event.startMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()
                        appointmentDate = dt.toLocalDate()
                        appointmentTime = dt.toLocalTime().withSecond(0).withNano(0)
                    }
                    showEventPicker = false
                }
            )
        }
    }

    @Composable
    private fun HeroCard(settings: AppSettings, estimate: RouteEstimate?, loading: Boolean) {
        val palette = paletteSpec(settings.palette)
        val heroColors = if (settings.palette == ColorPalette.MATERIAL_YOU) {
            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
        } else {
            listOf(palette.heroStart, palette.heroEnd)
        }
        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.background(Brush.linearGradient(heroColors)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.16f), shape = CircleShape) {
                        Icon(
                            Icons.Outlined.DirectionsCar,
                            null,
                            tint = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        tr(settings.language, "Smart departure planning", "Intelligente Abfahrtsplanung"),
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.88f),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                Text(
                    when {
                        loading -> tr(settings.language, "Calculating route…", "Route wird berechnet…")
                        estimate != null -> tr(settings.language, "Your drive is ready", "Deine Fahrt ist bereit")
                        else -> tr(settings.language, "Arrive on time", "Pünktlich ankommen")
                    },
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.headlineMedium
                )
                if (estimate != null) {
                    Text(
                        "${estimate.durationSeconds / 60} min  •  ${"%.1f".format(estimate.distanceMeters / 1000.0)} km  •  ${settings.routingProvider.displayName}",
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f)
                    )
                } else {
                    Text(
                        tr(settings.language, "Traffic-aware where supported, private by design.", "Verkehrsabhängig wo unterstützt, mit Datenschutz im Fokus."),
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.78f)
                    )
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = androidx.compose.ui.graphics.Color.White)
            }
        }
    }

    @Composable
    private fun SummaryCard(settings: AppSettings, route: RouteEstimate, pois: List<RoutePoi>, warning: String?) {
        AppCard {
            SectionHeader(
                Icons.Outlined.Insights,
                tr(settings.language, "Drive summary", "Fahrtübersicht"),
                settings.routingProvider.displayName
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile(tr(settings.language, "Drive", "Fahrt"), "${route.durationSeconds / 60} min", Modifier.weight(1f))
                MetricTile(tr(settings.language, "Distance", "Distanz"), "${"%.1f".format(route.distanceMeters / 1000.0)} km", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile(tr(settings.language, "Traffic delay", "Verzögerung"), "${route.trafficDelaySeconds / 60} min", Modifier.weight(1f))
                MetricTile(tr(settings.language, "Reliability", "Zuverlässigkeit"), "${settings.routingProvider.reliabilityScore}/5", Modifier.weight(1f))
            }
            if (settings.showSpeedCameras) {
                Spacer(Modifier.height(14.dp))
                Text(
                    tr(
                        settings.language,
                        "Speed cameras on the selected route: ${pois.count { it.kind == RoutePoi.Kind.SPEED_CAMERA }}. Source: OpenStreetMap highway=speed_camera via Overpass.",
                        "Blitzer auf der gewählten Strecke: ${pois.count { it.kind == RoutePoi.Kind.SPEED_CAMERA }}. Quelle: OpenStreetMap highway=speed_camera über Overpass."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (settings.showParking) {
                Text(
                    tr(
                        settings.language,
                        "Nearby parking: ${pois.count { it.kind == RoutePoi.Kind.PARKING }} results, sorted by approximate walking distance.",
                        "Nahegelegene Parkplätze: ${pois.count { it.kind == RoutePoi.Kind.PARKING }} Treffer, nach ungefährer Laufentfernung sortiert."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            warning?.let {
                Spacer(Modifier.height(12.dp))
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
    }

    @Composable
    private fun RouteMap(route: RouteEstimate, pois: List<RoutePoi>) {
        val points = remember(route.encodedPolyline) {
            runCatching { PolylineDecoder.decode(route.encodedPolyline) }.getOrDefault(emptyList())
        }
        if (points.size < 2) return

        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
            androidx.compose.ui.viewinterop.AndroidView(
                modifier = Modifier.fillMaxWidth().height(280.dp),
                factory = { ctx ->
                    MapView(ctx).apply {
                        setMultiTouchControls(true)
                        controller.setZoom(12.0)
                    }
                },
                update = { map ->
                    runCatching {
                        map.overlays.clear()
                        map.overlays.add(Polyline().apply {
                            setPoints(points)
                            outlinePaint.strokeWidth = 10f
                        })
                        pois.forEach { poi ->
                            map.overlays.add(Marker(map).apply {
                                position = poi.point
                                title = if (poi.kind == RoutePoi.Kind.SPEED_CAMERA) "Speed camera" else poi.name ?: "Parking"
                            })
                        }
                        map.invalidate()
                        map.post {
                            runCatching {
                                map.zoomToBoundingBox(
                                    org.osmdroid.util.BoundingBox.fromGeoPoints(points),
                                    true,
                                    72
                                )
                            }
                        }
                    }
                }
            )
        }
    }

    @Composable
    private fun SettingsScreen(
        modifier: Modifier,
        settings: AppSettings,
        keyStore: SecureApiKeyStore,
        calendarRepo: CalendarRepository,
        hasCalendarPermission: Boolean,
        onRequestCalendarPermission: () -> Unit,
        onChange: (AppSettings) -> Unit
    ) {
        val context = LocalContext.current
        val uriHandler = LocalUriHandler.current
        var calendars by remember { mutableStateOf<List<CalendarInfo>>(emptyList()) }
        var showTargetPicker by remember { mutableStateOf(false) }
        var showSourcePicker by remember { mutableStateOf(false) }
        var showAutomationTimePicker by remember { mutableStateOf(false) }
        var showAddPlace by remember { mutableStateOf(false) }
        var homeDraft by remember { mutableStateOf(settings.homeAddress) }
        var osrmDraft by remember { mutableStateOf(settings.osrmBaseUrl) }
        var valhallaDraft by remember { mutableStateOf(settings.valhallaBaseUrl) }
        var photonDraft by remember { mutableStateOf(settings.photonBaseUrl) }

        LaunchedEffect(hasCalendarPermission) {
            calendars = if (hasCalendarPermission) runCatching { calendarRepo.calendars() }.getOrDefault(emptyList()) else emptyList()
        }
        LaunchedEffect(homeDraft) {
            delay(500)
            if (homeDraft != settings.homeAddress) onChange(settings.copy(homeAddress = homeDraft))
        }
        LaunchedEffect(osrmDraft) {
            delay(600)
            if (osrmDraft != settings.osrmBaseUrl) onChange(settings.copy(osrmBaseUrl = osrmDraft))
        }
        LaunchedEffect(valhallaDraft) {
            delay(600)
            if (valhallaDraft != settings.valhallaBaseUrl) onChange(settings.copy(valhallaBaseUrl = valhallaDraft))
        }
        LaunchedEffect(photonDraft) {
            delay(600)
            if (photonDraft != settings.photonBaseUrl) onChange(settings.copy(photonBaseUrl = photonDraft))
        }

        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            SettingsCard(
                title = tr(settings.language, "Appearance", "Darstellung"),
                icon = Icons.Outlined.Palette
            ) {
                Text(tr(settings.language, "Language", "Sprache"), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.language == AppLanguage.ENGLISH,
                        onClick = { onChange(settings.copy(language = AppLanguage.ENGLISH)) },
                        label = { Text("English") }
                    )
                    FilterChip(
                        selected = settings.language == AppLanguage.GERMAN,
                        onClick = { onChange(settings.copy(language = AppLanguage.GERMAN)) },
                        label = { Text("Deutsch") }
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(tr(settings.language, "Theme", "Modus"), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppAppearance.entries.forEach { appearance ->
                        FilterChip(
                            selected = settings.appearance == appearance,
                            onClick = { onChange(settings.copy(appearance = appearance)) },
                            label = {
                                Text(
                                    when (appearance) {
                                        AppAppearance.SYSTEM -> tr(settings.language, "System", "System")
                                        AppAppearance.LIGHT -> tr(settings.language, "Light", "Hell")
                                        AppAppearance.DARK -> tr(settings.language, "Dark", "Dunkel")
                                    }
                                )
                            }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(tr(settings.language, "Color palette", "Farbpalette"), style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ColorPalette.entries.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { palette ->
                                PaletteChip(palette, settings.palette == palette, Modifier.weight(1f)) {
                                    onChange(settings.copy(palette = palette))
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            SettingsCard(
                title = tr(settings.language, "Locations", "Standorte"),
                icon = Icons.Outlined.HomeWork
            ) {
                AddressAutocompleteField(
                    settings = settings,
                    value = homeDraft,
                    onValueChange = { homeDraft = it },
                    label = tr(settings.language, "Default start location", "Standard-Startort"),
                    leadingIcon = { Icon(Icons.Outlined.Home, null) }
                )

                Spacer(Modifier.height(6.dp))
                Text(tr(settings.language, "Saved start locations", "Weitere Startpunkte"), style = MaterialTheme.typography.labelLarge)
                settings.savedPlaces.sorted().forEach { raw ->
                    val name = raw.substringBefore("|").trim()
                    val address = raw.substringAfter("|", "").trim()
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Place, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name.ifBlank { address }, fontWeight = FontWeight.SemiBold)
                                if (name.isNotBlank()) Text(address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { onChange(settings.copy(savedPlaces = settings.savedPlaces - raw)) }) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = tr(settings.language, "Delete", "Löschen"))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                FilledTonalButton(onClick = { showAddPlace = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(tr(settings.language, "Add start location", "Startpunkt hinzufügen"))
                }
            }

            SettingsCard(
                title = tr(settings.language, "Calendar", "Kalender"),
                icon = Icons.Outlined.CalendarMonth
            ) {
                if (!hasCalendarPermission) {
                    Text(
                        tr(settings.language, "Calendar permission is needed to select source and target calendars.", "Kalenderzugriff wird benötigt, um Quell- und Zielkalender auszuwählen."),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FilledTonalButton(onClick = onRequestCalendarPermission, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.LockOpen, null)
                        Spacer(Modifier.width(8.dp))
                        Text(tr(settings.language, "Grant calendar access", "Kalenderzugriff erlauben"))
                    }
                } else {
                    Text(tr(settings.language, "Target calendar", "Zielkalender"), style = MaterialTheme.typography.labelLarge)
                    SelectionButton(
                        text = calendars.firstOrNull { it.id == settings.targetCalendarId }?.name
                            ?: tr(settings.language, "Choose target calendar", "Zielkalender auswählen"),
                        onClick = { showTargetPicker = true }
                    )

                    Spacer(Modifier.height(10.dp))
                    Text(tr(settings.language, "Source calendars", "Quellkalender"), style = MaterialTheme.typography.labelLarge)
                    Text(
                        tr(settings.language, "Only appointments from these calendars appear in the appointment picker and automatic processing.", "Nur Termine aus diesen Kalendern erscheinen im Termin-Picker und in der Automatik."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    SelectionButton(
                        text = if (settings.sourceCalendarIds.isEmpty()) {
                            tr(settings.language, "Choose source calendars", "Quellkalender auswählen")
                        } else {
                            tr(
                                settings.language,
                                "${settings.sourceCalendarIds.size} calendar(s) selected",
                                "${settings.sourceCalendarIds.size} Kalender ausgewählt"
                            )
                        },
                        onClick = { showSourcePicker = true }
                    )
                }
            }

            SettingsCard(
                title = tr(settings.language, "Planning", "Planung"),
                icon = Icons.Outlined.Tune
            ) {
                NumberDraftField(
                    initialValue = settings.bufferMinutes,
                    label = tr(settings.language, "Arrival buffer (minutes)", "Ankunftspuffer (Minuten)"),
                    onValid = { onChange(settings.copy(bufferMinutes = it.coerceIn(0, 180))) }
                )
                NumberDraftField(
                    initialValue = settings.reminderLeadMinutes,
                    label = tr(settings.language, "Departure reminder lead (minutes)", "Benachrichtigung vor Abfahrt (Minuten)"),
                    onValid = { onChange(settings.copy(reminderLeadMinutes = it.coerceIn(0, 180))) }
                )

                SettingSwitch(
                    tr(settings.language, "Show speed cameras on selected route", "Blitzer auf der gewählten Strecke anzeigen"),
                    settings.showSpeedCameras
                ) { onChange(settings.copy(showSpeedCameras = it)) }
                Text(
                    tr(settings.language, "Source: OpenStreetMap highway=speed_camera via Overpass. Only points close to the calculated route are kept.", "Quelle: OpenStreetMap highway=speed_camera über Overpass. Es werden nur Punkte nahe der berechneten Strecke übernommen."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SettingSwitch(
                    tr(settings.language, "Find parking near destination", "Parkplätze am Ziel suchen"),
                    settings.showParking
                ) { onChange(settings.copy(showParking = it)) }
                SettingSwitch(
                    tr(settings.language, "Export ICS instead of calendar event", "ICS statt Kalendereintrag erzeugen"),
                    settings.outputIcs
                ) { onChange(settings.copy(outputIcs = it)) }
            }

            SettingsCard(
                title = tr(settings.language, "Automatic processing", "Automatische Verarbeitung"),
                icon = Icons.Outlined.AutoMode
            ) {
                SettingSwitch(
                    tr(settings.language, "Process next day automatically", "Nächsten Tag automatisch verarbeiten"),
                    settings.automaticEnabled
                ) { onChange(settings.copy(automaticEnabled = it)) }
                Text(
                    if (settings.automaticEnabled)
                        tr(settings.language, "The app schedules one daily background job.", "Die App plant einen täglichen Hintergrundjob.")
                    else
                        tr(settings.language, "Off: no periodic job and no boot/time receiver. The app only runs when opened or externally triggered.", "Aus: Kein periodischer Job und kein Boot-/Zeit-Receiver. Die App läuft nur beim Öffnen oder durch externe Trigger."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SelectionButton(
                    text = "%02d:%02d".format(settings.autoHour, settings.autoMinute),
                    onClick = { showAutomationTimePicker = true },
                    leadingIcon = Icons.Outlined.Schedule
                )
            }

            SettingsCard(
                title = tr(settings.language, "Routing provider", "Routingdienst"),
                icon = Icons.Outlined.Route
            ) {
                RoutingProvider.entries.forEach { provider ->
                    ProviderCard(
                        provider = provider,
                        selected = settings.routingProvider == provider,
                        settings = settings,
                        onSelect = { onChange(settings.copy(routingProvider = provider)) },
                        onCap = { cap -> onChange(settings.copy(providerCaps = settings.providerCaps.withProvider(provider, cap))) }
                    )
                    Spacer(Modifier.height(10.dp))
                }

                ProviderKeyAndEndpoints(
                    settings = settings,
                    keyStore = keyStore,
                    osrmDraft = osrmDraft,
                    valhallaDraft = valhallaDraft,
                    photonDraft = photonDraft,
                    onOsrmDraft = { osrmDraft = it },
                    onValhallaDraft = { valhallaDraft = it },
                    onPhotonDraft = { photonDraft = it },
                    onOpenUrl = { uriHandler.openUri(it) }
                )
            }

            SettingsCard(
                title = tr(settings.language, "Automation integrations", "Automatisierungs-Schnittstellen"),
                icon = Icons.Outlined.Key
            ) {
                Text(tr(settings.language, "Tasker / external trigger token", "Tasker / externer Trigger-Token"), style = MaterialTheme.typography.labelLarge)
                Text(AutomationTokenStore(context).token(), style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(28.dp))
        }

        if (showTargetPicker) {
            CalendarSinglePicker(
                settings, calendars, settings.targetCalendarId,
                onDismiss = { showTargetPicker = false },
                onSelect = { onChange(settings.copy(targetCalendarId = it)); showTargetPicker = false }
            )
        }
        if (showSourcePicker) {
            CalendarMultiPicker(
                settings, calendars, settings.sourceCalendarIds,
                onDismiss = { showSourcePicker = false },
                onApply = { onChange(settings.copy(sourceCalendarIds = it)); showSourcePicker = false }
            )
        }
        if (showAutomationTimePicker) {
            AppTimePickerDialog(
                LocalTime.of(settings.autoHour, settings.autoMinute),
                tr(settings.language, "Automatic processing time", "Zeit der automatischen Verarbeitung"),
                tr(settings.language, "Cancel", "Abbrechen"),
                tr(settings.language, "Apply", "Übernehmen"),
                onDismiss = { showAutomationTimePicker = false },
                onConfirm = {
                    onChange(settings.copy(autoHour = it.hour, autoMinute = it.minute))
                    showAutomationTimePicker = false
                }
            )
        }
        if (showAddPlace) {
            AddSavedPlaceDialog(
                settings,
                onDismiss = { showAddPlace = false },
                onAdd = { name, address ->
                    onChange(settings.copy(savedPlaces = settings.savedPlaces + "${name.trim()} | ${address.trim()}"))
                    showAddPlace = false
                }
            )
        }
    }

    @Composable
    private fun ProviderCard(
        provider: RoutingProvider,
        selected: Boolean,
        settings: AppSettings,
        onSelect: () -> Unit,
        onCap: (Int) -> Unit
    ) {
        val used = remember(provider) { RequestBudgetStore(this).usedToday(provider) }
        Surface(
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect)
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected, onClick = onSelect)
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(provider.displayName, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${reliabilityLabel(settings.language, provider.reliabilityScore)} • ${provider.reliabilityScore}/5",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (provider.costRisk) {
                        AssistChip(onClick = {}, label = { Text("$") })
                        Spacer(Modifier.width(4.dp))
                    }
                    if (provider.trafficAware) {
                        AssistChip(onClick = {}, label = { Text(tr(settings.language, "Traffic", "Verkehr")) })
                    }
                }
                Text(
                    providerDescription(provider, settings.language),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (selected) {
                    Spacer(Modifier.height(10.dp))
                    NumberDraftField(
                        initialValue = settings.providerCaps.forProvider(provider),
                        label = tr(settings.language, "Daily app request cap", "Tägliches Anfrage-Limit der App"),
                        onValid = { onCap(it.coerceIn(1, 100_000)) }
                    )
                    Text(
                        tr(
                            settings.language,
                            "Used today: $used. This is a local hard stop for API calls from this app, not a provider billing meter.",
                            "Heute genutzt: $used. Das ist ein lokaler harter Stopp für API-Aufrufe dieser App, kein Abrechnungszähler des Anbieters."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    @Composable
    private fun ProviderKeyAndEndpoints(
        settings: AppSettings,
        keyStore: SecureApiKeyStore,
        osrmDraft: String,
        valhallaDraft: String,
        photonDraft: String,
        onOsrmDraft: (String) -> Unit,
        onValhallaDraft: (String) -> Unit,
        onPhotonDraft: (String) -> Unit,
        onOpenUrl: (String) -> Unit
    ) {
        Text(
            tr(
                settings.language,
                "Address search: Photon. It is independent from the routing provider and is not counted against routing caps.",
                "Adresssuche: Photon. Sie ist unabhängig vom Routingdienst und wird nicht auf dessen Anfrage-Limit angerechnet."
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = photonDraft,
            onValueChange = onPhotonDraft,
            label = { Text("Photon HTTPS endpoint") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(10.dp))

        when (val provider = settings.routingProvider) {
            RoutingProvider.OSRM -> OutlinedTextField(
                value = osrmDraft,
                onValueChange = onOsrmDraft,
                label = { Text("OSRM HTTPS endpoint") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            RoutingProvider.VALHALLA -> OutlinedTextField(
                value = valhallaDraft,
                onValueChange = onValhallaDraft,
                label = { Text("Valhalla HTTPS endpoint") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            else -> if (provider.keyRequired) {
                ProviderApiKeyField(provider, settings, keyStore)
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { onOpenUrl(providerKeyUrl(provider)) }) {
                    Icon(Icons.Outlined.OpenInNew, null)
                    Spacer(Modifier.width(8.dp))
                    Text(tr(settings.language, "Get API key / manage quota", "API-Key beziehen / Kontingent verwalten"))
                }
            }
        }
    }

    @Composable
    private fun ProviderApiKeyField(provider: RoutingProvider, settings: AppSettings, keyStore: SecureApiKeyStore) {
        var keyDraft by remember(provider) { mutableStateOf(keyStore.read(provider).orEmpty()) }
        LaunchedEffect(provider, keyDraft) {
            delay(700)
            keyStore.save(provider, keyDraft.trim())
        }
        OutlinedTextField(
            value = keyDraft,
            onValueChange = { keyDraft = it },
            label = { Text("${provider.displayName} API key") },
            visualTransformation = PasswordVisualTransformation(),
            trailingIcon = { Icon(Icons.Outlined.Key, null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Text(
            providerLimitInfo(provider, settings.language),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    @Composable
    private fun EventPickerDialog(
        settings: AppSettings,
        events: List<CalendarEventRef>,
        calendarNames: Map<Long, String>,
        pickingStart: Boolean,
        onDismiss: () -> Unit,
        onSelect: (CalendarEventRef) -> Unit
    ) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(0.92f).heightIn(max = 650.dp)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        if (pickingStart) tr(settings.language, "Choose previous appointment", "Vorherigen Termin wählen")
                        else tr(settings.language, "Choose appointment", "Termin wählen"),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tr(settings.language, "Only selected source calendars are shown.", "Es werden nur ausgewählte Quellkalender angezeigt."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))
                    if (events.isEmpty()) {
                        Text(tr(settings.language, "No appointments with a location found.", "Keine Termine mit Ort gefunden."))
                    } else {
                        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            events.forEach { event ->
                                val dateTime = Instant.ofEpochMilli(event.startMillis).atZone(ZoneId.systemDefault())
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.fillMaxWidth().clickable { onSelect(event) }
                                ) {
                                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                                            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(dateTime.format(DateTimeFormatter.ofPattern("dd")), fontWeight = FontWeight.Bold)
                                                Text(dateTime.format(DateTimeFormatter.ofPattern("MMM")), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(event.title.ifBlank { tr(settings.language, "Appointment", "Termin") }, fontWeight = FontWeight.SemiBold)
                                            Text(event.location, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(
                                                "${dateTime.format(DateTimeFormatter.ofPattern("HH:mm"))} • ${calendarNames[event.calendarId].orEmpty()}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Icon(Icons.Outlined.ChevronRight, null)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                        Text(tr(settings.language, "Close", "Schließen"))
                    }
                }
            }
        }
    }

    @Composable
    private fun CalendarSinglePicker(
        settings: AppSettings,
        calendars: List<CalendarInfo>,
        selected: Long,
        onDismiss: () -> Unit,
        onSelect: (Long) -> Unit
    ) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth(0.9f)) {
                Column(Modifier.padding(20.dp)) {
                    Text(tr(settings.language, "Target calendar", "Zielkalender"), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    calendars.forEach { calendar ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onSelect(calendar.id) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(calendar.id == selected, onClick = { onSelect(calendar.id) })
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(calendar.name, fontWeight = FontWeight.Medium)
                                Text(calendar.accountName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CalendarMultiPicker(
        settings: AppSettings,
        calendars: List<CalendarInfo>,
        initial: Set<String>,
        onDismiss: () -> Unit,
        onApply: (Set<String>) -> Unit
    ) {
        var selected by remember { mutableStateOf(initial) }
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth(0.9f).heightIn(max = 650.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text(tr(settings.language, "Source calendars", "Quellkalender"), style = MaterialTheme.typography.titleLarge)
                    Text(
                        tr(settings.language, "Appointments from all other calendars are ignored.", "Termine aus allen anderen Kalendern werden ignoriert."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        calendars.forEach { calendar ->
                            val id = calendar.id.toString()
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    selected = if (id in selected) selected - id else selected + id
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = id in selected,
                                    onCheckedChange = { checked -> selected = if (checked) selected + id else selected - id }
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(calendar.name, fontWeight = FontWeight.Medium)
                                    Text(calendar.accountName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text(tr(settings.language, "Cancel", "Abbrechen")) }
                        Button(onClick = { onApply(selected) }) { Text(tr(settings.language, "Apply", "Übernehmen")) }
                    }
                }
            }
        }
    }

    @Composable
    private fun AddSavedPlaceDialog(
        settings: AppSettings,
        onDismiss: () -> Unit,
        onAdd: (String, String) -> Unit
    ) {
        var name by remember { mutableStateOf("") }
        var address by remember { mutableStateOf("") }
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth(0.9f)) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(tr(settings.language, "Add start location", "Startpunkt hinzufügen"), style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        name, { name = it },
                        label = { Text(tr(settings.language, "Friendly name", "Anzeigename")) },
                        placeholder = { Text(tr(settings.language, "e.g. Office", "z. B. Büro")) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    AddressAutocompleteField(
                        settings = settings,
                        value = address,
                        onValueChange = { address = it },
                        label = tr(settings.language, "Address", "Adresse")
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text(tr(settings.language, "Cancel", "Abbrechen")) }
                        Button(onClick = { onAdd(name, address) }, enabled = name.isNotBlank() && address.isNotBlank()) {
                            Text(tr(settings.language, "Add", "Hinzufügen"))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun QuickLocationChips(
        settings: AppSettings,
        includeDefault: Boolean,
        onSelect: (String) -> Unit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (includeDefault && settings.homeAddress.isNotBlank()) {
                AssistChip(
                    onClick = { onSelect(settings.homeAddress) },
                    label = { Text(tr(settings.language, "Default location", "Standard-Standort")) },
                    leadingIcon = { Icon(Icons.Outlined.Home, null) }
                )
            }
            settings.savedPlaces.sorted().forEach { raw ->
                val name = raw.substringBefore("|").trim()
                val address = raw.substringAfter("|", "").trim()
                if (address.isNotBlank()) {
                    AssistChip(
                        onClick = { onSelect(address) },
                        label = { Text(name.ifBlank { address }) },
                        leadingIcon = { Icon(Icons.Outlined.HomeWork, null) }
                    )
                }
            }
        }
    }

    @Composable
    private fun AppCard(content: @Composable ColumnScope.() -> Unit) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp), content = content)
        }
    }

    @Composable
    private fun SettingsCard(
        title: String,
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        content: @Composable ColumnScope.() -> Unit
    ) {
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(9.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(16.dp))
            content()
        }
    }

    @Composable
    private fun SectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = modifier) {
            Column(Modifier.padding(14.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(3.dp))
                Text(value, style = MaterialTheme.typography.titleLarge)
            }
        }
    }

    @Composable
    private fun SelectionButton(
        text: String,
        onClick: () -> Unit,
        leadingIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Outlined.UnfoldMore
    ) {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = MaterialTheme.shapes.medium
        ) {
            Icon(leadingIcon, null)
            Spacer(Modifier.width(10.dp))
            Text(text, Modifier.weight(1f))
            Icon(Icons.Outlined.ChevronRight, null)
        }
    }

    @Composable
    private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Switch(checked, onCheckedChange = onCheckedChange)
        }
    }

    @Composable
    private fun NumberDraftField(initialValue: Int, label: String, onValid: (Int) -> Unit) {
        var text by remember { mutableStateOf(initialValue.toString()) }
        LaunchedEffect(text) {
            delay(450)
            val parsed = text.toIntOrNull()
            if (parsed != null && parsed != initialValue) onValid(parsed)
        }
        OutlinedTextField(
            value = text,
            onValueChange = { if (it.all(Char::isDigit)) text = it },
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }

    @Composable
    private fun PaletteChip(palette: ColorPalette, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
        val spec = paletteSpec(palette)
        Surface(
            shape = MaterialTheme.shapes.medium,
            border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
            modifier = modifier.clickable(onClick = onClick)
        ) {
            Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(spec.primary))
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.size(14.dp).clip(CircleShape).background(spec.secondary))
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.size(14.dp).clip(CircleShape).background(spec.tertiary))
                }
                Spacer(Modifier.height(5.dp))
                Text(palette.label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    private fun providerDescription(provider: RoutingProvider, language: AppLanguage): String = when (provider) {
        RoutingProvider.VALHALLA -> tr(language, "Free open-source OSM routing on a public fair-use server. Good static ETA, no guaranteed live traffic.", "Kostenloses Open-Source-OSM-Routing auf einem Fair-Use-Server. Gute statische ETA, keine garantierten Live-Verkehrsdaten.")
        RoutingProvider.OPENROUTESERVICE -> tr(language, "Free API plan with an API key. OSM-based routing, no predictive live traffic in this integration.", "Kostenloser API-Tarif mit API-Key. OSM-basiertes Routing, keine prognostischen Live-Verkehrsdaten in dieser Integration.")
        RoutingProvider.OSRM -> tr(language, "Very fast open-source OSM routing. Public server is fair-use and provides static travel times.", "Sehr schnelles Open-Source-OSM-Routing. Öffentlicher Server ist Fair-Use und liefert statische Fahrzeiten.")
        RoutingProvider.GRAPHHOPPER -> tr(language, "Free tier available. Fast OSM-based routing without predictive traffic in this integration.", "Kostenloser Tarif verfügbar. Schnelles OSM-basiertes Routing ohne prognostische Verkehrsdaten in dieser Integration.")
        RoutingProvider.GOOGLE -> tr(language, "Predictive traffic and closures. Billing can apply beyond included credits.", "Prognostischer Verkehr und Sperrungen. Über enthaltene Guthaben hinaus können Kosten entstehen.")
        RoutingProvider.HERE -> tr(language, "Time-aware routing with live and historical traffic. Pay-as-you-grow pricing can apply.", "Zeitabhängiges Routing mit Live- und historischen Verkehrsdaten. Pay-as-you-grow-Kosten können anfallen.")
        RoutingProvider.TOMTOM -> tr(language, "Strong live and historical traffic routing. Free evaluation exists, paid usage can apply.", "Starkes Live- und historisches Verkehrs-Routing. Kostenlose Evaluation vorhanden, bezahlte Nutzung kann anfallen.")
    }

    private fun providerLimitInfo(provider: RoutingProvider, language: AppLanguage): String = when (provider) {
        RoutingProvider.VALHALLA -> tr(language, "Public demo: fair use, about 1 request/second per user. Self-host for heavy use.", "Öffentlicher Demo-Server: Fair Use, etwa 1 Anfrage/Sekunde pro Nutzer. Für hohe Nutzung selbst hosten.")
        RoutingProvider.OPENROUTESERVICE -> tr(language, "Free API access has quotas. The app uses the current api.heigit.org endpoint and stops at your local daily cap.", "Kostenloser API-Zugang hat Kontingente. Die App nutzt den aktuellen api.heigit.org-Endpunkt und stoppt am lokalen Tageslimit.")
        RoutingProvider.OSRM -> tr(language, "Public OSRM is fair-use infrastructure without an SLA. Keep the local cap conservative or self-host.", "Öffentliches OSRM ist Fair-Use-Infrastruktur ohne SLA. Lokales Limit niedrig halten oder selbst hosten.")
        RoutingProvider.GRAPHHOPPER -> tr(language, "Free plan currently includes 500 credits/day. A normal two-point route costs one credit.", "Der Free-Tarif enthält derzeit 500 Credits/Tag. Eine normale Route mit zwei Punkten kostet einen Credit.")
        RoutingProvider.GOOGLE -> tr(language, "Google Maps Platform pricing depends on enabled APIs. This app applies a local hard cap before requests.", "Google-Maps-Platform-Preise hängen von aktivierten APIs ab. Diese App setzt vor Anfragen ein lokales hartes Limit.")
        RoutingProvider.HERE -> tr(language, "HERE Base Plan includes free thresholds, then pay-as-you-grow pricing may apply.", "Der HERE Base Plan enthält kostenlose Schwellenwerte, danach können Pay-as-you-grow-Kosten anfallen.")
        RoutingProvider.TOMTOM -> tr(language, "TomTom offers a limited free evaluation; paid plans remove the daily evaluation limit.", "TomTom bietet eine begrenzte kostenlose Evaluation; bezahlte Tarife entfernen das tägliche Evaluationslimit.")
    }

    private fun providerKeyUrl(provider: RoutingProvider): String = when (provider) {
        RoutingProvider.VALHALLA -> "https://valhalla.github.io/valhalla/"
        RoutingProvider.OPENROUTESERVICE -> "https://openrouteservice.org/dev/#/signup"
        RoutingProvider.OSRM -> "https://project-osrm.org/"
        RoutingProvider.GRAPHHOPPER -> "https://www.graphhopper.com/dashboard/"
        RoutingProvider.GOOGLE -> "https://console.cloud.google.com/google/maps-apis/credentials"
        RoutingProvider.HERE -> "https://platform.here.com/"
        RoutingProvider.TOMTOM -> "https://developer.tomtom.com/"
    }

    companion object {
        const val ACTION_NEXT_DRIVE = "de.drivetime.notifier.ACTION_NEXT_DRIVE"
    }
}
