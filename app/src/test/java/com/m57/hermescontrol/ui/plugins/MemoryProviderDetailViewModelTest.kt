package com.m57.hermescontrol.ui.plugins

import com.m57.hermescontrol.data.model.MemoryProviderConfigResponse
import com.m57.hermescontrol.data.model.MemoryProviderConfigUpdateRequest
import com.m57.hermescontrol.data.model.MemoryProviderConfigUpdateResponse
import com.m57.hermescontrol.data.model.MemoryProviderField
import com.m57.hermescontrol.data.model.MemoryProviderSetupResponse
import com.m57.hermescontrol.data.model.MemoryProviderSetupResult
import com.m57.hermescontrol.data.model.MemoryProviderStatusRow
import com.m57.hermescontrol.data.model.MemoryResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

/**
 * Issue #783 — per-memory-provider management. Contracts verified against
 * hermes-agent web_server.py:
 * - GET  /api/memory → {active, providers:[{name, status, ...}], builtin_files}
 * - GET  /api/memory/providers/{name}/config → {name, label, docs_url, fields, setup}
 *   where fields are schema rows (text/secret/select/bool/number) carrying
 *   existing values; secrets are write-only (is_set, value never echoed).
 * - PUT  /api/memory/providers/{name}/config {values} → {ok, active}
 *   (also activates the provider).
 * - POST /api/memory/providers/{name}/setup {values} → synchronous
 *   {ok, provider, results:[{kind,name,status,...}], status}.
 *
 * NOTE: no mockkStatic(Dispatchers) here — a static Dispatchers mock bleeds
 * JVM-wide and breaks later test classes. The ViewModel under test does no
 * explicit IO hop (Retrofit suspend calls run off the caller thread), so
 * setMain(StandardTestDispatcher) alone makes it fully deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoryProviderDetailViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApi: HermesApiService

    private val webStatus =
        MemoryProviderStatusRow(
            name = "web",
            description = "Web memory",
            available = true,
            configured = true,
            status = "ready",
        )
    private val webConfig =
        MemoryProviderConfigResponse(
            name = "web",
            label = "Web Memory",
            docs_url = "https://example.com/docs",
            fields =
                listOf(
                    MemoryProviderField(
                        key = "model",
                        kind = "text",
                        label = "Model",
                        value = "gpt-4o",
                    ),
                    MemoryProviderField(
                        key = "api_key",
                        kind = "secret",
                        label = "API Key",
                        is_set = true,
                    ),
                    MemoryProviderField(
                        key = "memory_dir",
                        kind = "select",
                        label = "Directory",
                        options =
                            listOf(
                                com.m57.hermescontrol.data.model.MemoryProviderFieldOption(
                                    value = "default",
                                    label = "Default",
                                ),
                                com.m57.hermescontrol.data.model.MemoryProviderFieldOption(
                                    value = "custom",
                                    label = "Custom",
                                ),
                            ),
                        value = "default",
                    ),
                ),
        )
    private val setupOk =
        MemoryProviderSetupResponse(
            ok = true,
            provider = "web",
            results =
                listOf(
                    MemoryProviderSetupResult(
                        kind = "pip",
                        name = "honcho-ai",
                        status = "installed",
                        stdout = "Requirement already satisfied",
                    ),
                ),
            status = webStatus,
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ApiClient)
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi
        coEvery { mockApi.getMemory() } returns
            Response.success(
                MemoryResponse(active = "web", providers = listOf(webStatus)),
            )
        coEvery { mockApi.getMemoryProviderConfig("web") } returns Response.success(webConfig)
        coEvery { mockApi.updateMemoryProviderConfig("web", any()) } returns
            Response.success(MemoryProviderConfigUpdateResponse(ok = true, active = "web"))
        coEvery { mockApi.setupMemoryProvider("web", any()) } returns Response.success(setupOk)
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun createViewModel(): MemoryProviderDetailViewModel {
        val vm = MemoryProviderDetailViewModel()
        vm.setProvider("web")
        vm.load()
        testDispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    @Test
    fun `load fetches config and seeds non-secret edits`() {
        val vm = createViewModel()

        val state = vm.uiState.value
        assertEquals("web", state.providerName)
        assertEquals("web", state.config?.name)
        assertEquals("ready", state.status?.status)
        // Secrets stay blank (write-only); text/select values are seeded.
        assertEquals("gpt-4o", state.edits["model"])
        assertEquals("default", state.edits["memory_dir"])
        assertNull(state.edits["api_key"])
    }

    @Test
    fun `setProvider switches config to the selected provider and drops prior state`() {
        val vm = createViewModel()
        assertTrue(vm.uiState.value.config != null)

        vm.setFieldValue("model", "claude-4")
        vm.setProvider("tts")
        vm.load()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("tts", state.providerName)
        // No bleed from the previously viewed provider.
        assertNull(state.edits["model"])
        coVerify { mockApi.getMemoryProviderConfig("tts") }
    }

    @Test
    fun `saveConfig sends edits and shows saved toast`() {
        val vm = createViewModel()
        vm.setFieldValue("model", "claude-4")
        vm.setFieldValue("api_key", "sk-test")
        vm.saveConfig()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify {
            mockApi.updateMemoryProviderConfig(
                "web",
                MemoryProviderConfigUpdateRequest(
                    mapOf(
                        "model" to "claude-4",
                        "memory_dir" to "default",
                        "api_key" to "sk-test",
                    ),
                ),
            )
        }
        assertEquals("Memory provider settings saved", vm.uiState.value.toastMessage)
    }

    @Test
    fun `saveConfig with blank secret still sends it and backend keeps the stored value`() {
        val vm = createViewModel()
        vm.saveConfig()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify {
            mockApi.updateMemoryProviderConfig(
                "web",
                MemoryProviderConfigUpdateRequest(
                    mapOf(
                        "model" to "gpt-4o",
                        "memory_dir" to "default",
                        "api_key" to "",
                    ),
                ),
            )
        }
        assertFalse(vm.uiState.value.saving)
    }

    @Test
    fun `saveConfig failure shows error toast`() {
        coEvery { mockApi.updateMemoryProviderConfig("web", any()) } returns
            Response.error(500, "boom".toResponseBody())
        val vm = createViewModel()
        vm.saveConfig()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.toastMessage.orEmpty().contains("Failed to save"))
    }

    @Test
    fun `runSetup stores inline results and shows success toast`() {
        coEvery { mockApi.setupMemoryProvider("web", any()) } returns Response.success(setupOk)
        val vm = createViewModel()
        vm.runSetup()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("Setup completed", state.toastMessage)
        assertTrue(state.setupResult?.ok == true)
        assertEquals("installed", state.setupResult?.results?.first()?.status)
    }

    @Test
    fun `runSetup failure surfaces error toast`() {
        coEvery { mockApi.setupMemoryProvider("web", any()) } returns
            Response.error(400, "nope".toResponseBody())
        val vm = createViewModel()
        vm.runSetup()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.toastMessage.orEmpty().contains("Setup failed"))
    }

    @Test
    fun `load failure surfaces error message`() {
        coEvery { mockApi.getMemoryProviderConfig("web") } returns
            Response.error(404, "unknown".toResponseBody())
        val vm = MemoryProviderDetailViewModel()
        vm.setProvider("web")
        vm.load()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.config)
        assertEquals(
            "Not Found (HTTP 404): The requested resource could not be found.",
            vm.uiState.value.errorMessage,
        )
    }
}
