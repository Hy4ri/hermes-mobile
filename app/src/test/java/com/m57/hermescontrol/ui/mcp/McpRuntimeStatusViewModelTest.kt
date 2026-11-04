package com.m57.hermescontrol.ui.mcp

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.model.McpServer
import com.m57.hermescontrol.data.model.McpServersResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.contract.McpServersStatusParams
import com.m57.hermescontrol.data.ws.contract.RpcMethods
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
 * ViewModel-level acceptance tests for #419: the authoritative runtime rows are merged by name
 * into the config list, a WS failure never breaks the list, and rows never leak across a
 * profile/connection scope change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class McpRuntimeStatusViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    private lateinit var api: HermesApiService

    private val docs = McpServer(name = "docs", enabled = true, auth = "oauth")
    private val local = McpServer(name = "local", enabled = true, transport = "stdio")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = mockk()
        mockkObject(ApiClient, AuthManager, HermesWsClient)
        every { ApiClient.hermesApi } returns api
        every { AuthManager.activeProfileId } returns MutableStateFlow("work")
        every { AuthManager.currentDataScope() } returns DataScope("server-default", "https://default.example", "work")
        every { AuthManager.isGatedMode() } returns false
        every { AuthManager.getToken() } returns "test-token"
        every { AuthManager.endpointForBuild() } throws IllegalStateException("unused")
        // WS unavailable by default; individual tests stub the status read.
        coEvery { HermesWsClient.call(RpcMethods.MCP_SERVERS_STATUS, any(), any(), any()) } throws
            HermesWsClient.HermesRpcException("Connection lost — request cancelled")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `runtime rows are merged by name and drive the sign-in state`() {
        stubStatus(
            """{"servers":[{"name":"docs","transport":"http","tools":3,"connected":false,
                "disabled":false,"status":"failed","source":"config","plugin":null,"needs_auth":true}],
                "checked_at":1710000000}""",
        )
        val vm = loaded(listOf(docs, local))
        val rows = vm.uiState.value.runtimeStatus
        assertNotNull(rows)
        assertTrue(rows!!.getValue("docs").needsAuth)
        // The idle card now sees AUTH_REQUIRED from the row alone — no error text needed.
        assertEquals(
            McpHealthStatus.AUTH_REQUIRED,
            McpHealthStatus.resolveForServer(
                isTesting = false,
                testResult = null,
                runtime = rows.getValue("docs"),
                serverStatus = null,
                serverError = null,
            ),
        )
        // A server with no row keeps the legacy behavior (unknown, never forced healthy).
        assertFalse(rows.containsKey("local"))
    }

    @Test
    fun `ws failure never fails the config list`() {
        val vm = loaded(listOf(docs))
        assertEquals(listOf(docs), vm.uiState.value.servers)
        assertNull(vm.uiState.value.runtimeStatus)
    }

    @Test
    fun `runtime rows are cleared on scope change`() {
        stubStatus(
            """{"servers":[{"name":"docs","transport":"http","tools":0,"connected":true,
                "disabled":false,"status":"connected","source":"config"}]}""",
        )
        val vm = loaded(listOf(docs))
        assertNotNull(vm.uiState.value.runtimeStatus)
        vm.clearScopeOwnedState()
        assertNull(vm.uiState.value.runtimeStatus)
        assertTrue(
            vm.uiState.value.servers
                .isEmpty(),
        )
    }

    private fun stubStatus(payload: String) {
        val element: JsonElement = json.parseToJsonElement(payload)
        coEvery { HermesWsClient.call(RpcMethods.MCP_SERVERS_STATUS, any(), any(), any()) } returns element
    }

    private fun loaded(servers: List<McpServer>): McpServersViewModel {
        coEvery { api.getMcpServers() } returns Response.success(McpServersResponse(servers))
        return McpServersViewModel(ioDispatcher = dispatcher, scopedServiceFactory = { _, _, _ -> api }).also {
            it.loadServers()
            dispatcher.scheduler.advanceUntilIdle()
        }
    }
}
