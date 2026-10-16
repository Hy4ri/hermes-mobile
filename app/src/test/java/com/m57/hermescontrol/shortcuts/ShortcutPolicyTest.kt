package com.m57.hermescontrol.shortcuts

import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.model.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ShortcutPolicyTest {
    @Test
    fun `zero or exhausted launcher quota publishes no conversations`() {
        val rows = listOf(SessionInfo("one"), SessionInfo("two", pinned = true))
        assertEquals(emptyList<SessionInfo>(), selectShortcutSessions(rows, 0))
        assertEquals(emptyList<SessionInfo>(), selectShortcutSessions(rows, -1))
        assertEquals(listOf("two"), selectShortcutSessions(rows, 1).map { it.id })
    }

    @Test
    fun `automation children blank ids and duplicates are never launcher targets`() {
        val rows =
            listOf(
                SessionInfo(" "),
                SessionInfo("child", parent_session_id = "root"),
                SessionInfo("room", source = "bot_room"),
                SessionInfo("agent", source = "subagent"),
                SessionInfo("cron", source = "CRON"),
                SessionInfo("chat", source = "desktop"),
                SessionInfo("chat", source = "desktop"),
            )
        assertEquals(listOf("chat"), selectShortcutSessions(rows, 10).map { it.id })
    }

    @Test
    fun `recency falls back to start time and ties are deterministic`() {
        val rows =
            listOf(
                SessionInfo("z", started_at = 1.0),
                SessionInfo("new", last_active = 10.0),
                SessionInfo("a", started_at = 1.0),
            )
        assertEquals(listOf("new", "a", "z"), selectShortcutSessions(rows, 3).map { it.id })
    }

    @Test
    fun `same session id remains isolated by connection endpoint and profile`() {
        val scope = DataScope("conn", "http://localhost:8080", "default")
        val id = sessionShortcutId(scope, "session/one")
        for (other in listOf(
            scope.copy(connectionProfileId = "other"),
            scope.copy(baseUrl = "http://localhost:8081"),
            scope.copy(activeProfileId = "other"),
        )) {
            assertNotEquals(id, sessionShortcutId(other, "session/one"))
            assertNotEquals(shortcutScopeId(scope), shortcutScopeId(other))
        }
        assertFalse(id.contains("localhost"))
        assertFalse(id.contains("session/one"))
        assertEquals(id, sessionShortcutId(scope.copy(inMemoryAuthGeneration = 99), "session/one"))
    }

    @Test
    fun `pins precede recent visible conversations within launcher quota`() {
        val rows =
            listOf(
                SessionInfo("recent", last_active = 100.0),
                SessionInfo("hidden", pinned = true, hidden = true),
                SessionInfo("archived", pinned = true, archived = true),
                SessionInfo("cron", pinned = true, source = "cron"),
                SessionInfo("pin", pinned = true, last_active = 1.0),
            )
        assertEquals(listOf("pin", "recent"), selectShortcutSessions(rows, 2).map { it.id })
    }
}
