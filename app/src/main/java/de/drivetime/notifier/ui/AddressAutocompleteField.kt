package de.drivetime.notifier.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.drivetime.notifier.data.AppSettings
import de.drivetime.notifier.model.AddressSuggestion
import de.drivetime.notifier.routing.RoutingServiceFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun AddressAutocompleteField(
    settings: AppSettings,
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    leadingIcon: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    supportingText: String? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var suggestions by remember { mutableStateOf<List<AddressSuggestion>>(emptyList()) }
    var acceptedValue by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(value, settings.routingProvider, settings.language) {
        suggestions = emptyList()
        if (value.trim().length < 3 || value == acceptedValue) return@LaunchedEffect
        delay(550)
        searching = true
        suggestions = withTimeoutOrNull(8_000) {
            runCatching {
                RoutingServiceFactory.addressSearch(context, settings)
                    .suggest(value.trim(), settings.language.id)
            }.getOrDefault(emptyList())
        }.orEmpty()
        searching = false
    }

    Column(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                acceptedValue = null
                onValueChange(it)
            },
            label = { Text(label) },
            leadingIcon = leadingIcon,
            trailingIcon = {
                when {
                    searching -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    value.isNotEmpty() -> IconButton(onClick = {
                        acceptedValue = ""
                        suggestions = emptyList()
                        onValueChange("")
                    }) {
                        Icon(androidx.compose.material.icons.Icons.Outlined.Close, contentDescription = tr(settings.language, "Clear", "Löschen"))
                    }
                }
            },
            supportingText = supportingText?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        if (suggestions.isNotEmpty()) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 4.dp,
                shadowElevation = 5.dp,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) {
                Column {
                    suggestions.forEachIndexed { index, suggestion ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    acceptedValue = suggestion.label
                                    suggestions = emptyList()
                                    onValueChange(suggestion.label)
                                }
                                .padding(horizontal = 16.dp, vertical = 13.dp)
                        ) {
                            androidx.compose.material3.Icon(
                                androidx.compose.material.icons.Icons.Outlined.LocationOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(suggestion.label, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (index != suggestions.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}
