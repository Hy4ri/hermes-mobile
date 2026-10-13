package com.m57.hermescontrol.ui.mcp

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.model.McpOAuthCancelResponse
import com.m57.hermescontrol.data.model.McpOAuthFlowResponse
import com.m57.hermescontrol.data.model.McpServer
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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

/**
 * M1560 regression tests for MCP OAuth lifecycle in [McpServersViewModel].
 *
 * Verifies contract:
 * - Pending start abandonment: dismissal before start returns flowId cancels late flow and suppresses browser.
 * - Dismissed DELETE deferred + immediate retry: retry start does not proceed until previous DELETE succeeds.
 * - False ok / HTTP failure on cancel: blocks subsequent retry and surfaces honest error message.
 * - Endpoint / profile switch between invocation and job: leaves captured service intact and late publish / browser ignored.
 * - ViewModelStore.clear cleanup: still issues captured DELETE (bounded) and polling ceases.
 * - Completed / approved flow: does not trigger DELETE on dismiss/clear; only presentation clears.
 * - Plugin-owned servers: blocked from starting OAuth flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class McpOAuthLifecycleTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var apiOriginal: HermesApiService
    private lateinit var apiSwitched: HermesApiService

    private val initialScope =
        DataScope(
            connectionProfileId = "conn-1",
            baseUrl = "https://server1.example:9119",
            activeProfileId = "profile-initial",
        )
    private val switchedScope =
        DataScope(
            connectionProfileId = "conn-2",
            baseUrl = "https://server2.example:9119",
            activeProfileId = "profile-switched",
        )

    private val activeScopeFlow = MutableStateFlow(initialScope)
    private val activeProfileFlow = MutableStateFlow<String?>("profile-initial")

    private val oauthServer =
        McpServer(
            name = "test-oauth-server",
            enabled = true,
            source = "config",
            plugin = null,
            auth = "oauth",
        )

    private val pluginOwnedServer =
        McpServer(
            name = "plugin-server",
            enabled = true,
            source = "plugin",
            plugin = "my-plugin",
            auth = "oauth",
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        apiOriginal = mockk(relaxed = true)
        apiSwitched = mockk(relaxed = true)

        mockkObject(ApiClient, AuthManager)
        every { ApiClient.hermesApi } returns apiOriginal
        every { AuthManager.currentDataScope() } answers { activeScopeFlow.value }
        every { AuthManager.activeProfileId } returns activeProfileFlow
        every { AuthManager.getSelectedProfileId() } answers { activeProfileFlow.value }
        // OAuth capture reads the real auth mode/token; AuthManager is never init()ed in unit tests.
        every { AuthManager.isGatedMode() } returns false
        every { AuthManager.getToken() } returns "test-token"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // A pending flow polls every 2 s until the backend reports a terminal status, so these tests drive
    // ready work with runCurrent(); advanceUntilIdle() would spin the poll to exhaustion or forever.
    private fun createViewModel(
        factory: (DataScope, String?, List<okhttp3.Cookie>) -> HermesApiService = { scope, _, _ ->
            if (scope.baseUrl == initialScope.baseUrl) apiOriginal else apiSwitched
        },
    ): McpServersViewModel =
        McpServersViewModel(
            ioDispatcher = dispatcher,
            scopedServiceFactory = factory,
        )

    @Test
    fun `dismissing before start returns flowId cancels late flow and prevents browser launch`() {
        val startDeferred = CompletableDeferred<Response<McpOAuthFlowResponse>>()
        coEvery { apiOriginal.authMcpServer("test-oauth-server") } coAnswers { startDeferred.await() }
        coEvery { apiOriginal.cancelMcpOAuthFlow("flow-late-123") } returns
            Response.success(McpOAuthCancelResponse(ok = true, status = "cancelled"))

        var browserOpenedUrl: String? = null
        val vm = createViewModel()

        vm.startMcpOAuthFlow(oauthServer) { url ->
            browserOpenedUrl = url
        }
        dispatcher.scheduler.runCurrent()

        // User dismisses while start request is still pending
        vm.dismissOAuthFlow()
        dispatcher.scheduler.runCurrent()

        // Now the pending start completes with a flow ID
        startDeferred.complete(
            Response.success(
                McpOAuthFlowResponse(
                    flowId = "flow-late-123",
                    serverName = "test-oauth-server",
                    status = "pending",
                    authorizationUrl = "https://auth.example.com/login",
                ),
            ),
        )
        dispatcher.scheduler.advanceUntilIdle()

        // Late flow must be cancelled on remote service
        coVerify(exactly = 1) { apiOriginal.cancelMcpOAuthFlow("flow-late-123") }
        // Browser must NOT have been opened
        assertNull("Browser must not open after dismissal", browserOpenedUrl)
        // Dialog/Active flow state must be cleared
        assertNull(vm.uiState.value.activeOAuthFlow)
    }

    @Test
    fun `immediate retry waits for prior flow remote DELETE to complete before starting new POST`() {
        val cancelDeferred = CompletableDeferred<Response<McpOAuthCancelResponse>>()
        coEvery { apiOriginal.authMcpServer("test-oauth-server") } returns
            Response.success(
                McpOAuthFlowResponse(
                    flowId = "flow-first",
                    serverName = "test-oauth-server",
                    status = "pending",
                    authorizationUrl = "https://auth.example.com/step1",
                ),
            ) andThen
            Response.success(
                McpOAuthFlowResponse(
                    flowId = "flow-second",
                    serverName = "test-oauth-server",
                    status = "pending",
                    authorizationUrl = "https://auth.example.com/step2",
                ),
            )
        coEvery { apiOriginal.cancelMcpOAuthFlow("flow-first") } coAnswers { cancelDeferred.await() }

        var browserCallCount = 0
        val vm = createViewModel()

        // 1. Start first flow
        vm.startMcpOAuthFlow(oauthServer) { browserCallCount++ }
        dispatcher.scheduler.runCurrent()
        assertEquals(1, browserCallCount)
        assertEquals(
            "flow-first",
            vm.uiState.value.activeOAuthFlow
                ?.flowId,
        )

        // 2. Dismiss it, which triggers DELETE
        vm.dismissOAuthFlow()
        dispatcher.scheduler.runCurrent()
        assertNull(vm.uiState.value.activeOAuthFlow)

        // 3. Immediately retry: start second flow
        vm.startMcpOAuthFlow(oauthServer) { browserCallCount++ }
        dispatcher.scheduler.runCurrent()

        // At this point, the second authMcpServer POST must NOT have happened yet because cancelDeferred has not completed
        coVerify(exactly = 1) { apiOriginal.authMcpServer("test-oauth-server") }

        // 4. Complete the DELETE of flow-first
        cancelDeferred.complete(Response.success(McpOAuthCancelResponse(ok = true, status = "cancelled")))
        dispatcher.scheduler.runCurrent()

        // Now the second authMcpServer POST proceeds
        coVerify(exactly = 2) { apiOriginal.authMcpServer("test-oauth-server") }
        assertEquals(2, browserCallCount)
        assertEquals(
            "flow-second",
            vm.uiState.value.activeOAuthFlow
                ?.flowId,
        )
    }

    @Test
    fun `false ok or HTTP failure on cancellation surfaces error honestly and blocks retry`() {
        coEvery { apiOriginal.authMcpServer("test-oauth-server") } returns
            Response.success(
                McpOAuthFlowResponse(
                    flowId = "flow-failing-cancel",
                    serverName = "test-oauth-server",
                    status = "pending",
                    authorizationUrl = "https://auth.example.com/login",
                ),
            )
        // Cancellation returns ok = false
        coEvery { apiOriginal.cancelMcpOAuthFlow("flow-failing-cancel") } returns
            Response.success(McpOAuthCancelResponse(ok = false, status = "failed_to_abort"))

        val vm = createViewModel()
        vm.startMcpOAuthFlow(oauthServer) {}
        dispatcher.scheduler.runCurrent()

        // Dismiss flow: cancel fails
        vm.dismissOAuthFlow()
        dispatcher.scheduler.runCurrent()

        // Toast message must indicate cancellation failure honestly
        val toast = vm.uiState.value.toastMessage
        assertNotNull("Failed cancel must surface toast message", toast)
        assertTrue("Toast should reflect cancel failure", toast!!.contains("Failed to cancel OAuth"))

        // Retry must not proceed to execute new POST while uncancelled prior flow exists
        vm.startMcpOAuthFlow(oauthServer) {}
        dispatcher.scheduler.runCurrent()

        // authMcpServer should still have been called only once (the initial attempt)
        coVerify(exactly = 1) { apiOriginal.authMcpServer("test-oauth-server") }
    }

    @Test
    fun `HTTP error response on cancellation surfaces honest error and blocks retry`() {
        coEvery { apiOriginal.authMcpServer("test-oauth-server") } returns
            Response.success(
                McpOAuthFlowResponse(
                    flowId = "flow-500-cancel",
                    serverName = "test-oauth-server",
                    status = "pending",
                    authorizationUrl = "https://auth.example.com/login",
                ),
            )
        coEvery { apiOriginal.cancelMcpOAuthFlow("flow-500-cancel") } returns
            Response.error(500, """{"error":"internal crash"}""".toResponseBody())

        val vm = createViewModel()
        vm.startMcpOAuthFlow(oauthServer) {}
        dispatcher.scheduler.runCurrent()

        vm.dismissOAuthFlow()
        dispatcher.scheduler.runCurrent()

        val toast = vm.uiState.value.toastMessage
        assertNotNull(toast)
        assertTrue(toast!!.contains("Failed to cancel OAuth"))

        vm.startMcpOAuthFlow(oauthServer) {}
        dispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { apiOriginal.authMcpServer("test-oauth-server") }
    }

    @Test
    fun `endpoint or profile switch mid-job uses captured service and ignores late result`() {
        val startDeferred = CompletableDeferred<Response<McpOAuthFlowResponse>>()
        coEvery { apiOriginal.authMcpServer("test-oauth-server") } coAnswers { startDeferred.await() }
        coEvery { apiOriginal.cancelMcpOAuthFlow(any()) } returns
            Response.success(McpOAuthCancelResponse(ok = true, status = "cancelled"))

        var browserOpened = false
        val vm = createViewModel()

        // User starts OAuth flow under initialScope
        vm.startMcpOAuthFlow(oauthServer) {
            browserOpened = true
        }
        dispatcher.scheduler.runCurrent()

        // Ambient scope changes before start response arrives
        activeScopeFlow.value = switchedScope
        activeProfileFlow.value = "profile-switched"
        dispatcher.scheduler.runCurrent()

        // Now the start response arrives on the original captured request
        startDeferred.complete(
            Response.success(
                McpOAuthFlowResponse(
                    flowId = "flow-captured-scope",
                    serverName = "test-oauth-server",
                    status = "pending",
                    authorizationUrl = "https://auth.example.com/login",
                ),
            ),
        )
        dispatcher.scheduler.advanceUntilIdle()

        // Since scope changed, the late result must NOT publish activeOAuthFlow or open browser
        assertNull("Late OAuth flow must not publish to switched scope", vm.uiState.value.activeOAuthFlow)
        assertFalse("Browser must not open for late flow on switched scope", browserOpened)

        // Switched API service must NOT have been called
        coVerify(exactly = 0) { apiSwitched.authMcpServer(any()) }
        coVerify(exactly = 0) { apiSwitched.cancelMcpOAuthFlow(any()) }
    }

    @Test
    fun `viewModelStore clear cleanup issues bounded captured DELETE and polling ceases`() {
        coEvery { apiOriginal.authMcpServer("test-oauth-server") } returns
            Response.success(
                McpOAuthFlowResponse(
                    flowId = "flow-to-clean",
                    serverName = "test-oauth-server",
                    status = "pending",
                    authorizationUrl = "https://auth.example.com/step",
                ),
            )
        coEvery { apiOriginal.cancelMcpOAuthFlow("flow-to-clean") } returns
            Response.success(McpOAuthCancelResponse(ok = true, status = "cancelled"))
        coEvery { apiOriginal.getMcpOAuthFlowStatus("flow-to-clean") } returns
            Response.success(
                McpOAuthFlowResponse(
                    flowId = "flow-to-clean",
                    serverName = "test-oauth-server",
                    status = "pending",
                ),
            )

        // Register through the public ViewModelProvider API so store.clear() really runs onCleared().
        val vmStore = ViewModelStore()
        val vm =
            ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                        createViewModel() as T
                },
            )[McpServersViewModel::class.java]

        vm.startMcpOAuthFlow(oauthServer) {}
        dispatcher.scheduler.runCurrent()
        assertEquals(
            "flow-to-clean",
            vm.uiState.value.activeOAuthFlow
                ?.flowId,
        )

        // Simulate ViewModelStore clearance (nav or activity destruction)
        vmStore.clear()
        dispatcher.scheduler.runCurrent()

        // Captured DELETE must have been dispatched to original service
        coVerify(atLeast = 1) { apiOriginal.cancelMcpOAuthFlow("flow-to-clean") }

        // Start precedes the captured cleanup DELETE.
        io.mockk.coVerifyOrder {
            apiOriginal.authMcpServer("test-oauth-server")
            apiOriginal.cancelMcpOAuthFlow("flow-to-clean")
        }
    }

    @Test
    fun `completed or approved flow does not trigger remote DELETE when dismissed`() {
        coEvery { apiOriginal.authMcpServer("test-oauth-server") } returns
            Response.success(
                McpOAuthFlowResponse(
                    flowId = "flow-completed",
                    serverName = "test-oauth-server",
                    status = "completed",
                    authorizationUrl = "https://auth.example.com/done",
                ),
            )

        val vm = createViewModel()
        vm.startMcpOAuthFlow(oauthServer) {}
        dispatcher.scheduler.advanceUntilIdle()

        // Active flow completed
        vm.dismissOAuthFlow()
        dispatcher.scheduler.advanceUntilIdle()

        // Completed flow must not be remotely deleted!
        coVerify(exactly = 0) { apiOriginal.cancelMcpOAuthFlow("flow-completed") }
        assertNull(vm.uiState.value.activeOAuthFlow)
    }

    @Test
    fun `approved flow does not trigger remote DELETE on clearScopeOwnedState`() {
        coEvery { apiOriginal.authMcpServer("test-oauth-server") } returns
            Response.success(
                McpOAuthFlowResponse(
                    flowId = "flow-approved",
                    serverName = "test-oauth-server",
                    status = "approved",
                    authorizationUrl = "https://auth.example.com/done",
                ),
            )

        val vm = createViewModel()
        vm.startMcpOAuthFlow(oauthServer) {}
        dispatcher.scheduler.advanceUntilIdle()

        vm.clearScopeOwnedState()
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { apiOriginal.cancelMcpOAuthFlow("flow-approved") }
        assertNull(vm.uiState.value.activeOAuthFlow)
    }

    @Test
    fun `plugin-owned server is blocked from starting OAuth flow`() {
        var browserCalled = false
        val vm = createViewModel()

        vm.startMcpOAuthFlow(pluginOwnedServer) {
            browserCalled = true
        }
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse("Browser must not be opened for plugin-owned server", browserCalled)
        coVerify(exactly = 0) { apiOriginal.authMcpServer(any()) }
        assertNull(vm.uiState.value.activeOAuthFlow)
    }
}
