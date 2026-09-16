package com.m57.hermescontrol.ui.kanban

import com.m57.hermescontrol.data.model.CreateTaskBody
import com.m57.hermescontrol.data.model.CreateTaskResponse
import com.m57.hermescontrol.data.model.KanbanBoard
import com.m57.hermescontrol.data.model.KanbanBoardResponse
import com.m57.hermescontrol.data.model.KanbanColumn
import com.m57.hermescontrol.data.model.KanbanTask
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.repository.KanbanRepository
import com.m57.hermescontrol.data.ws.KanbanEventsClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
class KanbanCreateTaskPayloadTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockRepository = mockk<KanbanRepository>(relaxed = true)
    private val mockEventsClient = mockk<KanbanEventsClient>(relaxed = true)

    private fun createViewModel(): KanbanViewModel =
        KanbanViewModel(
            repository = mockRepository,
            eventsClientProvider = { mockEventsClient },
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
    fun testCreateTaskPayloadWithAllFields() =
        runTest(testDispatcher) {
            val board = KanbanBoard(id = "dev", name = "Dev")
            val newTask = KanbanTask(id = "t_100", title = "Full task", status = "todo")

            coEvery { mockRepository.getBoard("dev", any(), any()) } returns
                NetworkResult.Success(KanbanBoardResponse(columns = listOf(KanbanColumn("todo", emptyList()))))
            coEvery { mockRepository.createTask("dev", any()) } returns
                NetworkResult.Success(CreateTaskResponse(task = newTask))

            val vm = createViewModel()
            vm.selectBoard(board)
            testDispatcher.scheduler.advanceUntilIdle()

            val body =
                CreateTaskBody(
                    title = "Full task",
                    body = "Rich details",
                    assignee = "researcher",
                    priority = 2,
                    goalMode = true,
                    parents = listOf("p_1", "p_2"),
                    modelOverride = "claude-3-5-sonnet",
                    providerOverride = "anthropic",
                    reasoningEffort = "high",
                    workspaceKind = "worktree",
                    workspacePath = "/custom/wt/path",
                    skills = listOf("git", "testing"),
                    triage = true,
                )

            vm.createTask(body, targetStatus = "triage")
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify {
                mockRepository.createTask(
                    "dev",
                    match {
                        it.title == "Full task" &&
                            it.body == "Rich details" &&
                            it.assignee == "researcher" &&
                            it.priority == 2 &&
                            it.goalMode &&
                            it.parents == listOf("p_1", "p_2") &&
                            it.modelOverride == "claude-3-5-sonnet" &&
                            it.providerOverride == "anthropic" &&
                            it.reasoningEffort == "high" &&
                            it.workspaceKind == "worktree" &&
                            it.workspacePath == "/custom/wt/path" &&
                            it.skills == listOf("git", "testing") &&
                            it.triage
                    },
                )
            }
        }

    @Test
    fun testScratchWorkspaceNeverSendsPath() =
        runTest(testDispatcher) {
            val board = KanbanBoard(id = "dev", name = "Dev")
            coEvery { mockRepository.getBoard("dev", any(), any()) } returns
                NetworkResult.Success(KanbanBoardResponse(columns = emptyList()))
            coEvery { mockRepository.createTask("dev", any()) } returns
                NetworkResult.Success(
                    CreateTaskResponse(task = KanbanTask(id = "t_1", title = "Task", status = "todo")),
                )

            val vm = createViewModel()
            vm.selectBoard(board)
            testDispatcher.scheduler.advanceUntilIdle()

            // In our dialog logic, scratch sets workspaceKind = null (or scratch) and workspacePath = null
            val body =
                CreateTaskBody(
                    title = "Scratch Task",
                    workspaceKind = null,
                    workspacePath = null,
                )

            vm.createTask(body)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify {
                mockRepository.createTask(
                    "dev",
                    match {
                        it.workspaceKind == null && it.workspacePath == null
                    },
                )
            }
        }

    @Test
    fun testParkedAssigneeSendsNull() =
        runTest(testDispatcher) {
            val board = KanbanBoard(id = "dev", name = "Dev")
            coEvery { mockRepository.getBoard("dev", any(), any()) } returns
                NetworkResult.Success(KanbanBoardResponse(columns = emptyList()))
            coEvery { mockRepository.createTask("dev", any()) } returns
                NetworkResult.Success(
                    CreateTaskResponse(task = KanbanTask(id = "t_1", title = "Task", status = "todo")),
                )

            val vm = createViewModel()
            vm.selectBoard(board)
            testDispatcher.scheduler.advanceUntilIdle()

            val body =
                CreateTaskBody(
                    title = "Parked Task",
                    assignee = null, // Parked
                )

            vm.createTask(body)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify {
                mockRepository.createTask(
                    "dev",
                    match { it.assignee == null },
                )
            }
        }
}
