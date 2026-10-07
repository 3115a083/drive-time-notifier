package de.drivetime.notifier.sharing

import de.drivetime.notifier.network.awaitResponse
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object SharedLinkResolver {
    private val shortHosts = setOf("maps.app.goo.gl", "goo.gl")
    private val mapHosts = shortHosts + setOf("www.google.com", "google.com", "maps.google.com", "www.google.de", "maps.google.de")
    suspend fun resolve(raw: String): String? = withTimeoutOrNull(15_000L) { withContext(Dispatchers.IO) {
        SharedDestination.parse(raw)?.let { return@withContext it }
        val link = Regex("https://[^\\s<>]+").find(raw.take(8192))?.value ?: return@withContext null
        var url = link.toHttpUrlOrNull() ?: return@withContext null
        if (url.host !in shortHosts || (url.host == "goo.gl" && !url.encodedPath.startsWith("/maps"))) return@withContext null
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
        repeat(4) {
            if (!url.isHttps || url.host !in mapHosts || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) return@withContext null
            SharedDestination.parse(url.toString())?.let { return@withContext it }
            client.newCall(Request.Builder().url(url).header("User-Agent", "DriveTimeNotifier").get().build()).awaitResponse().use { response ->
                if (response.code !in setOf(301, 302, 303, 307, 308)) return@withContext null
                url = url.resolve(response.header("Location") ?: return@withContext null) ?: return@withContext null
            }
        }
        if (url.isHttps && url.host in mapHosts) SharedDestination.parse(url.toString()) else null
    } }
}
