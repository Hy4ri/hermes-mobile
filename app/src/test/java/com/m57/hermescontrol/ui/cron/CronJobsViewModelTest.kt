package com.m57.hermescontrol.ui.cron

import android.app.Application
import com.m57.hermescontrol.data.model.CreateCronJobRequest
import com.m57.hermescontrol.data.model.CronBlueprint
import com.m57.hermescontrol.data.model.CronBlueprintField
import com.m57.hermescontrol.data.model.CronBlueprintListResponse
import com.m57.hermescontrol.data.model.CronCancelResult
import com.m57.hermescontrol.data.model.CronJob
import com.m57.hermescontrol.data.model.CronJobFireError
import com.m57.hermescontrol.data.model.DeliveryTarget
import com.m57.hermescontrol.data.model.DeliveryTargetsResponse
import com.m57.hermescontrol.data.model.InstantiateBlueprintRequest
import com.m57.hermescontrol.data.model.UpdateCronJobRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CronJobsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockApi = mockk<HermesApiService>(relaxed = true)
    private val mockApp = mockk<Application>(relaxed = true)

    private val morningBrief =
        CronBlueprint(
            key = "morning-brief",
            title = "Morning briefing",
            description = "A short daily briefing.",
            fields =
                listOf(
                    CronBlueprintField(
                        name = "time",
                        type = "time",
                        label = "What time?",
                        default = JsonPrimitive("08:00"),
                        help = "24h local time, e.g. 08:00",
                    ),
                    CronBlueprintField(
                        name = "deliver",
                        type = "enum",
                        label = "Where to deliver?",
                        default = JsonPrimitive("origin"),
                        options = listOf("origin", "local", "telegram"),
                        strict = false,
                    ),
                ),
        )

    private fun createViewModel(): CronJobsViewModel {
        val vm = CronJobsViewModel(mockApp)
        vm.ioDispatcher = testDispatcher
        testDispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    private fun settle() {
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun stubEditorData() {
        coEvery { mockApi.getCronBlueprints() } returns
            Response.success(CronBlueprintListResponse(blueprints = listOf(morningBrief)))
        coEvery { mockApi.getCronDeliveryTargets() } returns
            Response.success(
                DeliveryTargetsResponse(
                    targets =
                        listOf(
                            DeliveryTarget(id = "local", name = "Local (save only)", home_target_set = true),
                            DeliveryTarget(id = "telegram", name = "Telegram", home_target_set = true),
                        ),
                ),
            )
        coEvery { mockApi.getCronJobs() } returns Response.success(emptyList())
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ApiClient)
        every { ApiClient.hermesApi } returns mockApi
        every { mockApp.getString(com.m57.hermescontrol.R.string.cron_stop_requested) } returns
            "Stop requested for running job"
        every { mockApp.getString(com.m57.hermescontrol.R.string.cron_stop_nothing_to_stop) } returns
            "No run of this job is in flight"
        every { mockApp.getString(eq(com.m57.hermescontrol.R.string.cron_stop_failed), any()) } answers {
            val err = secondArg<Array<out Any>>().firstOrNull() ?: ""
            "Failed to stop job: $err"
        }
        every { mockApp.getString(com.m57.hermescontrol.R.string.cron_edit_error_grace_minutes) } returns
            "Grace window must be a whole number between 0 and 1440 minutes"
        stubEditorData()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `openNewJobDialog loads blueprints and delivery targets`() {
        val vm = createViewModel()

        vm.openNewJobDialog()
        settle()

        val editor = vm.uiState.value.editorState
        assertTrue(editor.isOpen)
        assertTrue(editor.isNew)
        assertEquals(listOf("morning-brief"), editor.blueprints.map { it.key })
        assertEquals(listOf("local", "telegram"), editor.deliveryTargets.map { it.id })
        // origin first, then local + connected platforms
        assertEquals(listOf("origin", "local", "telegram"), editor.deliveryOptions)
    }

    @Test
    fun `selectBlueprint seeds slot defaults`() {
        val vm = createViewModel()
        vm.openNewJobDialog()
        settle()

        vm.selectBlueprint("morning-brief")

        val editor = vm.uiState.value.editorState
        assertEquals("morning-brief", editor.selectedBlueprintKey)
        assertEquals(mapOf("time" to "08:00", "deliver" to "origin"), editor.blueprintValues)
        assertEquals("Morning briefing", editor.selectedBlueprint?.title)
    }

    @Test
    fun `selectBlueprint null returns to blank job`() {
        val vm = createViewModel()
        vm.openNewJobDialog()
        settle()

        vm.selectBlueprint("morning-brief")
        vm.selectBlueprint(null)

        val editor = vm.uiState.value.editorState
        assertNull(editor.selectedBlueprintKey)
        assertTrue(editor.blueprintValues.isEmpty())
    }

    @Test
    fun `updateBlueprintValue overrides a seeded slot`() {
        val vm = createViewModel()
        vm.openNewJobDialog()
        settle()
        vm.selectBlueprint("morning-brief")

        vm.updateBlueprintValue("time", "09:30")

        assertEquals("09:30", vm.uiState.value.editorState.blueprintValues["time"])
    }

    @Test
    fun `saveEditor with blueprint instantiated`() {
        val vm = createViewModel()
        vm.openNewJobDialog()
        settle()
        vm.selectBlueprint("morning-brief")
        coEvery { mockApi.instantiateBlueprint(any()) } returns
            Response.success(CronJob(id = "j1", name = "Morning briefing"))
        coEvery { mockApi.getCronJobs() } returns Response.success(emptyList())

        vm.saveEditor()
        settle()

        val requestSlot = slot<InstantiateBlueprintRequest>()
        coVerify { mockApi.instantiateBlueprint(capture(requestSlot)) }
        assertEquals("morning-brief", requestSlot.captured.blueprint)
        assertEquals(mapOf("time" to "08:00", "deliver" to "origin"), requestSlot.captured.values)
        assertFalse(vm.uiState.value.editorState.isOpen)
    }

    @Test
    fun `saveEditor blueprint validation error surfaces backend detail`() {
        val vm = createViewModel()
        vm.openNewJobDialog()
        settle()
        vm.selectBlueprint("morning-brief")
        coEvery { mockApi.instantiateBlueprint(any()) } returns
            Response.error(422, """{"detail":"invalid time '25:00' - use HH:MM (24h)"}""".toResponseBody())

        vm.saveEditor()
        settle()

        val toast =
            vm.uiState.value.editorState.toastMessage
                .orEmpty()
        assertTrue(toast, toast.contains("invalid time '25:00'"))
        assertFalse(vm.uiState.value.editorState.isSaving)
    }

    @Test
    fun `saveEditor blank job still creates via createCronJob`() {
        val vm = createViewModel()
        vm.openNewJobDialog()
        settle()
        vm.updateEditorField("name", "Test job")
        vm.updateEditorField("schedule", "0 9 * * *")
        vm.updateEditorField("prompt", "Say hi")
        coEvery { mockApi.createCronJob(any()) } returns
            Response.success(CronJob(id = "j2", name = "Test job"))
        coEvery { mockApi.getCronJobs() } returns Response.success(emptyList())

        vm.saveEditor()
        settle()

        coVerify { mockApi.createCronJob(any()) }
        coVerify(exactly = 0) { mockApi.instantiateBlueprint(any()) }
        assertFalse(vm.uiState.value.editorState.isOpen)
    }

    @Test
    fun `requestDeleteJob sets the delete target without deleting`() {
        val vm = createViewModel()
        val job = CronJob(id = "j-del", name = "Doomed")

        vm.requestDeleteJob(job)

        assertEquals(job, vm.uiState.value.deleteTarget)
        coVerify(exactly = 0) { mockApi.deleteCronJob(any()) }
    }

    @Test
    fun `dismissDeleteDialog clears the target without deleting`() {
        val vm = createViewModel()
        vm.requestDeleteJob(CronJob(id = "j-del", name = "Doomed"))

        vm.dismissDeleteDialog()

        assertNull(vm.uiState.value.deleteTarget)
        coVerify(exactly = 0) { mockApi.deleteCronJob(any()) }
    }

    @Test
    fun `confirmDeleteJob clears the target and deletes the job`() {
        val vm = createViewModel()
        vm.requestDeleteJob(CronJob(id = "j-del", name = "Doomed"))

        vm.confirmDeleteJob()
        settle()

        assertNull(vm.uiState.value.deleteTarget)
        coVerify { mockApi.deleteCronJob("j-del") }
    }

    @Test
    fun `confirmDeleteJob without a target is a no-op`() {
        val vm = createViewModel()

        vm.confirmDeleteJob()
        settle()

        assertNull(vm.uiState.value.deleteTarget)
        coVerify(exactly = 0) { mockApi.deleteCronJob(any()) }
    }

    @Test
    fun `setMonitorMode switching source clears the other`() {
        val vm = createViewModel()
        vm.openNewJobDialog()
        settle()

        vm.setMonitorMode("script")
        vm.updateEditorField("monitor_script", "check.sh")

        var editor = vm.uiState.value.editorState
        assertEquals("script", editor.monitorMode)
        assertEquals("check.sh", editor.monitor_script)

        vm.setMonitorMode("url")

        editor = vm.uiState.value.editorState
        assertEquals("url", editor.monitorMode)
        assertEquals("", editor.monitor_script)
    }

    @Test
    fun `setMonitorMode off clears both sources`() {
        val vm = createViewModel()
        vm.openNewJobDialog()
        settle()
        vm.setMonitorMode("url")
        vm.updateEditorField("monitor_url", "https://example.com/status")

        vm.setMonitorMode("off")

        val editor = vm.uiState.value.editorState
        assertEquals("off", editor.monitorMode)
        assertEquals("", editor.monitor_script)
        assertEquals("", editor.monitor_url)
    }

    @Test
    fun `toggleNoAgent clears monitor fields`() {
        val vm = createViewModel()
        vm.openNewJobDialog()
        settle()
        vm.setMonitorMode("script")
        vm.updateEditorField("monitor_script", "check.sh")

        vm.toggleNoAgent()

        val editor = vm.uiState.value.editorState
        assertTrue(editor.no_agent)
        assertEquals("off", editor.monitorMode)
        assertEquals("", editor.monitor_script)
        assertEquals("", editor.monitor_url)
    }

    @Test
    fun `new job with monitor mode applies it via follow-up update`() {
        val vm = createViewModel()
        vm.openNewJobDialog()
        settle()
        vm.updateEditorField("name", "Watch job")
        vm.updateEditorField("schedule", "every 10m")
        vm.updateEditorField("prompt", "Summarize changes")
        vm.setMonitorMode("script")
        vm.updateEditorField("monitor_script", "check.sh")
        val created = CronJob(id = "j3", name = "Watch job")
        coEvery { mockApi.createCronJob(any()) } returns Response.success(created)
        coEvery { mockApi.updateCronJob(eq("j3"), any()) } returns Response.success(created)
        coEvery { mockApi.getCronJobs() } returns Response.success(emptyList())

        vm.saveEditor()
        settle()

        coVerify(exactly = 1) { mockApi.createCronJob(any()) }
        val updateSlot = slot<UpdateCronJobRequest>()
        coVerify { mockApi.updateCronJob(eq("j3"), capture(updateSlot)) }
        assertEquals(JsonPrimitive("check.sh"), updateSlot.captured.updates["monitor_script"])
        assertFalse(vm.uiState.value.editorState.isOpen)
    }

    @Test
    fun `new job without monitor skips follow-up update`() {
        val vm = createViewModel()
        vm.openNewJobDialog()
        settle()
        vm.updateEditorField("name", "Plain job")
        vm.updateEditorField("schedule", "every 10m")
        val created = CronJob(id = "j4", name = "Plain job")
        coEvery { mockApi.createCronJob(any()) } returns Response.success(created)
        coEvery { mockApi.getCronJobs() } returns Response.success(emptyList())

        vm.saveEditor()
        settle()

        coVerify(exactly = 1) { mockApi.createCronJob(any()) }
        coVerify(exactly = 0) { mockApi.updateCronJob(any(), any()) }
        assertFalse(vm.uiState.value.editorState.isOpen)
    }

    @Test
    fun `monitor update failure rolls back the created job`() {
        val vm = createViewModel()
        vm.openNewJobDialog()
        settle()
        vm.updateEditorField("name", "Watch job")
        vm.updateEditorField("schedule", "every 10m")
        vm.setMonitorMode("url")
        vm.updateEditorField("monitor_url", "https://example.com/status")
        val created = CronJob(id = "j5", name = "Watch job")
        coEvery { mockApi.createCronJob(any()) } returns Response.success(created)
        coEvery { mockApi.updateCronJob(eq("j5"), any()) } returns
            Response.error(
                400,
                """{"detail":"monitor_script and monitor_url are mutually exclusive"}""".toResponseBody(),
            )
        coEvery { mockApi.deleteCronJob(eq("j5")) } returns Response.success(Unit)

        vm.saveEditor()
        settle()

        coVerify { mockApi.deleteCronJob("j5") }
        assertTrue(vm.uiState.value.editorState.isOpen)
        assertTrue(
            vm.uiState.value.editorState.toastMessage
                .orEmpty()
                .contains("Failed to save"),
        )
    }

    @Test
    fun `openEditJobDialog prefills monitor fields and mode`() {
        val vm = createViewModel()
        coEvery { mockApi.getCronJob("j6") } returns
            Response.success(
                CronJob(
                    id = "j6",
                    name = "Monitored",
                    schedule = JsonPrimitive("every 10m"),
                    monitor_script = "check.sh",
                ),
            )

        vm.openEditJobDialog("j6")
        settle()

        val editor = vm.uiState.value.editorState
        assertEquals("script", editor.monitorMode)
        assertEquals("check.sh", editor.monitor_script)
        assertEquals("", editor.monitor_url)
    }

    @Test
    fun `edit save sends monitor fields in updates`() {
        val vm = createViewModel()
        coEvery { mockApi.getCronJob("j7") } returns
            Response.success(
                CronJob(
                    id = "j7",
                    name = "Monitored",
                    schedule = JsonPrimitive("every 10m"),
                    monitor_url = "https://example.com/status",
                ),
            )
        coEvery { mockApi.updateCronJob(eq("j7"), any()) } returns
            Response.success(CronJob(id = "j7", name = "Monitored"))
        coEvery { mockApi.getCronJobs() } returns Response.success(emptyList())

        vm.openEditJobDialog("j7")
        settle()
        vm.updateEditorField("name", "Renamed")
        vm.saveEditor()
        settle()

        val updateSlot = slot<UpdateCronJobRequest>()
        coVerify { mockApi.updateCronJob(eq("j7"), capture(updateSlot)) }
        assertEquals(JsonPrimitive("https://example.com/status"), updateSlot.captured.updates["monitor_url"])
        assertFalse(vm.uiState.value.editorState.isOpen)
    }

    @Test
    fun `edit save clears monitor when field emptied`() {
        val vm = createViewModel()
        coEvery { mockApi.getCronJob("j8") } returns
            Response.success(
                CronJob(
                    id = "j8",
                    name = "Monitored",
                    schedule = JsonPrimitive("every 10m"),
                    monitor_script = "check.sh",
                ),
            )
        coEvery { mockApi.updateCronJob(eq("j8"), any()) } returns
            Response.success(CronJob(id = "j8", name = "Monitored"))
        coEvery { mockApi.getCronJobs() } returns Response.success(emptyList())

        vm.openEditJobDialog("j8")
        settle()
        vm.updateEditorField("monitor_script", "")
        vm.saveEditor()
        settle()

        val updateSlot = slot<UpdateCronJobRequest>()
        coVerify { mockApi.updateCronJob(eq("j8"), capture(updateSlot)) }
        assertEquals(JsonNull, updateSlot.captured.updates["monitor_script"])
    }

    @Test
    fun `new job with run continuity sends context_from self in create`() {
        val vm = createViewModel()
        vm.openNewJobDialog()
        settle()
        vm.updateEditorField("name", "Daily digest")
        vm.updateEditorField("schedule", "every 1d")
        vm.toggleRunContinuity()
        val created = CronJob(id = "j9", name = "Daily digest")
        coEvery { mockApi.createCronJob(any()) } returns Response.success(created)
        coEvery { mockApi.getCronJobs() } returns Response.success(emptyList())

        vm.saveEditor()
        settle()

        val createSlot = slot<CreateCronJobRequest>()
        coVerify(exactly = 1) { mockApi.createCronJob(capture(createSlot)) }
        assertEquals(listOf("self"), createSlot.captured.context_from)
        assertFalse(vm.uiState.value.editorState.isOpen)
    }

    @Test
    fun `edit save preserves run continuity from loaded job`() {
        val vm = createViewModel()
        coEvery { mockApi.getCronJob("j10") } returns
            Response.success(
                CronJob(
                    id = "j10",
                    name = "Daily digest",
                    schedule = JsonPrimitive("every 1d"),
                    context_from = listOf("self"),
                ),
            )
        coEvery { mockApi.updateCronJob(eq("j10"), any()) } returns
            Response.success(CronJob(id = "j10", name = "Daily digest"))
        coEvery { mockApi.getCronJobs() } returns Response.success(emptyList())

        vm.openEditJobDialog("j10")
        settle()

        assertTrue(vm.uiState.value.editorState.runContinuity)

        vm.updateEditorField("name", "Renamed digest")
        vm.saveEditor()
        settle()

        val updateSlot = slot<UpdateCronJobRequest>()
        coVerify { mockApi.updateCronJob(eq("j10"), capture(updateSlot)) }
        assertEquals(
            kotlinx.serialization.json.JsonArray(
                listOf(JsonPrimitive("self")),
            ),
            updateSlot.captured.updates["context_from"],
        )
        assertFalse(vm.uiState.value.editorState.isOpen)
    }

    @Test
    fun `loadCronJobs surfaces last_fire_error on a job`() {
        val vm = createViewModel()
        coEvery { mockApi.getCronJobs() } returns
            Response.success(
                listOf(
                    CronJob(
                        id = "j11",
                        name = "Flaky job",
                        schedule = JsonPrimitive("every 1h"),
                        last_fire_error = CronJobFireError(at = "2026-08-18T09:00:00", detail = "gateway unreachable"),
                    ),
                ),
            )

        vm.loadCronJobs()
        settle()

        val job =
            vm.uiState.value.jobs
                .first()
        assertNotNull(job.last_fire_error)
        assertEquals("gateway unreachable", job.last_fire_error?.detail)
        assertEquals("2026-08-18T09:00:00", job.last_fire_error?.at)
    }

    @Test
    fun `resumeCronJob updates optimistic state to scheduled`() {
        val vm = createViewModel()
        val pausedJob = CronJob(id = "j-paused", name = "Test", state = "paused")
        coEvery { mockApi.getCronJobs() } returns Response.success(listOf(pausedJob))
        coEvery { mockApi.resumeCronJob("j-paused") } returns Response.success(Unit)

        vm.loadCronJobs()
        settle()
        assertEquals(
            "paused",
            vm.uiState.value.jobs
                .first()
                .state,
        )

        vm.resumeCronJob("j-paused")
        assertEquals(
            "scheduled",
            vm.uiState.value.jobs
                .first()
                .state,
        )

        settle()
        assertEquals(
            "scheduled",
            vm.uiState.value.jobs
                .single()
                .state,
        )
        coVerify { mockApi.resumeCronJob("j-paused") }
    }

    @Test
    fun `pauseCronJob updates optimistic state to paused`() {
        for (initialState in listOf("active", "scheduled")) {
            val vm = createViewModel()
            val jobId = "j-$initialState"
            val job = CronJob(id = jobId, name = "Test", state = initialState)
            coEvery { mockApi.getCronJobs() } returns Response.success(listOf(job))
            coEvery { mockApi.pauseCronJob(jobId) } returns Response.success(Unit)

            vm.loadCronJobs()
            settle()
            assertEquals(
                initialState,
                vm.uiState.value.jobs
                    .single()
                    .state,
            )

            vm.pauseCronJob(jobId)
            assertEquals(
                "paused",
                vm.uiState.value.jobs
                    .single()
                    .state,
            )

            settle()
            assertEquals(
                "paused",
                vm.uiState.value.jobs
                    .single()
                    .state,
            )
            coVerify(exactly = 1) { mockApi.pauseCronJob(jobId) }
        }
    }

    // ── Cron loading and mutations ──────────────────────────────────────

    @Test
    fun `loadCronJobs success populates jobs and metadata`() {
        val job = CronJob("id1", "Job 1", JsonPrimitive("*/5 * * * *"), "active", "success", "2026-06-15T15:10:00Z")
        coEvery { mockApi.getCronJobs() } returns Response.success(listOf(job))

        val vm = createViewModel()
        vm.loadCronJobs()
        assertTrue(vm.uiState.value.isLoading)

        settle()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals(1, vm.uiState.value.jobs.size)
        assertEquals(
            "id1",
            vm.uiState.value.jobs[0]
                .id,
        )
        assertEquals(
            "Job 1",
            vm.uiState.value.jobs[0]
                .name,
        )
        assertEquals(
            "active",
            vm.uiState.value.jobs[0]
                .state,
        )
        assertEquals(
            "success",
            vm.uiState.value.jobs[0]
                .last_run_status,
        )
        assertEquals(
            "2026-06-15T15:10:00Z",
            vm.uiState.value.jobs[0]
                .next_run,
        )
    }

    @Test
    fun `loadCronJobs failure surfaces error message and stops loading`() {
        val errorResponse = Response.error<List<CronJob>>(500, "".toResponseBody(null))
        coEvery { mockApi.getCronJobs() } returns errorResponse

        val vm = createViewModel()
        vm.loadCronJobs()
        settle()

        assertFalse(vm.uiState.value.isLoading)
        assertNotNull(vm.uiState.value.errorMessage)
        assertTrue(
            vm.uiState.value.errorMessage!!
                .contains("HTTP 500"),
        )
    }

    @Test
    fun `loadCronJobs refresh updates state with newest listing`() {
        val jobA = CronJob("idA", "Job A", null, null, null, null)
        val jobB = CronJob("idB", "Job B", null, null, null, null)
        coEvery { mockApi.getCronJobs() } returnsMany
            listOf(
                Response.success(listOf(jobA)),
                Response.success(listOf(jobB)),
            )

        val vm = createViewModel()
        vm.loadCronJobs()
        settle()
        assertEquals(
            "Job A",
            vm.uiState.value.jobs[0]
                .name,
        )

        vm.loadCronJobs(forceRefresh = true)
        settle()
        assertEquals(
            "Job B",
            vm.uiState.value.jobs[0]
                .name,
        )
    }

    @Test
    fun `pauseCronJob HTTP error reverts state and surfaces toast error`() {
        val job = CronJob("id1", "Job 1", null, "active", null, null)
        coEvery { mockApi.getCronJobs() } returns Response.success(listOf(job))
        coEvery { mockApi.pauseCronJob("id1") } returns Response.error<Unit>(500, "".toResponseBody(null))

        val vm = createViewModel()
        vm.loadCronJobs()
        settle()

        vm.pauseCronJob("id1")
        settle()

        assertEquals(
            "active",
            vm.uiState.value.jobs[0]
                .state,
        )
        assertNotNull(vm.uiState.value.toastMessage)
        assertTrue(
            vm.uiState.value.toastMessage!!
                .contains("HTTP 500"),
        )
    }

    @Test
    fun `triggerCronJob success sets success toast message`() {
        coEvery { mockApi.triggerCronJob("id1") } returns Response.success(Unit)

        val vm = createViewModel()
        vm.triggerCronJob("id1")
        settle()

        assertEquals("Job triggered successfully", vm.uiState.value.toastMessage)
    }

    @Test
    fun `triggerCronJob HTTP error surfaces toast error`() {
        coEvery { mockApi.triggerCronJob("id1") } returns Response.error<Unit>(500, "".toResponseBody(null))

        val vm = createViewModel()
        vm.triggerCronJob("id1")
        settle()

        assertNotNull(vm.uiState.value.toastMessage)
        assertTrue(
            vm.uiState.value.toastMessage!!
                .contains("HTTP 500"),
        )
    }

    @Test
    fun `deleteCronJob success applies optimistic removal and completes`() {
        val job = CronJob("id1", "Job 1", null, null, null, null)
        coEvery { mockApi.getCronJobs() } returns Response.success(listOf(job))
        coEvery { mockApi.deleteCronJob("id1") } returns Response.success(Unit)

        val vm = createViewModel()
        vm.loadCronJobs()
        settle()

        assertEquals(1, vm.uiState.value.jobs.size)

        vm.deleteCronJob("id1")
        // Verify optimistic update (immediate removal)
        assertTrue(
            vm.uiState.value.jobs
                .isEmpty(),
        )

        settle()
        assertTrue(
            vm.uiState.value.jobs
                .isEmpty(),
        )
    }

    @Test
    fun `deleteCronJob HTTP error reverts state and surfaces toast error`() {
        val job = CronJob("id1", "Job 1", null, null, null, null)
        coEvery { mockApi.getCronJobs() } returns Response.success(listOf(job))
        coEvery { mockApi.deleteCronJob("id1") } returns Response.error<Unit>(500, "".toResponseBody(null))

        val vm = createViewModel()
        vm.loadCronJobs()
        settle()

        vm.deleteCronJob("id1")
        settle()

        assertEquals(1, vm.uiState.value.jobs.size)
        assertNotNull(vm.uiState.value.toastMessage)
        assertTrue(
            vm.uiState.value.toastMessage!!
                .contains("HTTP 500"),
        )
    }

    @Test
    fun `loadCronJobs network timeout surfaces timeout in error message`() {
        coEvery { mockApi.getCronJobs() } throws IOException("Timeout")

        val vm = createViewModel()
        vm.loadCronJobs()
        settle()

        assertTrue(
            vm.uiState.value.errorMessage!!
                .contains("Timeout"),
        )
    }

    @Test
    fun `loadCronJobs empty response produces empty jobs list without error`() {
        coEvery { mockApi.getCronJobs() } returns Response.success(emptyList())

        val vm = createViewModel()
        vm.loadCronJobs()
        settle()

        assertFalse(vm.uiState.value.isLoading)
        assertTrue(
            vm.uiState.value.jobs
                .isEmpty(),
        )
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `stopCronJob success when cancelled requests refresh and notifies`() {
        val vm = createViewModel()
        val runningJob = CronJob(id = "job-stop", name = "Runner")
        coEvery { mockApi.cancelCronJob("job-stop") } returns
            Response.success(
                CronCancelResult(
                    cancelled = true,
                    execution_id = "e-1",
                    job_id = "job-stop",
                    detail = "Cancel requested",
                ),
            )
        coEvery { mockApi.getCronJobs() } returns Response.success(listOf(runningJob))

        vm.stopCronJob("job-stop")
        settle()

        coVerify { mockApi.cancelCronJob("job-stop") }
        coVerify { mockApi.getCronJobs() }
        assertFalse(
            vm.uiState.value.stoppingJobIds
                .contains("job-stop"),
        )
        assertEquals("Stop requested for running job", vm.uiState.value.toastMessage)
    }

    @Test
    fun `stopCronJob success when nothing in flight surfaces detail`() {
        val vm = createViewModel()
        coEvery { mockApi.cancelCronJob("job-idle") } returns
            Response.success(
                CronCancelResult(
                    cancelled = false,
                    job_id = "job-idle",
                    detail = "No run of this job is in flight — nothing to cancel.",
                ),
            )

        vm.stopCronJob("job-idle")
        settle()

        coVerify { mockApi.cancelCronJob("job-idle") }
        assertFalse(
            vm.uiState.value.stoppingJobIds
                .contains("job-idle"),
        )
        assertEquals("No run of this job is in flight — nothing to cancel.", vm.uiState.value.toastMessage)
    }

    @Test
    fun `stopCronJob failure surfaces error message`() {
        val vm = createViewModel()
        coEvery { mockApi.cancelCronJob("job-err") } returns
            Response.error(400, """{"detail":"Failed to stop"}""".toResponseBody())

        vm.stopCronJob("job-err")
        settle()

        coVerify { mockApi.cancelCronJob("job-err") }
        assertFalse(
            vm.uiState.value.stoppingJobIds
                .contains("job-err"),
        )
        assertTrue(
            vm.uiState.value.toastMessage
                .orEmpty()
                .contains("Failed to stop"),
        )
    }

    @Test
    fun `openEditJobDialog loads timezone grace and catch_up`() {
        val vm = createViewModel()
        coEvery { mockApi.getCronJob("j-policy") } returns
            Response.success(
                CronJob(
                    id = "j-policy",
                    name = "Policy Job",
                    schedule = JsonPrimitive("0 12 * * *"),
                    timezone = "America/New_York",
                    grace_minutes = 30,
                    catch_up = true,
                ),
            )

        vm.openEditJobDialog("j-policy")
        settle()

        val editor = vm.uiState.value.editorState
        assertEquals("America/New_York", editor.timezone)
        assertEquals("30", editor.grace_minutes)
        assertEquals("run", editor.catch_up)
    }

    @Test
    fun `saveEditor sends timezone grace and catch_up on update`() {
        val vm = createViewModel()
        coEvery { mockApi.getCronJob("j-edit-policy") } returns
            Response.success(
                CronJob(
                    id = "j-edit-policy",
                    name = "Policy Job",
                    schedule = JsonPrimitive("0 12 * * *"),
                ),
            )
        coEvery { mockApi.updateCronJob(eq("j-edit-policy"), any()) } returns
            Response.success(CronJob(id = "j-edit-policy", name = "Policy Job"))
        coEvery { mockApi.getCronJobs() } returns Response.success(emptyList())

        vm.openEditJobDialog("j-edit-policy")
        settle()

        vm.updateEditorField("timezone", "Asia/Amman")
        vm.updateEditorField("grace_minutes", "60")
        vm.updateEditorField("catch_up", "skip")

        vm.saveEditor()
        settle()

        val updateSlot = slot<UpdateCronJobRequest>()
        coVerify { mockApi.updateCronJob(eq("j-edit-policy"), capture(updateSlot)) }
        assertEquals(JsonPrimitive("Asia/Amman"), updateSlot.captured.updates["timezone"])
        assertEquals(JsonPrimitive(60), updateSlot.captured.updates["grace_minutes"])
        assertEquals(JsonPrimitive(false), updateSlot.captured.updates["catch_up"])
        assertFalse(vm.uiState.value.editorState.isOpen)
    }

    @Test
    fun `saveEditor sends timezone grace and catch_up on create`() {
        val vm = createViewModel()
        vm.openNewJobDialog()
        settle()

        vm.updateEditorField("name", "New Policy Job")
        vm.updateEditorField("schedule", "0 0 * * *")
        vm.updateEditorField("timezone", "UTC")
        vm.updateEditorField("grace_minutes", "15")
        vm.updateEditorField("catch_up", "run")

        val created = CronJob(id = "j-new-policy", name = "New Policy Job")
        coEvery { mockApi.createCronJob(any()) } returns Response.success(created)
        coEvery { mockApi.getCronJobs() } returns Response.success(emptyList())

        vm.saveEditor()
        settle()

        val createSlot = slot<CreateCronJobRequest>()
        coVerify { mockApi.createCronJob(capture(createSlot)) }
        assertEquals("UTC", createSlot.captured.timezone)
        assertEquals(15, createSlot.captured.grace_minutes)
        assertEquals(true, createSlot.captured.catch_up)
        assertFalse(vm.uiState.value.editorState.isOpen)
    }

    @Test
    fun `stopCronJob guards against duplicate in-flight calls`() {
        val vm = createViewModel()
        coEvery { mockApi.cancelCronJob("job-dup") } returns
            Response.success(
                CronCancelResult(
                    cancelled = true,
                    execution_id = "e-1",
                    job_id = "job-dup",
                ),
            )
        coEvery { mockApi.getCronJobs() } returns Response.success(emptyList())

        vm.stopCronJob("job-dup")
        // Calling again while job-dup is in stoppingJobIds should be a no-op
        vm.stopCronJob("job-dup")
        settle()

        coVerify(exactly = 1) { mockApi.cancelCronJob("job-dup") }
    }

    @Test
    fun `saveEditor rejects non-integer grace_minutes without making API call`() {
        val vm = createViewModel()
        vm.openNewJobDialog()
        settle()

        vm.updateEditorField("name", "Test")
        vm.updateEditorField("schedule", "0 0 * * *")
        vm.updateEditorField("grace_minutes", "abc")

        vm.saveEditor()
        settle()

        coVerify(exactly = 0) { mockApi.createCronJob(any()) }
        coVerify(exactly = 0) { mockApi.updateCronJob(any(), any()) }
        assertTrue(vm.uiState.value.editorState.isOpen)
        assertEquals(
            "Grace window must be a whole number between 0 and 1440 minutes",
            vm.uiState.value.editorState.toastMessage,
        )
    }

    @Test
    fun `saveEditor rejects decimal grace_minutes without making API call`() {
        val vm = createViewModel()
        vm.openNewJobDialog()
        settle()

        vm.updateEditorField("name", "Test")
        vm.updateEditorField("schedule", "0 0 * * *")
        vm.updateEditorField("grace_minutes", "1.5")

        vm.saveEditor()
        settle()

        coVerify(exactly = 0) { mockApi.createCronJob(any()) }
        coVerify(exactly = 0) { mockApi.updateCronJob(any(), any()) }
        assertTrue(vm.uiState.value.editorState.isOpen)
        assertEquals(
            "Grace window must be a whole number between 0 and 1440 minutes",
            vm.uiState.value.editorState.toastMessage,
        )
    }

    @Test
    fun `saveEditor rejects out-of-range grace_minutes without making API call`() {
        val vm = createViewModel()
        vm.openNewJobDialog()
        settle()

        vm.updateEditorField("name", "Test")
        vm.updateEditorField("schedule", "0 0 * * *")

        // Negative
        vm.updateEditorField("grace_minutes", "-5")
        vm.saveEditor()
        settle()

        coVerify(exactly = 0) { mockApi.createCronJob(any()) }
        assertEquals(
            "Grace window must be a whole number between 0 and 1440 minutes",
            vm.uiState.value.editorState.toastMessage,
        )

        // Overflow > 1440
        vm.updateEditorField("grace_minutes", "1441")
        vm.saveEditor()
        settle()

        coVerify(exactly = 0) { mockApi.createCronJob(any()) }

        // Large integer string overflow
        vm.updateEditorField("grace_minutes", "99999999999999999999")
        vm.saveEditor()
        settle()

        coVerify(exactly = 0) { mockApi.createCronJob(any()) }
    }

    @Test
    fun `saveEditor accepts 0 as grace_minutes`() {
        val vm = createViewModel()
        vm.openNewJobDialog()
        settle()

        vm.updateEditorField("name", "Test Zero")
        vm.updateEditorField("schedule", "0 0 * * *")
        vm.updateEditorField("grace_minutes", "0")

        val created = CronJob(id = "j-zero", name = "Test Zero")
        coEvery { mockApi.createCronJob(any()) } returns Response.success(created)
        coEvery { mockApi.getCronJobs() } returns Response.success(emptyList())

        vm.saveEditor()
        settle()

        val createSlot = slot<CreateCronJobRequest>()
        coVerify(exactly = 1) { mockApi.createCronJob(capture(createSlot)) }
        assertEquals(0, createSlot.captured.grace_minutes)
    }

    @Test
    fun `saveEditor with blank grace_minutes resets to default null`() {
        val vm = createViewModel()
        coEvery { mockApi.getCronJob("j-reset") } returns
            Response.success(
                CronJob(
                    id = "j-reset",
                    name = "Reset Job",
                    schedule = JsonPrimitive("0 12 * * *"),
                    grace_minutes = 30,
                ),
            )
        coEvery { mockApi.updateCronJob(eq("j-reset"), any()) } returns
            Response.success(CronJob(id = "j-reset", name = "Reset Job"))
        coEvery { mockApi.getCronJobs() } returns Response.success(emptyList())

        vm.openEditJobDialog("j-reset")
        settle()

        assertEquals("30", vm.uiState.value.editorState.grace_minutes)

        // Clear it
        vm.updateEditorField("grace_minutes", "   ")
        vm.saveEditor()
        settle()

        val updateSlot = slot<UpdateCronJobRequest>()
        coVerify { mockApi.updateCronJob(eq("j-reset"), capture(updateSlot)) }
        assertEquals(JsonNull, updateSlot.captured.updates["grace_minutes"])
    }

    @Test
    fun `stopCronJob uses localized string resolver for toast feedback`() {
        val app = mockk<Application>()
        every { app.getString(com.m57.hermescontrol.R.string.cron_stop_requested) } returns "CUSTOM_STOP_MESSAGE"
        val vm = CronJobsViewModel(app)
        vm.ioDispatcher = testDispatcher
        coEvery { mockApi.cancelCronJob("j-resolve") } returns
            Response.success(
                CronCancelResult(
                    cancelled = true,
                    execution_id = "e-1",
                    job_id = "j-resolve",
                ),
            )
        coEvery { mockApi.getCronJobs() } returns Response.success(emptyList())

        vm.stopCronJob("j-resolve")
        settle()

        io.mockk.verify { app.getString(com.m57.hermescontrol.R.string.cron_stop_requested) }
        assertEquals("CUSTOM_STOP_MESSAGE", vm.uiState.value.toastMessage)
    }

    @Test
    fun `saveEditor uses localized string resolver for grace validation failure`() {
        val app = mockk<Application>()
        every { app.getString(com.m57.hermescontrol.R.string.cron_edit_error_grace_minutes) } returns
            "CUSTOM_GRACE_ERROR"
        val vm = CronJobsViewModel(app)
        vm.ioDispatcher = testDispatcher

        vm.openNewJobDialog()
        settle()
        vm.updateEditorField("name", "Test")
        vm.updateEditorField("schedule", "0 0 * * *")
        vm.updateEditorField("grace_minutes", "invalid")

        vm.saveEditor()
        settle()

        io.mockk.verify { app.getString(com.m57.hermescontrol.R.string.cron_edit_error_grace_minutes) }
        assertEquals("CUSTOM_GRACE_ERROR", vm.uiState.value.editorState.toastMessage)
    }
}
