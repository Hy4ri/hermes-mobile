package com.m57.hermescontrol.ui.kanban

import com.m57.hermescontrol.data.model.KanbanTaskDetailResponse
import com.m57.hermescontrol.data.model.KanbanTaskFull
import com.m57.hermescontrol.data.model.ReassignTaskResponse
import com.m57.hermescontrol.data.model.ReclaimTaskResponse
import com.m57.hermescontrol.data.model.UpdateTaskResponse
import com.m57.hermescontrol.data.model.WorkerLog
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.repository.KanbanRepository
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KanbanTaskViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockRepository = mockk<KanbanRepository>(relaxed = true)

    private fun createViewModel(): KanbanTaskViewModel =
        KanbanTaskViewModel(
            repository = mockRepository,
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
    fun testLoadTaskSuccess() =
        runTest(testDispatcher) {
            val task = KanbanTaskFull(id = "t_1", title = "Task 1", status = "todo")
            coEvery { mockRepository.getTask("t_1", "dev") } returns
                NetworkResult.Success(KanbanTaskDetailResponse(task = task))

            val vm = createViewModel()
            vm.loadTask("dev", "t_1")
            testDispatcher.scheduler.advanceUntilIdle()

            assertNotNull(vm.uiState.value.detail)
            assertEquals(
                "Task 1",
                vm.uiState.value.detail
                    ?.task
                    ?.title,
            )
        }

    @Test
    fun testLoadRunningTaskLoadsLog() =
        runTest(testDispatcher) {
            val task = KanbanTaskFull(id = "t_1", title = "Running task", status = "running")
            coEvery { mockRepository.getTask("t_1", "dev") } returns
                NetworkResult.Success(KanbanTaskDetailResponse(task = task))
            coEvery { mockRepository.getTaskLog("t_1", "dev", any()) } returns
                NetworkResult.Success(WorkerLog(taskId = "t_1", content = "Log line 1", exists = true))

            val vm = createViewModel()
            vm.loadTask("dev", "t_1")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                "Log line 1",
                vm.uiState.value.workerLog
                    ?.content,
            )
            coVerify { mockRepository.getTaskLog("t_1", "dev", any()) }
        }

    @Test
    fun testUpdateDescription() =
        runTest(testDispatcher) {
            val task = KanbanTaskFull(id = "t_1", title = "Task 1", body = "Old body", status = "todo")
            coEvery { mockRepository.getTask("t_1", "dev") } returns
                NetworkResult.Success(KanbanTaskDetailResponse(task = task))
            coEvery { mockRepository.updateTask("t_1", "dev", any()) } returns
                NetworkResult.Success(UpdateTaskResponse())

            val vm = createViewModel()
            vm.loadTask("dev", "t_1")
            testDispatcher.scheduler.advanceUntilIdle()

            vm.updateDescription("dev", "t_1", "New description")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                "New description",
                vm.uiState.value.detail
                    ?.task
                    ?.body,
            )
            assertEquals("Description saved", vm.uiState.value.toastMessage)
        }

    @Test
    fun testAddComment() =
        runTest(testDispatcher) {
            val task = KanbanTaskFull(id = "t_1", title = "Task 1", status = "todo")
            coEvery { mockRepository.getTask("t_1", "dev") } returns
                NetworkResult.Success(KanbanTaskDetailResponse(task = task))
            coEvery { mockRepository.addComment("t_1", "dev", "Steering note") } returns
                NetworkResult.Success(Unit)

            val vm = createViewModel()
            vm.loadTask("dev", "t_1")
            testDispatcher.scheduler.advanceUntilIdle()

            vm.addComment("dev", "t_1", "Steering note")
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { mockRepository.addComment("t_1", "dev", "Steering note") }
            assertEquals("Comment added", vm.uiState.value.toastMessage)
        }

    @Test
    fun testPostNoteAndRequeue() =
        runTest(testDispatcher) {
            val task = KanbanTaskFull(id = "t_1", title = "Running task", status = "running")
            coEvery { mockRepository.getTask("t_1", "dev") } returns
                NetworkResult.Success(KanbanTaskDetailResponse(task = task))
            coEvery { mockRepository.addComment("t_1", "dev", "Stop now") } returns
                NetworkResult.Success(Unit)
            coEvery { mockRepository.reclaimTask("t_1", "dev", any()) } returns
                NetworkResult.Success(ReclaimTaskResponse(taskId = "t_1"))

            val vm = createViewModel()
            vm.loadTask("dev", "t_1")
            testDispatcher.scheduler.advanceUntilIdle()

            vm.postNoteAndRequeue("dev", "t_1", "Stop now")
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { mockRepository.addComment("t_1", "dev", "Stop now") }
            coVerify { mockRepository.reclaimTask("t_1", "dev", match { it.contains("Requeued") }) }
            assertTrue(
                vm.uiState.value.toastMessage
                    ?.contains("requeued") == true,
            )
        }

    @Test
    fun testReassign() =
        runTest(testDispatcher) {
            coEvery { mockRepository.reassignTask("t_1", "dev", "coder", true) } returns
                NetworkResult.Success(ReassignTaskResponse(taskId = "t_1", assignee = "coder"))

            val vm = createViewModel()
            vm.reassign("dev", "t_1", "coder")
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { mockRepository.reassignTask("t_1", "dev", "coder", true) }
            assertTrue(
                vm.uiState.value.toastMessage
                    ?.contains("coder") == true,
            )
        }
}
