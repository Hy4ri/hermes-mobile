package com.m57.hermescontrol.ui.kanban

import com.m57.hermescontrol.data.model.KanbanBoard
import com.m57.hermescontrol.data.model.KanbanBoardResponse
import com.m57.hermescontrol.data.model.KanbanBoardsResponse
import com.m57.hermescontrol.data.model.KanbanColumn
import com.m57.hermescontrol.data.model.KanbanTask
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
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
import kotlinx.coroutines.test.advanceUntilIdle
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
    private val mockApi = mockk<HermesApiService>(relaxed = true)
    private val mockEventsClient = mockk<KanbanEventsClient>(relaxed = true)

    private fun createViewModel(): KanbanViewModel {
        val vm = KanbanViewModel(eventsClientProvider = { mockEventsClient })
        testDispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    /**
     * Pump the test scheduler while letting the real Dispatchers.IO hops
     * (safeLaunchLoad / withContext(IO)) land their resumptions.
     */
    private fun settle() {
        repeat(20) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(10)
        }
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun stubBoard(tasks: List<KanbanTask> = listOf(KanbanTask(id = "t1", title = "Task 1", status = "todo"))) {
        coEvery { mockApi.getKanbanBoards() } returns
            Response.success(
                KanbanBoardsResponse(
                    boards = listOf(KanbanBoard(id = "work", name = "Work")),
                    current = "work",
                ),
            )
        coEvery { mockApi.switchKanbanBoard("work") } returns Response.success(Unit)
        coEvery { mockApi.getKanbanBoard() } returns
            Response.success(KanbanBoardResponse(columns = listOf(KanbanColumn(name = "todo", tasks = tasks))))
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ApiClient)
        every { ApiClient.hermesApi } returns mockApi
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

        coEvery { mockApi.getKanbanBoard() } returns
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
        coVerify(exactly = 2) { mockApi.getKanbanBoard() }
    }

    @Test
    fun `board switch reconnects events stream with new board`() {
        val vm = createViewModel()
        vm.loadBoards()
        settle()
        coEvery { mockApi.switchKanbanBoard("ops") } returns Response.success(Unit)
        coEvery { mockApi.getKanbanBoard() } returns
            Response.success(KanbanBoardResponse(columns = listOf(KanbanColumn(name = "todo", tasks = emptyList()))))
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
        coEvery { mockApi.updateKanbanTask(any(), any()) } returns Response.success(Unit)
        vm.moveTask(KanbanTask(id = "t1", title = "Task 1", status = "todo"), KanbanTaskAction.READY)
        settle()
        assertEquals("ready", vm.uiState.value.tasks.first { it.id == "t1" }.status)
        coVerify { mockApi.updateKanbanTask("t1", mapOf("status" to "ready")) }
    }

    @Test
    fun `moveTask failure reverts status and shows toast`() {
        val vm = createViewModel()
        vm.loadBoards()
        settle()
        coEvery { mockApi.updateKanbanTask(any(), any()) } returns Response.error(409, "".toResponseBody())
        vm.moveTask(KanbanTask(id = "t1", title = "Task 1", status = "todo"), KanbanTaskAction.READY)
        settle()
        assertEquals("todo", vm.uiState.value.tasks.first { it.id == "t1" }.status)
        assertTrue(vm.uiState.value.toastMessage?.contains("Move failed") == true)
    }

    @Test
    fun `moveTask complete sends the completion summary`() {
        val vm = createViewModel()
        vm.loadBoards()
        settle()
        coEvery { mockApi.updateKanbanTask(any(), any()) } returns Response.success(Unit)
        vm.moveTask(
            KanbanTask(id = "t1", title = "Task 1", status = "ready"),
            KanbanTaskAction.COMPLETE,
            summary = "Shipped it",
        )
        settle()
        coVerify {
            mockApi.updateKanbanTask(
                "t1",
                mapOf("status" to "done", "result" to "Shipped it", "summary" to "Shipped it"),
            )
        }
    }
}
