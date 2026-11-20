package de.drivetime.notifier.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.osmdroid.tileprovider.modules.IFilesystemCache
import org.osmdroid.tileprovider.modules.TileDownloader
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.views.MapView
import java.io.ByteArrayOutputStream

class PlannerMapRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fittingBeforeLayoutWaitsAndKeepsOnlyTheLatestRoute() {
        val first = listOf(org.osmdroid.util.GeoPoint(48.10, 11.50), org.osmdroid.util.GeoPoint(48.15, 11.60))
        val latest = listOf(org.osmdroid.util.GeoPoint(52.50, 13.35), org.osmdroid.util.GeoPoint(52.55, 13.45))
        compose.runOnUiThread {
            val map = createPlannerMap(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext)
            // This regression is deterministic: request the fit before attach/measurement.
            map.setUseDataConnection(false)
            try {
                org.junit.Assert.assertEquals(0, map.width)
                fitPlannerRoute(map, first)
                fitPlannerRoute(map, latest)
                org.junit.Assert.assertEquals(12.0, map.zoomLevelDouble, 0.0)
                map.measure(android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY))
                map.layout(0, 0, 600, 400)
                org.junit.Assert.assertTrue(map.zoomLevelDouble.isFinite())
                org.junit.Assert.assertTrue(map.zoomLevelDouble > 9.0)
                org.junit.Assert.assertTrue(map.boundingBox.contains(latest[0]))
                org.junit.Assert.assertTrue(map.boundingBox.contains(latest[1]))
                org.junit.Assert.assertFalse(map.boundingBox.contains(first[0]))
                // Ordinary recomposition must not reset the user's zoom.
                map.controller.setZoom(10.0)
                fitPlannerRoute(map, latest)
                org.junit.Assert.assertEquals(10.0, map.zoomLevelDouble, 0.0)
            } finally { map.onPause(); map.onDetach() }
        }
    }

    @Test fun onlineTileBecomesVisibleEvenWhenTheDiskCacheIsEmpty() {
        val png = ByteArrayOutputStream().apply {
            val bitmap = Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.MAGENTA)
            bitmap.compress(Bitmap.CompressFormat.PNG,100,this)
            bitmap.recycle()
        }.toByteArray()
        val source = XYTileSource("render-fixture",0,19,256,".png",arrayOf("https://tiles.invalid/"))
        val transport = object : TileDownloader() {
            override fun downloadTile(index:Long, redirects:Int, url:String, cache:IFilesystemCache?, source:OnlineTileSourceBase) =
                source.getDrawable(png.inputStream())
        }
        lateinit var map:MapView
        compose.setContent { AndroidView(factory={ ctx ->
            createPlannerMap(ctx,source,transport).also {
                map=it
                fitPlannerRoute(it, listOf(org.osmdroid.util.GeoPoint(52.50,13.35), org.osmdroid.util.GeoPoint(52.55,13.45)))
            }
        },modifier=Modifier.size(220.dp), onRelease={ it.onPause();it.onDetach() }, update={}) }
        compose.waitUntil(15000) {
            var visible=false
            compose.runOnUiThread {
                if(map.width>0 && map.height>0) {
                    val frame=Bitmap.createBitmap(map.width,map.height,Bitmap.Config.ARGB_8888)
                    map.draw(Canvas(frame))
                    visible=frame.getPixel(map.width/2,map.height/2)==Color.MAGENTA
                    frame.recycle()
                }
            }
            visible
        }
    }
}
