package com.m57.hermescontrol.shortcuts

import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.model.SessionInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionShortcutCoordinatorTest {
    private val scope = DataScope("connection", "http://localhost:8080", "default")

    @Test
    fun `scope change and logout clear titles without waiting for network`() =
        runTest {
            var clears = 0
            val coordinator =
                SessionShortcutCoordinator(
                    currentScope = { scope },
                    loadSessions = { error("No network request is needed for cleanup") },
                    publish = { _, _ -> error("No publication is needed for cleanup") },
                    clear = { clears++ },
                )
            coordinator.updateOwner(scope)
            assertEquals(1, clears)
            coordinator.updateOwner(scope)
            assertEquals(1, clears)
            coordinator.updateOwner(scope.copy(activeProfileId = "other"))
            coordinator.updateOwner(null)
            assertEquals(3, clears)
        }

    @Test
    fun `a stale scoped id is rejected before fetching a same-id chat`() =
        runTest {
            val coordinator =
                SessionShortcutCoordinator(
                    currentScope = { scope },
                    loadSessions = { error("Must reject the foreign scope without reading") },
                    publish = { _, _ -> },
                    clear = {},
                )
            val other = scope.copy(connectionProfileId = "other")
            assertFalse(coordinator.resolve(scope, shortcutScopeId(other), "same-id"))
        }

    @Test
    fun `deleted hidden archived and automation targets cannot be resumed`() =
        runTest {
            var rows = listOf(SessionInfo("chat"))
            val coordinator =
                SessionShortcutCoordinator(
                    currentScope = { scope },
                    loadSessions = { rows },
                    publish = { _, _ -> },
                    clear = {},
                )
            assertTrue(coordinator.resolve(scope, shortcutScopeId(scope), "chat"))
            for (row in listOf(
                SessionInfo("chat", hidden = true),
                SessionInfo("chat", archived = true),
                SessionInfo("chat", source = "cron"),
            )) {
                rows = listOf(row)
                assertFalse(coordinator.resolve(scope, shortcutScopeId(scope), "chat"))
            }
            rows = emptyList()
            assertFalse(coordinator.resolve(scope, shortcutScopeId(scope), "chat"))
        }

    @Test
    fun `logout during target validation prevents a queued resume`() =
        runTest {
            var active: DataScope? = scope
            val coordinator =
                SessionShortcutCoordinator(
                    currentScope = { active },
                    loadSessions = {
                        active = null
                        listOf(SessionInfo("chat"))
                    },
                    publish = { _, _ -> },
                    clear = {},
                )
            assertFalse(coordinator.resolve(scope, shortcutScopeId(scope), "chat"))
        }

    @Test
    fun `a logged-out owner cannot fetch or publish chats`() =
        runTest {
            val coordinator =
                SessionShortcutCoordinator(
                    currentScope = { null },
                    loadSessions = { error("No authenticated scope") },
                    publish = { _, _ -> error("Must not publish") },
                    clear = {},
                )
            coordinator.refresh(scope)
            assertFalse(coordinator.resolve(scope, shortcutScopeId(scope), "chat"))
        }

    @Test
    fun `late result from another scope never publishes its conversation titles`() =
        runTest {
            var active = scope
            val published = mutableListOf<List<String>>()
            val coordinator =
                SessionShortcutCoordinator(
                    currentScope = { active },
                    loadSessions = {
                        active = scope.copy(activeProfileId = "other")
                        listOf(SessionInfo("private"))
                    },
                    publish = { _, rows -> published.add(rows.map { it.id }) },
                    clear = {},
                )
            coordinator.refresh(scope)
            assertEquals(emptyList<List<String>>(), published)
        }

    @Test
    fun `refresh publishes eligible chats for the authenticated owner`() =
        runTest {
            val published = mutableListOf<List<String>>()
            val coordinator =
                SessionShortcutCoordinator(
                    currentScope = { scope },
                    loadSessions = { listOf(SessionInfo("chat")) },
                    publish = { _, rows -> published.add(rows.map { it.id }) },
                    clear = {},
                )
            coordinator.refresh(scope)
            assertEquals(listOf(listOf("chat")), published)
        }
}
