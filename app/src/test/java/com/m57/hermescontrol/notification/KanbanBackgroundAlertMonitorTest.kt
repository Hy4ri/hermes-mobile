package com.m57.hermescontrol.notification

import com.m57.hermescontrol.data.model.KanbanBoard
import com.m57.hermescontrol.data.model.KanbanBoardsResponse
import com.m57.hermescontrol.data.remote.NetworkError
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.repository.KanbanRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
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
