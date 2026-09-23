package com.m57.hermescontrol.data.repository

import com.m57.hermescontrol.data.model.CreateTaskBody
import com.m57.hermescontrol.data.model.CreateTaskResponse
import com.m57.hermescontrol.data.model.DeleteAttachmentResponse
import com.m57.hermescontrol.data.model.KanbanBoardResponse
import com.m57.hermescontrol.data.model.KanbanBoardsResponse
import com.m57.hermescontrol.data.model.KanbanTask
import com.m57.hermescontrol.data.model.SpecifyTaskResponse
import com.m57.hermescontrol.data.model.TaskLinkResponse
import com.m57.hermescontrol.data.model.TerminateRunResponse
import com.m57.hermescontrol.data.model.UpdateTaskBody
import com.m57.hermescontrol.data.model.UpdateTaskResponse
import com.m57.hermescontrol.data.remote.KanbanApiService
import com.m57.hermescontrol.data.remote.NetworkResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class KanbanRepositoryTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val mockApi = mockk<KanbanApiService>(relaxed = true)
    private lateinit var repository: KanbanRepository

    @Before
    fun setUp() {
        repository = KanbanRepositoryImpl(apiProvider = { mockApi }, ioDispatcher = testDispatcher)
    }

    @Test
    fun testGetBoardsPassesIncludeArchived() =
        runTest(testDispatcher) {
            coEvery { mockApi.getBoards(true) } returns Response.success(KanbanBoardsResponse())
            val result = repository.getBoards(includeArchived = true)
            assertTrue(result is NetworkResult.Success)
            coVerify { mockApi.getBoards(true) }
        }

    @Test
    fun testGetBoardScopedPassesBoardAndTenant() =
        runTest(testDispatcher) {
            coEvery {
                mockApi.getBoard(
                    board = "custom",
                    includeArchived = false,
                    tenant = "team-a",
                )
            } returns Response.success(KanbanBoardResponse())

            val result = repository.getBoard(board = "custom", includeArchived = false, tenant = "team-a")
            assertTrue(result is NetworkResult.Success)
            coVerify { mockApi.getBoard(board = "custom", includeArchived = false, tenant = "team-a") }
        }

    @Test
    fun testCreateTaskScopesToBoard() =
        runTest(testDispatcher) {
            val body = CreateTaskBody(title = "New Task")
            coEvery { mockApi.createTask(board = "ops", body = body) } returns
                Response.success(
                    CreateTaskResponse(task = KanbanTask(id = "t_1", title = "New Task", status = "triage")),
                )

            val result = repository.createTask(board = "ops", body = body)
            assertTrue(result is NetworkResult.Success)
            assertEquals("t_1", (result as NetworkResult.Success).data.task?.id)
            coVerify { mockApi.createTask(board = "ops", body = body) }
        }

    @Test
    fun testUpdateTaskScopesToBoard() =
        runTest(testDispatcher) {
            val body = UpdateTaskBody(status = "ready")
            coEvery { mockApi.updateTask(taskId = "t_1", board = "ops", body = body) } returns
                Response.success(
                    UpdateTaskResponse(task = KanbanTask(id = "t_1", title = "New Task", status = "ready")),
                )

            val result = repository.updateTask(taskId = "t_1", board = "ops", body = body)
            assertTrue(result is NetworkResult.Success)
            assertEquals("ready", (result as NetworkResult.Success).data.task?.status)
            coVerify { mockApi.updateTask(taskId = "t_1", board = "ops", body = body) }
        }

    @Test
    fun testDeleteTaskScopesToBoard() =
        runTest(testDispatcher) {
            coEvery { mockApi.deleteTask(taskId = "t_1", board = "ops") } returns Response.success(Unit)

            val result = repository.deleteTask(taskId = "t_1", board = "ops")
            assertTrue(result is NetworkResult.Success)
            coVerify { mockApi.deleteTask(taskId = "t_1", board = "ops") }
        }

    @Test
    fun testHttpErrorWrapsFailure() =
        runTest(testDispatcher) {
            coEvery { mockApi.getBoard("missing", false, null) } returns
                Response.error(404, "Not Found".toResponseBody())

            val result = repository.getBoard("missing")
            assertTrue(result is NetworkResult.Failure)
        }

    @Test
    fun testNewTaskOperationsCarryBoardAndTypedResults() =
        runTest(testDispatcher) {
            coEvery { mockApi.deleteAttachment(9L, "ops") } returns
                Response.success(DeleteAttachmentResponse(ok = true, id = 9L))
            coEvery { mockApi.terminateRun(7L, "ops", any()) } returns
                Response.success(TerminateRunResponse(ok = true, runId = 7L, taskId = "t_1"))
            coEvery { mockApi.specifyTask("t_1", "ops", any()) } returns
                Response.success(SpecifyTaskResponse(ok = false, taskId = "t_1", reason = "Not configured"))
            coEvery { mockApi.createTaskLink("ops", any()) } returns
                Response.success(TaskLinkResponse(ok = true, gated = true))

            assertTrue((repository.deleteAttachment(9L, "ops") as NetworkResult.Success).data.ok)
            assertEquals("t_1", (repository.terminateRun(7L, "ops") as NetworkResult.Success).data.taskId)
            assertEquals("Not configured", (repository.specifyTask("t_1", "ops") as NetworkResult.Success).data.reason)
            assertTrue((repository.createTaskLink("ops", "parent", "t_1") as NetworkResult.Success).data.gated)
            coVerify { mockApi.deleteAttachment(9L, "ops") }
            coVerify { mockApi.terminateRun(7L, "ops", any()) }
            coVerify { mockApi.specifyTask("t_1", "ops", any()) }
            coVerify {
                mockApi.createTaskLink("ops", match { it.parentId == "parent" && it.childId == "t_1" })
            }
        }
}
