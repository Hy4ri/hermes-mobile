package com.m57.hermescontrol.data.session

import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionLiveStatus
import com.m57.hermescontrol.ui.sessions.SessionsUiState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Badge fallback from the shared session database fields (desktop, Signal and other processes). */
class SessionSharedStatusTest {
    private val now = 10_000.0

    private fun row(
        lastActive: Double? = 5_000.0,
        activityAt: Double? = null,
        description: String? = null,
        unread: Boolean? = null,
        lastReadAt: Double? = null,
    ) = SessionInfo(
        id = "s",
        last_active = lastActive,
        last_activity_at = activityAt,
        last_activity_description = description,
        unread = unread,
        last_read_at = lastReadAt,
    )

    @Test
    fun `fresh activity description is WORKING`() {
        val r = row(activityAt = now - 10, description = "terminal: ls")
        assertEquals(SessionLiveStatus.WORKING, sharedStatusOf(r, null, now))
    }

    @Test
    fun `activity at the freshness boundary is stale`() {
        val r = row(activityAt = now - SHARED_ACTIVITY_FRESH_SECONDS, description = "terminal: ls")
        assertNull(sharedStatusOf(r, null, now))
    }

    @Test
    fun `stale or blank activity is not WORKING`() {
        assertNull(sharedStatusOf(row(activityAt = now - 600, description = "x"), null, now))
        assertNull(sharedStatusOf(row(activityAt = now - 1, description = "  "), null, now))
        assertNull(sharedStatusOf(row(activityAt = now - 1, description = null), null, now))
        assertNull(sharedStatusOf(row(activityAt = null, description = "x"), null, now))
    }

    @Test
    fun `unread row is DONE`() {
        assertEquals(SessionLiveStatus.DONE, sharedStatusOf(row(unread = true), null, now))
    }

    @Test
    fun `read row and row without watermark are not DONE`() {
        assertNull(sharedStatusOf(row(unread = false), null, now))
        // No watermark: the backend reports unread=false, and desktop treats it as read.
        assertNull(sharedStatusOf(row(unread = false, lastReadAt = null), null, now))
    }

    @Test
    fun `WORKING wins over unread`() {
        val r = row(activityAt = now - 5, description = "thinking", unread = true)
        assertEquals(SessionLiveStatus.WORKING, sharedStatusOf(r, null, now))
    }

    @Test
    fun `local read at or after last activity clears a stale server unread`() {
        assertNull(sharedStatusOf(row(lastActive = 5_000.0, unread = true), 5_000.0, now))
        assertNull(sharedStatusOf(row(lastActive = 5_000.0, unread = true), 6_000.0, now))
    }

    @Test
    fun `activity after the local read is DONE again`() {
        assertEquals(SessionLiveStatus.DONE, sharedStatusOf(row(lastActive = 7_000.0, unread = true), 6_000.0, now))
    }

    @Test
    fun `old backend without shared fields gives no badge`() {
        assertNull(sharedStatusOf(SessionInfo(id = "s", last_active = 1.0), null, now))
        assertNull(sharedStatusOf(SessionInfo(id = "s"), 5.0, now))
    }

    @Test
    fun `SessionInfo decodes shared fields in seconds and tolerates their absence`() {
        val json = Json { ignoreUnknownKeys = true }
        val full =
            json.decodeFromString<SessionInfo>(
                """{"id":"a","last_read_at":1790000000.5,"unread":true,
                    "last_activity_at":1790000100.25,"last_activity_description":"running tool"}""",
            )
        assertEquals(1790000000.5, full.last_read_at!!, 0.0)
        assertEquals(true, full.unread)
        assertEquals(1790000100.25, full.last_activity_at!!, 0.0)
        assertEquals("running tool", full.last_activity_description)

        val old = json.decodeFromString<SessionInfo>("""{"id":"b"}""")
        assertNull(old.last_read_at)
        assertNull(old.unread)
        assertNull(old.last_activity_at)
        assertNull(old.last_activity_description)
    }

    @Test
    fun `statusFor order is live then attention then shared`() {
        val shared = row(unread = true).copy(id = "s")
        val base = SessionsUiState(sessions = listOf(shared))
        assertEquals(SessionLiveStatus.DONE, base.statusFor("s", nowSeconds = now))

        val withAttention = base.copy(attention = mapOf("s" to SessionLiveStatus.FAILED))
        assertEquals(SessionLiveStatus.FAILED, withAttention.statusFor("s", nowSeconds = now))

        val withLive = withAttention.copy(liveStatuses = mapOf("s" to SessionLiveStatus.WORKING))
        assertEquals(SessionLiveStatus.WORKING, withLive.statusFor("s", nowSeconds = now))
    }

    @Test
    fun `statusFor honours the optimistic local read and unchanged old-backend behavior`() {
        val shared = row(lastActive = 5_000.0, unread = true).copy(id = "s")
        val state = SessionsUiState(sessions = listOf(shared), localReads = mapOf("s" to 5_001.0))
        assertNull(state.statusFor("s", nowSeconds = now))

        val old = SessionsUiState(sessions = listOf(SessionInfo(id = "o")))
        assertNull(old.statusFor("o", nowSeconds = now))
        assertNull(old.statusFor("missing", nowSeconds = now))
    }
}
