package com.m57.hermescontrol.ui.cron

import com.m57.hermescontrol.data.model.CronBlueprint
import com.m57.hermescontrol.data.model.CronBlueprintField
import com.m57.hermescontrol.data.model.CronBlueprintListResponse
import com.m57.hermescontrol.data.model.CronJob
import com.m57.hermescontrol.data.model.DeliveryTarget
import com.m57.hermescontrol.data.model.DeliveryTargetsResponse
import com.m57.hermescontrol.data.model.InstantiateBlueprintRequest
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
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class CronJobsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockApi = mockk<HermesApiService>(relaxed = true)

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
        val vm = CronJobsViewModel()
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

        val toast = vm.uiState.value.editorState.toastMessage.orEmpty()
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
}
