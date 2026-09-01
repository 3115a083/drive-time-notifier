package de.drivetime.notifier.routing

import org.osmdroid.util.GeoPoint
import kotlin.math.roundToInt

object PolylineEncoder {
    fun encode(points: List<GeoPoint>): String {
        var prevLat = 0
        var prevLng = 0
        val out = StringBuilder()
        for (point in points) {
            val lat = (point.latitude * 1e5).roundToInt()
            val lng = (point.longitude * 1e5).roundToInt()
            encodeValue(lat - prevLat, out)
            encodeValue(lng - prevLng, out)
            prevLat = lat
            prevLng = lng
        }
        return out.toString()
    }

    private fun encodeValue(value: Int, out: StringBuilder) {
        var v = if (value < 0) value.shl(1).inv() else value.shl(1)
        while (v >= 0x20) {
            out.append(((0x20 or (v and 0x1f)) + 63).toChar())
            v = v shr 5
        }
        out.append((v + 63).toChar())
    }
}
