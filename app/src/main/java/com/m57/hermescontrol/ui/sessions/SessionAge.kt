package com.m57.hermescontrol.ui.sessions

import com.m57.hermescontrol.data.model.SessionInfo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * How long ago a session was last active, bucketed the way chat lists show it:
 * "now", minutes, hours, days for the last week, then the calendar date.
 */
sealed interface SessionAge {
    data object Now : SessionAge

    data class Minutes(
        val value: Long,
    ) : SessionAge

    data class Hours(
        val value: Long,
    ) : SessionAge

    data class Days(
        val value: Long,
    ) : SessionAge

    data class Date(
        val date: LocalDate,
        val sameYear: Boolean,
    ) : SessionAge
}

private const val MINUTE_SECONDS = 60L
private const val HOUR_SECONDS = 60 * MINUTE_SECONDS
private const val DAY_SECONDS = 24 * HOUR_SECONDS
private const val WEEK_SECONDS = 7 * DAY_SECONDS

/** Latest activity in epoch seconds: the backend's `last_active`, else `started_at`. */
fun SessionInfo.activityEpochSeconds(): Double? = last_active?.takeIf { it > 0.0 } ?: started_at?.takeIf { it > 0.0 }

fun sessionAge(
    epochSeconds: Double?,
    now: Instant,
    zone: ZoneId,
): SessionAge? {
    if (epochSeconds == null || epochSeconds <= 0.0) return null
    val then = Instant.ofEpochSecond(epochSeconds.toLong())
    val elapsed = (now.epochSecond - then.epochSecond).coerceAtLeast(0)
    return when {
        elapsed < MINUTE_SECONDS -> {
            SessionAge.Now
        }

        elapsed < HOUR_SECONDS -> {
            SessionAge.Minutes(elapsed / MINUTE_SECONDS)
        }

        elapsed < DAY_SECONDS -> {
            SessionAge.Hours(elapsed / HOUR_SECONDS)
        }

        elapsed < WEEK_SECONDS -> {
            SessionAge.Days(elapsed / DAY_SECONDS)
        }

        else -> {
            val date = then.atZone(zone).toLocalDate()
            SessionAge.Date(date, sameYear = date.year == now.atZone(zone).year)
        }
    }
}
