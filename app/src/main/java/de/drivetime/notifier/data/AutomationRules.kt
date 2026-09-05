package de.drivetime.notifier.data

private val urlPattern = Regex("""(?i)^(https?://|www\.)\S+$""")
private val phonePattern = Regex("""^\+?[0-9][0-9 ()/.-]{5,}$""")

fun AppSettings.startLocationForCalendar(calendarId: Long): String =
    calendarStartLocations
        .firstOrNull { it.substringBefore("|") == calendarId.toString() }
        ?.substringAfter("|", "")
        ?.trim()
        .orEmpty()
        .ifBlank { homeAddress }

fun AppSettings.withCalendarStartLocation(calendarId: Long, address: String?): AppSettings {
    val prefix = "${calendarId}|"
    val cleaned = calendarStartLocations.filterNot { it.startsWith(prefix) }.toMutableSet()
    if (!address.isNullOrBlank()) cleaned += "${calendarId}|${address.trim()}"
    return copy(calendarStartLocations = cleaned)
}

fun AppSettings.excludesLocation(location: String): Boolean {
    val value = location.trim()
    if (value.isBlank()) return true
    return exclusionRules.any { raw ->
        val mode = raw.substringBefore("|")
        val rule = raw.substringAfter("|", "")
        when (mode) {
            "exact" -> value == rule
            "ignore_case" -> value.equals(rule, ignoreCase = true)
            "url" -> urlPattern.matches(value)
            "phone" -> phonePattern.matches(value)
            "regex" -> runCatching { Regex(rule).matches(value) }.getOrDefault(false)
            else -> false
        }
    }
}
