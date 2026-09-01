package de.drivetime.notifier.routing

import org.osmdroid.util.GeoPoint
import kotlin.math.pow

object PolylineDecoder {
    fun decode(encoded: String, precision: Int = 5): List<GeoPoint> {
        if (encoded.isBlank()) return emptyList()
        val points = ArrayList<GeoPoint>()
        var index = 0
        var lat = 0
        var lng = 0
        val factor = 10.0.pow(precision)
        while (index < encoded.length) {
            var result = 0
            var shift = 0
            var b: Int
            do {
                if (index >= encoded.length) return points
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            result = 0
            shift = 0
            do {
                if (index >= encoded.length) return points
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            points += GeoPoint(lat / factor, lng / factor)
        }
        return points
    }
}
