package de.drivetime.notifier.sharing

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import de.drivetime.notifier.MainActivity
import de.drivetime.notifier.data.*
import de.drivetime.notifier.ui.tr
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** A real system overlay. No persistent job, background location or exported service. */
class PlanningOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var manager: WindowManager
    private var panel: View? = null
    private var picker: Dialog? = null
    private var pendingLocationField: EditText? = null
    private var language = AppLanguage.GERMAN
    private val handler = Handler(Looper.getMainLooper())
    private val expiry = Runnable { stopSelf() }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(WindowManager::class.java)
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("planning_overlay", "Planning popup", NotificationManager.IMPORTANCE_LOW))
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
            intent.getStringExtra("coordinates")?.takeIf { it.length <= 100 }?.let { value -> pendingLocationField?.setText(value) }
            return START_NOT_STICKY
        }
        val destination = intent?.getStringExtra("destination")?.takeIf { it.isNotBlank() && it.length <= 8192 }
        if (destination == null || !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        scope.launch {
            val settings = SettingsStore(this@PlanningOverlayService).flow.first()
            if (!settings.sharePopupEnabled) { stopSelf(); return@launch }
            language = settings.language
            runCatching { showPanel(settings, destination) }.onFailure {
                startActivity(Intent(this@PlanningOverlayService, MainActivity::class.java)
                    .putExtra("destination", destination).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun label(en: String, de: String) = tr(language, en, de)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun showPanel(settings: AppSettings, shared: String) {
        removePanel()
        val themed = ContextThemeWrapper(this, android.R.style.Theme_Material_Light)
        val content = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        fun text(value: String) { content.addView(TextView(themed).apply { text = value; setTextColor(android.graphics.Color.BLACK) }) }
        fun button(value: String, action: () -> Unit): Button = Button(themed).apply { text = value; setOnClickListener { action() }; content.addView(this) }
        fun address(title: String, initial: String): EditText {
            text(title)
            val field = EditText(themed).apply { setText(initial); maxLines = 3; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; filters = arrayOf(android.text.InputFilter.LengthFilter(8192)) }
            content.addView(field)
            val choices = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL }
            fun chip(name: String, action: () -> Unit) { choices.addView(Button(themed).apply { text = name; setOnClickListener { action() } }) }
            chip(label("Current location", "Aktueller Standort")) { pendingLocationField = field; locate(field) }
            if (settings.homeAddress.isNotBlank()) chip(settings.homeName.ifBlank { "Standard" }) { field.setText(settings.homeAddress) }
            settings.savedPlaces.sorted().forEach { place ->
                val value = place.substringAfter('|', "").trim()
                if (value.isNotBlank()) chip(place.substringBefore('|').ifBlank { value }) { field.setText(value) }
            }
            content.addView(HorizontalScrollView(themed).apply { addView(choices) })
            return field
        }
        text(label("Plan shared destination", "Geteiltes Ziel planen"))
        val origin = address(label("Start location", "Startort"), settings.homeAddress)
        val destination = address(label("Destination", "Ziel"), shared)
        var arrival = LocalDateTime.now().plusHours(1).withSecond(0).withNano(0)
        lateinit var dateButton: Button
        lateinit var timeButton: Button
        dateButton = button(arrival.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))) {
            showPicker(DatePickerDialog(themed, { _, y, m, d ->
                arrival = arrival.withDayOfMonth(1).withYear(y).withMonth(m + 1).withDayOfMonth(d)
                dateButton.text = arrival.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
            }, arrival.year, arrival.monthValue - 1, arrival.dayOfMonth))
        }
        timeButton = button(arrival.format(DateTimeFormatter.ofPattern("HH:mm"))) {
            showPicker(TimePickerDialog(themed, { _, h, m ->
                arrival = arrival.withHour(h).withMinute(m)
                timeButton.text = arrival.format(DateTimeFormatter.ofPattern("HH:mm"))
            }, arrival.hour, arrival.minute, true))
        }
        val buffer = CheckBox(themed).apply { text = label("Arrival buffer", "Ankunftspuffer"); isChecked = settings.bufferMinutes > 0 }
        content.addView(buffer)
        val minutes = EditText(themed).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(settings.bufferMinutes.toString()); hint = label("Minutes (0–180)", "Minuten (0–180)"); filters = arrayOf(android.text.InputFilter.LengthFilter(3)); visibility = if (buffer.isChecked) View.VISIBLE else View.GONE }
        content.addView(minutes)
        buffer.setOnCheckedChangeListener { _, checked -> minutes.visibility = if (checked) View.VISIBLE else View.GONE }
        button(label("Open route planner", "Routenplanung öffnen")) {
            val amount = if (buffer.isChecked) minutes.text.toString().toIntOrNull() else 0
            if (origin.text.isBlank() || destination.text.isBlank() || amount == null || amount !in 0..180) {
                Toast.makeText(this, label("Enter start, destination and valid buffer.", "Start, Ziel und gültigen Puffer eingeben."), Toast.LENGTH_LONG).show()
            } else {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("origin", origin.text.toString().trim()); putExtra("destination", destination.text.toString().trim())
                    putExtra("datetime", arrival.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                    putExtra("share_buffer_minutes", amount)
                })
                stopSelf()
            }
        }
        button(label("Cancel", "Abbrechen")) { stopSelf() }
        val scroll = ScrollView(themed).apply { addView(content) }
        val width = minOf(resources.displayMetrics.widthPixels - dp(32), dp(440))
        val height = minOf(resources.displayMetrics.heightPixels - dp(100), dp(640))
        val params = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        scroll.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { stopSelf(); true } else false
        }
        manager.addView(scroll, params)
        panel = scroll
        handler.removeCallbacks(expiry)
        handler.postDelayed(expiry, 15 * 60 * 1000L)
    }

    private fun showPicker(dialog: Dialog) {
        picker?.dismiss()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        picker = dialog
        dialog.show()
    }

    private fun locate(field: EditText) {
        pendingLocationField = field
        startActivity(Intent(this, OverlayLocationPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun removePanel() {
        picker?.dismiss(); picker = null
        panel?.let { manager.removeView(it) }; panel = null
        pendingLocationField = null
    }
    override fun onDestroy() {
        handler.removeCallbacks(expiry)
        removePanel()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
