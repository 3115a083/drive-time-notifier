package de.drivetime.notifier.network

import java.net.URI

object HttpsEndpoint {
    fun normalize(value: String): String? {
        val clean = value.trim().removeSuffix("/")
        if (clean.length !in 1..240 || clean.any(Char::isISOControl)) return null
        val uri = runCatching { URI(clean) }.getOrNull() ?: return null
        return clean.takeIf {
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.fragment == null && (uri.port == -1 || uri.port in 1..65535)
        }
    }
}
