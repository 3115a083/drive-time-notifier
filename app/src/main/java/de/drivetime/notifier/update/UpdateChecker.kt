package de.drivetime.notifier.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed interface UpdateCheckResult {
    data class UpToDate(val latestVersion: String) : UpdateCheckResult
    data class UpdateAvailable(val latestVersion: String, val releaseUrl: String) : UpdateCheckResult
    data class Error(val reason: String) : UpdateCheckResult
}

class UpdateChecker(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
) {
    suspend fun check(installedVersion: String, debugBuild: Boolean): UpdateCheckResult = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(
                Request.Builder()
                    .url(LATEST_RELEASE_URL)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "DriveTimeNotifier/${installedVersion.take(40)}")
                    .get()
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) return@use UpdateCheckResult.Error("HTTP ${response.code}")
                val body = response.body ?: return@use UpdateCheckResult.Error("Empty response")
                if (body.contentLength() > MAX_BYTES) return@use UpdateCheckResult.Error("Response too large")
                val bytes = body.source().readByteArray(MAX_BYTES + 1L)
                if (bytes.size > MAX_BYTES) return@use UpdateCheckResult.Error("Response too large")
                parseReleaseJson(installedVersion, debugBuild, String(bytes, Charsets.UTF_8))
            }
        }.getOrElse { UpdateCheckResult.Error(it.message?.take(120) ?: "Network error") }
    }

    internal fun parseReleaseJson(installedVersion: String, debugBuild: Boolean, json: String): UpdateCheckResult {
        val root = runCatching { JSONObject(json) }.getOrElse { return UpdateCheckResult.Error("Invalid response") }
        if (root.optBoolean("draft", false) || root.optBoolean("prerelease", false)) {
            return UpdateCheckResult.Error("No stable release")
        }
        val latest = normalize(root.optString("tag_name")) ?: return UpdateCheckResult.Error("Missing version")
        val releaseUrl = root.optString("html_url").takeIf {
            it.startsWith("https://github.com/3115a083/drive-time-notifier/releases/")
        } ?: "https://github.com/3115a083/drive-time-notifier/releases"
        val installed = normalize(installedVersion) ?: return UpdateCheckResult.Error("Invalid installed version")
        return if (compareVersions(latest, installed) > 0) {
            UpdateCheckResult.UpdateAvailable(latest, releaseUrl)
        } else {
            // For debug builds this means the debug build is based on the current or newer code version.
            UpdateCheckResult.UpToDate(latest)
        }
    }

    companion object {
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/3115a083/drive-time-notifier/releases/latest"
        private const val MAX_BYTES = 256_000L

        internal fun normalize(raw: String): String? {
            val text = raw.trim().removePrefix("v").substringBefore('-').substringBefore('+')
            return text.takeIf { it.matches(Regex("\\d+(?:\\.\\d+){0,3}")) }
        }

        internal fun compareVersions(a: String, b: String): Int {
            val aa = a.split('.').map { it.toIntOrNull() ?: 0 }
            val bb = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(aa.size, bb.size)) {
                val av = aa.getOrElse(i) { 0 }
                val bv = bb.getOrElse(i) { 0 }
                if (av != bv) return av.compareTo(bv)
            }
            return 0
        }
    }
}
