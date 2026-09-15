package com.m57.hermescontrol.ui.sessions

import com.m57.hermescontrol.data.model.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

class SessionAgeTest {
    private val zone = ZoneOffset.UTC
    private val now = ZonedDateTime.of(2026, 9, 15, 12, 0, 0, 0, zone).toInstant()

    private fun ageSecondsAgo(seconds: Long) = sessionAge((now.epochSecond - seconds).toDouble(), now, zone)

    @Test
    fun `under a minute reads as now`() {
        assertEquals(SessionAge.Now, ageSecondsAgo(0))
        assertEquals(SessionAge.Now, ageSecondsAgo(59))
    }

    @Test
    fun `minutes bucket`() {
        assertEquals(SessionAge.Minutes(1), ageSecondsAgo(60))
        assertEquals(SessionAge.Minutes(15), ageSecondsAgo(15 * 60 + 30))
        assertEquals(SessionAge.Minutes(59), ageSecondsAgo(3599))
    }

    @Test
    fun `hours bucket`() {
        assertEquals(SessionAge.Hours(1), ageSecondsAgo(3600))
        assertEquals(SessionAge.Hours(21), ageSecondsAgo(21 * 3600))
        assertEquals(SessionAge.Hours(23), ageSecondsAgo(24 * 3600 - 1))
    }

    @Test
    fun `days bucket up to a week`() {
        assertEquals(SessionAge.Days(1), ageSecondsAgo(24 * 3600))
        assertEquals(SessionAge.Days(6), ageSecondsAgo(7 * 24 * 3600 - 1))
    }

    @Test
    fun `a week or older shows the date`() {
        assertEquals(
            SessionAge.Date(LocalDate.of(2026, 9, 8), sameYear = true),
            ageSecondsAgo(7 * 24 * 3600),
        )
        assertEquals(
            SessionAge.Date(LocalDate.of(2025, 12, 31), sameYear = false),
            sessionAge(ZonedDateTime.of(2025, 12, 31, 23, 0, 0, 0, zone).toEpochSecond().toDouble(), now, zone),
        )
    }

    @Test
    fun `future timestamps from clock skew read as now`() {
        assertEquals(SessionAge.Now, ageSecondsAgo(-300))
    }

    @Test
    fun `missing timestamps have no age`() {
        assertNull(sessionAge(null, now, zone))
        assertNull(sessionAge(0.0, now, zone))
    }

    @Test
    fun `activity prefers last active over started at`() {
        assertEquals(20.0, SessionInfo(id = "s", started_at = 10.0, last_active = 20.0).activityEpochSeconds())
        assertEquals(10.0, SessionInfo(id = "s", started_at = 10.0).activityEpochSeconds())
        assertEquals(10.0, SessionInfo(id = "s", started_at = 10.0, last_active = 0.0).activityEpochSeconds())
        assertNull(SessionInfo(id = "s").activityEpochSeconds())
    }
}
