package de.drivetime.notifier.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import de.drivetime.notifier.automation.*
import de.drivetime.notifier.data.AppLanguage
import de.drivetime.notifier.security.PlanHookStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun PlanHookSettingsDialog(language: AppLanguage, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { PlanHookStore(context) }
    var config by remember { mutableStateOf(store.read()) }
    var secretsVisible by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(store.status()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = { if (!saving) onDismiss() }) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth().heightIn(max = 680.dp)) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr(language, "Forward saved plans", "Gespeicherte Planungen weitergeben"), style = MaterialTheme.typography.titleLarge)
                Text(tr(language, "Send selected fields after a drive has been created, updated or exported. Both outputs are optional.",
                    "Ausgewählte Felder nach Erstellung, Aktualisierung oder Export einer Fahrt senden. Beide Ausgaben sind optional."), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(language, "Broadcast intent", "Broadcast-Intent"), Modifier.weight(1f))
                    Switch(config.intentEnabled, { config = config.copy(intentEnabled = it) })
                }
                if (config.intentEnabled) {
                    OutlinedTextField(config.action, { config = config.copy(action = it.take(240)) },
                        label = { Text(tr(language, "Intent action", "Intent-Aktion")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(config.targetPackage, { config = config.copy(targetPackage = it.take(240)) },
                        label = { Text(tr(language, "Receiver package (Tasker preset)", "Empfängerpaket (Tasker voreingestellt)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(tr(language, "In Tasker use Event → System → Intent Received and the same action. Selected fields become variables; payload contains the JSON.",
                        "In Tasker Ereignis → System → Intent Received mit derselben Aktion wählen. Ausgewählte Felder werden Variablen; payload enthält das JSON."), style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(language, "HTTPS webhook", "HTTPS-Webhook"), Modifier.weight(1f))
                    Switch(config.webhookEnabled, { config = config.copy(webhookEnabled = it) })
                }
                if (config.webhookEnabled) {
                    val transformation = if (secretsVisible) VisualTransformation.None else PasswordVisualTransformation()
                    OutlinedTextField(config.webhookUrl, { config = config.copy(webhookUrl = it.take(4_096)) },
                        label = { Text(tr(language, "Webhook URL (HTTPS)", "Webhook-URL (HTTPS)")) }, visualTransformation = transformation,
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(config.bearerToken, { config = config.copy(bearerToken = it.take(4_096)) },
                        label = { Text(tr(language, "Bearer token (optional)", "Bearer-Token (optional)")) }, visualTransformation = transformation,
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { secretsVisible = !secretsVisible }) {
                        Text(tr(language, if (secretsVisible) "Hide URL and token" else "Show URL and token",
                            if (secretsVisible) "URL und Token verbergen" else "URL und Token anzeigen"))
                    }
                    Text(tr(language, "JSON POST, no redirects. Home Assistant webhooks normally need only their secret URL. Failed network deliveries retry up to three times within two hours.",
                        "JSON-POST ohne Weiterleitungen. Home-Assistant-Webhooks benötigen normalerweise nur ihre geheime URL. Netzwerkfehler werden innerhalb von zwei Stunden bis zu dreimal versucht."), style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                Text(tr(language, "Fields to send", "Zu sendende Felder"), style = MaterialTheme.typography.titleMedium)
                PlanPayloadField.entries.forEach { field ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(field.id in config.fields, { selected -> config = config.copy(fields =
                            if (selected) config.fields + field.id else config.fields - field.id) })
                        Text(tr(language, field.english, field.german), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(tr(language, "Times use UTC ISO 8601 and Unix milliseconds. plan_id and revision_ms let receivers replace alarms and reject older updates. Delivery IDs and schema version are always included.",
                    "Zeiten verwenden UTC nach ISO 8601 und Unix-Millisekunden. plan_id und revision_ms ermöglichen das Ersetzen von Alarmen und Verwerfen alter Updates. Zustellungs-ID und Schemaversion werden immer mitgesendet."), style = MaterialTheme.typography.bodySmall)
                Text(tr(language, "Example payload", "Beispielinhalt"), style = MaterialTheme.typography.titleMedium)
                val example = SavedPlan("Start address", "Destination address", 1_800_000_000_000, 1_800_003_600_000,
                    1_800_004_500_000, 42, 7, 1_799_999_000_000, "updated", null, "Drive")
                Text(JSONObject(PlanPayload.values(example, config.fields, "example-delivery")).toString(2), style = MaterialTheme.typography.bodySmall)
                if (status.isNotBlank()) Text(tr(language, "Last delivery: ", "Letzte Zustellung: ") + status, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { status = store.status() }) { Text(tr(language, "Refresh status", "Status aktualisieren")) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !saving) { Text(tr(language, "Cancel", "Abbrechen")) }
                    Button(enabled = !saving, onClick = {
                        if (!config.valid()) {
                            error = tr(language, "Check the action, package and HTTPS URL. Tokens must not contain line breaks.",
                                "Prüfe Aktion, Paket und HTTPS-URL. Tokens dürfen keine Zeilenumbrüche enthalten.")
                        } else {
                            saving = true
                            scope.launch {
                                try { withContext(Dispatchers.IO) { store.save(config) }; onDismiss() }
                                catch (_: Exception) { error = tr(language, "Could not save forwarding settings.", "Weitergabe-Einstellungen konnten nicht gespeichert werden.") }
                                finally { saving = false }
                            }
                        }
                    }) { Text(tr(language, "Save", "Speichern")) }
                }
            }
        }
    }
}
