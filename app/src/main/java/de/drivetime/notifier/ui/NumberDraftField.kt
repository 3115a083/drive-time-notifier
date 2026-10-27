package de.drivetime.notifier.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.delay

@Composable
fun NumberDraftField(initialValue: Int, label: String, minimum: Int = 0, maximum: Int = Int.MAX_VALUE,
    onValid: (Int) -> Unit) {
    val draft = rememberTextDraft(initialValue.toString())
    val latestOnValid by rememberUpdatedState(onValid)
    val latestInitial by rememberUpdatedState(initialValue)
    val focus = LocalFocusManager.current
    val parsed = draft.value.number(minimum, maximum)
    // Persistence never writes back into the draft, including blank/invalid intermediate input.
    LaunchedEffect(draft.text, draft.edited, minimum, maximum) {
        if (!draft.edited) return@LaunchedEffect
        delay(450)
        draft.value.number(minimum, maximum)?.takeIf { it != latestInitial }?.let(latestOnValid)
    }
    OutlinedTextField(value = draft.text, onValueChange = draft::edit,
        label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        isError = draft.edited && parsed == null,
        supportingText = if (draft.edited && parsed == null) ({ Text("$minimum–$maximum") }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            parsed?.takeIf { it != latestInitial }?.let(latestOnValid)
            focus.clearFocus()
        }))
}
