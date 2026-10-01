package de.drivetime.notifier.sharing

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import de.drivetime.notifier.MainActivity
import de.drivetime.notifier.data.*
import de.drivetime.notifier.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Validates incoming map targets before opening the planner or its opt-in overlay. */
class SharedTargetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val raw = when (intent.action) {
                Intent.ACTION_SEND -> if (intent.type == "text/plain") intent.getStringExtra(Intent.EXTRA_TEXT) else null
                Intent.ACTION_VIEW -> intent.dataString
                else -> null
            }.orEmpty()
            val destination = try { SharedLinkResolver.resolve(raw) } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            val settings = SettingsStore(this@SharedTargetActivity).flow.first()
            if (destination == null) {
                Toast.makeText(this@SharedTargetActivity, tr(settings.language, "This link has no usable address. Share an address or coordinates instead.", "Dieser Link enthält keine verwendbare Adresse. Bitte eine Adresse oder Koordinaten teilen."), Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            if (!settings.sharePopupEnabled || !android.provider.Settings.canDrawOverlays(this@SharedTargetActivity)) {
                openPlanner(destination, null, null, null)
            } else {
                try {
                    androidx.core.content.ContextCompat.startForegroundService(this@SharedTargetActivity,
                        Intent(this@SharedTargetActivity, PlanningOverlayService::class.java).putExtra("destination", destination))
                    finish()
                } catch (_: RuntimeException) {
                    openPlanner(destination, null, null, null)
                }
            }
        }
    }

    private fun openPlanner(destination: String, origin: String?, time: LocalDateTime?, buffer: Int?) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("destination", destination)
            origin?.let { putExtra("origin", it) }
            time?.let { putExtra("datetime", it.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))) }
            buffer?.let { putExtra("share_buffer_minutes", it.coerceIn(0, 180)) }
            // A fresh planner also works when the app was already open in its settings.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
        finish()
    }

}
