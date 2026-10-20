package de.drivetime.notifier.automation

import java.net.URI
import java.security.MessageDigest

data class PlanHookConfig(
    val intentEnabled: Boolean = false,
    val webhookEnabled: Boolean = false,
    val action: String = "de.drivetime.notifier.PLAN_SAVED",
    val targetPackage: String = "net.dinglisch.android.taskerm",
    val webhookUrl: String = "",
    val bearerToken: String = "",
    val fields: Set<String> = PlanPayloadField.defaults
) {
    fun valid(): Boolean = fields.all { id -> PlanPayloadField.entries.any { it.id == id } } &&
        (!intentEnabled || (validName(action) && validName(targetPackage) && !action.startsWith("android."))) &&
        (!webhookEnabled || validWebhookUrl(webhookUrl)) &&
        bearerToken.length <= 4_096 && bearerToken.none(Char::isISOControl)

    fun fingerprint(): String = MessageDigest.getInstance("SHA-256")
        .digest(listOf(intentEnabled.toString(), webhookEnabled.toString(), action, targetPackage,
            webhookUrl, bearerToken, fields.sorted().joinToString(",")).joinToString("\u0000").toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        fun validName(value: String): Boolean = value.length in 3..240 &&
            value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))

        fun validWebhookUrl(value: String): Boolean {
            if (value.length !in 1..4_096 || value.any(Char::isISOControl)) return false
            val uri = runCatching { URI(value) }.getOrNull() ?: return false
            return uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.fragment == null && (uri.port == -1 || uri.port in 1..65535)
        }
    }
}
