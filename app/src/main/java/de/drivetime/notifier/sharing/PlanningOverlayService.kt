package de.drivetime.notifier.sharing

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.*
import androidx.savedstate.*
import de.drivetime.notifier.MainActivity
import de.drivetime.notifier.data.*
import de.drivetime.notifier.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

/** A user-initiated system overlay using exactly the app's Compose theme. */
class PlanningOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var manager: WindowManager
    private var panel: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var origin by mutableStateOf("")
    private var destination by mutableStateOf("")
    private var locating by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)
    private var pendingField: String? = null
    private var requestId: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val expiry = Runnable { stopSelf() }

    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(WindowManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("planning_overlay", "Planning popup", NotificationManager.IMPORTANCE_LOW))
        val close = PendingIntent.getService(this, 0, Intent(this, javaClass).setAction("close"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "planning_overlay")
            .setSmallIcon(android.R.drawable.ic_dialog_map).setContentTitle("Drive Time Notifier")
            .setContentText("Planning popup / Planungs-Popup").setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Close / Schließen", close).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(61, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(61, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "close") { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == "location") {
            if (panel == null) { stopSelf(); return START_NOT_STICKY }
            if (intent.getStringExtra("request_id") != requestId) return START_NOT_STICKY
            locating = false
            val value = intent.getStringExtra("coordinates")
            if (value != null && SharedDestination.coordinates(value) != null) {
                if (pendingField == "origin") origin = value else destination = value
                message = null
            } else message = intent.getStringExtra("error")?.take(240)
            pendingField = null; requestId = null
            return START_NOT_STICKY
        }
        val shared = intent?.getStringExtra("destination")?.takeIf { it.isNotBlank() && it.length <= 8192 }
        if (shared == null || !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        scope.launch {
            val settings = SettingsStore(this@PlanningOverlayService).flow.first()
            if (!settings.sharePopupEnabled) { stopSelf(); return@launch }
            try { showPanel(settings, shared) } catch (e: Exception) {
                if (e is CancellationException) throw e
                openPlanner(shared, null, null, null)
            }
        }
        return START_NOT_STICKY
    }
    private fun openPlanner(target: String, start: String?, arrival: LocalDateTime?, buffer: Int?) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("destination", target)
            start?.let { putExtra("origin", it) }
            arrival?.let { putExtra("datetime", it.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))) }
            buffer?.let { putExtra("share_buffer_minutes", it) }
        })
        stopSelf()
    }
    private fun showPanel(settings: AppSettings, shared: String) {
        removePanel()
        origin = settings.homeAddress; destination = shared; message = null; locating = false
        val lifecycleOwner = OverlayOwner().also { owner = it }
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { DriveTimeTheme(settings.appearance, settings.palette) { PlanningPanel(settings) } }
        }
        val density = resources.displayMetrics.density
        val width = minOf(resources.displayMetrics.widthPixels - (24 * density).toInt(), (440 * density).toInt())
        val height = minOf(resources.displayMetrics.heightPixels - (80 * density).toInt(), (660 * density).toInt())
        val params = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.CENTER; softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        panel = view
        manager.addView(view, params)
        lifecycleOwner.start()
        handler.removeCallbacks(expiry); handler.postDelayed(expiry, 15 * 60 * 1000L)
    }
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PlanningPanel(settings: AppSettings) {
        fun label(en: String, de: String) = tr(settings.language, en, de)
        var arrival by remember { mutableStateOf(LocalDateTime.now().plusHours(1).withSecond(0).withNano(0)) }
        var useBuffer by remember { mutableStateOf(settings.bufferMinutes > 0 || settings.dynamicBufferEnabled) }
        var minutes by remember { mutableStateOf(settings.bufferMinutes.toString()) }
        var picker by remember { mutableStateOf<String?>(null) }
        val palette = paletteSpec(settings.palette)
        Surface(modifier = Modifier.fillMaxSize(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
            Column {
                Row(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(palette.heroStart, palette.heroEnd))).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Route, null, tint = androidx.compose.ui.graphics.Color.White)
                    Spacer(Modifier.width(12.dp))
                    Text(label("Plan your drive", "Fahrt planen"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = androidx.compose.ui.graphics.Color.White)
                    IconButton(onClick = { stopSelf() }) { Icon(Icons.Outlined.Close, label("Close", "Schließen"), tint = androidx.compose.ui.graphics.Color.White) }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (picker == "date") {
                        val date = rememberDatePickerState(initialSelectedDateMillis = arrival.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                        DatePicker(date, showModeToggle = false)
                        Button(onClick = { date.selectedDateMillis?.let { arrival = LocalDateTime.of(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate(), arrival.toLocalTime()) }; picker = null }, modifier = Modifier.fillMaxWidth()) { Text(label("Done", "Fertig")) }
                        TextButton(onClick = { picker = null }) { Text(label("Cancel", "Abbrechen")) }
                    } else if (picker == "time") {
                        val time = rememberTimePickerState(arrival.hour, arrival.minute, true)
                        TimeInput(time)
                        Button(onClick = { arrival = arrival.withHour(time.hour).withMinute(time.minute); picker = null }, modifier = Modifier.fillMaxWidth()) { Text(label("Done", "Fertig")) }
                        TextButton(onClick = { picker = null }) { Text(label("Cancel", "Abbrechen")) }
                    } else {
                        AddressAutocompleteField(settings, origin, { origin = it.take(8192) }, label("Start location", "Startort"))
                        SavedChips(settings, "origin") { origin = it }
                        AddressAutocompleteField(settings, destination, { destination = it.take(8192) }, label("Destination", "Ziel"))
                        SavedChips(settings, "destination") { destination = it }
                        Text(label("Arrival time", "Ankunftszeit"), style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { picker = "date" }, modifier = Modifier.weight(1f)) { Text(arrival.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))) }
                            OutlinedButton(onClick = { picker = "time" }, modifier = Modifier.weight(1f)) { Text(arrival.format(DateTimeFormatter.ofPattern("HH:mm"))) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label("Arrival buffer", "Ankunftspuffer"), Modifier.weight(1f))
                            Switch(useBuffer, { useBuffer = it })
                        }
                        if (useBuffer) OutlinedTextField(minutes, { if (it.length <= 3 && it.all(Char::isDigit)) minutes = it }, label = { Text(label("Minutes (0–180)", "Minuten (0–180)")) }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        if (useBuffer && settings.dynamicBufferEnabled) Text(label("Dynamic reserve is added using your settings.", "Der dynamische Zusatzpuffer folgt deinen Einstellungen."), style = MaterialTheme.typography.bodySmall)
                        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        Button(onClick = { openPlanner(destination.trim(), origin.trim(), arrival, if (useBuffer) minutes.toInt() else 0) }, enabled = origin.isNotBlank() && destination.isNotBlank() && (!useBuffer || minutes.toIntOrNull()?.let { it in 0..180 } == true), modifier = Modifier.fillMaxWidth()) {
                            Text(label("Open route planner", "Routenplanung öffnen"))
                        }
                    }
                }
            }
        }
    }
    @Composable
    private fun SavedChips(settings: AppSettings, field: String, select: (String) -> Unit) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(enabled = !locating, onClick = {
                pendingField = field; requestId = UUID.randomUUID().toString(); locating = true; message = null
                try { startActivity(Intent(this@PlanningOverlayService, OverlayLocationPermissionActivity::class.java)
                    .putExtra("request_id", requestId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (_: RuntimeException) { locating = false; message = tr(settings.language, "Open the app to grant location access.", "App öffnen, um den Standortzugriff freizugeben.") }
            }, label = { Text(tr(settings.language, if (locating) "Locating…" else "Current location", if (locating) "Standort wird ermittelt…" else "Aktueller Standort")) }, leadingIcon = { Icon(Icons.Outlined.MyLocation, null) })
            if (settings.homeAddress.isNotBlank()) AssistChip(onClick = { select(settings.homeAddress) }, label = { Text(settings.homeName.ifBlank { "Standard" }) })
            settings.savedPlaces.sorted().forEach { place ->
                val value = place.substringAfter('|', "").trim()
                if (value.isNotBlank()) AssistChip(onClick = { select(value) }, label = { Text(place.substringBefore('|').ifBlank { value }) })
            }
        }
    }
    private fun removePanel() {
        requestId = null; pendingField = null; locating = false
        panel?.let { if (it.isAttachedToWindow) manager.removeViewImmediate(it); it.disposeComposition() }; panel = null
        owner?.close(); owner = null
    }
    override fun onDestroy() {
        handler.removeCallbacks(expiry); removePanel(); scope.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
}

private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
    override val viewModelStore = ViewModelStore()
    init { controller.performAttach(); controller.performRestore(null); registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE) }
    fun start() { registry.handleLifecycleEvent(Lifecycle.Event.ON_START); registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
    fun close() { registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY); viewModelStore.clear() }
}
