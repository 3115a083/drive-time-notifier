package de.drivetime.notifier.ui

import org.osmdroid.tileprovider.tilesource.XYTileSource

internal val plannerTiles = XYTileSource(
    "Mapnik", 0, 19, 256, ".png",
    arrayOf("https://tile.openstreetmap.org/")
)

internal fun createPlannerMap(context: android.content.Context) =
    org.osmdroid.views.MapView(context).apply {
        setTileSource(plannerTiles)
        setUseDataConnection(true)
        setMultiTouchControls(true)
        controller.setZoom(12.0)
        onResume()
    }
