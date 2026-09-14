package com.m57.hermescontrol.ui.kanban

import com.m57.hermescontrol.data.local.InMemoryKanbanPreferencesStore
import com.m57.hermescontrol.data.model.CreateTaskBody
import com.m57.hermescontrol.data.model.CreateTaskResponse
import com.m57.hermescontrol.data.model.KanbanBoard
import com.m57.hermescontrol.data.model.KanbanBoardResponse
import com.m57.hermescontrol.data.model.KanbanColumn
import com.m57.hermescontrol.data.model.KanbanTask
import com.m57.hermescontrol.data.model.UpdateTaskBody
import com.m57.hermescontrol.data.model.UpdateTaskResponse
import com.m57.hermescontrol.data.remote.NetworkError
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KanbanMutationTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockRepository = mockk<KanbanRepository>(relaxed = true)
    private val mockEventsClient = mockk<KanbanEventsClient>(relaxed = true)
    private val preferences = InMemoryKanbanPreferencesStore()

    private fun createViewModel(): KanbanViewModel =
        KanbanViewModel(
            repository = mockRepository,
            preferences = preferences,
            eventsClientProvider = { mockEventsClient },
            endpointProvider = { "http://127.0.0.1:9119" },
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testCreateTaskSuccessWithWarning() =
        runTest(testDispatcher) {
            val board = KanbanBoard(id = "dev", name = "Dev")
            val newTask = KanbanTask(id = "t_100", title = "New task", status = "todo")

            coEvery { mockRepository.getBoard("dev") } returns
                NetworkResult.Success(KanbanBoardResponse(columns = listOf(KanbanColumn("todo", emptyList()))))
            coEvery { mockRepository.createTask("dev", any()) } returns
                NetworkResult.Success(CreateTaskResponse(task = newTask, warning = "Dispatcher not running"))

            val vm = createViewModel()
            vm.selectBoard(board)
            testDispatcher.scheduler.advanceUntilIdle()

            vm.createTask("New task", "Description", "todo")
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify {
                mockRepository.createTask(
                    "dev",
                    match { it.title == "New task" && it.body == "Description" },
                )
            }
            assertTrue(
                vm.uiState.value.toastMessage
                    ?.contains("Dispatcher not running") == true,
            )
        }

    @Test
    fun testCreateTaskFailurePreservesStateAndShowsToast() =
        runTest(testDispatcher) {
            val board = KanbanBoard(id = "dev", name = "Dev")
            coEvery { mockRepository.getBoard("dev") } returns
                NetworkResult.Success(KanbanBoardResponse(columns = listOf(KanbanColumn("todo", emptyList()))))
            coEvery { mockRepository.createTask("dev", any()) } returns
                NetworkResult.Failure(NetworkError.Http(400, "Validation failed"))

            val vm = createViewModel()
            vm.selectBoard(board)
            testDispatcher.scheduler.advanceUntilIdle()

            vm.createTask("Bad task", null, "todo")
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(
                vm.uiState.value.toastMessage
                    ?.contains("Validation failed") == true,
            )
            assertFalse(vm.uiState.value.isCreatingTask)
        }

    @Test
    fun testMoveTaskUpdatesBothTasksAndColumnsOptimistically() =
        runTest(testDispatcher) {
            val board = KanbanBoard(id = "dev", name = "Dev")
            val task = KanbanTask(id = "t_1", title = "Task 1", status = "todo")
            val colTodo = KanbanColumn("todo", listOf(task))
            val colReady = KanbanColumn("ready", emptyList())

            val deferred = CompletableDeferred<NetworkResult<UpdateTaskResponse>>()

            coEvery { mockRepository.getBoard("dev") } returns
                NetworkResult.Success(KanbanBoardResponse(columns = listOf(colTodo, colReady)))
            coEvery { mockRepository.updateTask("t_1", "dev", any()) } coAnswers { deferred.await() }

            val vm = createViewModel()
            vm.selectBoard(board)
            testDispatcher.scheduler.advanceUntilIdle()

            // Initiate move
            vm.moveTask(task, KanbanTaskAction.READY)

            // Optimistic check: both tasks AND columns must reflect "ready"!
            assertEquals(
                "ready",
                vm.uiState.value.tasks
                    .first { it.id == "t_1" }
                    .status,
            )
            assertTrue(
                vm.uiState.value.columns
                    .first { it.name == "todo" }
                    .tasks
                    .none { it.id == "t_1" },
            )
            assertTrue(
                vm.uiState.value.columns
                    .first { it.name == "ready" }
                    .tasks
                    .any { it.id == "t_1" },
            )
            assertTrue(
                vm.uiState.value.operatingTaskIds
                    .contains("t_1"),
            )

            // Complete network call
            deferred.complete(NetworkResult.Success(UpdateTaskResponse(task = task.copy(status = "ready"))))
            testDispatcher.scheduler.advanceUntilIdle()

            assertFalse(
                vm.uiState.value.operatingTaskIds
                    .contains("t_1"),
            )
            assertEquals(
                "ready",
                vm.uiState.value.tasks
                    .first { it.id == "t_1" }
                    .status,
            )
        }

    @Test
    fun testMoveTaskFailureRevertsTasksAndColumns() =
        runTest(testDispatcher) {
            val board = KanbanBoard(id = "dev", name = "Dev")
            val task = KanbanTask(id = "t_1", title = "Task 1", status = "todo")
            val colTodo = KanbanColumn("todo", listOf(task))
            val colReady = KanbanColumn("ready", emptyList())

            coEvery { mockRepository.getBoard("dev") } returns
                NetworkResult.Success(KanbanBoardResponse(columns = listOf(colTodo, colReady)))
            coEvery { mockRepository.updateTask("t_1", "dev", any()) } returns
                NetworkResult.Failure(NetworkError.Http(409, "Parent blocked"))

            val vm = createViewModel()
            vm.selectBoard(board)
            testDispatcher.scheduler.advanceUntilIdle()

            vm.moveTask(task, KanbanTaskAction.READY)
            testDispatcher.scheduler.advanceUntilIdle()

            // Reverted back to "todo" in both tasks and columns!
            assertEquals(
                "todo",
                vm.uiState.value.tasks
                    .first { it.id == "t_1" }
                    .status,
            )
            assertTrue(
                vm.uiState.value.columns
                    .first { it.name == "todo" }
                    .tasks
                    .any { it.id == "t_1" },
            )
            assertTrue(
                vm.uiState.value.columns
                    .first { it.name == "ready" }
                    .tasks
                    .none { it.id == "t_1" },
            )
            assertTrue(
                vm.uiState.value.toastMessage
                    ?.contains("Parent blocked") == true,
            )
            assertFalse(
                vm.uiState.value.operatingTaskIds
                    .contains("t_1"),
            )
        }

    @Test
    fun testDuplicateConcurrentMoveIgnored() =
        runTest(testDispatcher) {
            val board = KanbanBoard(id = "dev", name = "Dev")
            val task = KanbanTask(id = "t_1", title = "Task 1", status = "todo")
            val colTodo = KanbanColumn("todo", listOf(task))

            val deferred = CompletableDeferred<NetworkResult<UpdateTaskResponse>>()
            coEvery { mockRepository.getBoard("dev") } returns
                NetworkResult.Success(KanbanBoardResponse(columns = listOf(colTodo)))
            coEvery { mockRepository.updateTask("t_1", "dev", any()) } coAnswers { deferred.await() }

            val vm = createViewModel()
            vm.selectBoard(board)
            testDispatcher.scheduler.advanceUntilIdle()

            // First tap
            vm.moveTask(task, KanbanTaskAction.READY)
            // Second duplicate tap while in-flight
            vm.moveTask(task, KanbanTaskAction.ARCHIVE)

            testDispatcher.scheduler.runCurrent()

            // Only 1 updateTask call initiated
            coVerify(exactly = 1) { mockRepository.updateTask("t_1", "dev", any()) }
        }

    @Test
    fun testCreateTaskWithFullOptionsAndStatusAlignment() =
        runTest(testDispatcher) {
            val board = KanbanBoard(id = "dev", name = "Dev")
            val returnedTask = KanbanTask(id = "t_101", title = "Complex Task", status = "ready")

            coEvery { mockRepository.getBoard("dev") } returns
                NetworkResult.Success(KanbanBoardResponse(columns = listOf(KanbanColumn("todo", emptyList()))))
            coEvery { mockRepository.createTask("dev", any()) } returns
                NetworkResult.Success(CreateTaskResponse(task = returnedTask))
            coEvery { mockRepository.updateTask("t_101", "dev", any()) } returns
                NetworkResult.Success(UpdateTaskResponse(task = returnedTask.copy(status = "todo")))

            val vm = createViewModel()
            vm.selectBoard(board)
            testDispatcher.scheduler.advanceUntilIdle()

            val body =
                CreateTaskBody(
                    title = "Complex Task",
                    body = "Rich spec",
                    assignee = "reviewer",
                    priority = 2,
                    goalMode = true,
                    modelOverride = "claude-3-5-sonnet",
                    providerOverride = "anthropic",
                    reasoningEffort = "high",
                    skills = listOf("kotlin", "compose"),
                    parents = listOf("t_parent"),
                    workspaceKind = "worktree",
                )

            vm.createTask(body = body, targetStatus = "todo")
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify {
                mockRepository.createTask(
                    "dev",
                    match {
                        it.title == "Complex Task" &&
                            it.body == "Rich spec" &&
                            it.assignee == "reviewer" &&
                            it.priority == 2 &&
                            it.goalMode &&
                            it.modelOverride == "claude-3-5-sonnet" &&
                            it.providerOverride == "anthropic" &&
                            it.reasoningEffort == "high" &&
                            it.skills == listOf("kotlin", "compose") &&
                            it.parents == listOf("t_parent") &&
                            it.workspaceKind == "worktree"
                    },
                )
            }

            // Since backend returned "ready" but user wanted "todo", status alignment update is sent
            coVerify {
                mockRepository.updateTask(
                    "t_101",
                    "dev",
                    match { it.status == "todo" },
                )
            }
        }

    @Test
    fun testLoadProfilesPopulatesState() =
        runTest(testDispatcher) {
            val profiles =
                listOf(
                    com.m57.hermescontrol.data.model
                        .KanbanProfile(name = "researcher", model = "gpt-4o"),
                    com.m57.hermescontrol.data.model
                        .KanbanProfile(name = "reviewer", model = "claude-3-5-sonnet"),
                )
            coEvery { mockRepository.getProfiles() } returns
                NetworkResult.Success(
                    com.m57.hermescontrol.data.model
                        .KanbanProfilesResponse(profiles),
                )

            val vm = createViewModel()
            vm.loadProfiles()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(2, vm.uiState.value.profiles.size)
            assertEquals(
                "researcher",
                vm.uiState.value.profiles[0]
                    .name,
            )
            assertEquals(
                "reviewer",
                vm.uiState.value.profiles[1]
                    .name,
            )
        }
}
