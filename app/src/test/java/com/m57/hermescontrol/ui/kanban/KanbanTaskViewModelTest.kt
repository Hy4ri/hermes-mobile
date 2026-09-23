package com.m57.hermescontrol.ui.kanban

import com.m57.hermescontrol.data.model.AttachmentListResponse
import com.m57.hermescontrol.data.model.DeleteAttachmentResponse
import com.m57.hermescontrol.data.model.KanbanAttachment
import com.m57.hermescontrol.data.model.KanbanRun
import com.m57.hermescontrol.data.model.KanbanRunInspection
import com.m57.hermescontrol.data.model.KanbanRunResponse
import com.m57.hermescontrol.data.model.KanbanTaskDetailResponse
import com.m57.hermescontrol.data.model.KanbanTaskFull
import com.m57.hermescontrol.data.model.ReassignTaskResponse
import com.m57.hermescontrol.data.model.ReclaimTaskResponse
import com.m57.hermescontrol.data.model.SpecifyTaskResponse
import com.m57.hermescontrol.data.model.TaskLinkResponse
import com.m57.hermescontrol.data.model.TerminateRunResponse
import com.m57.hermescontrol.data.model.UpdateTaskResponse
import com.m57.hermescontrol.data.model.WorkerLog
import com.m57.hermescontrol.data.remote.NetworkError
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.repository.KanbanRepository
import com.m57.hermescontrol.data.ws.KanbanEventsClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
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
    private val mockEventsClient = mockk<KanbanEventsClient>(relaxed = true)

    private fun createViewModel(enableFallbackPolling: Boolean = false): KanbanTaskViewModel =
        KanbanTaskViewModel(
            repository = mockRepository,
            eventsClientProvider = { mockEventsClient },
            enableFallbackPolling = enableFallbackPolling,
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
            testDispatcher.scheduler.runCurrent()

            assertEquals(
                "Log line 1",
                vm.uiState.value.workerLog
                    ?.content,
            )
            coVerify { mockRepository.getTaskLog("t_1", "dev", any()) }
            vm.onCleared()
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
            val task = KanbanTaskFull(id = "t_1", title = "Task 1", status = "todo")
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

    @Test
    fun testUploadAttachmentSuccess() =
        runTest(testDispatcher) {
            val task = KanbanTaskFull(id = "t_1", title = "Task with file", status = "todo")
            coEvery { mockRepository.getTask("t_1", "dev") } returns
                NetworkResult.Success(KanbanTaskDetailResponse(task = task))
            coEvery { mockRepository.uploadAttachment("t_1", "dev", any()) } returns
                NetworkResult.Success(
                    com.m57.hermescontrol.data.model.AttachmentUploadResponse(
                        attachment =
                            com.m57.hermescontrol.data.model.KanbanAttachment(
                                id = 101L,
                                filename = "test.png",
                            ),
                    ),
                )

            val vm = createViewModel()
            vm.uploadAttachment(
                "dev",
                "t_1",
                MultipartBody.Part.createFormData("file", "test.png", byteArrayOf(1, 2, 3).toRequestBody()),
            )
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { mockRepository.uploadAttachment("t_1", "dev", any()) }
            assertEquals("Attachment uploaded", vm.uiState.value.toastMessage)
        }

    @Test
    fun testUpdateModelOverride() =
        runTest(testDispatcher) {
            val task = KanbanTaskFull(id = "t_1", title = "Task 1", status = "todo")
            coEvery { mockRepository.getTask("t_1", "dev") } returns
                NetworkResult.Success(KanbanTaskDetailResponse(task = task))
            coEvery { mockRepository.updateTask("t_1", "dev", any()) } returns
                NetworkResult.Success(UpdateTaskResponse())

            val vm = createViewModel()
            vm.loadTask("dev", "t_1")
            testDispatcher.scheduler.advanceUntilIdle()

            val override = KanbanModelOverride(model = "claude-3-5-sonnet", provider = "anthropic", effort = "high")
            vm.updateModelOverride("dev", "t_1", override)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify {
                mockRepository.updateTask(
                    "t_1",
                    "dev",
                    match {
                        it.modelOverride == "claude-3-5-sonnet" &&
                            it.providerOverride == "anthropic" &&
                            it.reasoningEffort == "high" &&
                            !it.clearModelOverride
                    },
                )
            }
            assertEquals("Model override updated", vm.uiState.value.toastMessage)
        }

    @Test
    fun testEstimateTask() =
        runTest(testDispatcher) {
            val task = KanbanTaskFull(id = "t_1", title = "Task 1", status = "todo")
            coEvery { mockRepository.getTask("t_1", "dev") } returns
                NetworkResult.Success(KanbanTaskDetailResponse(task = task))
            coEvery { mockRepository.estimateTask("t_1", "dev") } returns
                NetworkResult.Success(
                    com.m57.hermescontrol.data.model.TaskEstimate(
                        ok = true,
                        estTokens = 1500,
                        complexity = "medium",
                    ),
                )

            val vm = createViewModel()
            vm.loadTask("dev", "t_1")
            testDispatcher.scheduler.advanceUntilIdle()

            vm.estimateTask("dev", "t_1")
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { mockRepository.estimateTask("t_1", "dev") }
            assertEquals(
                1500,
                vm.uiState.value.estimate
                    ?.tokens,
            )
            assertEquals(
                "medium",
                vm.uiState.value.estimate
                    ?.complexity,
            )
        }

    @Test
    fun testDeleteTask() =
        runTest(testDispatcher) {
            coEvery { mockRepository.deleteTask("t_1", "dev") } returns NetworkResult.Success(Unit)

            val vm = createViewModel()
            var deleted = false
            vm.deleteTask("dev", "t_1") {
                deleted = true
            }
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { mockRepository.deleteTask("t_1", "dev") }
            assertTrue(deleted)
            assertEquals("Task deleted", vm.uiState.value.toastMessage)
        }

    @Test
    fun testFallbackPollingReloadsTaskWithoutEvents() =
        runTest(testDispatcher) {
            val first = KanbanTaskFull(id = "t_1", title = "Before", status = "todo")
            val second = KanbanTaskFull(id = "t_1", title = "After", status = "todo")
            coEvery { mockRepository.getTask("t_1", "dev") } returnsMany
                listOf(
                    NetworkResult.Success(KanbanTaskDetailResponse(task = first)),
                    NetworkResult.Success(KanbanTaskDetailResponse(task = second)),
                )

            val vm = createViewModel(enableFallbackPolling = true)
            vm.loadTask("dev", "t_1")
            testDispatcher.scheduler.runCurrent()
            assertEquals(
                "Before",
                vm.uiState.value.detail
                    ?.task
                    ?.title,
            )

            testDispatcher.scheduler.advanceTimeBy(30_000L)
            testDispatcher.scheduler.runCurrent()

            assertEquals(
                "After",
                vm.uiState.value.detail
                    ?.task
                    ?.title,
            )
            coVerify(exactly = 2) { mockRepository.getTask("t_1", "dev") }
            vm.onCleared()
        }

    @Test
    fun testTerminateRunRefreshesRunAndExactTask() =
        runTest(testDispatcher) {
            val running = KanbanTaskFull(id = "t_1", title = "Task", status = "running")
            val ready = running.copy(status = "ready")
            coEvery { mockRepository.getTask("t_1", "dev", any(), any()) } returnsMany
                listOf(
                    NetworkResult.Success(KanbanTaskDetailResponse(task = running)),
                    NetworkResult.Success(KanbanTaskDetailResponse(task = ready)),
                )
            coEvery { mockRepository.terminateRun(7L, "dev") } returns
                NetworkResult.Success(TerminateRunResponse(ok = true, runId = 7L, taskId = "t_1"))
            coEvery { mockRepository.getRun(7L, "dev") } returns
                NetworkResult.Success(KanbanRunResponse(KanbanRun(id = 7L, status = "reclaimed")))

            val vm = createViewModel()
            vm.loadTask("dev", "t_1")
            testDispatcher.scheduler.runCurrent()
            vm.terminateRun("dev", "t_1", 7L)
            testDispatcher.scheduler.runCurrent()

            assertEquals(
                "ready",
                vm.uiState.value.detail
                    ?.task
                    ?.status,
            )
            assertEquals(
                "reclaimed",
                vm.uiState.value.selectedRun
                    ?.status,
            )
            coVerify(exactly = 2) { mockRepository.getTask("t_1", "dev", any(), any()) }
            coVerify { mockRepository.getRun(7L, "dev") }
            vm.onCleared()
        }

    @Test
    fun testRunInspectionUnavailableIsRepresentedHonestly() =
        runTest(testDispatcher) {
            coEvery { mockRepository.getRun(7L, "dev") } returns
                NetworkResult.Success(KanbanRunResponse(KanbanRun(id = 7L, status = "running")))
            coEvery { mockRepository.inspectRun(7L, "dev") } returns
                NetworkResult.Success(KanbanRunInspection(runId = 7L, alive = false, reason = "psutil not available"))

            val vm = createViewModel()
            vm.inspectRun("dev", "t_1", 7L)
            testDispatcher.scheduler.runCurrent()

            assertEquals(
                "psutil not available",
                vm.uiState.value.runInspection
                    ?.reason,
            )
            assertEquals(
                7L,
                vm.uiState.value.selectedRun
                    ?.id,
            )
        }

    @Test
    fun testSpecifyFailureUsesTypedReasonWithoutRefreshingTask() =
        runTest(testDispatcher) {
            coEvery { mockRepository.specifyTask("t_1", "dev") } returns
                NetworkResult.Success(SpecifyTaskResponse(ok = false, taskId = "t_1", reason = "Not configured"))

            val vm = createViewModel()
            vm.specifyTask("dev", "t_1")
            testDispatcher.scheduler.runCurrent()

            assertTrue(
                vm.uiState.value.toastMessage
                    ?.contains("Not configured") == true,
            )
            coVerify(exactly = 0) { mockRepository.getTask("t_1", "dev", any(), any()) }
        }

    @Test
    fun testLinkGatingOutcomeIsVisibleAndReloadsTask() =
        runTest(testDispatcher) {
            coEvery { mockRepository.createTaskLink("dev", "parent", "t_1") } returns
                NetworkResult.Success(TaskLinkResponse(ok = true, gated = true))
            coEvery { mockRepository.getTask("t_1", "dev", any(), any()) } returns
                NetworkResult.Success(
                    KanbanTaskDetailResponse(task = KanbanTaskFull(id = "t_1", title = "Child", status = "todo")),
                )

            val vm = createViewModel()
            vm.createParentLink("dev", "t_1", "parent")
            testDispatcher.scheduler.runCurrent()

            assertTrue(
                vm.uiState.value.toastMessage
                    ?.contains("gated") == true,
            )
            coVerify { mockRepository.getTask("t_1", "dev", any(), any()) }
        }

    @Test
    fun testDeleteAttachmentReloadsExactTaskAttachmentList() =
        runTest(testDispatcher) {
            val task = KanbanTaskFull(id = "t_1", title = "Task", status = "todo")
            coEvery { mockRepository.getTask("t_1", "dev", any(), any()) } returns
                NetworkResult.Success(
                    KanbanTaskDetailResponse(task = task, attachments = listOf(KanbanAttachment(9L, filename = "old"))),
                )
            coEvery { mockRepository.deleteAttachment(9L, "dev") } returns
                NetworkResult.Success(DeleteAttachmentResponse(ok = true, id = 9L))
            coEvery { mockRepository.listAttachments("t_1", "dev") } returns
                NetworkResult.Success(AttachmentListResponse(emptyList()))

            val vm = createViewModel()
            vm.loadTask("dev", "t_1")
            testDispatcher.scheduler.runCurrent()
            vm.deleteAttachment("dev", "t_1", 9L)
            testDispatcher.scheduler.runCurrent()

            assertTrue(
                vm.uiState.value.detail
                    ?.attachments
                    ?.isEmpty() == true,
            )
            coVerify { mockRepository.listAttachments("t_1", "dev") }
        }

    @Test
    fun testRunFilterPassesPairedBackendParameters() =
        runTest(testDispatcher) {
            val detail = KanbanTaskDetailResponse(task = KanbanTaskFull("t_1", "Task", status = "done"))
            coEvery { mockRepository.getTask("t_1", "dev", any(), any()) } returns NetworkResult.Success(detail)

            val vm = createViewModel()
            vm.loadTask("dev", "t_1")
            testDispatcher.scheduler.runCurrent()
            vm.setRunFilter("dev", "t_1", "status", "reclaimed")
            testDispatcher.scheduler.runCurrent()

            coVerify { mockRepository.getTask("t_1", "dev", "status", "reclaimed") }
            assertEquals("reclaimed", vm.uiState.value.runStateName)
        }

    @Test
    fun testTerminateRefusalPreservesTaskAndReportsReason() =
        runTest(testDispatcher) {
            coEvery { mockRepository.terminateRun(7L, "dev") } returns
                NetworkResult.Failure(NetworkError.Http(409, "run 7 already ended"))
            val vm = createViewModel()
            vm.terminateRun("dev", "t_1", 7L)
            testDispatcher.scheduler.runCurrent()

            assertEquals("run 7 already ended", vm.uiState.value.toastMessage)
            assertTrue(!vm.uiState.value.isTerminatingRun)
            coVerify(exactly = 0) { mockRepository.getRun(7L, "dev") }
        }
}
