package de.drivetime.notifier.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import de.drivetime.notifier.automation.PlanHookConfig
import de.drivetime.notifier.automation.PlanPayloadField
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypt the complete destination configuration, including secret webhook URLs. */
class PlanHookStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("plan-hooks", Context.MODE_PRIVATE)

    fun read(): PlanHookConfig {
        val encrypted = preferences.getString("cipher", null) ?: return PlanHookConfig()
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128,
                Base64.decode(requireNotNull(preferences.getString("iv", null)), Base64.NO_WRAP)))
            cipher.updateAAD(context.packageName.toByteArray())
            val json = JSONObject(String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8))
            val fields = json.optJSONArray("fields") ?: JSONArray(PlanPayloadField.defaults.toList())
            PlanHookConfig(json.optBoolean("intent"), json.optBoolean("webhook"),
                json.optString("action", PlanHookConfig().action), json.optString("package", PlanHookConfig().targetPackage),
                json.optString("url"), json.optString("token"), (0 until fields.length()).map { fields.getString(it) }.toSet())
                .takeIf { it.valid() } ?: PlanHookConfig()
        }.getOrElse { PlanHookConfig() }
    }

    fun save(config: PlanHookConfig) {
        require(config.valid()) { "Invalid forwarding configuration." }
        val json = JSONObject().put("intent", config.intentEnabled).put("webhook", config.webhookEnabled)
            .put("action", config.action).put("package", config.targetPackage)
            .put("url", config.webhookUrl).put("token", config.bearerToken).put("fields", JSONArray(config.fields.sorted()))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(context.packageName.toByteArray())
        preferences.edit().putString("cipher", Base64.encodeToString(cipher.doFinal(json.toString().toByteArray()), Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).apply()
    }

    fun status(): String = preferences.getString("status", "").orEmpty()
    fun status(value: String) { preferences.edit().putString("status", value.take(200)).apply() }

    companion object {
        @Synchronized private fun key(): SecretKey {
            val alias = "planning_forwarding_v1"
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
    }
}
