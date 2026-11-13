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
            createPlannerMap(ctx,source,transport).also { map=it }
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
