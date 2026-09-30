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

/** A translucent Activity, requiring no system overlay or background location permission. */
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
            if (!settings.sharePopupEnabled) {
                openPlanner(destination, null, null, null)
            } else setContent {
                DriveTimeTheme(settings.appearance, settings.palette) { PlanningDialog(settings, destination) }
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

    @Composable
    private fun PlanningDialog(settings: AppSettings, sharedDestination: String) {
        var destination by rememberSaveable { mutableStateOf(sharedDestination) }
        var origin by rememberSaveable { mutableStateOf(settings.homeAddress) }
        var datetime by rememberSaveable { mutableStateOf(LocalDateTime.now().plusHours(1).withSecond(0).withNano(0).toString()) }
        var useBuffer by rememberSaveable { mutableStateOf(settings.bufferMinutes > 0) }
        var minutes by rememberSaveable { mutableStateOf(settings.bufferMinutes.toString()) }
        val time = LocalDateTime.parse(datetime)
        AlertDialog(
            onDismissRequest = { finish() },
            title = { Text(tr(settings.language, "Plan shared destination", "Geteiltes Ziel planen")) },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AddressAutocompleteField(settings, origin, { origin = it }, tr(settings.language, "Start location", "Startort"))
                    SavedChips(settings) { origin = it }
                    AddressAutocompleteField(settings, destination, { destination = it }, tr(settings.language, "Destination", "Ziel"))
                    SavedChips(settings) { destination = it }
                    Text(tr(settings.language, "Arrival time", "Ankunftszeit"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            DatePickerDialog(this@SharedTargetActivity, { _, y, m, d -> datetime = time.withDayOfMonth(1).withYear(y).withMonth(m + 1).withDayOfMonth(d).toString() }, time.year, time.monthValue - 1, time.dayOfMonth).show()
                        }) { Text(time.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))) }
                        OutlinedButton(onClick = {
                            TimePickerDialog(this@SharedTargetActivity, { _, h, m -> datetime = time.withHour(h).withMinute(m).toString() }, time.hour, time.minute, true).show()
                        }) { Text(time.format(DateTimeFormatter.ofPattern("HH:mm"))) }
                    }
                    Row { Checkbox(useBuffer, { useBuffer = it }); Text(tr(settings.language, "Arrival buffer", "Ankunftspuffer")) }
                    if (useBuffer) OutlinedTextField(minutes, { if (it.length <= 3 && it.all(Char::isDigit)) minutes = it }, label = { Text(tr(settings.language, "Minutes (0–180)", "Minuten (0–180)")) }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = origin.isNotBlank() && destination.isNotBlank() && (!useBuffer || minutes.toIntOrNull()?.let { it in 0..180 } == true),
                    onClick = { openPlanner(destination.trim(), origin.trim(), time, if (useBuffer) minutes.toInt() else 0) }
                ) { Text(tr(settings.language, "Open route planner", "Routenplanung öffnen")) }
            },
            dismissButton = { TextButton(onClick = { finish() }) { Text(tr(settings.language, "Cancel", "Abbrechen")) } }
        )
    }

    @Composable
    private fun SavedChips(settings: AppSettings, select: (String) -> Unit) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CurrentLocationChip(settings.language, select)
            if (settings.homeAddress.isNotBlank()) AssistChip(onClick = { select(settings.homeAddress) }, label = { Text(settings.homeName.ifBlank { "Standard" }) })
            settings.savedPlaces.sorted().forEach { place ->
                val address = place.substringAfter('|', "").trim()
                if (address.isNotBlank()) AssistChip(onClick = { select(address) }, label = { Text(place.substringBefore('|').ifBlank { address }) })
            }
        }
    }
}
