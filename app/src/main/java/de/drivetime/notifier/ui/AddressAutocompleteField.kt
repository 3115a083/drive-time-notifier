package de.drivetime.notifier.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.drivetime.notifier.data.AppSettings
import de.drivetime.notifier.model.AddressSuggestion
import de.drivetime.notifier.routing.PhotonSearchService
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
    val context = LocalContext.current
    var suggestions by remember { mutableStateOf<List<AddressSuggestion>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var userEdited by remember { mutableStateOf(false) }

    LaunchedEffect(value, focused, userEdited, settings.language, settings.photonBaseUrl) {
        suggestions = emptyList()
        searching = false
        if (!focused || !userEdited || value.trim().length < 3) return@LaunchedEffect

        delay(500)
        searching = true
        suggestions = withTimeoutOrNull(8_000) {
            runCatching {
                PhotonSearchService(settings.photonBaseUrl, context.packageName)
                    .suggest(value.trim(), settings.language.id)
            }.getOrDefault(emptyList())
        }.orEmpty()
        searching = false
    }

    Column(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                userEdited = true
                onValueChange(it)
            },
            label = { Text(label) },
            leadingIcon = leadingIcon,
            trailingIcon = {
                when {
                    searching -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    value.isNotEmpty() -> IconButton(onClick = {
                        userEdited = true
                        suggestions = emptyList()
                        onValueChange("")
                    }) {
                        Icon(Icons.Outlined.Close, contentDescription = tr(settings.language, "Clear", "Löschen"))
                    }
                }
            },
            supportingText = supportingText?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth().onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) {
                    suggestions = emptyList()
                    userEdited = false
                }
            },
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
                                    userEdited = false
                                    suggestions = emptyList()
                                    onValueChange(suggestion.label)
                                }
                                .padding(horizontal = 16.dp, vertical = 13.dp)
                        ) {
                            Icon(
                                Icons.Outlined.LocationOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(suggestion.label, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (index != suggestions.lastIndex) {
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
    }
}
