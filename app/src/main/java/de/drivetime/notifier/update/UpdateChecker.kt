package de.drivetime.notifier.update

import de.drivetime.notifier.network.readBytesLimited
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
                val bytes = body.readBytesLimited(MAX_BYTES)
                parseReleaseJson(installedVersion, debugBuild, String(bytes, Charsets.UTF_8))
            }
        }.getOrElse { UpdateCheckResult.Error(it.message?.take(120) ?: "Network error") }
    }

    internal fun parseReleaseJson(installedVersion: String, debugBuild: Boolean, json: String): UpdateCheckResult {
        val root = runCatching { JSONObject(json) }.getOrElse { return UpdateCheckResult.Error("Invalid response") }
        return evaluateRelease(
            installedVersion = installedVersion,
            debugBuild = debugBuild,
            latestRaw = root.optString("tag_name"),
            releaseUrlRaw = root.optString("html_url"),
            draft = root.optBoolean("draft", false),
            prerelease = root.optBoolean("prerelease", false)
        )
    }

    companion object {
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/3115a083/drive-time-notifier/releases/latest"
        private const val RELEASES_URL = "https://github.com/3115a083/drive-time-notifier/releases"
        private const val MAX_BYTES = 256_000L

        internal fun evaluateRelease(
            installedVersion: String,
            debugBuild: Boolean,
            latestRaw: String,
            releaseUrlRaw: String,
            draft: Boolean,
            prerelease: Boolean
        ): UpdateCheckResult {
            if (draft || prerelease) return UpdateCheckResult.Error("No stable release")
            val latest = normalize(latestRaw) ?: return UpdateCheckResult.Error("Missing version")
            val installed = normalize(installedVersion) ?: return UpdateCheckResult.Error("Invalid installed version")
            val releaseUrl = releaseUrlRaw.takeIf {
                it.startsWith("$RELEASES_URL/")
            } ?: RELEASES_URL

            // A debug build may still report that a newer official version exists, but the
            // UI explicitly labels itself as a debug build and never performs a silent update.
            @Suppress("UNUSED_VARIABLE")
            val isDebug = debugBuild
            return if (compareVersions(latest, installed) > 0) {
                UpdateCheckResult.UpdateAvailable(latest, releaseUrl)
            } else {
                UpdateCheckResult.UpToDate(latest)
            }
        }

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
