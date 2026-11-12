package de.drivetime.notifier.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import de.drivetime.notifier.data.*
import de.drivetime.notifier.network.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class EndpointEditorUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun multiHeadersAutosaveRemoveAndRestoreIncompleteDraftsWithoutSaveButtons() {
        val endpoint="https://editor-fixture.invalid/private"
        val restoration=StateRestorationTester(compose)
        EndpointSecurity.saveHeaders(endpoint,emptyList())
        try {
            restoration.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { EndpointAuthEditor(endpoint,AppLanguage.GERMAN) } } }
            compose.onNodeWithText("Authentifizierung speichern").assertDoesNotExist()
            compose.onNodeWithText("Header hinzufügen").performClick()
            compose.onAllNodes(hasSetTextAction() and hasText("Header-Name"))[0].performTextInput("P-Access-Token-Id")
            compose.onAllNodes(hasSetTextAction() and hasText("Header-Wert"))[0].performTextInput("fixture-id")
            compose.runOnIdle { assertEquals(1,EndpointSecurity.headers(endpoint)!!.size) }
            compose.onNodeWithText("Header hinzufügen").performScrollTo().performClick()
            compose.onAllNodes(hasSetTextAction() and hasText("Header-Name"))[1].performScrollTo().performTextInput("P-Access-Token")
            compose.onAllNodes(hasSetTextAction() and hasText("Header-Wert"))[1].performScrollTo().performTextInput("fixture-token")
            compose.runOnIdle { assertEquals(2,EndpointSecurity.headers(endpoint)!!.size) }
            compose.onAllNodes(hasSetTextAction() and hasText("Header-Wert"))[1].performTextClearance()
            restoration.emulateSavedInstanceStateRestore()
            compose.runOnIdle { assertEquals("fixture-token",EndpointSecurity.headers(endpoint)!!.last().value) }
            compose.onAllNodesWithContentDescription("Header entfernen")[1].performScrollTo().performClick()
            compose.runOnIdle { assertEquals(1,EndpointSecurity.headers(endpoint)!!.size) }
            compose.onAllNodesWithContentDescription("Header entfernen")[0].performScrollTo().performClick()
            compose.runOnIdle { assertFalse(EndpointSecurity.configured(endpoint)) }
        } finally { EndpointSecurity.saveHeaders(endpoint,emptyList()) }
    }
    @Test fun endpointAutosaveKeepsItsEditorIdentityAndEmptyDraftAcrossOldEchoAndCollapse() {
        var settings by mutableStateOf(AppSettings(language=AppLanguage.GERMAN,photonBaseUrl="https://original.invalid",photonEndpoints=listOf("https://original.invalid")))
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { MapServiceLists(settings) { settings=it } } } }
        compose.onNodeWithContentDescription("Einstellungen für original.invalid").performClick()
        val field=compose.onNode(hasSetTextAction() and hasText("HTTPS-Endpunkt"))
        field.performTextReplacement("https://replacement.invalid/api")
        compose.runOnIdle { assertEquals("https://replacement.invalid/api",settings.photonBaseUrl) }
        field.performTextReplacement("https://replacement.invalid/api/v2")
        compose.runOnIdle { assertEquals("https://replacement.invalid/api/v2",settings.photonBaseUrl) }
        field.performTextClearance()
        compose.runOnIdle { settings=settings.copy(photonBaseUrl="https://original.invalid",photonEndpoints=listOf("https://original.invalid")) }
        field.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString("")))
        compose.onNodeWithContentDescription("Einstellungen für original.invalid").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Einstellungen für original.invalid").performClick()
        compose.onNode(hasSetTextAction() and hasText("HTTPS-Endpunkt")).assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString("")))
        compose.onNodeWithText("Endpunkt speichern").assertDoesNotExist()
    }
}
