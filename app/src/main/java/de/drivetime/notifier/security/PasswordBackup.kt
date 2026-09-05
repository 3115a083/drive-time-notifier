package de.drivetime.notifier.security

import de.drivetime.notifier.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class BackupImportResult(
    val settings: AppSettings,
    val apiKeys: Map<RoutingProvider, String>
)

object PasswordBackup {
    private val MAGIC = byteArrayOf(0x44, 0x54, 0x4E, 0x42, 0x01) // DTNB + v1
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256

    fun export(
        output: OutputStream,
        settings: AppSettings,
        keyStore: SecureApiKeyStore,
        password: CharArray
    ) {
        require(password.size >= 8) { "Password must contain at least 8 characters." }

        val root = JSONObject()
            .put("formatVersion", 1)
            .put("settings", settingsToJson(settings))
            .put("apiKeys", JSONObject().apply {
                RoutingProvider.entries.forEach { provider ->
                    keyStore.read(provider)?.takeIf { it.isNotBlank() }?.let { put(provider.id, it) }
                }
            })

        val plain = root.toString().toByteArray(Charsets.UTF_8)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plain)

        output.buffered().use { out ->
            out.write(MAGIC)
            out.write(salt)
            out.write(iv)
            out.write(encrypted)
            out.flush()
        }
        password.fill('\u0000')
    }

    fun import(input: InputStream, password: CharArray): BackupImportResult {
        require(password.size >= 8) { "Password must contain at least 8 characters." }
        val bytes = input.buffered().use { it.readBytes() }
        require(bytes.size > MAGIC.size + 16 + 12 + 16) { "Backup file is incomplete." }
        require(bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Not a Drive Time Notifier backup." }

        var offset = MAGIC.size
        val salt = bytes.copyOfRange(offset, offset + 16).also { offset += 16 }
        val iv = bytes.copyOfRange(offset, offset + 12).also { offset += 12 }
        val encrypted = bytes.copyOfRange(offset, bytes.size)

        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val plain = try {
            cipher.doFinal(encrypted)
        } catch (_: Exception) {
            throw IllegalArgumentException("Wrong password or damaged backup file.")
        } finally {
            password.fill('\u0000')
        }

        val root = JSONObject(String(plain, Charsets.UTF_8))
        require(root.optInt("formatVersion", -1) == 1) { "Unsupported backup version." }
        val settings = settingsFromJson(root.getJSONObject("settings"))
        val keys = buildMap {
            val json = root.optJSONObject("apiKeys") ?: JSONObject()
            RoutingProvider.entries.forEach { provider ->
                json.optString(provider.id).takeIf { it.isNotBlank() }?.let { put(provider, it) }
            }
        }
        plain.fill(0)
        return BackupImportResult(settings, keys)
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES").also { bytes.fill(0) }
    }

    private fun settingsToJson(s: AppSettings) = JSONObject().apply {
        put("homeName", s.homeName)
        put("homeAddress", s.homeAddress)
        put("savedPlaces", JSONArray(s.savedPlaces.toList()))
        put("calendarStartLocations", JSONArray(s.calendarStartLocations.toList()))
        put("exclusionRules", JSONArray(s.exclusionRules.toList()))
        put("bufferMinutes", s.bufferMinutes)
        put("reminderLeadMinutes", s.reminderLeadMinutes)
        put("automaticEnabled", s.automaticEnabled)
        put("autoHour", s.autoHour)
        put("autoMinute", s.autoMinute)
        put("outputIcs", s.outputIcs)
        put("showSpeedCameras", s.showSpeedCameras)
        put("showParking", s.showParking)
        put("targetCalendarId", s.targetCalendarId)
        put("sourceCalendarIds", JSONArray(s.sourceCalendarIds.toList()))
        put("calendarEventTitle", s.calendarEventTitle)
        put("routingProvider", s.routingProvider.id)
        put("osrmBaseUrl", s.osrmBaseUrl)
        put("valhallaBaseUrl", s.valhallaBaseUrl)
        put("photonBaseUrl", s.photonBaseUrl)
        put("language", s.language.id)
        put("appearance", s.appearance.id)
        put("palette", s.palette.id)
        put("fallbackProviderIds", JSONArray(s.fallbackProviderIds))
        put("providerCaps", JSONObject().apply {
            RoutingProvider.entries.forEach { put(it.id, s.providerCaps.forProvider(it)) }
        })
        put("providerPeriods", JSONObject().apply {
            RoutingProvider.entries.forEach { put(it.id, s.providerLimitPeriods.forProvider(it).id) }
        })
    }

    private fun settingsFromJson(j: JSONObject): AppSettings {
        val defaults = AppSettings()
        val capsJson = j.optJSONObject("providerCaps") ?: JSONObject()
        val periodsJson = j.optJSONObject("providerPeriods") ?: JSONObject()
        var caps = defaults.providerCaps
        var periods = defaults.providerLimitPeriods
        RoutingProvider.entries.forEach { provider ->
            caps = caps.withProvider(provider, capsJson.optInt(provider.id, caps.forProvider(provider)))
            periods = periods.withProvider(
                provider,
                LimitPeriod.fromId(periodsJson.optString(provider.id, periods.forProvider(provider).id))
            )
        }
        return AppSettings(
            homeName = j.optString("homeName", defaults.homeName),
            homeAddress = j.optString("homeAddress", defaults.homeAddress),
            savedPlaces = j.stringSet("savedPlaces"),
            calendarStartLocations = j.stringSet("calendarStartLocations"),
            exclusionRules = j.stringSet("exclusionRules"),
            bufferMinutes = j.optInt("bufferMinutes", defaults.bufferMinutes),
            reminderLeadMinutes = j.optInt("reminderLeadMinutes", defaults.reminderLeadMinutes),
            automaticEnabled = j.optBoolean("automaticEnabled", defaults.automaticEnabled),
            autoHour = j.optInt("autoHour", defaults.autoHour),
            autoMinute = j.optInt("autoMinute", defaults.autoMinute),
            outputIcs = j.optBoolean("outputIcs", defaults.outputIcs),
            showSpeedCameras = j.optBoolean("showSpeedCameras", defaults.showSpeedCameras),
            showParking = j.optBoolean("showParking", defaults.showParking),
            targetCalendarId = j.optLong("targetCalendarId", defaults.targetCalendarId),
            sourceCalendarIds = j.stringSet("sourceCalendarIds"),
            calendarEventTitle = j.optString("calendarEventTitle", defaults.calendarEventTitle),
            routingProvider = RoutingProvider.fromId(j.optString("routingProvider", defaults.routingProvider.id)),
            osrmBaseUrl = j.optString("osrmBaseUrl", defaults.osrmBaseUrl),
            valhallaBaseUrl = j.optString("valhallaBaseUrl", defaults.valhallaBaseUrl),
            photonBaseUrl = j.optString("photonBaseUrl", defaults.photonBaseUrl),
            language = AppLanguage.fromId(j.optString("language", defaults.language.id)),
            appearance = AppAppearance.fromId(j.optString("appearance", defaults.appearance.id)),
            palette = ColorPalette.fromId(j.optString("palette", defaults.palette.id)),
            providerCaps = caps,
            providerLimitPeriods = periods,
            fallbackProviderIds = j.stringList("fallbackProviderIds")
                .filter { id -> RoutingProvider.entries.any { it.id == id } }
                .distinct()
        )
    }

    private fun JSONObject.stringSet(name: String): Set<String> =
        stringList(name).toSet()

    private fun JSONObject.stringList(name: String): List<String> {
        val array = optJSONArray(name) ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                array.optString(i).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }
}
