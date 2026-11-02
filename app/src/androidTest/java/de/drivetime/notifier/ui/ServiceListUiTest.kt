package de.drivetime.notifier.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import de.drivetime.notifier.data.AppLanguage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ServiceListUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun draggingAHandlePersistsTheDisplayedOrder() {
        var order by mutableStateOf(listOf("A","B","C"))
        compose.setContent {MaterialTheme {
            OrderedServiceList(order,AppLanguage.GERMAN,{it},{order=it}) {
                val draft=rememberTextDraft("initial")
                OutlinedTextField(draft.text,draft::edit,label={Text("Details $it")})
            }
        }}
        val first=compose.onNodeWithContentDescription("Verschieben: A")
        val second=compose.onNodeWithContentDescription("Verschieben: B")
        val delta=second.fetchSemanticsNode().boundsInRoot.center.y-first.fetchSemanticsNode().boundsInRoot.center.y
        first.performTouchInput {
            down(center)
            advanceEventTime(900)
            moveBy(Offset(0f,delta),delayMillis=300)
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle {assertEquals(listOf("B","A","C"),order)}
        compose.onNodeWithContentDescription("Einstellungen für A").performClick()
        compose.onNodeWithText("Details A").assertIsDisplayed()
        compose.onNodeWithText("Details B").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextReplacement("unsaved value")
        compose.onNodeWithContentDescription("Einstellungen für A").performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Einstellungen für A").performClick()
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("unsaved value")))
    }
}
