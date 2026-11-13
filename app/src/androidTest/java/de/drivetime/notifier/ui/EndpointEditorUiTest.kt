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
        var keyboard:androidx.compose.ui.platform.SoftwareKeyboardController?=null
        EndpointSecurity.saveHeaders(endpoint,emptyList())
        try {
            restoration.setContent { keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current; MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { EndpointAuthEditor(endpoint,AppLanguage.GERMAN) } } }
            compose.onNodeWithText("Authentifizierung speichern").assertDoesNotExist()
            compose.onNodeWithText("Header hinzufügen").performClick()
            compose.onAllNodes(hasSetTextAction() and hasText("Header-Name"))[0].performTextInput("P-Access-Token-Id")
            compose.onAllNodes(hasSetTextAction() and hasText("Header-Wert"))[0].performTextInput("fixture-id")
            compose.runOnIdle { assertEquals(1,EndpointSecurity.headers(endpoint)!!.size) }
            compose.onNodeWithText("Header hinzufügen").performScrollTo().performClick()
            compose.onAllNodes(hasSetTextAction() and hasText("Header-Name"))[1].performScrollTo().performTextInput("P-Access-Token")
            compose.onAllNodes(hasSetTextAction() and hasText("Header-Wert"))[1].performScrollTo().performTextInput("fixture-token")
            compose.runOnIdle { assertEquals(2,EndpointSecurity.headers(endpoint)!!.size) }
            val device=androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
            compose.runOnIdle { keyboard?.hide() }
            compose.waitForIdle()
            compose.onNodeWithText("Authentifizierungs-Header").performScrollTo()
            val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            val screenshot=java.io.File(context.getExternalFilesDir(null),"endpoint-headers.png")
            assertTrue(device.takeScreenshot(screenshot))
            device.executeShellCommand("cp ${screenshot.absolutePath} /data/local/tmp/${screenshot.name}")
            compose.onAllNodes(hasSetTextAction() and hasText("Header-Wert"))[1].performTextClearance()
            restoration.emulateSavedInstanceStateRestore()
            compose.onAllNodes(hasSetTextAction() and hasText("Header-Wert"))[1].assert(
                SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString("")))
            compose.runOnIdle { assertEquals("fixture-token",EndpointSecurity.headers(endpoint)!!.last().value) }
            compose.onAllNodesWithContentDescription("Header entfernen")[1].performScrollTo().performClick()
            compose.runOnIdle { assertEquals(1,EndpointSecurity.headers(endpoint)!!.size) }
            compose.onAllNodesWithContentDescription("Header entfernen")[0].performScrollTo().performClick()
            compose.runOnIdle { assertFalse(EndpointSecurity.configured(endpoint)) }
        } finally { EndpointSecurity.saveHeaders(endpoint,emptyList()) }
    }
    @Test fun unreadableCredentialsCanBeRemovedWithoutLeavingAStaleWarning() {
        val endpoint="https://corrupt-editor.invalid"
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("endpoint_headers",0).edit().putString(digest(endpoint),"corrupt").commit()
        try {
            compose.setContent { MaterialTheme { Column { EndpointAuthEditor(endpoint,AppLanguage.GERMAN) } } }
            compose.onNodeWithText("Gespeicherte Header entfernen").performClick()
            compose.onNodeWithText("Gespeicherte Header entfernen").assertDoesNotExist()
            compose.runOnIdle { assertFalse(EndpointSecurity.configured(endpoint)) }
        } finally {EndpointSecurity.saveHeaders(endpoint,emptyList())}
    }
    @Test fun newEndpointSavesHeadersOnlyWhenAdded() {
        val endpoint="https://new-endpoint.invalid"
        var settings by mutableStateOf(AppSettings(language=AppLanguage.GERMAN))
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { MapServiceLists(settings) { settings=it } } } }
        try {
            for(confirm in listOf(false,true)) {
                compose.onNodeWithText("Photon-Endpunkt hinzufügen").performScrollTo().performClick()
                compose.onNode(hasSetTextAction() and hasText("HTTPS-Endpunkt")).performTextReplacement(endpoint)
                compose.onNodeWithText("Header hinzufügen").performScrollTo().performClick()
                compose.onNodeWithText("Hinzufügen").assertIsNotEnabled()
                compose.onNode(hasSetTextAction() and hasText("Header-Name")).performScrollTo().performTextInput("X-Access")
                compose.onNode(hasSetTextAction() and hasText("Header-Wert")).performScrollTo().performTextInput("fixture-value")
                compose.runOnIdle { assertFalse(EndpointSecurity.configured(endpoint)) }
                compose.onNodeWithText(if(confirm) "Hinzufügen" else "Abbrechen").performClick()
                compose.runOnIdle {
                    assertEquals(confirm,endpoint in settings.photonEndpoints)
                    assertEquals(confirm,EndpointSecurity.configured(endpoint))
                }
            }
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
