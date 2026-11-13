package de.drivetime.notifier.ui

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.util.MapTileIndex

class PlannerTilesTest {
    @Test fun plannerTileRoundTripsThroughTheConfiguredAndroidCache() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Configuration.getInstance().osmdroidTileCache
        assertTrue(directory.isDirectory && directory.canWrite())
        assertTrue(directory.canonicalPath.startsWith(context.cacheDir.canonicalPath + "/"))
        val index = MapTileIndex.getTileIndex(17, 1234, 4321)
        assertEquals("https://tile.openstreetmap.org/17/1234/4321.png", plannerTiles.getTileURLString(index))
        val pixels = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream()
        pixels.eraseColor(android.graphics.Color.BLUE)
        pixels.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        pixels.recycle()
        val cache = SqlTileWriter()
        try {
            cache.saveFile(plannerTiles, index, bytes.toByteArray().inputStream(), System.currentTimeMillis() + 86400000)
            assertNotNull(cache.loadTile(plannerTiles, index))
        } finally {
            cache.remove(plannerTiles, index)
        }
    }
}
