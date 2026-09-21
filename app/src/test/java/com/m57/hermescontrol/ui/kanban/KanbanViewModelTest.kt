package com.m57.hermescontrol.ui.kanban

import com.m57.hermescontrol.data.model.KanbanBoard
import com.m57.hermescontrol.data.model.KanbanBoardResponse
import com.m57.hermescontrol.data.model.KanbanBoardsResponse
import com.m57.hermescontrol.data.model.KanbanColumn
import com.m57.hermescontrol.data.model.KanbanTask
import com.m57.hermescontrol.data.model.UpdateTaskResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.KanbanApiService
import com.m57.hermescontrol.data.repository.KanbanDispatcherNudger
import com.m57.hermescontrol.data.repository.KanbanRepositoryImpl
import com.m57.hermescontrol.data.ws.KanbanEvent
import com.m57.hermescontrol.data.ws.KanbanEventsClient
import com.m57.hermescontrol.data.ws.KanbanEventsEnvelope
import com.m57.hermescontrol.data.ws.KanbanLiveStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class KanbanViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockApi = mockk<KanbanApiService>(relaxed = true)
    private val mockEventsClient = mockk<KanbanEventsClient>(relaxed = true)

    private fun createViewModel(): KanbanViewModel {
        val mockNudger = mockk<KanbanDispatcherNudger>(relaxed = true)
        val repo =
            KanbanRepositoryImpl(
                apiProvider = { mockApi },
                ioDispatcher = testDispatcher,
                nudger = mockNudger,
            )
        val vm =
            KanbanViewModel(
                repository = repo,
                eventsClientProvider = { mockEventsClient },
            )
        vm.ioDispatcher = testDispatcher
        testDispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    private fun settle() {
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun stubBoard(tasks: List<KanbanTask> = listOf(KanbanTask(id = "t1", title = "Task 1", status = "todo"))) {
        coEvery { mockApi.getBoards(any()) } returns
            Response.success(
                KanbanBoardsResponse(
                    boards = listOf(KanbanBoard(id = "work", name = "Work")),
                    current = "work",
                ),
            )
        coEvery { mockApi.getBoard(board = "work", any(), any()) } returns
            Response.success(KanbanBoardResponse(columns = listOf(KanbanColumn(name = "todo", tasks = tasks))))
        coEvery { mockApi.getBoard(board = "ops", any(), any()) } returns
            Response.success(KanbanBoardResponse(columns = listOf(KanbanColumn(name = "todo", tasks = emptyList()))))
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ApiClient)
        every { ApiClient.kanbanApi } returns mockApi
        stubBoard()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `loadBoards connects events stream for current board`() {
        val vm = createViewModel()
        vm.loadBoards()
        settle()
        verify { mockEventsClient.connect(any(), eq("work"), any(), any(), any()) }
    }

    @Test
    fun `connected status flips isLive`() {
        val vm = createViewModel()
        vm.loadBoards()
        settle()
        val statusSlot = slot<(KanbanLiveStatus) -> Unit>()
        verify { mockEventsClient.connect(any(), any(), any(), any(), capture(statusSlot)) }
        statusSlot.captured(KanbanLiveStatus.CONNECTED)
        assertTrue(vm.uiState.value.isLive)
        statusSlot.captured(KanbanLiveStatus.DISCONNECTED)
        assertFalse(vm.uiState.value.isLive)
    }

    @Test
    fun `events batch triggers debounced board reload`() {
        val vm = createViewModel()
        vm.loadBoards()
        settle()
        val eventsSlot = slot<(KanbanEventsEnvelope) -> Unit>()
        verify { mockEventsClient.connect(any(), any(), any(), capture(eventsSlot), any()) }

        coEvery { mockApi.getBoard(board = "work", any(), any()) } returns
            Response.success(
                KanbanBoardResponse(
                    columns =
                        listOf(
                            KanbanColumn(
                                name = "todo",
                                tasks =
                                    listOf(
                                        KanbanTask(id = "t1", title = "Task 1", status = "todo"),
                                        KanbanTask(id = "t2", title = "Task 2", status = "todo"),
                                    ),
                            ),
                        ),
                ),
            )

        eventsSlot.captured(KanbanEventsEnvelope(events = listOf(KanbanEvent(id = 1, kind = "created")), cursor = 1))
        testDispatcher.scheduler.advanceTimeBy(249)
        assertEquals(1, vm.uiState.value.tasks.size)
        testDispatcher.scheduler.advanceTimeBy(1)
        settle()
        assertEquals(2, vm.uiState.value.tasks.size)
        coVerify(atLeast = 2) { mockApi.getBoard(board = "work", any(), any()) }
    }

    @Test
    fun `board switch reconnects events stream with new board`() {
        val vm = createViewModel()
        vm.loadBoards()
        settle()
        vm.selectBoard(KanbanBoard(id = "ops", name = "Ops"))
        settle()
        verify { mockEventsClient.connect(any(), eq("ops"), any(), any(), any()) }
    }

    @Test
    fun `re-selecting already-live board does not reconnect`() {
        val vm = createViewModel()
        vm.loadBoards()
        settle()
        val statusSlot = slot<(KanbanLiveStatus) -> Unit>()
        verify { mockEventsClient.connect(any(), any(), any(), any(), capture(statusSlot)) }
        statusSlot.captured(KanbanLiveStatus.CONNECTED)

        vm.selectBoard(KanbanBoard(id = "work", name = "Work"))
        settle()
        verify(exactly = 1) { mockEventsClient.connect(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `onCleared disconnects events stream`() {
        val vm = createViewModel()
        vm.loadBoards()
        settle()
        val method = KanbanViewModel::class.java.getDeclaredMethod("onCleared")
        method.isAccessible = true
        method.invoke(vm)
        verify { mockEventsClient.disconnect() }
    }

    @Test
    fun `kanbanActionsForStatus gates transitions like the desktop`() {
        assertEquals(
            listOf(KanbanTaskAction.TRIAGE, KanbanTaskAction.READY, KanbanTaskAction.ARCHIVE),
            kanbanActionsForStatus("todo"),
        )
        assertEquals(
            listOf(
                KanbanTaskAction.TRIAGE,
                KanbanTaskAction.UNBLOCK,
                KanbanTaskAction.COMPLETE,
                KanbanTaskAction.ARCHIVE,
            ),
            kanbanActionsForStatus("blocked"),
        )
        assertEquals(
            listOf(
                KanbanTaskAction.TRIAGE,
                KanbanTaskAction.READY,
                KanbanTaskAction.BLOCK,
                KanbanTaskAction.COMPLETE,
                KanbanTaskAction.ARCHIVE,
            ),
            kanbanActionsForStatus("running"),
        )
        assertEquals(
            listOf(KanbanTaskAction.TRIAGE, KanbanTaskAction.READY, KanbanTaskAction.ARCHIVE),
            kanbanActionsForStatus("done"),
        )
        // Backend-rejected targets are never offered.
        assertFalse(kanbanActionsForStatus("todo").any { it.targetStatus == "review" })
        assertFalse(kanbanActionsForStatus("todo").any { it.targetStatus == "running" })
        assertFalse(kanbanActionsForStatus("ready").any { it.targetStatus == "running" })
    }

    @Test
    fun `moveTask updates status and PATCHes the target`() {
        val vm = createViewModel()
        vm.loadBoards()
        settle()
        coEvery { mockApi.updateTask(any(), any(), any()) } returns Response.success(UpdateTaskResponse())
        vm.moveTask(KanbanTask(id = "t1", title = "Task 1", status = "todo"), KanbanTaskAction.READY)
        settle()
        assertEquals(
            "ready",
            vm.uiState.value.tasks
                .first { it.id == "t1" }
                .status,
        )
        coVerify { mockApi.updateTask("t1", "work", match { it.status == "ready" }) }
    }

    @Test
    fun `moveTask failure reverts status and shows toast`() {
        val vm = createViewModel()
        vm.loadBoards()
        settle()
        coEvery { mockApi.updateTask(any(), any(), any()) } returns Response.error(409, "".toResponseBody())
        vm.moveTask(KanbanTask(id = "t1", title = "Task 1", status = "todo"), KanbanTaskAction.READY)
        settle()
        assertEquals(
            "todo",
            vm.uiState.value.tasks
                .first { it.id == "t1" }
                .status,
        )
        assertTrue(
            vm.uiState.value.toastMessage
                ?.contains("Move failed") == true,
        )
    }

    @Test
    fun `moveTask complete sends the completion summary`() {
        val vm = createViewModel()
        vm.loadBoards()
        settle()
        coEvery { mockApi.updateTask(any(), any(), any()) } returns Response.success(UpdateTaskResponse())
        vm.moveTask(
            KanbanTask(id = "t1", title = "Task 1", status = "ready"),
            KanbanTaskAction.COMPLETE,
            summary = "Shipped it",
        )
        settle()
        coVerify {
            mockApi.updateTask(
                "t1",
                "work",
                match { it.status == "done" && it.summary == "Shipped it" && it.result == "Shipped it" },
            )
        }
    }
}
