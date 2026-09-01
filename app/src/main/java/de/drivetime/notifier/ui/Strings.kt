package de.drivetime.notifier.ui

import de.drivetime.notifier.data.AppLanguage

fun tr(language: AppLanguage, english: String, german: String): String =
    if (language == AppLanguage.GERMAN) german else english

fun reliabilityLabel(language: AppLanguage, score: Int): String = when (score) {
    5 -> tr(language, "Very high", "Sehr hoch")
    4 -> tr(language, "High", "Hoch")
    3 -> tr(language, "Medium", "Mittel")
    2 -> tr(language, "Low", "Niedrig")
    else -> tr(language, "Very low", "Sehr niedrig")
}
