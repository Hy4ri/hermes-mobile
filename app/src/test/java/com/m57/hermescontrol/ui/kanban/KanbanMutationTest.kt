package com.m57.hermescontrol.ui.kanban

import com.m57.hermescontrol.data.local.InMemoryKanbanPreferencesStore
import com.m57.hermescontrol.data.model.BulkTaskResult
import com.m57.hermescontrol.data.model.BulkTasksResponse
import com.m57.hermescontrol.data.model.CreateBoardResponse
import com.m57.hermescontrol.data.model.CreateTaskBody
import com.m57.hermescontrol.data.model.CreateTaskResponse
import com.m57.hermescontrol.data.model.DeleteBoardResponse
import com.m57.hermescontrol.data.model.KanbanBoard
import com.m57.hermescontrol.data.model.KanbanBoardResponse
import com.m57.hermescontrol.data.model.KanbanColumn
import com.m57.hermescontrol.data.model.KanbanTask
import com.m57.hermescontrol.data.model.RenameBoardResponse
import com.m57.hermescontrol.data.model.TaskEstimate
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
            coEvery { mockRepository.getBoard("dev") } returns
                NetworkResult.Success(
                    KanbanBoardResponse(
                        columns =
                            listOf(
                                KanbanColumn("todo", emptyList()),
                                KanbanColumn("ready", listOf(task.copy(status = "ready"))),
                            ),
                    ),
                )
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

    @Test
    fun testCreateBoardSuccess() =
        runTest(testDispatcher) {
            val board = KanbanBoard(id = "sprint-2", name = "Sprint 2")
            coEvery { mockRepository.createBoard(any()) } returns
                NetworkResult.Success(CreateBoardResponse(board = board))
            coEvery { mockRepository.getBoards() } returns
                NetworkResult.Success(
                    com.m57.hermescontrol.data.model
                        .KanbanBoardsResponse(listOf(board), "sprint-2"),
                )

            val vm = createViewModel()
            vm.createBoard(slug = "sprint-2", name = "Sprint 2", description = "Next cycle")
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify {
                mockRepository.createBoard(
                    match {
                        it.slug == "sprint-2" && it.name == "Sprint 2" && it.description == "Next cycle"
                    },
                )
            }
            assertEquals("Board created: Sprint 2", vm.uiState.value.toastMessage)
        }

    @Test
    fun testBulkAssignUsesReclaimAndRetainsOnlyFailures() =
        runTest(testDispatcher) {
            val board = KanbanBoard(id = "dev", name = "Dev")
            coEvery { mockRepository.getBoard("dev") } returns
                NetworkResult.Success(KanbanBoardResponse(columns = listOf(KanbanColumn("todo", emptyList()))))
            coEvery { mockRepository.bulkTasks("dev", any()) } returns
                NetworkResult.Success(
                    BulkTasksResponse(
                        results =
                            listOf(
                                BulkTaskResult("t_1", ok = true),
                                BulkTaskResult("t_2", ok = false, error = "locked"),
                            ),
                    ),
                )

            val vm = createViewModel()
            vm.selectBoard(board)
            testDispatcher.scheduler.advanceUntilIdle()

            var failedIds = emptySet<String>()
            vm.bulkAssign(listOf("t_1", "t_2"), assignee = null) { failedIds = it }
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify {
                mockRepository.bulkTasks(
                    "dev",
                    match { it.ids == listOf("t_1", "t_2") && it.assignee == "" && it.reclaimFirst },
                )
            }
            assertEquals(setOf("t_2"), failedIds)
        }

    @Test
    fun testRenameBoardSuccess() =
        runTest(testDispatcher) {
            val board = KanbanBoard(id = "dev", name = "Dev Renamed")
            coEvery { mockRepository.updateBoard("dev", any()) } returns
                NetworkResult.Success(RenameBoardResponse(board = board))
            coEvery { mockRepository.getBoards() } returns
                NetworkResult.Success(
                    com.m57.hermescontrol.data.model
                        .KanbanBoardsResponse(listOf(board), "dev"),
                )

            val vm = createViewModel()
            vm.renameBoard("dev", "Dev Renamed")
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify {
                mockRepository.updateBoard("dev", match { it.name == "Dev Renamed" })
            }
            assertEquals("Board updated: Dev Renamed", vm.uiState.value.toastMessage)
        }

    @Test
    fun testDeleteBoardSuccess() =
        runTest(testDispatcher) {
            coEvery { mockRepository.deleteBoard("old-board", delete = false) } returns
                NetworkResult.Success(DeleteBoardResponse(current = null))
            coEvery { mockRepository.getBoards(any()) } returns
                NetworkResult.Success(
                    com.m57.hermescontrol.data.model
                        .KanbanBoardsResponse(emptyList(), null),
                )

            val vm = createViewModel()
            preferences.setSelectedBoard("http://127.0.0.1:9119", "old-board")
            vm.deleteBoard("old-board")
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { mockRepository.deleteBoard("old-board", delete = false) }
            assertEquals("Board archived", vm.uiState.value.toastMessage)
            assertEquals(null, preferences.getSelectedBoard("http://127.0.0.1:9119"))
        }

    @Test
    fun testDeleteDefaultBoardRefused() =
        runTest(testDispatcher) {
            val vm = createViewModel()
            vm.deleteBoard("default")
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 0) { mockRepository.deleteBoard(any(), any()) }
            assertTrue(
                vm.uiState.value.toastMessage
                    ?.contains("default board") == true,
            )
        }

    @Test
    fun testSetIncludeArchivedTogglesAndReloadsBoard() =
        runTest(testDispatcher) {
            val board = KanbanBoard(id = "dev", name = "Dev")
            coEvery { mockRepository.getBoard(board = "dev", includeArchived = any(), any()) } returns
                NetworkResult.Success(KanbanBoardResponse(columns = listOf(KanbanColumn("todo", emptyList()))))

            val vm = createViewModel()
            vm.selectBoard(board)
            testDispatcher.scheduler.advanceUntilIdle()

            vm.setIncludeArchived(true)
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(vm.uiState.value.includeArchived)
            coVerify { mockRepository.getBoard(board = "dev", includeArchived = true, any()) }

            vm.setIncludeArchived(false)
            testDispatcher.scheduler.advanceUntilIdle()

            assertFalse(vm.uiState.value.includeArchived)
            coVerify { mockRepository.getBoard(board = "dev", includeArchived = false, any()) }
        }

    @Test
    fun testBulkMovePartialFailureKeepsFailedIds() =
        runTest(testDispatcher) {
            val board = KanbanBoard(id = "dev", name = "Dev")
            coEvery { mockRepository.getBoard("dev", any(), any()) } returns
                NetworkResult.Success(KanbanBoardResponse(columns = listOf(KanbanColumn("done", emptyList()))))
            coEvery { mockRepository.bulkTasks("dev", any()) } returns
                NetworkResult.Success(
                    BulkTasksResponse(
                        listOf(
                            BulkTaskResult("t_1", true),
                            BulkTaskResult("t_2", false, "Conflict"),
                        ),
                    ),
                )

            val vm = createViewModel()
            vm.selectBoard(board)
            testDispatcher.scheduler.advanceUntilIdle()

            var reportedFailed: Set<String>? = null
            vm.bulkMove(listOf("t_1", "t_2"), "done", summary = "Finished selected tasks") { failed ->
                reportedFailed = failed
            }
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(setOf("t_2"), reportedFailed)
            assertTrue(
                vm.uiState.value.toastMessage
                    ?.contains("1 failed") == true,
            )
        }

    @Test
    fun testCreateBoardWithProject() =
        runTest(testDispatcher) {
            val board = KanbanBoard(id = "scoped", name = "Scoped", projectId = "proj-123")
            coEvery { mockRepository.createBoard(any()) } returns
                NetworkResult.Success(CreateBoardResponse(board = board))
            coEvery { mockRepository.getBoards(any()) } returns
                NetworkResult.Success(
                    com.m57.hermescontrol.data.model
                        .KanbanBoardsResponse(listOf(board), "scoped"),
                )

            val vm = createViewModel()
            vm.createBoard(slug = "scoped", name = "Scoped", projectId = "proj-123")
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify {
                mockRepository.createBoard(
                    match { it.slug == "scoped" && it.projectId == "proj-123" },
                )
            }
        }

    @Test
    fun testBulkMoveTasksSuccess() =
        runTest(testDispatcher) {
            val board = KanbanBoard(id = "dev", name = "Dev")
            coEvery { mockRepository.getBoard("dev") } returns
                NetworkResult.Success(KanbanBoardResponse(columns = listOf(KanbanColumn("done", emptyList()))))
            coEvery { mockRepository.bulkTasks("dev", any()) } returns
                NetworkResult.Success(
                    BulkTasksResponse(
                        listOf(
                            BulkTaskResult("t_1", true),
                            BulkTaskResult("t_2", true),
                        ),
                    ),
                )

            val vm = createViewModel()
            vm.selectBoard(board)
            testDispatcher.scheduler.advanceUntilIdle()

            vm.bulkMove(listOf("t_1", "t_2"), "done", summary = "Finished selected tasks")
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify {
                mockRepository.bulkTasks(
                    "dev",
                    match {
                        it.ids == listOf("t_1", "t_2") &&
                            it.status == "done" &&
                            it.summary == "Finished selected tasks" &&
                            it.result == "Finished selected tasks"
                    },
                )
            }
            assertEquals("Moved 2 tasks to done", vm.uiState.value.toastMessage)
        }

    @Test
    fun testBulkMoveToDoneRejectsEmptyCompletionEvidence() =
        runTest(testDispatcher) {
            val board = KanbanBoard(id = "dev", name = "Dev")
            coEvery { mockRepository.getBoard("dev") } returns
                NetworkResult.Success(KanbanBoardResponse(columns = listOf(KanbanColumn("done", emptyList()))))

            val vm = createViewModel()
            vm.selectBoard(board)
            testDispatcher.scheduler.advanceUntilIdle()

            var reportedFailed: Set<String>? = null
            vm.bulkMove(listOf("t_1", "t_2"), "done", summary = "   ") { failed ->
                reportedFailed = failed
            }
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 0) { mockRepository.bulkTasks(any(), any()) }
            assertEquals(setOf("t_1", "t_2"), reportedFailed)
            assertEquals("Completion summary is required", vm.uiState.value.toastMessage)
        }

    @Test
    fun testBulkArchiveTasksSuccess() =
        runTest(testDispatcher) {
            val board = KanbanBoard(id = "dev", name = "Dev")
            coEvery { mockRepository.getBoard("dev") } returns
                NetworkResult.Success(KanbanBoardResponse(columns = listOf(KanbanColumn("todo", emptyList()))))
            coEvery { mockRepository.bulkTasks("dev", any()) } returns
                NetworkResult.Success(
                    BulkTasksResponse(
                        listOf(
                            BulkTaskResult("t_1", true),
                        ),
                    ),
                )

            val vm = createViewModel()
            vm.selectBoard(board)
            testDispatcher.scheduler.advanceUntilIdle()

            vm.bulkArchive(listOf("t_1"))
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify {
                mockRepository.bulkTasks(
                    "dev",
                    match { it.ids == listOf("t_1") && it.archive },
                )
            }
            assertEquals("Archived 1 tasks", vm.uiState.value.toastMessage)
        }

    @Test
    fun testEstimateNewTaskSuccess() =
        runTest(testDispatcher) {
            coEvery { mockRepository.estimateNew("Title", "Body") } returns
                NetworkResult.Success(TaskEstimate(ok = true, estTokens = 1200, complexity = "low"))

            val vm = createViewModel()
            val estimate = vm.estimateNewTask("Title", "Body")

            assertEquals(1200, estimate?.estTokens)
            assertEquals("low", estimate?.complexity)
        }
}
