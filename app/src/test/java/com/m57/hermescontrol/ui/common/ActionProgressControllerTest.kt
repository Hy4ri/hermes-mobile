package com.m57.hermescontrol.ui.common

import com.m57.hermescontrol.data.model.ActionStatusResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * NOTE: no mockkStatic(Dispatchers) here — a static Dispatchers mock bleeds
 * into later test classes in the same JVM. Real Dispatchers are fine: the
 * network layer is mocked, so the poll loop is driven by the test dispatcher
 * only (the controller calls the suspend API directly, no IO hops).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActionProgressControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private lateinit var mockApi: HermesApiService
    private var finishedExitCode: Int? = null

    @Before
    fun setup() {
        mockkObject(ApiClient)
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi
        finishedExitCode = null
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    private fun controller(onFinished: ((com.m57.hermescontrol.data.model.ActionStatusResponse?) -> Unit)? = null) =
        ActionProgressController(
            scope = scope,
            onFinished = onFinished ?: { finishedExitCode = it?.exit_code },
        )

    private fun tick() {
        scope.advanceTimeBy(1_500)
        scope.runCurrent()
    }

    @Test
    fun `open shows the dialog in starting phase`() {
        val controller = controller()

        controller.open()

        val state = controller.state.value
        assertTrue(state.visible)
        assertEquals(ActionProgressPhase.STARTING, state.phase)
        assertNull(state.actionName)
        assertTrue(state.lines.isEmpty())
    }

    @Test
    fun `markStarted polls the status log until exit and reports success`() {
        val controller = controller()
        coEvery { mockApi.getActionStatus("hermes-update") } returnsMany
            listOf(
                Response.success(
                    ActionStatusResponse(name = "hermes-update", running = true, lines = listOf("l1")),
                ),
                Response.success(
                    ActionStatusResponse(
                        name = "hermes-update",
                        running = true,
                        lines = listOf("l1", "l2"),
                    ),
                ),
                Response.success(
                    ActionStatusResponse(
                        name = "hermes-update",
                        running = false,
                        exit_code = 0,
                        lines = listOf("l1", "l2", "l3"),
                    ),
                ),
            )

        controller.open()
        controller.markStarted("hermes-update")

        assertEquals(ActionProgressPhase.RUNNING, controller.state.value.phase)
        assertEquals("hermes-update", controller.state.value.actionName)

        tick()
        assertEquals(ActionProgressPhase.RUNNING, controller.state.value.phase)
        assertEquals(listOf("l1"), controller.state.value.lines)

        tick()
        assertEquals(listOf("l1", "l2"), controller.state.value.lines)

        tick()
        val state = controller.state.value
        assertEquals(ActionProgressPhase.SUCCEEDED, state.phase)
        assertEquals(0, state.exitCode)
        assertTrue(state.visible)
        assertEquals(0, finishedExitCode)
    }

    @Test
    fun `non-zero exit reports failure with the exit code`() {
        val controller = controller()
        coEvery { mockApi.getActionStatus("hermes-update") } returns
            Response.success(
                ActionStatusResponse(name = "hermes-update", running = false, exit_code = 7),
            )

        controller.open()
        controller.markStarted("hermes-update")
        tick()

        val state = controller.state.value
        assertEquals(ActionProgressPhase.FAILED, state.phase)
        assertEquals(7, state.exitCode)
        assertEquals(7, finishedExitCode)
    }

    @Test
    fun `fail surfaces the trigger error in the dialog`() {
        val controller = controller()

        controller.open()
        controller.fail("Failed to start update: boom")

        val state = controller.state.value
        assertTrue(state.visible)
        assertEquals(ActionProgressPhase.FAILED, state.phase)
        assertEquals("Failed to start update: boom", state.error)
    }

    @Test
    fun `repeated poll failures settle on failed with the error message`() {
        val controller = controller()
        // 404 is not retried by safeApiCall, so each tick is exactly one poll
        // iteration; 5 consecutive failures end the loop.
        coEvery { mockApi.getActionStatus("hermes-update") } returns
            Response.error(404, "not found".toResponseBody())

        controller.open()
        controller.markStarted("hermes-update")
        repeat(5) { tick() }

        val state = controller.state.value
        assertEquals(ActionProgressPhase.FAILED, state.phase)
        assertTrue(state.error != null)
        assertNull(finishedExitCode)
    }

    @Test
    fun `dismiss stops polling and hides the dialog`() {
        val controller = controller()
        var calls = 0
        coEvery { mockApi.getActionStatus("hermes-update") } answers {
            calls++
            Response.success(ActionStatusResponse(name = "hermes-update", running = true))
        }

        controller.open()
        controller.markStarted("hermes-update")
        tick()
        val callsAfterFirstPoll = calls

        controller.dismiss()
        assertFalse(controller.state.value.visible)

        repeat(3) { tick() }
        assertEquals(callsAfterFirstPoll, calls)
    }

    @Test
    fun `onFinished is not called when dismissed mid-run`() {
        val controller = controller()
        coEvery { mockApi.getActionStatus("hermes-update") } returns
            Response.success(ActionStatusResponse(name = "hermes-update", running = true))

        controller.open()
        controller.markStarted("hermes-update")
        tick()
        controller.dismiss()
        repeat(3) { tick() }

        assertNull(finishedExitCode)
    }
}
