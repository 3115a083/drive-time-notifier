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
import androidx.compose.foundation.Image
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
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
import de.drivetime.notifier.security.PasswordBackup
import de.drivetime.notifier.security.SecureApiKeyStore
import de.drivetime.notifier.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(R.drawable.app_logo),
                                contentDescription = null,
                                modifier = Modifier.size(42.dp),
                                colorFilter = if (resolvedDarkMode(settings.appearance)) {
                                    androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.ui.graphics.Color.White)
                                } else null
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("Drive Time", fontWeight = FontWeight.Bold)
                                Text(
                                    if (showSettings) tr(settings.language, "Settings", "Einstellungen")
                                    else tr(settings.language, "Plan your departure", "Abfahrt planen"),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
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
        var planConflict by remember { mutableStateOf(false) }
        var plannedStart by remember { mutableStateOf<Long?>(null) }
        var plannedEnd by remember { mutableStateOf<Long?>(null) }
        var calendarSaving by remember { mutableStateOf(false) }
        var calendarSaved by remember { mutableStateOf(false) }
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

        val saveIcsLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/calendar")
        ) { uri ->
            val route = estimate
            val start = plannedStart
            val end = plannedEnd
            if (uri != null && route != null && start != null && end != null) {
                scope.launch {
                    runCatching {
                        val description = DriveEventDescriptionBuilder.build(
                            settings.language,
                            settings.routingProvider,
                            origin,
                            destination,
                            route,
                            pois
                        )
                        IcsExporter(context).writeToUri(
                            uri,
                            origin,
                            destination,
                            start,
                            end,
                            resolvedDriveEventTitle(settings),
                            description
                        )
                    }.onFailure {
                        error = it.message ?: tr(
                            settings.language,
                            "Could not save the ICS file.",
                            "Die ICS-Datei konnte nicht gespeichert werden."
                        )
                    }
                }
            }
        }

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
                        val previousEnd = previousEndMillis
                        planConflict = previousEnd != null && plan.departureMillis < previousEnd
                        planWarning = listOfNotNull(
                            planWarningText(settings.language, plan, settings.bufferMinutes),
                            routeWarningText(settings.language, settings.routingProvider, route.warning)
                        ).joinToString(" ").ifBlank { null }
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
            HeroCard(settings, estimate, plannedStart, loading)

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
                    Text(tr(settings.language, "Use calendar event as start", "Kalendereintrag als Start verwenden"))
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
                RouteMap(settings, route, pois)
                SummaryCard(settings, route, pois, plannedStart, planWarning, planConflict)

                if (settings.outputIcs) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                val stamp = "${appointmentDate}_${appointmentTime.format(DateTimeFormatter.ofPattern("HHmm"))}"
                                saveIcsLauncher.launch("drive-$stamp.ics")
                            },
                            modifier = Modifier.weight(1f).height(56.dp),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(Icons.Outlined.SaveAlt, null)
                            Spacer(Modifier.width(8.dp))
                            Text(tr(settings.language, "Save ICS", "ICS speichern"))
                        }
                        FilledTonalButton(
                            onClick = {
                                val start = plannedStart ?: return@FilledTonalButton
                                val end = plannedEnd ?: return@FilledTonalButton
                                runCatching {
                                    val description = DriveEventDescriptionBuilder.build(
                                        settings.language,
                                        settings.routingProvider,
                                        origin,
                                        destination,
                                        route,
                                        pois
                                    )
                                    val uri = IcsExporter(context).create(
                                        origin,
                                        destination,
                                        start,
                                        end,
                                        resolvedDriveEventTitle(settings),
                                        description
                                    )
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "text/calendar"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            },
                                            tr(settings.language, "Export ICS", "ICS exportieren")
                                        )
                                    )
                                }.onFailure { error = it.message }
                            },
                            modifier = Modifier.weight(1f).height(56.dp),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(Icons.Outlined.Share, null)
                            Spacer(Modifier.width(8.dp))
                            Text(tr(settings.language, "Export ICS", "ICS exportieren"))
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            if (calendarSaving || calendarSaved) return@Button
                            val start = plannedStart ?: return@Button
                            val end = plannedEnd ?: return@Button
                            calendarSaving = true
                            error = null
                            scope.launch {
                                runCatching {
                                    val description = DriveEventDescriptionBuilder.build(
                                        settings.language,
                                        settings.routingProvider,
                                        origin,
                                        destination,
                                        route,
                                        pois
                                    )
                                    calendarRepo.insertDrive(
                                        settings.targetCalendarId,
                                        origin,
                                        destination,
                                        start,
                                        end,
                                        settings.reminderLeadMinutes,
                                        resolvedDriveEventTitle(settings),
                                        description
                                    )
                                }.onSuccess {
                                    calendarSaved = true
                                    delay(1400)
                                    calendarSaved = false
                                }.onFailure {
                                    error = it.message ?: tr(
                                        settings.language,
                                        "Could not add the drive to the calendar.",
                                        "Die Fahrt konnte nicht in den Kalender eingetragen werden."
                                    )
                                }
                                calendarSaving = false
                            }
                        },
                        enabled = !calendarSaving && !calendarSaved,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        when {
                            calendarSaving -> CircularProgressIndicator(
                                Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            calendarSaved -> Icon(
                                Icons.Outlined.CheckCircle,
                                null,
                                tint = androidx.compose.ui.graphics.Color(0xFF2E7D32)
                            )
                            else -> Icon(Icons.Outlined.EventAvailable, null)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (calendarSaved)
                                tr(settings.language, "Added successfully", "Erfolgreich eingetragen")
                            else
                                tr(settings.language, "Save drive to calendar", "Fahrt im Kalender speichern")
                        )
                    }
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
    private fun HeroCard(
        settings: AppSettings,
        estimate: RouteEstimate?,
        departureMillis: Long?,
        loading: Boolean
    ) {
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
                        "${formatDuration(estimate.durationSeconds, settings.language)}  •  ${"%.1f".format(estimate.distanceMeters / 1000.0)} km  •  ${tr(settings.language, "Depart", "Abfahrt")} ${formatClock(departureMillis)}",
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
    private fun SummaryCard(
        settings: AppSettings,
        route: RouteEstimate,
        pois: List<RoutePoi>,
        departureMillis: Long?,
        warning: String?,
        conflict: Boolean
    ) {
        AppCard {
            SectionHeader(
                Icons.Outlined.Insights,
                tr(settings.language, "Drive summary", "Fahrtübersicht"),
                settings.routingProvider.displayName
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile(tr(settings.language, "Drive time", "Fahrtzeit"), formatDuration(route.durationSeconds, settings.language), Modifier.weight(1f))
                MetricTile(tr(settings.language, "Distance", "Distanz"), "${"%.1f".format(route.distanceMeters / 1000.0)} km", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile(tr(settings.language, "Possible delay", "Mögliche Verzögerung"), formatDuration(route.trafficDelaySeconds, settings.language), Modifier.weight(1f))
                MetricTile(tr(settings.language, "Departure", "Abfahrt"), formatClock(departureMillis), Modifier.weight(1f))
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
                val background = if (conflict) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
                val foreground = if (conflict) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
                Surface(color = background, shape = MaterialTheme.shapes.small) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(
                            if (conflict) Icons.Outlined.WarningAmber else Icons.Outlined.Info,
                            contentDescription = null,
                            tint = foreground
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(it, color = foreground)
                    }
                }
            }
        }
    }

    @Composable
    private fun RouteMap(settings: AppSettings, route: RouteEstimate, pois: List<RoutePoi>) {
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
                                title = if (poi.kind == RoutePoi.Kind.SPEED_CAMERA) {
                                    tr(settings.language, "Speed camera", "Blitzer")
                                } else {
                                    poi.name ?: tr(settings.language, "Parking", "Parkplatz")
                                }
                                icon = ContextCompat.getDrawable(
                                    map.context,
                                    if (poi.kind == RoutePoi.Kind.SPEED_CAMERA) {
                                        R.drawable.ic_speed_camera_marker
                                    } else {
                                        R.drawable.ic_parking_marker
                                    }
                                )
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
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
        val scope = rememberCoroutineScope()
        val uriHandler = LocalUriHandler.current
        val clipboard = LocalClipboardManager.current
        val interfaceHealthStore = remember { InterfaceHealthStore(context) }
        var healthRevision by remember { mutableIntStateOf(0) }
        var calendars by remember { mutableStateOf<List<CalendarInfo>>(emptyList()) }
        var showTargetPicker by remember { mutableStateOf(false) }
        var showSourcePicker by remember { mutableStateOf(false) }
        var showAutomationTimePicker by remember { mutableStateOf(false) }
        var showAddPlace by remember { mutableStateOf(false) }
        var calendarPlaceAssignmentRaw by remember { mutableStateOf<String?>(null) }
        var showAddExclusion by remember { mutableStateOf(false) }
        var showFallbackPicker by remember { mutableStateOf(false) }
        var showTokenRotateConfirm by remember { mutableStateOf(false) }
        var backupPasswordMode by remember { mutableStateOf<String?>(null) }
        var backupPassword by remember { mutableStateOf("") }
        var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
        var pendingExportPassword by remember { mutableStateOf<String?>(null) }
        var backupMessage by remember { mutableStateOf<String?>(null) }
        var automationToken by remember { mutableStateOf(AutomationTokenStore(context).token()) }
        var homeNameDraft by remember { mutableStateOf(settings.homeName) }
        var homeDraft by remember { mutableStateOf(settings.homeAddress) }
        var calendarTitleDraft by remember { mutableStateOf(settings.calendarEventTitle) }
        var osrmDraft by remember { mutableStateOf(settings.osrmBaseUrl) }
        var valhallaDraft by remember { mutableStateOf(settings.valhallaBaseUrl) }
        var photonDraft by remember { mutableStateOf(settings.photonBaseUrl) }
        val latestSettings by rememberUpdatedState(settings)

        val exportBackupLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            val password = pendingExportPassword
            pendingExportPassword = null
            if (uri != null && !password.isNullOrEmpty()) {
                scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                                PasswordBackup.export(output, latestSettings, keyStore, password.toCharArray())
                            } ?: error("Could not open backup file.")
                        }
                    }
                    backupMessage = result.fold(
                        onSuccess = { tr(latestSettings.language, "Encrypted backup exported.", "Verschlüsseltes Backup exportiert.") },
                        onFailure = { it.message ?: tr(latestSettings.language, "Backup export failed.", "Backup-Export fehlgeschlagen.") }
                    )
                }
            }
        }

        val importBackupLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                pendingImportUri = uri
                backupPassword = ""
                backupPasswordMode = "import"
            }
        }

        LaunchedEffect(hasCalendarPermission) {
            calendars = if (hasCalendarPermission) runCatching { calendarRepo.calendars() }.getOrDefault(emptyList()) else emptyList()
        }
        LaunchedEffect(homeNameDraft) {
            delay(500)
            if (homeNameDraft != latestSettings.homeName) {
                onChange(latestSettings.copy(homeName = homeNameDraft))
            }
        }
        LaunchedEffect(homeDraft) {
            delay(500)
            if (homeDraft != latestSettings.homeAddress) {
                onChange(latestSettings.copy(homeAddress = homeDraft))
            }
        }
        LaunchedEffect(calendarTitleDraft) {
            delay(500)
            if (calendarTitleDraft != latestSettings.calendarEventTitle) {
                onChange(latestSettings.copy(calendarEventTitle = calendarTitleDraft))
            }
        }
        LaunchedEffect(osrmDraft) {
            delay(600)
            if (osrmDraft != latestSettings.osrmBaseUrl) {
                onChange(latestSettings.copy(osrmBaseUrl = osrmDraft))
            }
        }
        LaunchedEffect(valhallaDraft) {
            delay(600)
            if (valhallaDraft != latestSettings.valhallaBaseUrl) {
                onChange(latestSettings.copy(valhallaBaseUrl = valhallaDraft))
            }
        }
        LaunchedEffect(photonDraft) {
            delay(600)
            if (photonDraft != latestSettings.photonBaseUrl) {
                onChange(latestSettings.copy(photonBaseUrl = photonDraft))
            }
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
                OutlinedTextField(
                    value = homeNameDraft,
                    onValueChange = { homeNameDraft = it },
                    label = { Text(tr(settings.language, "Default location name", "Name des Standard-Standorts")) },
                    supportingText = {
                        Text(
                            tr(
                                settings.language,
                                "Shown as a quick action on the planner. During automatic processing, calendars that are not assigned to another start location use this default location.",
                                "Wird als Schnellwahl in der Planung angezeigt. Bei der automatischen Verarbeitung starten alle Kalender, die keinem anderen Startort zugeordnet wurden, an diesem Standard-Standort."
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(10.dp))
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
                            IconButton(onClick = { calendarPlaceAssignmentRaw = raw }) {
                                Icon(
                                    Icons.Outlined.CalendarMonth,
                                    contentDescription = tr(
                                        settings.language,
                                        "Assign calendars to this start location",
                                        "Kalender diesem Startort zuordnen"
                                    )
                                )
                            }
                            IconButton(onClick = {
                                val addressPrefix = address.trim()
                                onChange(
                                    settings.copy(
                                        savedPlaces = settings.savedPlaces - raw,
                                        calendarStartLocations = settings.calendarStartLocations.filterNot {
                                            it.substringAfter("|", "").trim() == addressPrefix
                                        }.toSet()
                                    )
                                )
                            }) {
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
                title = tr(settings.language, "Excluded locations", "Ausschlussadressen"),
                icon = Icons.Outlined.Block
            ) {
                Text(
                    tr(
                        settings.language,
                        "Automatic processing skips matching appointment locations before any routing request is sent.",
                        "Die automatische Verarbeitung überspringt passende Terminorte, bevor eine Routing-Anfrage gesendet wird."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                settings.exclusionRules.sorted().forEach { rule ->
                    val mode = rule.substringBefore("|")
                    val value = rule.substringAfter("|", "")
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Block, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                when (mode) {
                                    "exact" -> tr(settings.language, "Exact match", "Exakt")
                                    "ignore_case" -> tr(settings.language, "Ignore upper/lower case", "Groß-/Kleinschreibung ignorieren")
                                    "url" -> tr(settings.language, "Any web link", "Beliebiger Web-Link")
                                    "phone" -> tr(settings.language, "Any phone number", "Beliebige Telefonnummer")
                                    "regex" -> tr(settings.language, "Regular expression", "Regulärer Ausdruck")
                                    else -> mode
                                },
                                fontWeight = FontWeight.Medium
                            )
                            if (value.isNotBlank()) {
                                Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = {
                            onChange(settings.copy(exclusionRules = settings.exclusionRules - rule))
                        }) {
                            Icon(Icons.Outlined.DeleteOutline, tr(settings.language, "Delete", "Löschen"))
                        }
                    }
                }
                FilledTonalButton(onClick = { showAddExclusion = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(tr(settings.language, "Add exclusion", "Ausschluss hinzufügen"))
                }
            }

            SettingsCard(
                title = tr(settings.language, "Calendar", "Kalender"),
                icon = Icons.Outlined.CalendarMonth
            ) {
                OutlinedTextField(
                    value = calendarTitleDraft,
                    onValueChange = { calendarTitleDraft = it },
                    label = { Text(tr(settings.language, "Drive event title", "Titel des Fahrt-Termins")) },
                    placeholder = { Text(resolvedDriveEventTitle(settings)) },
                    supportingText = {
                        Text(
                            tr(
                                settings.language,
                                "Leave empty to use “Your drive starts”.",
                                "Leer lassen für „Deine Fahrt beginnt“."
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(14.dp))
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
                        keyStore = keyStore,
                        interfaceHealthStore = interfaceHealthStore,
                        healthRevision = healthRevision,
                        onSelect = {
                            onChange(
                                settings.copy(
                                    routingProvider = provider,
                                    fallbackProviderIds = settings.fallbackProviderIds.filterNot { it == provider.id }
                                )
                            )
                        },
                        onCap = { cap ->
                            onChange(settings.copy(providerCaps = settings.providerCaps.withProvider(provider, cap)))
                        },
                        onPeriod = { period ->
                            onChange(
                                settings.copy(
                                    providerLimitPeriods = settings.providerLimitPeriods.withProvider(provider, period)
                                )
                            )
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                }

                Text(
                    tr(settings.language, "Fallback routing", "Fallback-Routing"),
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    tr(
                        settings.language,
                        "If the primary provider reaches its local cap or fails, providers below are tried in this order.",
                        "Wenn der primäre Dienst sein lokales Limit erreicht oder fehlschlägt, werden die folgenden Dienste in dieser Reihenfolge versucht."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                settings.fallbackProviderIds.forEachIndexed { index, id ->
                    val fallback = RoutingProvider.entries.firstOrNull { it.id == id }
                    if (fallback != null && fallback != settings.routingProvider) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${index + 1}.", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.width(8.dp))
                            ProviderMonochromeMark(fallback)
                            Spacer(Modifier.width(8.dp))
                            Text(fallback.displayName, Modifier.weight(1f))
                            IconButton(
                                enabled = index > 0,
                                onClick = {
                                    val list = settings.fallbackProviderIds.toMutableList()
                                    val item = list.removeAt(index)
                                    list.add(index - 1, item)
                                    onChange(settings.copy(fallbackProviderIds = list))
                                }
                            ) { Icon(Icons.Outlined.KeyboardArrowUp, null) }
                            IconButton(
                                enabled = index < settings.fallbackProviderIds.lastIndex,
                                onClick = {
                                    val list = settings.fallbackProviderIds.toMutableList()
                                    val item = list.removeAt(index)
                                    list.add(index + 1, item)
                                    onChange(settings.copy(fallbackProviderIds = list))
                                }
                            ) { Icon(Icons.Outlined.KeyboardArrowDown, null) }
                            IconButton(onClick = {
                                onChange(settings.copy(fallbackProviderIds = settings.fallbackProviderIds - id))
                            }) {
                                Icon(Icons.Outlined.DeleteOutline, tr(settings.language, "Remove", "Entfernen"))
                            }
                        }
                    }
                }
                FilledTonalButton(onClick = { showFallbackPicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(tr(settings.language, "Add fallback provider", "Fallback-Dienst hinzufügen"))
                }
                Spacer(Modifier.height(12.dp))

                ProviderKeyAndEndpoints(
                    settings = settings,
                    keyStore = keyStore,
                    osrmDraft = osrmDraft,
                    valhallaDraft = valhallaDraft,
                    photonDraft = photonDraft,
                    interfaceHealthStore = interfaceHealthStore,
                    healthRevision = healthRevision,
                    onHealthChanged = { healthRevision++ },
                    onOsrmDraft = { osrmDraft = it },
                    onValhallaDraft = { valhallaDraft = it },
                    onPhotonDraft = { photonDraft = it },
                    onOpenUrl = { uriHandler.openUri(it) }
                )

            }

            SettingsCard(
                title = tr(settings.language, "Backup & device transfer", "Backup & Gerätewechsel"),
                icon = Icons.Outlined.Backup
            ) {
                Text(
                    tr(
                        settings.language,
                        "Export settings and provider API keys to a password-protected file. The automation token, local request counters and interface test states stay device-specific.",
                        "Exportiert Einstellungen und Provider-API-Keys in eine passwortgeschützte Datei. Automatisierungs-Token, lokale Anfragezähler und Schnittstellen-Teststatus bleiben gerätespezifisch."
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            backupPassword = ""
                            backupPasswordMode = "export"
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.FileUpload, null)
                        Spacer(Modifier.width(6.dp))
                        Text(tr(settings.language, "Export", "Export"))
                    }
                    OutlinedButton(
                        onClick = {
                            backupMessage = null
                            importBackupLauncher.launch(arrayOf("application/octet-stream", "application/json", "*/*"))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.FileDownload, null)
                        Spacer(Modifier.width(6.dp))
                        Text(tr(settings.language, "Import", "Import"))
                    }
                }
                Text(
                    tr(
                        settings.language,
                        "Use at least 8 characters. Calendar IDs can differ between devices, so review source and target calendars after importing.",
                        "Verwende mindestens 8 Zeichen. Kalender-IDs können sich zwischen Geräten unterscheiden. Prüfe deshalb nach dem Import Quell- und Zielkalender."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                backupMessage?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                }
            }

            SettingsCard(
                title = tr(settings.language, "Automation integrations", "Automatisierungs-\nSchnittstellen"),
                icon = Icons.Outlined.Key
            ) {
                Text(
                    tr(
                        settings.language,
                        "Each app installation gets its own random 256-bit token. Copy it into Tasker, MacroDroid or another trusted automation tool.",
                        "Jede App-Installation erhält einen eigenen zufälligen 256-Bit-Token. Kopiere ihn in Tasker, MacroDroid oder ein anderes vertrauenswürdiges Automatisierungs-Tool."
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                CopyableAutomationValue(
                    label = tr(settings.language, "Automation token", "Automatisierungs-Token"),
                    value = automationToken,
                    onCopy = { clipboard.setText(AnnotatedString(automationToken)) }
                )
                OutlinedButton(
                    onClick = { showTokenRotateConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text(tr(settings.language, "Generate new token", "Neuen Token erzeugen"))
                }
                Text(
                    tr(
                        settings.language,
                        "Changing the token immediately invalidates the previous token. Update all external automations afterwards.",
                        "Ein Token-Wechsel macht den bisherigen Token sofort ungültig. Aktualisiere danach alle externen Automationen."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                CopyableAutomationValue(
                    label = tr(settings.language, "Process tomorrow action", "Action für morgige Fahrten"),
                    value = AutomationReceiver.ACTION_PROCESS_NEXT_DAY,
                    onCopy = { clipboard.setText(AnnotatedString(AutomationReceiver.ACTION_PROCESS_NEXT_DAY)) }
                )
                CopyableAutomationValue(
                    label = tr(settings.language, "Single drive action", "Action für einzelne Fahrt"),
                    value = AutomationReceiver.ACTION_PROCESS_EVENT,
                    onCopy = { clipboard.setText(AnnotatedString(AutomationReceiver.ACTION_PROCESS_EVENT)) }
                )
                CopyableAutomationValue(
                    label = tr(settings.language, "Package", "Paket"),
                    value = packageName,
                    onCopy = { clipboard.setText(AnnotatedString(packageName)) }
                )
                Text(
                    tr(
                        settings.language,
                        "External broadcasts are accepted only when the supplied token matches this installation.",
                        "Externe Broadcasts werden nur akzeptiert, wenn der mitgesendete Token zu dieser Installation passt."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Vibecoded with ❤️", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { uriHandler.openUri("https://github.com/3115a083/drive-time-notifier/") }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_github),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text("GitHub")
                }
            }

            Spacer(Modifier.height(20.dp))
        }

        if (backupPasswordMode != null) {
            val mode = backupPasswordMode
            val importing = mode == "import"
            AlertDialog(
                onDismissRequest = {
                    backupPasswordMode = null
                    backupPassword = ""
                    if (importing) pendingImportUri = null
                },
                title = {
                    Text(
                        tr(
                            settings.language,
                            if (importing) "Import encrypted backup" else "Export encrypted backup",
                            if (importing) "Verschlüsseltes Backup importieren" else "Verschlüsseltes Backup exportieren"
                        )
                    )
                },
                text = {
                    Column {
                        Text(
                            tr(
                                settings.language,
                                if (importing) "Enter the password used when the backup was created." else "Choose a password for this backup.",
                                if (importing) "Gib das Passwort ein, mit dem das Backup erstellt wurde." else "Lege ein Passwort für dieses Backup fest."
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = backupPassword,
                            onValueChange = { backupPassword = it },
                            label = { Text(tr(settings.language, "Password", "Passwort")) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            isError = backupPassword.isNotEmpty() && backupPassword.length < 8,
                            supportingText = {
                                Text(tr(settings.language, "At least 8 characters.", "Mindestens 8 Zeichen."))
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        enabled = backupPassword.length >= 8,
                        onClick = {
                            val password = backupPassword
                            backupPassword = ""
                            backupPasswordMode = null
                            if (importing) {
                                val uri = pendingImportUri
                                pendingImportUri = null
                                if (uri != null) {
                                    scope.launch {
                                        val result = runCatching {
                                            withContext(Dispatchers.IO) {
                                                context.contentResolver.openInputStream(uri)?.use { input ->
                                                    PasswordBackup.import(input, password.toCharArray())
                                                } ?: error("Could not open backup file.")
                                            }
                                        }
                                        result.onSuccess { imported ->
                                            imported.apiKeys.forEach { (provider, value) -> keyStore.save(provider, value) }
                                            homeNameDraft = imported.settings.homeName
                                            homeDraft = imported.settings.homeAddress
                                            calendarTitleDraft = imported.settings.calendarEventTitle
                                            osrmDraft = imported.settings.osrmBaseUrl
                                            valhallaDraft = imported.settings.valhallaBaseUrl
                                            photonDraft = imported.settings.photonBaseUrl
                                            onChange(imported.settings)
                                            healthRevision++
                                            backupMessage = tr(
                                                imported.settings.language,
                                                "Backup imported. Review source and target calendars on this device.",
                                                "Backup importiert. Prüfe Quell- und Zielkalender auf diesem Gerät."
                                            )
                                        }.onFailure {
                                            backupMessage = it.message ?: tr(
                                                latestSettings.language,
                                                "Backup import failed.",
                                                "Backup-Import fehlgeschlagen."
                                            )
                                        }
                                    }
                                }
                            } else {
                                pendingExportPassword = password
                                val date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                                exportBackupLauncher.launch("drive-time-notifier-backup-$date.dtnb")
                            }
                        }
                    ) {
                        Text(tr(settings.language, if (importing) "Import" else "Export", if (importing) "Importieren" else "Exportieren"))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        backupPasswordMode = null
                        backupPassword = ""
                        if (importing) pendingImportUri = null
                    }) {
                        Text(tr(settings.language, "Cancel", "Abbrechen"))
                    }
                }
            )
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
        if (showFallbackPicker) {
            FallbackProviderPicker(
                settings = settings,
                onDismiss = { showFallbackPicker = false },
                onSelect = { provider ->
                    if (provider != settings.routingProvider && provider.id !in settings.fallbackProviderIds) {
                        onChange(settings.copy(fallbackProviderIds = settings.fallbackProviderIds + provider.id))
                    }
                    showFallbackPicker = false
                }
            )
        }
        if (showTokenRotateConfirm) {
            AlertDialog(
                onDismissRequest = { showTokenRotateConfirm = false },
                title = { Text(tr(settings.language, "Generate new token?", "Neuen Token erzeugen?")) },
                text = {
                    Text(
                        tr(
                            settings.language,
                            "The old token stops working immediately. Existing external automations must be updated.",
                            "Der alte Token funktioniert sofort nicht mehr. Bestehende externe Automationen müssen aktualisiert werden."
                        )
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        automationToken = AutomationTokenStore(context).rotate()
                        showTokenRotateConfirm = false
                    }) {
                        Text(tr(settings.language, "Generate", "Erzeugen"))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showTokenRotateConfirm = false }) {
                        Text(tr(settings.language, "Cancel", "Abbrechen"))
                    }
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
        calendarPlaceAssignmentRaw?.let { raw ->
            val name = raw.substringBefore("|").trim()
            val address = raw.substringAfter("|", "").trim()
            CalendarLocationAssignmentDialog(
                settings = settings,
                calendars = calendars.filter { it.id.toString() in settings.sourceCalendarIds },
                locationName = name.ifBlank { address },
                address = address,
                onDismiss = { calendarPlaceAssignmentRaw = null },
                onApply = { selectedIds ->
                    var updated = settings
                    settings.sourceCalendarIds.mapNotNull { it.toLongOrNull() }.forEach { calendarId ->
                        val currentlyThisAddress = settings.calendarStartLocations.any {
                            it.substringBefore("|") == calendarId.toString() &&
                                it.substringAfter("|", "").trim() == address
                        }
                        if (calendarId in selectedIds) {
                            updated = updated.withCalendarStartLocation(calendarId, address)
                        } else if (currentlyThisAddress) {
                            updated = updated.withCalendarStartLocation(calendarId, null)
                        }
                    }
                    onChange(updated)
                    calendarPlaceAssignmentRaw = null
                }
            )
        }
        if (showAddExclusion) {
            AddExclusionDialog(
                settings = settings,
                onDismiss = { showAddExclusion = false },
                onAdd = { rule ->
                    onChange(settings.copy(exclusionRules = settings.exclusionRules + rule))
                    showAddExclusion = false
                }
            )
        }
    }

    @Composable
    private fun ProviderCard(
        provider: RoutingProvider,
        selected: Boolean,
        settings: AppSettings,
        keyStore: SecureApiKeyStore,
        interfaceHealthStore: InterfaceHealthStore,
        healthRevision: Int,
        onSelect: () -> Unit,
        onCap: (Int) -> Unit,
        onPeriod: (LimitPeriod) -> Unit
    ) {
        val period = settings.providerLimitPeriods.forProvider(provider)
        val used = remember(provider, period, settings.providerCaps) {
            RequestBudgetStore(this).used(provider, period)
        }
        val key = keyStore.read(provider).orEmpty()
        val interfaceStatus = remember(
            provider,
            healthRevision,
            key,
            settings.osrmBaseUrl,
            settings.valhallaBaseUrl
        ) {
            interfaceHealthStore.read(
                provider.id,
                ProviderConnectivityChecker.providerFingerprint(provider, settings, key)
            )?.state ?: InterfaceCheckState.UNKNOWN
        }
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
                    InterfaceStatusIcon(
                        state = interfaceStatus,
                        contentDescription = when (interfaceStatus) {
                            InterfaceCheckState.VALID -> tr(
                                settings.language,
                                if (provider.keyRequired) "API key verified" else "Interface reachable",
                                if (provider.keyRequired) "API-Key geprüft" else "Schnittstelle erreichbar"
                            )
                            InterfaceCheckState.INVALID -> tr(settings.language, "Interface/key check failed", "Schnittstellen-/Key-Prüfung fehlgeschlagen")
                            InterfaceCheckState.UNKNOWN -> tr(settings.language, "Interface not checked", "Schnittstelle nicht geprüft")
                        }
                    )
                    Spacer(Modifier.width(5.dp))
                    if (provider == RoutingProvider.TOMTOM) {
                        ProviderIconBadge(
                            icon = Icons.Outlined.Favorite,
                            contentDescription = tr(settings.language, "Recommended", "Empfohlen"),
                            highlighted = true
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    if (provider.costRisk) {
                        ProviderTextBadge("$")
                        Spacer(Modifier.width(5.dp))
                    }
                    if (provider.trafficAware) {
                        ProviderIconBadge(
                            icon = Icons.Outlined.DirectionsCar,
                            contentDescription = tr(settings.language, "Traffic-aware", "Verkehrsabhängig")
                        )
                    }
                }
                Text(
                    providerDescription(provider, settings.language),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (selected) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        tr(settings.language, "Request cap period", "Zeitraum des Anfrage-Limits"),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LimitPeriod.entries.forEach { option ->
                            FilterChip(
                                selected = period == option,
                                onClick = { onPeriod(option) },
                                label = { Text(limitPeriodLabel(settings.language, option)) }
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    NumberDraftField(
                        initialValue = settings.providerCaps.forProvider(provider),
                        label = tr(
                            settings.language,
                            "Request cap (${limitPeriodLabel(settings.language, period)})",
                            "Anfrage-Limit (${limitPeriodLabel(settings.language, period)})"
                        ),
                        onValid = { onCap(it.coerceIn(1, 1_000_000)) }
                    )
                    Text(
                        tr(
                            settings.language,
                            "Used in the current period: $used. This is a local hard stop, not a provider billing meter and not a guarantee against provider-side charges.",
                            "Im aktuellen Zeitraum genutzt: $used. Das ist ein lokaler harter Stopp, kein Abrechnungszähler des Anbieters und keine Garantie gegen Kosten auf Anbieterseite."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    @Composable
    private fun InterfaceStatusIcon(state: InterfaceCheckState, contentDescription: String) {
        val tint = when (state) {
            InterfaceCheckState.VALID -> androidx.compose.ui.graphics.Color(0xFF2E7D32)
            InterfaceCheckState.INVALID -> MaterialTheme.colorScheme.error
            InterfaceCheckState.UNKNOWN -> MaterialTheme.colorScheme.outline
        }
        val icon = when (state) {
            InterfaceCheckState.VALID -> Icons.Outlined.CheckCircle
            InterfaceCheckState.INVALID -> Icons.Outlined.ErrorOutline
            InterfaceCheckState.UNKNOWN -> Icons.Outlined.HelpOutline
        }
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(21.dp)
        )
    }

    @Composable
    private fun ProviderIconBadge(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        contentDescription: String,
        highlighted: Boolean = false
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = if (highlighted) androidx.compose.ui.graphics.Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (highlighted) androidx.compose.ui.graphics.Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline
            )
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = if (highlighted) androidx.compose.ui.graphics.Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp).size(18.dp)
            )
        }
    }

    @Composable
    private fun ProviderTextBadge(text: String) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp)
            )
        }
    }

    @Composable
    private fun CopyableAutomationValue(label: String, value: String, onCopy: () -> Unit) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    value,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
                IconButton(onClick = onCopy) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    @Composable
    private fun ProviderMonochromeMark(provider: RoutingProvider?) {
        val logo = when (provider) {
            RoutingProvider.TOMTOM -> R.drawable.ic_provider_tomtom
            RoutingProvider.VALHALLA -> R.drawable.ic_provider_valhalla
            RoutingProvider.OPENROUTESERVICE -> R.drawable.ic_provider_openrouteservice
            RoutingProvider.GRAPHHOPPER -> R.drawable.ic_provider_graphhopper
            RoutingProvider.GOOGLE -> R.drawable.ic_provider_google_maps
            RoutingProvider.HERE -> R.drawable.ic_provider_here
            RoutingProvider.OSRM, null -> null
        }
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.size(30.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (logo != null) {
                    Image(
                        painter = painterResource(logo),
                        contentDescription = provider?.displayName,
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.size(19.dp)
                    )
                } else {
                    Icon(
                        Icons.Outlined.Route,
                        contentDescription = provider?.displayName,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }
    }

    @Composable
    private fun FallbackProviderPicker(
        settings: AppSettings,
        onDismiss: () -> Unit,
        onSelect: (RoutingProvider) -> Unit
    ) {
        val available = RoutingProvider.entries.filter {
            it != settings.routingProvider && it.id !in settings.fallbackProviderIds
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(tr(settings.language, "Add fallback provider", "Fallback-Dienst hinzufügen")) },
            text = {
                Column {
                    if (available.isEmpty()) {
                        Text(tr(settings.language, "All other providers are already configured.", "Alle anderen Dienste sind bereits konfiguriert."))
                    } else {
                        available.forEach { provider ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onSelect(provider) }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ProviderMonochromeMark(provider)
                                Spacer(Modifier.width(10.dp))
                                Text(provider.displayName)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(tr(settings.language, "Close", "Schließen")) }
            }
        )
    }

    @Composable
    private fun ProviderKeyAndEndpoints(
        settings: AppSettings,
        keyStore: SecureApiKeyStore,
        osrmDraft: String,
        valhallaDraft: String,
        photonDraft: String,
        interfaceHealthStore: InterfaceHealthStore,
        healthRevision: Int,
        onHealthChanged: () -> Unit,
        onOsrmDraft: (String) -> Unit,
        onValhallaDraft: (String) -> Unit,
        onPhotonDraft: (String) -> Unit,
        onOpenUrl: (String) -> Unit
    ) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var testingId by remember { mutableStateOf<String?>(null) }
        var confirmProvider by remember { mutableStateOf<RoutingProvider?>(null) }
        var confirmPhoton by remember { mutableStateOf(false) }

        fun providerStatus(provider: RoutingProvider): InterfaceCheckState {
            val key = keyStore.read(provider).orEmpty()
            return interfaceHealthStore.read(
                provider.id,
                ProviderConnectivityChecker.providerFingerprint(provider, settings, key)
            )?.state ?: InterfaceCheckState.UNKNOWN
        }

        fun runProviderTest(provider: RoutingProvider) {
            if (testingId != null) return
            testingId = provider.id
            scope.launch {
                ProviderConnectivityChecker(context, settings, keyStore, interfaceHealthStore)
                    .checkProvider(provider)
                onHealthChanged()
                testingId = null
            }
        }

        fun requestProviderTest(provider: RoutingProvider) {
            if (providerStatus(provider) == InterfaceCheckState.VALID) {
                confirmProvider = provider
            } else {
                runProviderTest(provider)
            }
        }

        fun runPhotonTest() {
            if (testingId != null) return
            testingId = ProviderConnectivityChecker.PHOTON_ID
            scope.launch {
                ProviderConnectivityChecker(context, settings, keyStore, interfaceHealthStore)
                    .checkPhoton()
                onHealthChanged()
                testingId = null
            }
        }

        val photonStatus = remember(healthRevision, settings.photonBaseUrl) {
            interfaceHealthStore.read(
                ProviderConnectivityChecker.PHOTON_ID,
                ProviderConnectivityChecker.photonFingerprint(settings)
            )?.state ?: InterfaceCheckState.UNKNOWN
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            InterfaceStatusIcon(
                state = photonStatus,
                contentDescription = when (photonStatus) {
                    InterfaceCheckState.VALID -> tr(settings.language, "Photon reachable", "Photon erreichbar")
                    InterfaceCheckState.INVALID -> tr(settings.language, "Photon check failed", "Photon-Prüfung fehlgeschlagen")
                    InterfaceCheckState.UNKNOWN -> tr(settings.language, "Photon not checked", "Photon nicht geprüft")
                }
            )
            Spacer(Modifier.width(8.dp))
            Text(
                tr(
                    settings.language,
                    "Address search: Photon. It is independent from the routing provider, is not counted against routing caps, and the preset public endpoint requires no API key.",
                    "Adresssuche: Photon. Sie ist unabhängig vom Routingdienst, wird nicht auf dessen Anfrage-Limit angerechnet und der voreingestellte öffentliche Endpunkt benötigt keinen API-Key."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = photonDraft,
                onValueChange = onPhotonDraft,
                label = { Text("Photon HTTPS endpoint") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            ProviderTestButton(
                state = photonStatus,
                testing = testingId == ProviderConnectivityChecker.PHOTON_ID,
                language = settings.language,
                onClick = {
                    if (photonStatus == InterfaceCheckState.VALID) confirmPhoton = true else runPhotonTest()
                }
            )
        }
        Spacer(Modifier.height(10.dp))

        val provider = settings.routingProvider
        val currentStatus = remember(
            provider,
            healthRevision,
            settings.osrmBaseUrl,
            settings.valhallaBaseUrl,
            keyStore.read(provider)
        ) { providerStatus(provider) }

        Text(
            providerCredentialInfo(provider, settings.language),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(8.dp))

        when (provider) {
            RoutingProvider.OSRM -> Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = osrmDraft,
                    onValueChange = onOsrmDraft,
                    label = { Text("OSRM HTTPS endpoint") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                ProviderTestButton(
                    state = currentStatus,
                    testing = testingId == provider.id,
                    language = settings.language,
                    onClick = { requestProviderTest(provider) }
                )
            }
            RoutingProvider.VALHALLA -> Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = valhallaDraft,
                    onValueChange = onValhallaDraft,
                    label = { Text("Valhalla HTTPS endpoint") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                ProviderTestButton(
                    state = currentStatus,
                    testing = testingId == provider.id,
                    language = settings.language,
                    onClick = { requestProviderTest(provider) }
                )
            }
            else -> if (provider.keyRequired) {
                ProviderApiKeyField(
                    provider = provider,
                    settings = settings,
                    keyStore = keyStore,
                    status = currentStatus,
                    testing = testingId == provider.id,
                    onTest = {
                        keyStore.save(provider, it.trim())
                        requestProviderTest(provider)
                    }
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { onOpenUrl(providerKeyUrl(provider)) }) {
                    Icon(Icons.Outlined.OpenInNew, null)
                    Spacer(Modifier.width(8.dp))
                    Text(tr(settings.language, "Get API key / manage quota", "API-Key beziehen / Kontingent verwalten"))
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            tr(
                settings.language,
                "Each provider test uses fixed coordinates, sends no calendar or address data, and counts as one local request when a provider request is actually sent.",
                "Jeder Provider-Test verwendet feste Koordinaten, sendet keine Kalender- oder Adressdaten und zählt als eine lokale Anfrage, sobald tatsächlich eine Provider-Anfrage gesendet wird."
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        confirmProvider?.let { providerToRetest ->
            AlertDialog(
                onDismissRequest = { confirmProvider = null },
                title = { Text(tr(settings.language, "Test again?", "Erneut testen?")) },
                text = {
                    Text(
                        tr(
                            settings.language,
                            "This interface is already verified. Testing again sends another provider request and increases the local request counter by one.",
                            "Diese Schnittstelle ist bereits geprüft. Ein erneuter Test sendet eine weitere Provider-Anfrage und erhöht den lokalen Anfragezähler um eins."
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmProvider = null
                        runProviderTest(providerToRetest)
                    }) { Text(tr(settings.language, "Test again", "Erneut testen")) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmProvider = null }) {
                        Text(tr(settings.language, "Cancel", "Abbrechen"))
                    }
                }
            )
        }

        if (confirmPhoton) {
            AlertDialog(
                onDismissRequest = { confirmPhoton = false },
                title = { Text(tr(settings.language, "Test again?", "Erneut testen?")) },
                text = {
                    Text(
                        tr(
                            settings.language,
                            "Photon is already reachable. Testing again sends another request to the public endpoint.",
                            "Photon ist bereits erreichbar. Ein erneuter Test sendet eine weitere Anfrage an den öffentlichen Endpunkt."
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmPhoton = false
                        runPhotonTest()
                    }) { Text(tr(settings.language, "Test again", "Erneut testen")) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmPhoton = false }) {
                        Text(tr(settings.language, "Cancel", "Abbrechen"))
                    }
                }
            )
        }
    }

    @Composable
    private fun ProviderTestButton(
        state: InterfaceCheckState,
        testing: Boolean,
        language: AppLanguage,
        onClick: () -> Unit
    ) {
        val container = when (state) {
            InterfaceCheckState.VALID -> androidx.compose.ui.graphics.Color(0xFF2E7D32)
            InterfaceCheckState.INVALID -> MaterialTheme.colorScheme.error
            InterfaceCheckState.UNKNOWN -> MaterialTheme.colorScheme.outline
        }
        Button(
            onClick = onClick,
            enabled = !testing,
            colors = ButtonDefaults.buttonColors(
                containerColor = container,
                contentColor = androidx.compose.ui.graphics.Color.White
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            modifier = Modifier.height(40.dp)
        ) {
            if (testing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = androidx.compose.ui.graphics.Color.White
                )
            } else {
                Icon(Icons.Outlined.NetworkCheck, null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text(tr(language, "Test", "Test"))
            }
        }
    }

    @Composable
    private fun ProviderApiKeyField(
        provider: RoutingProvider,
        settings: AppSettings,
        keyStore: SecureApiKeyStore,
        status: InterfaceCheckState,
        testing: Boolean,
        onTest: (String) -> Unit
    ) {
        var keyDraft by remember(provider) { mutableStateOf(keyStore.read(provider).orEmpty()) }
        LaunchedEffect(provider, keyDraft) {
            delay(700)
            keyStore.save(provider, keyDraft.trim())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = keyDraft,
                onValueChange = { keyDraft = it },
                label = { Text("${provider.displayName} API key") },
                visualTransformation = PasswordVisualTransformation(),
                trailingIcon = { Icon(Icons.Outlined.Key, null) },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            ProviderTestButton(
                state = status,
                testing = testing,
                language = settings.language,
                onClick = { onTest(keyDraft) }
            )
        }
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
                                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
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
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth(0.9f).heightIn(max = 650.dp)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(tr(settings.language, "Target calendar", "Zielkalender"), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
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
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                        Text(tr(settings.language, "Cancel", "Abbrechen"))
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
    private fun CalendarLocationAssignmentDialog(
        settings: AppSettings,
        calendars: List<CalendarInfo>,
        locationName: String,
        address: String,
        onDismiss: () -> Unit,
        onApply: (Set<Long>) -> Unit
    ) {
        val initial = settings.calendarStartLocations
            .filter { it.substringAfter("|", "").trim() == address }
            .mapNotNull { it.substringBefore("|").toLongOrNull() }
            .toSet()
        var selected by remember(address) { mutableStateOf(initial) }
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth(0.9f).heightIn(max = 650.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        tr(settings.language, "Calendars for $locationName", "Kalender für $locationName"),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        tr(
                            settings.language,
                            "Appointments in selected calendars always start from this location during automatic processing.",
                            "Termine in ausgewählten Kalendern starten bei der automatischen Verarbeitung immer von diesem Standort."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    if (calendars.isEmpty()) {
                        Text(tr(settings.language, "No source calendars selected.", "Keine Quellkalender ausgewählt."))
                    } else {
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                            calendars.forEach { calendar ->
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        selected = if (calendar.id in selected) selected - calendar.id else selected + calendar.id
                                    }.padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = calendar.id in selected,
                                        onCheckedChange = { checked ->
                                            selected = if (checked) selected + calendar.id else selected - calendar.id
                                        }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(calendar.name, fontWeight = FontWeight.Medium)
                                        Text(calendar.accountName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
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
    private fun AddExclusionDialog(
        settings: AppSettings,
        onDismiss: () -> Unit,
        onAdd: (String) -> Unit
    ) {
        var mode by remember { mutableStateOf("ignore_case") }
        var value by remember { mutableStateOf("") }
        val needsValue = mode == "exact" || mode == "ignore_case" || mode == "regex"
        val regexValid = mode != "regex" || runCatching { Regex(value) }.isSuccess
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth(0.9f)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tr(settings.language, "Add excluded location", "Ausschlussadresse hinzufügen"), style = MaterialTheme.typography.titleLarge)
                    listOf(
                        "exact" to tr(settings.language, "Exact text", "Exakter Text"),
                        "ignore_case" to tr(settings.language, "Text, ignore upper/lower case", "Text, Groß-/Kleinschreibung ignorieren"),
                        "url" to tr(settings.language, "Any web link", "Beliebiger Web-Link"),
                        "phone" to tr(settings.language, "Any phone number", "Beliebige Telefonnummer"),
                        "regex" to tr(settings.language, "Regular expression", "Regulärer Ausdruck")
                    ).forEach { (id, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { mode = id }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = mode == id, onClick = { mode = id })
                            Spacer(Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                    if (needsValue) {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { value = it },
                            label = {
                                Text(
                                    if (mode == "regex")
                                        tr(settings.language, "Regular expression", "Regulärer Ausdruck")
                                    else
                                        tr(settings.language, "Location text", "Ortstext")
                                )
                            },
                            placeholder = {
                                Text(
                                    if (mode == "regex")
                                        tr(settings.language, "e.g. (?i)zoom|teams|meet\\.", "z. B. (?i)zoom|teams|meet\\.")
                                    else
                                        tr(settings.language, "e.g. online or Zoom", "z. B. online oder Zoom")
                                )
                            },
                            isError = mode == "regex" && value.isNotBlank() && !regexValid,
                            supportingText = if (mode == "regex") {
                                {
                                    Text(
                                        if (regexValid)
                                            tr(settings.language, "Matches the complete appointment location using Kotlin regular expressions.", "Prüft den vollständigen Terminort mit Kotlin-Regulären-Ausdrücken.")
                                        else
                                            tr(settings.language, "Invalid regular expression.", "Ungültiger regulärer Ausdruck.")
                                    )
                                }
                            } else null,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text(tr(settings.language, "Cancel", "Abbrechen")) }
                        Button(
                            onClick = { onAdd("$mode|${value.trim()}") },
                            enabled = (!needsValue || value.isNotBlank()) && regexValid
                        ) {
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
        onSelect: (String) -> Unit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (settings.homeAddress.isNotBlank()) {
                AssistChip(
                    onClick = { onSelect(settings.homeAddress) },
                    label = { Text(settings.homeName.ifBlank { "Standard" }) },
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
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
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
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
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
        val latestOnValid by rememberUpdatedState(onValid)
        val latestInitial by rememberUpdatedState(initialValue)
        LaunchedEffect(text) {
            delay(450)
            val parsed = text.toIntOrNull()
            if (parsed != null && parsed != latestInitial) latestOnValid(parsed)
        }
        LaunchedEffect(initialValue) {
            if (text.toIntOrNull() != initialValue) text = initialValue.toString()
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
        RoutingProvider.TOMTOM -> tr(language, "Recommended. Strong live and historical traffic routing. Free evaluation exists, paid usage can apply.", "Empfohlen. Starkes Live- und historisches Verkehrs-Routing. Kostenlose Evaluation vorhanden, bezahlte Nutzung kann anfallen.")
    }

    private fun providerCredentialInfo(provider: RoutingProvider, language: AppLanguage): String = when (provider) {
        RoutingProvider.VALHALLA -> tr(
            language,
            "Required: no API key for the preset public Valhalla endpoint. A self-hosted endpoint may have its own access rules.",
            "Benötigt: kein API-Key für den voreingestellten öffentlichen Valhalla-Endpunkt. Ein selbst gehosteter Endpunkt kann eigene Zugangsregeln haben."
        )
        RoutingProvider.OPENROUTESERVICE -> tr(
            language,
            "Required: API key.",
            "Benötigt: API-Key."
        )
        RoutingProvider.OSRM -> tr(
            language,
            "Required: no API key for the preset public OSRM endpoint.",
            "Benötigt: kein API-Key für den voreingestellten öffentlichen OSRM-Endpunkt."
        )
        RoutingProvider.GRAPHHOPPER -> tr(
            language,
            "Required: API key.",
            "Benötigt: API-Key."
        )
        RoutingProvider.GOOGLE -> tr(
            language,
            "Required: API key with Routes API enabled. No OAuth client ID or client secret is used.",
            "Benötigt: API-Key mit aktivierter Routes API. OAuth-Client-ID und Client-Secret werden nicht verwendet."
        )
        RoutingProvider.HERE -> tr(
            language,
            "Required: API key. App ID is not required and is not sent by this app.",
            "Benötigt: API-Key. App ID wird nicht benötigt und von dieser App nicht übertragen."
        )
        RoutingProvider.TOMTOM -> tr(
            language,
            "Required: API key.",
            "Benötigt: API-Key."
        )
    }

    private fun providerLimitInfo(provider: RoutingProvider, language: AppLanguage): String = when (provider) {
        RoutingProvider.VALHALLA -> tr(language, "Public demo: fair use, about 1 request/second per user. Self-host for heavy use.", "Öffentlicher Demo-Server: Fair Use, etwa 1 Anfrage/Sekunde pro Nutzer. Für hohe Nutzung selbst hosten.")
        RoutingProvider.OPENROUTESERVICE -> tr(language, "Free Standard plan: 2,000 Directions requests/day. The value is editable if the provider changes its quota.", "Kostenloser Standard-Tarif: 2.000 Directions-Anfragen/Tag. Der Wert bleibt editierbar, falls der Anbieter sein Kontingent ändert.")
        RoutingProvider.OSRM -> tr(language, "Public OSRM is fair-use infrastructure without an SLA. Keep the local cap conservative or self-host.", "Öffentliches OSRM ist Fair-Use-Infrastruktur ohne SLA. Lokales Limit niedrig halten oder selbst hosten.")
        RoutingProvider.GRAPHHOPPER -> tr(language, "Free plan is preset to 500 credits/day; a normal two-point route costs one credit. Check your account if the plan changes.", "Der Free-Tarif ist auf 500 Credits/Tag voreingestellt; eine normale Route mit zwei Punkten kostet einen Credit. Prüfe dein Konto, falls sich der Tarif ändert.")
        RoutingProvider.GOOGLE -> tr(language, "Compute Routes Pro currently has a 5,000-event monthly free usage cap. This app presets that local monthly cap.", "Compute Routes Pro hat derzeit ein kostenloses Monatskontingent von 5.000 Events. Die App setzt dieses lokale Monatslimit vorein.")
        RoutingProvider.HERE -> tr(language, "HERE Limited Plan is preset to its 1,000 daily request limit. Base/PAYG accounts may have different terms.", "Der HERE Limited Plan ist auf sein Limit von 1.000 Anfragen/Tag voreingestellt. Base/PAYG-Konten können andere Bedingungen haben.")
        RoutingProvider.TOMTOM -> tr(language, "The local default is 2,500 requests/day for the free evaluation. Keep this editable because TomTom plan limits can change.", "Das lokale Standardlimit für die kostenlose Evaluation beträgt 2.500 Anfragen/Tag. Der Wert bleibt editierbar, da TomTom Tariflimits ändern kann.")
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
