package de.drivetime.notifier.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import de.drivetime.notifier.data.RoutingProvider
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureApiKeyStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("secure_api", Context.MODE_PRIVATE)

    fun save(value: String) = save(RoutingProvider.GOOGLE, value)
    fun read(): String? = read(RoutingProvider.GOOGLE)

    fun save(provider: RoutingProvider, value: String) {
        val keyName = provider.id
        if (value.isBlank()) {
            prefs.edit().remove("${keyName}_cipher").remove("${keyName}_iv").apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(provider))
        prefs.edit()
            .putString("${keyName}_cipher", Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
            .putString("${keyName}_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun read(provider: RoutingProvider): String? {
        val keyName = provider.id
        val cipherText = prefs.getString("${keyName}_cipher", null)
        val iv = prefs.getString("${keyName}_iv", null)
        if (cipherText != null && iv != null) {
            return decrypt(provider, cipherText, iv)
        }

        // Migrate the key format used by versions before multiple providers.
        if (provider == RoutingProvider.GOOGLE) {
            val legacyCipher = prefs.getString("cipher", null)
            val legacyIv = prefs.getString("iv", null)
            if (legacyCipher != null && legacyIv != null) {
                val value = runCatching { decryptLegacy(legacyCipher, legacyIv) }.getOrNull()
                if (!value.isNullOrBlank()) {
                    save(provider, value)
                    prefs.edit().remove("cipher").remove("iv").apply()
                    return value
                }
            }
        }
        return null
    }

    private fun decrypt(provider: RoutingProvider, cipherText: String, iv: String): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(provider), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    private fun decryptLegacy(cipherText: String, iv: String): String {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val legacy = store.getKey("routing_api_key", null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, legacy, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun key(provider: RoutingProvider): SecretKey {
        val alias = "routing_api_key_${provider.id}"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
        }.generateKey()
    }
}
