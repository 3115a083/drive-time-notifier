package de.drivetime.notifier.routing

import org.osmdroid.util.GeoPoint
import kotlin.math.pow

object FlexiblePolylineDecoder {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val decodeTable = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
    }

    fun decode(encoded: String): List<GeoPoint> {
        var index = 0
        val version = decodeUnsigned(encoded, index).also { index = it.second }.first
        require(version == 1L) { "Unsupported flexible polyline version." }
        val header = decodeUnsigned(encoded, index).also { index = it.second }.first
        val precision = (header and 15).toInt()
        val thirdDim = ((header shr 4) and 7).toInt()
        val thirdPrecision = ((header shr 7) and 15).toInt()

        var lastLat = 0L
        var lastLng = 0L
        var lastZ = 0L
        val factor = 10.0.pow(precision)
        val points = mutableListOf<GeoPoint>()

        while (index < encoded.length) {
            val latRaw = decodeUnsigned(encoded, index); index = latRaw.second
            val lngRaw = decodeUnsigned(encoded, index); index = lngRaw.second
            lastLat += toSigned(latRaw.first)
            lastLng += toSigned(lngRaw.first)
            if (thirdDim != 0) {
                val zRaw = decodeUnsigned(encoded, index); index = zRaw.second
                lastZ += toSigned(zRaw.first)
                @Suppress("UNUSED_VARIABLE")
                val ignoredZ = lastZ / 10.0.pow(thirdPrecision)
            }
            points += GeoPoint(lastLat / factor, lastLng / factor)
        }
        return points
    }

    private fun decodeUnsigned(encoded: String, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var index = start
        while (index < encoded.length) {
            val code = encoded[index++].code
            require(code < decodeTable.size && decodeTable[code] >= 0) { "Invalid flexible polyline." }
            val value = decodeTable[code]
            result = result or ((value and 0x1f).toLong() shl shift)
            if ((value and 0x20) == 0) break
            shift += 5
        }
        return result to index
    }

    private fun toSigned(value: Long): Long = if ((value and 1L) != 0L) -(value shr 1) - 1L else value shr 1
}
