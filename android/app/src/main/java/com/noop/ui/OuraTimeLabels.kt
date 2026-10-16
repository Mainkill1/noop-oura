package com.noop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

/** Chart and sensor timestamps follow the same app clock preference as the rest of the dashboard. */
internal fun ouraTimestampFormatter(is24h: Boolean, seconds: Boolean, locale: Locale, zone: ZoneId): DateTimeFormatter =
    DateTimeFormatterBuilder().appendLocalized(FormatStyle.SHORT, null).appendLiteral(" · ")
        .appendPattern(if (is24h) { if (seconds) "HH:mm:ss" else "HH:mm" }
            else { if (seconds) "h:mm:ss a" else "h:mm a" }).toFormatter(locale).withZone(zone)

@Composable
internal fun rememberOuraTimestampFormatter(seconds: Boolean = false): DateTimeFormatter {
    val is24h = ClockPrefs.uses24Hour(LocalContext.current)
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    return remember(is24h, seconds, locale, zone) { ouraTimestampFormatter(is24h, seconds, locale, zone) }
}
