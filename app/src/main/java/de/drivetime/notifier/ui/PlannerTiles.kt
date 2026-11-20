package de.drivetime.notifier.ui

import androidx.core.view.doOnLayout
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

// Keep the same OSM source and download policy as the public release.
// A bare XYTileSource loses the concurrency, no-prefetch and user-agent policy.
internal val plannerTiles: OnlineTileSourceBase = TileSourceFactory.MAPNIK

internal fun createPlannerMap(
    context: android.content.Context,
    source: OnlineTileSourceBase = plannerTiles,
    transport: org.osmdroid.tileprovider.modules.TileDownloader? = null
) =
    org.osmdroid.views.MapView(context, PlannerTileProvider(context, source, transport)).apply {
        setTileSource(source)
        setUseDataConnection(true)
        setMultiTouchControls(true)
        controller.setZoom(12.0)
        onResume()
    }

/** A posted Runnable is not a layout barrier. Fitting a zero-sized view yields NaN zoom. */
internal fun fitPlannerRoute(map: MapView, points: List<GeoPoint>) {
    if (points.size < 2 || map.tag === points) return
    map.tag = points
    map.doOnLayout {
        // A newer route may have arrived while the first layout was pending.
        if (map.tag !== points) return@doOnLayout
        if (map.width <= 0 || map.height <= 0) {
            map.tag = null
            return@doOnLayout
        }
        val border = minOf(72, (minOf(map.width, map.height) - 1) / 2)
        map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), false, border)
    }
}
