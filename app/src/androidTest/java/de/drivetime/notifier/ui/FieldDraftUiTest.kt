package de.drivetime.notifier.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FieldDraftUiTest {
    private fun SemanticsNodeInteraction.assertDraftText(text: String) =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text)))

    @get:Rule val compose = createComposeRule()

    @Test fun numberDraftKeepsBlankInvalidAndPastedTextThroughPausesAndStaleEchoes() {
        var source by mutableIntStateOf(15)
        var label by mutableStateOf("Ankunftspuffer")
        val submitted = mutableListOf<Int>()
        compose.setContent { MaterialTheme { NumberDraftField(source, label, 0, 180) { submitted += it } } }
        val field = compose.onNode(hasSetTextAction())
        field.performTextClearance()
        Thread.sleep(900)
        field.assertDraftText("")
        compose.runOnIdle { source = 25 }
        field.assertDraftText("")
        field.performTextInput("00030")
        compose.waitUntil(5_000) { submitted.contains(30) }
        compose.runOnIdle { source = 30; label = "Arrival buffer" }
        field.assertDraftText("00030")
        field.performTextReplacement("999999999999999999")
        Thread.sleep(900)
        field.assertDraftText("999999999999999999")
        compose.runOnIdle { assertEquals(listOf(30), submitted) }
    }

    @Test fun rawDraftAndSelectionSurviveRestorationAndUnrelatedRecomposition() {
        val restoration = StateRestorationTester(compose)
        var source by mutableStateOf("saved")
        restoration.setContent {
            val draft = rememberTextDraft(source)
            MaterialTheme { OutlinedTextField(value = draft.text, onValueChange = draft::edit) }
        }
        val field = compose.onNode(hasSetTextAction())
        field.performTextReplacement("my draft")
        field.performTextInputSelection(androidx.compose.ui.text.TextRange(2))
        compose.runOnIdle { source = "late background value" }
        field.performTextInput("X")
        field.assertDraftText("myX draft")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assertDraftText("myX draft")
    }
}
