package de.drivetime.notifier.security

import android.content.Context
import java.security.SecureRandom
import android.util.Base64

class AutomationTokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("automation_security", Context.MODE_PRIVATE)

    fun token(): String {
        prefs.getString("token", null)?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
        prefs.edit().putString("token", token).apply()
        return token
    }

    fun rotate(): String {
        prefs.edit().remove("token").apply()
        return token()
    }
}
