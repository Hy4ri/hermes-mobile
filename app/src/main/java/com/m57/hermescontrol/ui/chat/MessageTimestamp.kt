package com.m57.hermescontrol.ui.chat

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun formatTimestamp(
    timestamp: Long,
    is24Hour: Boolean,
): String {
    val pattern = if (is24Hour) "HH:mm" else "h:mm a"
    return DateTimeFormatter
        .ofPattern(pattern)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(timestamp))
}
