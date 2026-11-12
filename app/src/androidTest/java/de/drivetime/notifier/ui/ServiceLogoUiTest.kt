package de.drivetime.notifier.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ServiceLogoUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun logosRenderInBothThemesAndValhallaHasNoSquareBackground() {
        var dark by mutableStateOf(false)
        val background=Color(0xFF443355)
        compose.setContent {
            MaterialTheme(colorScheme=if(dark) darkColorScheme() else lightColorScheme()) {
                Surface {
                    Column(Modifier.padding(16.dp).testTag("logo-gallery")) {
                        listOf("valhalla","osrm","openrouteservice","graphhopper","google","here","tomtom","photon","overpass").forEach { id ->
                            Row(Modifier.padding(4.dp)) {
                                Box(Modifier.background(background).testTag("logo-$id")) { ServiceImage(id) }
                                Spacer(Modifier.width(12.dp));Text(id)
                            }
                        }
                    }
                }
            }
        }
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val device=androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        for(theme in listOf("light","dark")) {
            compose.runOnIdle { dark=theme=="dark" }
            compose.waitForIdle()
            for(id in listOf("valhalla","here","osrm")) {
                val pixels=compose.onNodeWithTag("logo-$id").captureToImage().toPixelMap()
                assertEquals("$id top-left",background,pixels[0,0])
                assertEquals("$id top-right",background,pixels[pixels.width-1,0])
            }
            val file=java.io.File(context.getExternalFilesDir(null),"service-logos-$theme.png")
            file.outputStream().use { compose.onNodeWithTag("logo-gallery").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            device.executeShellCommand("cp ${file.absolutePath} /data/local/tmp/${file.name}")
        }
    }
}
