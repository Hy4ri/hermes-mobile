package com.m57.hermescontrol.ui.mcp

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.McpServer
import com.m57.hermescontrol.data.model.McpServerTestResponse
import com.m57.hermescontrol.data.model.McpServerUpdateRequest
import com.m57.hermescontrol.data.model.McpServersResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.OkHttpProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
class McpOwnershipTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: HermesApiService
    private val owned = McpServer(name = "scanner", enabled = true, source = "plugin", plugin = "security")
    private val conflict = "Server 'scanner' is provided by plugin 'security' and cannot be modified"

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = mockk()
        mockkObject(ApiClient, AuthManager)
        every { ApiClient.hermesApi } returns api
        every { AuthManager.currentDataScope() } throws IllegalStateException("No test scope")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `ownership decodes and legacy servers remain editable`() {
        val json = OkHttpProvider.json
        val plugin =
            json.decodeFromString<McpServer>(
                """{"name":"scanner","enabled":true,"source":"plugin","plugin":"security"}""",
            )
        assertEquals(owned, plugin)
        assertTrue(plugin.isPluginOwned)
        assertTrue(plugin.copy(plugin = null).isPluginOwned)
        val legacy = json.decodeFromString<McpServer>("""{"name":"local","enabled":true}""")
        assertFalse(legacy.isPluginOwned)
        assertNull(legacy.plugin)
        val config =
            json.decodeFromString<McpServer>(
                """{"name":"local","enabled":true,"source":"config","plugin":null}""",
            )
        assertFalse(config.isPluginOwned)
    }

    @Test
    fun `plugin mutations are blocked even for stale callbacks but testing remains available`() {
        val vm = loaded(owned)
        val stale = owned.copy(source = "config", plugin = null)
        vm.toggleServer(stale)
        vm.deleteServer(owned.name)
        vm.startEditingEnv(stale)
        vm.updateEnvKey("KEY")
        vm.updateEnvValue("value")
        vm.addEnvVar(owned.name)
        vm.removeEnvVar(owned.name, "KEY")
        vm.startMcpOAuthFlow(stale) { error("Must not open browser") }
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(
            vm.uiState.value.servers
                .single()
                .enabled,
        )
        assertNull(vm.uiState.value.editingEnvFor)
        coVerify(exactly = 0) { api.toggleMcpServer(any(), any()) }
        coVerify(exactly = 0) { api.deleteMcpServer(any()) }
        coVerify(exactly = 0) { api.updateMcpServer(any(), any()) }
        coVerify(exactly = 0) { api.authMcpServer(any()) }
        coEvery { api.testMcpServer(owned.name) } returns Response.success(McpServerTestResponse(ok = true))
        vm.testServer(owned.name)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(
            vm.uiState.value.serverTestResults
                .getValue(owned.name)
                .ok,
        )
    }

    @Test
    fun `config server toggle and environment editing remain available`() {
        val config = owned.copy(source = "config", plugin = null)
        val vm = loaded(config)
        coEvery { api.toggleMcpServer(config.name, any()) } returns Response.success(Unit)
        vm.toggleServer(config)
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(
            vm.uiState.value.servers
                .single()
                .enabled,
        )
        vm.startEditingEnv(config)
        assertEquals(config.name, vm.uiState.value.editingEnvFor)
        coEvery { api.updateMcpServer(config.name, any()) } returns Response.success(config)
        vm.updateEnvKey("KEY")
        vm.updateEnvValue("value")
        vm.addEnvVar(config.name)
        dispatcher.scheduler.advanceUntilIdle()
        coVerify { api.updateMcpServer(config.name, McpServerUpdateRequest(env = mapOf("KEY" to "value"))) }
        vm.removeEnvVar(config.name, "KEY")
        dispatcher.scheduler.advanceUntilIdle()
        coVerify { api.updateMcpServer(config.name, McpServerUpdateRequest(env = emptyMap())) }
        coEvery { api.deleteMcpServer(config.name) } returns Response.success(Unit)
        vm.deleteServer(config.name)
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { api.deleteMcpServer(config.name) }
    }

    @Test
    fun `stale ownership conflicts surface detail verbatim and revert toggle`() {
        val config = owned.copy(source = "config", plugin = null)
        val vm = loaded(config)
        coEvery { api.toggleMcpServer(any(), any()) } returns conflictResponse()
        vm.toggleServer(config)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(
            vm.uiState.value.servers
                .single()
                .enabled,
        )
        assertEquals(conflict, vm.uiState.value.toastMessage)
        coEvery { api.deleteMcpServer(any()) } returns conflictResponse()
        vm.deleteServer(config.name)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(conflict, vm.uiState.value.toastMessage)
        coEvery { api.updateMcpServer(any(), any()) } coAnswers { conflictResponse() }
        vm.updateEnvKey("KEY")
        vm.addEnvVar(config.name)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(conflict, vm.uiState.value.toastMessage)
        vm.clearToast()
        vm.removeEnvVar(config.name, "KEY")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(conflict, vm.uiState.value.toastMessage)
        coEvery { api.authMcpServer(any()) } returns conflictResponse()
        vm.clearToast()
        vm.startMcpOAuthFlow(config) { error("Must not open browser") }
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(conflict, vm.uiState.value.toastMessage)
    }

    @Test
    fun `add collision surfaces plugin conflict verbatim`() {
        coEvery { api.addMcpServer(any()) } returns conflictResponse()
        val vm = McpServersViewModel(ioDispatcher = dispatcher)
        vm.toggleAddForm()
        vm.updateAddServerName(owned.name)
        vm.updateAddServerUrl("https://example.com/mcp")
        vm.submitAddServer()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(conflict, vm.uiState.value.toastMessage)
        assertFalse(vm.uiState.value.addingServer)
        assertTrue(vm.uiState.value.showAddForm)
        assertEquals(owned.name, vm.uiState.value.addServerName)
    }

    private fun loaded(server: McpServer): McpServersViewModel {
        coEvery { api.getMcpServers() } returns Response.success(McpServersResponse(listOf(server)))
        return McpServersViewModel(ioDispatcher = dispatcher).also {
            it.loadServers()
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf(server), it.uiState.value.servers)
        }
    }

    private fun <T> conflictResponse(): Response<T> = Response.error(409, """{"detail":"$conflict"}""".toResponseBody())
}
