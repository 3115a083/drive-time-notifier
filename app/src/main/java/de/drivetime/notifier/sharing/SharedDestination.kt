package de.drivetime.notifier.sharing

import java.net.URI
import java.net.URLDecoder

/** Parses data only. Incoming links never become arbitrary network requests. */
object SharedDestination {
    fun coordinates(value: String): Pair<Double, Double>? {
        val parts = value.trim().split(',')
        if (parts.size != 2) return null
        val lat = parts[0].trim().toDoubleOrNull() ?: return null
        val lon = parts[1].trim().toDoubleOrNull() ?: return null
        return if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
    }

    fun parse(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty() || text.length > 8192) return null
        val link = Regex("(?:https?://|geo:|google.navigation:)[^\\s<>]+", RegexOption.IGNORE_CASE)
            .find(text)?.value
        if (link == null) return text.takeIf { it.length <= 2048 && !it.contains("://") && !it.contains('\u0000') }
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        val query = uri.rawQuery ?: uri.rawSchemeSpecificPart.substringAfter('?', "")
        val params = query.split('&').mapNotNull {
            val key = it.substringBefore('=')
            val value = runCatching { URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }.getOrNull()
            value?.let { v -> key to v }
        }.toMap()
        fun valid(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2048 && !it.contains('\u0000') }
        if (uri.scheme.equals("geo", true) || uri.scheme.equals("google.navigation", true)) {
            valid(params["q"] ?: params["daddr"])?.let { q ->
                // geo:q may attach a human-readable label to coordinates.
                val point = q.substringBefore('(').trim()
                return if (coordinates(point) != null) point else q
            }
            val point = uri.rawSchemeSpecificPart.substringBefore('?').substringBefore(';')
            return point.takeIf { coordinates(it) != null }
        }
        if (!uri.scheme.equals("https", true) || uri.userInfo != null || uri.port !in listOf(-1, 443)) return null
        val host = uri.host?.lowercase() ?: return null
        if (host !in setOf("maps.google.com", "www.google.com", "google.com", "maps.google.de", "www.google.de", "maps.apple.com", "www.openstreetmap.org", "openstreetmap.org")) return null
        for (key in listOf("destination", "daddr", "q", "query")) valid(params[key])?.let { return it }
        if (host.endsWith("openstreetmap.org")) {
            val point = "${params["mlat"]},${params["mlon"]}"
            if (coordinates(point) != null) return point
            val parts = uri.fragment?.substringAfter("map=", "")?.split('/')
            if (parts?.size == 3) return "${parts[1]},${parts[2]}".takeIf { coordinates(it) != null }
        }
        valid(params["ll"])?.let { if (coordinates(it) != null) return it }
        val path = URLDecoder.decode(uri.rawPath.orEmpty(), "UTF-8")
        Regex("!3d(-?[0-9.]+)!4d(-?[0-9.]+)").find(path)?.let {
            val point = "${it.groupValues[1]},${it.groupValues[2]}"
            if (coordinates(point) != null) return point
        }
        Regex("/maps/(?:place|search)/([^/]+)").find(path)?.let { return valid(it.groupValues[1]) }
        Regex("@(-?[0-9.]+),(-?[0-9.]+)").find(path)?.let {
            val point = "${it.groupValues[1]},${it.groupValues[2]}"
            if (coordinates(point) != null) return point
        }
        return null
    }
}
