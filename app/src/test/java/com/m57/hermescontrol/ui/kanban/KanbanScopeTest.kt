package com.m57.hermescontrol.ui.kanban

import com.m57.hermescontrol.data.local.InMemoryKanbanPreferencesStore
import com.m57.hermescontrol.data.model.KanbanBoard
import com.m57.hermescontrol.data.model.KanbanBoardResponse
import com.m57.hermescontrol.data.model.KanbanBoardsResponse
import com.m57.hermescontrol.data.model.KanbanColumn
import com.m57.hermescontrol.data.model.KanbanTask
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.repository.KanbanRepository
import com.m57.hermescontrol.data.ws.KanbanEventsClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KanbanScopeTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockRepository = mockk<KanbanRepository>(relaxed = true)
    private val mockEventsClient = mockk<KanbanEventsClient>(relaxed = true)
    private val preferences = InMemoryKanbanPreferencesStore()

    private fun createViewModel(): KanbanViewModel =
        KanbanViewModel(
            repository = mockRepository,
            preferences = preferences,
            eventsClientProvider = { mockEventsClient },
        ).also {
            it.ioDispatcher = testDispatcher
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testBoardSwitchScopesCorrectlyForSameTaskId() =
        runTest(testDispatcher) {
            val boardA = KanbanBoard(id = "board_a", name = "Board A")
            val boardB = KanbanBoard(id = "board_b", name = "Board B")

            val taskA = KanbanTask(id = "t_1", title = "Task in Board A", status = "todo")
            val taskB = KanbanTask(id = "t_1", title = "Task in Board B", status = "ready")

            coEvery { mockRepository.getBoards() } returns
                NetworkResult.Success(KanbanBoardsResponse(boards = listOf(boardA, boardB), current = "board_a"))
            coEvery { mockRepository.getBoard("board_a") } returns
                NetworkResult.Success(KanbanBoardResponse(columns = listOf(KanbanColumn("todo", listOf(taskA)))))
            coEvery { mockRepository.getBoard("board_b") } returns
                NetworkResult.Success(KanbanBoardResponse(columns = listOf(KanbanColumn("ready", listOf(taskB)))))

            val vm = createViewModel()
            vm.loadBoards()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                "board_a",
                vm.uiState.value.selectedBoard
                    ?.id,
            )
            assertEquals(
                "Task in Board A",
                vm.uiState.value.tasks
                    .first()
                    .title,
            )

            // Switch to board B
            vm.selectBoard(boardB)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                "board_b",
                vm.uiState.value.selectedBoard
                    ?.id,
            )
            assertEquals(
                "Task in Board B",
                vm.uiState.value.tasks
                    .first()
                    .title,
            )
            assertEquals(
                "ready",
                vm.uiState.value.tasks
                    .first()
                    .status,
            )
        }

    @Test
    fun testDelayedLateResponseDoesNotOverwriteNewerSelection() =
        runTest(testDispatcher) {
            val boardA = KanbanBoard(id = "board_a", name = "Board A")
            val boardB = KanbanBoard(id = "board_b", name = "Board B")

            val taskA = KanbanTask(id = "t_a", title = "Task A", status = "todo")
            val taskB = KanbanTask(id = "t_b", title = "Task B", status = "todo")

            val deferredB = CompletableDeferred<NetworkResult<KanbanBoardResponse>>()

            coEvery { mockRepository.getBoard("board_a") } returns
                NetworkResult.Success(KanbanBoardResponse(columns = listOf(KanbanColumn("todo", listOf(taskA)))))
            coEvery { mockRepository.getBoard("board_b") } coAnswers { deferredB.await() }

            val vm = createViewModel()

            // User selects board B (deferred response)
            vm.selectBoard(boardB)
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(
                "board_b",
                vm.uiState.value.selectedBoard
                    ?.id,
            )

            // User quickly selects board A before B responds
            vm.selectBoard(boardA)
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(
                "board_a",
                vm.uiState.value.selectedBoard
                    ?.id,
            )
            assertEquals(
                "Task A",
                vm.uiState.value.tasks
                    .first()
                    .title,
            )

            // Now B finally resolves late
            deferredB.complete(
                NetworkResult.Success(KanbanBoardResponse(columns = listOf(KanbanColumn("todo", listOf(taskB))))),
            )
            testDispatcher.scheduler.advanceUntilIdle()

            // State MUST remain Board A and Task A!
            assertEquals(
                "board_a",
                vm.uiState.value.selectedBoard
                    ?.id,
            )
            assertEquals(
                "Task A",
                vm.uiState.value.tasks
                    .first()
                    .title,
            )
        }

    @Test
    fun testBoardListRefreshPreservesUserSelection() =
        runTest(testDispatcher) {
            val boardA = KanbanBoard(id = "board_a", name = "Board A")
            val boardB = KanbanBoard(id = "board_b", name = "Board B")

            coEvery { mockRepository.getBoards() } returns
                NetworkResult.Success(KanbanBoardsResponse(boards = listOf(boardA, boardB), current = "board_a"))
            coEvery { mockRepository.getBoard("board_b") } returns
                NetworkResult.Success(KanbanBoardResponse(columns = listOf(KanbanColumn("todo", emptyList()))))

            val vm = createViewModel()
            vm.selectBoard(boardB)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                "board_b",
                vm.uiState.value.selectedBoard
                    ?.id,
            )

            // Refresh boards — server says current is "board_a"
            vm.loadBoards()
            testDispatcher.scheduler.advanceUntilIdle()

            // Local user selection "board_b" must be preserved!
            assertEquals(
                "board_b",
                vm.uiState.value.selectedBoard
                    ?.id,
            )
        }

    @Test
    fun testEmptyBoardListHandledGracefully() =
        runTest(testDispatcher) {
            coEvery { mockRepository.getBoards() } returns
                NetworkResult.Success(KanbanBoardsResponse(boards = emptyList(), current = null))

            val vm = createViewModel()
            vm.loadBoards()
            testDispatcher.scheduler.advanceUntilIdle()

            assertNull(vm.uiState.value.selectedBoard)
            assertTrue(
                vm.uiState.value.tasks
                    .isEmpty(),
            )
            assertTrue(
                vm.uiState.value.columns
                    .isEmpty(),
            )
        }

    @Test
    fun testZeroSwitchCallsDuringNavigation() =
        runTest(testDispatcher) {
            val boardA = KanbanBoard(id = "board_a", name = "Board A")
            val boardB = KanbanBoard(id = "board_b", name = "Board B")

            coEvery { mockRepository.getBoards() } returns
                NetworkResult.Success(KanbanBoardsResponse(boards = listOf(boardA, boardB), current = "board_a"))
            coEvery { mockRepository.getBoard(any()) } returns
                NetworkResult.Success(KanbanBoardResponse())

            val vm = createViewModel()
            vm.loadBoards()
            testDispatcher.scheduler.advanceUntilIdle()

            vm.selectBoard(boardB)
            testDispatcher.scheduler.advanceUntilIdle()

            // Zero calls to switchBoard anywhere!
            coVerify(exactly = 0) { mockRepository.updateBoard(any(), any()) }
        }
}
