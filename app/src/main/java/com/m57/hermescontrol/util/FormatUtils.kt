package com.m57.hermescontrol.util

import java.util.Locale

/**
 * Formats a byte count into human-readable B, KB, MB, or GB.
 */
fun formatBytes(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> String.format(Locale.US, "%.1f GB", bytes.toDouble() / (1024 * 1024 * 1024))
    }

/**
 * Formats seconds into a human-readable duration (e.g. "2d 4h 15m").
 */
fun formatDuration(totalSeconds: Double): String {
    val days = (totalSeconds / 86400).toInt()
    val hours = ((totalSeconds % 86400) / 3600).toInt()
    val minutes = ((totalSeconds % 3600) / 60).toInt()
    return buildString {
        if (days > 0) append("${days}d ")
        if (hours > 0 || days > 0) append("${hours}h ")
        append("${minutes}m")
    }
}
