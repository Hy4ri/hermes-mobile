package com.m57.hermescontrol.notification

import android.content.Context
import android.content.SharedPreferences
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.KanbanBoard
import com.m57.hermescontrol.data.model.KanbanBoardsResponse
import com.m57.hermescontrol.data.remote.NetworkError
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.ServerEndpoint
import com.m57.hermescontrol.data.repository.KanbanRepository
import com.m57.hermescontrol.data.ws.KanbanEvent
import com.m57.hermescontrol.data.ws.KanbanEventsClient
import com.m57.hermescontrol.data.ws.KanbanEventsEnvelope
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KanbanBackgroundAlertMonitorTest {
    @Test
    fun `completion maps to completed`() {
        assertEquals(KanbanAlertKind.COMPLETED, kanbanEventAlertKind("completed"))
    }

    @Test
    fun `human-action events map to needs attention`() {
        listOf("blocked", "needs_input", "review_requested", "failed").forEach {
            assertEquals(KanbanAlertKind.NEEDS_ATTENTION, kanbanEventAlertKind(it))
        }
    }

    @Test
    fun `ordinary lifecycle events never notify`() {
        listOf("created", "claimed", "spawned", "heartbeat", "commented", "unblocked", "promoted", "archived")
            .forEach { assertNull(kanbanEventAlertKind(it)) }
    }

    @Test
    fun `kanban notifications setting gates only terminal and attention events`() {
        assertTrue(shouldNotifyKanbanEvent(true, "completed"))
        assertTrue(shouldNotifyKanbanEvent(true, "blocked"))
        assertFalse(shouldNotifyKanbanEvent(false, "completed"))
        assertFalse(shouldNotifyKanbanEvent(false, "failed"))
        assertFalse(shouldNotifyKanbanEvent(true, "heartbeat"))
    }

    @Test
    fun `muted startup keeps cursors current and enabling resumes without restarting`() =
        runBlocking {
            val context = mockk<Context>()
            val prefs = mockk<SharedPreferences>()
            val editor = mockk<SharedPreferences.Editor>(relaxed = true)
            val repository = mockk<KanbanRepository>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val connections = Channel<Pair<Long?, (KanbanEventsEnvelope) -> Unit>>(Channel.UNLIMITED)
            val endpoint = ServerEndpoint.parse("https://example.com/")
            val key = "events_${endpoint.baseUrl}|default"
            var cursor = 42L
            var enabled = false
            every { context.getSharedPreferences(any(), any()) } returns prefs
            every { prefs.getLong(key, 0L) } answers { cursor }
            every { prefs.contains(key) } returns true
            every { prefs.edit() } returns editor
            every { editor.putLong(key, any()) } answers {
                cursor = secondArg()
                editor
            }
            coEvery { repository.getBoards(includeArchived = false) } returns
                NetworkResult.Success(KanbanBoardsResponse(boards = listOf(KanbanBoard(id = "default"))))
            mockkObject(AuthManager)
            mockkConstructor(KanbanEventsClient::class)
            every { AuthManager.isNotifyKanbanAlerts() } answers { enabled }
            every { AuthManager.endpointForBuild() } returns endpoint
            every { anyConstructed<KanbanEventsClient>().connect(any(), any(), any(), any(), any()) } answers {
                connections.trySend(thirdArg<Long?>() to arg<(KanbanEventsEnvelope) -> Unit>(3))
                Unit
            }
            every { anyConstructed<KanbanEventsClient>().disconnect() } just Runs
            val monitor = spyk(KanbanBackgroundAlertMonitor(context, repository), recordPrivateCalls = true)
            every { monitor["notifyTask"](any<String>(), any<String>(), any<KanbanAlertKind>()) } answers { Unit }
            try {
                monitor.start(scope)
                val (since, onEvents) = withTimeout(5_000) { connections.receive() }
                assertEquals(42L, since)
                val mutedEvent = KanbanEvent(43, "t_1234abcd", kind = "completed")
                onEvents(KanbanEventsEnvelope(events = listOf(mutedEvent)))
                assertEquals(43L, cursor)
                verify(exactly = 0) {
                    monitor["notifyTask"](any<String>(), any<String>(), any<KanbanAlertKind>())
                }

                enabled = true
                val liveEvent = KanbanEvent(44, "t_1234abcd", kind = "blocked")
                onEvents(KanbanEventsEnvelope(events = listOf(mutedEvent, liveEvent)))
                assertEquals(44L, cursor)
                verify(exactly = 1) {
                    monitor["notifyTask"]("default", "t_1234abcd", KanbanAlertKind.NEEDS_ATTENTION)
                }
                verify(exactly = 0) {
                    monitor["notifyTask"]("default", "t_1234abcd", KanbanAlertKind.COMPLETED)
                }

                monitor.stop()
                monitor.start(scope)
                val (resumedSince, resumedEvents) = withTimeout(5_000) { connections.receive() }
                assertEquals(44L, resumedSince)
                resumedEvents(KanbanEventsEnvelope(events = listOf(mutedEvent, liveEvent)))
                verify(exactly = 1) {
                    monitor["notifyTask"](any<String>(), any<String>(), any<KanbanAlertKind>())
                }
            } finally {
                monitor.stop()
                scope.cancel()
                connections.close()
                unmockkConstructor(KanbanEventsClient::class)
                unmockkObject(AuthManager)
            }
        }

    @Test
    fun `tags are scoped to board and task`() {
        assertNotEquals(kanbanNotificationTag("a", "t_1234abcd"), kanbanNotificationTag("b", "t_1234abcd"))
        assertNotEquals(kanbanNotificationTag("a", "t_1234abcd"), kanbanNotificationTag("a", "t_ffffffff"))
    }

    @Test
    fun `tags do not collide when the java hash does`() {
        assertEquals("Aa|t_1234abcd".hashCode(), "BB|t_1234abcd".hashCode())
        assertNotEquals(kanbanNotificationTag("Aa", "t_1234abcd"), kanbanNotificationTag("BB", "t_1234abcd"))
    }

    @Test
    fun `board selection dedupes rejects bad slugs and caps sockets`() {
        assertEquals(
            listOf("default", "board_2"),
            selectKanbanAlertBoards(listOf("", "default", "board_2", "a/b", "../oops", "default")),
        )
        assertEquals(12, selectKanbanAlertBoards((1..30).map { "board-$it" }).size)
    }

    @Test
    fun `task id pattern rejects forged ids`() {
        assertTrue(KANBAN_TASK_ID_PATTERN.matches("t_1234abcd"))
        assertFalse(KANBAN_TASK_ID_PATTERN.matches("1234abcd"))
        assertFalse(KANBAN_TASK_ID_PATTERN.matches("t_../x"))
    }

    @Test
    fun `board discovery returns every active board slug`() =
        runTest {
            val repository = mockk<KanbanRepository>()
            coEvery { repository.getBoards(includeArchived = false) } returns
                NetworkResult.Success(
                    KanbanBoardsResponse(boards = listOf(KanbanBoard(id = "default"), KanbanBoard(id = "other"))),
                )
            assertEquals(listOf("default", "other"), loadKanbanAlertBoards(repository))
        }

    @Test
    fun `board discovery failure yields no boards`() =
        runTest {
            val repository = mockk<KanbanRepository>()
            coEvery { repository.getBoards(includeArchived = false) } returns
                NetworkResult.Failure(NetworkError.Http(503, "unavailable"))
            assertEquals(emptyList<String>(), loadKanbanAlertBoards(repository))
        }
}
