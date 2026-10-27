package de.drivetime.notifier.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import kotlin.reflect.KProperty

/** Once edited, the user owns this draft until an explicit reset or a new editing session. */
data class DraftValue(val text: String, val edited: Boolean = false) {
    fun edit(text: String) = DraftValue(text, true)
    fun receive(source: String) = if (edited) this else DraftValue(source)
    fun number(minimum: Int, maximum: Int): Int? = text.toIntOrNull()?.takeIf { it in minimum..maximum }
}

@Stable
class TextDraftState(initial: DraftValue) {
    var value by mutableStateOf(initial)
        private set
    val text get() = value.text
    val edited get() = value.edited
    fun edit(text: String) { value = value.edit(text) }
    fun receive(source: String) { value = value.receive(source) }
    fun reset(source: String) { value = DraftValue(source) }
    operator fun getValue(owner: Any?, property: KProperty<*>) = text
    operator fun setValue(owner: Any?, property: KProperty<*>, text: String) = edit(text)

    companion object {
        val Saver = listSaver<TextDraftState, Any>(
            save = { listOf(it.text, it.edited) },
            restore = { TextDraftState(DraftValue(it[0] as String, it[1] as Boolean)) }
        )
    }
}

@Composable
fun rememberTextDraft(source: String, identity: Any = Unit): TextDraftState {
    val draft = rememberSaveable(identity, saver = TextDraftState.Saver) { TextDraftState(DraftValue(source)) }
    LaunchedEffect(source) { draft.receive(source) }
    return draft
}

/** A delayed result must not replace any edit made after its request, even after undoing. */
fun fieldRequestStillCurrent(requestText: String, requestRevision: Long, currentText: String, currentRevision: Long) =
    requestText == currentText && requestRevision == currentRevision
