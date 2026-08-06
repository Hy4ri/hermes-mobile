package com.m57.hermescontrol.ui.toolsets

import com.m57.hermescontrol.data.model.ActionStatusResponse
import com.m57.hermescontrol.data.model.EnvVarRevealResponse
import com.m57.hermescontrol.data.model.ToolsetConfigResponse
import com.m57.hermescontrol.data.model.ToolsetEnvUpdateResponse
import com.m57.hermescontrol.data.model.ToolsetEnvVar
import com.m57.hermescontrol.data.model.ToolsetPostSetupResponse
import com.m57.hermescontrol.data.model.ToolsetProvider
import com.m57.hermescontrol.data.model.ToolsetProviderSelectResponse
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
 * Issue #782 — per-toolset management. Contracts verified against
 * hermes-agent web_routers/tools.py:
 * - GET  /api/tools/toolsets/{name}/config → {name, has_category, providers,
 *   active_provider, ...} where each provider row carries env_vars with
 *   is_set, post_setup key and a server-computed readiness status.
 * - PUT  /api/tools/toolsets/{name}/provider {provider} → {ok, name, provider}
 * - PUT  /api/tools/toolsets/{name}/env {env} → {ok, saved, skipped, is_set}
 * - POST /api/tools/toolsets/{name}/post-setup {key} → {ok, pid, name, key},
 *   then tail GET /api/actions/{name}/status until running flips false.
 *
 * NOTE: no mockkStatic(Dispatchers) here — a static Dispatchers mock bleeds
 * JVM-wide and breaks later test classes. The ViewModel under test does no
 * explicit IO hop (Retrofit suspend calls run off the caller thread), so
 * setMain(StandardTestDispatcher) alone makes it fully deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolsetDetailViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApi: HermesApiService

    private val providerA =
        ToolsetProvider(
            name = "prov-a",
            envVars = listOf(ToolsetEnvVar(key = "KEY_A", prompt = "Prompt A", isSet = true)),
            status = "ready",
        )
    private val providerB =
        ToolsetProvider(
            name = "prov-b",
            envVars = listOf(ToolsetEnvVar(key = "KEY_B", isSet = false)),
            postSetup = "camofox",
            isActive = true,
            status = "needs_keys",
        )
    private val config =
        ToolsetConfigResponse(
            name = "web",
            hasCategory = true,
            providers = listOf(providerA, providerB),
            activeProvider = "prov-b",
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ApiClient)
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi
        coEvery { mockApi.getToolsetConfig("web") } returns Response.success(config)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun <T> errorResponse(code: Int): Response<T> = Response.error(code, "{}".toResponseBody(null))

    private fun createViewModel(): ToolsetDetailViewModel {
        val vm = ToolsetDetailViewModel()
        vm.setToolset("web")
        vm.loadConfig()
        testDispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    @Test
    fun `loadConfig success populates config and defaults expanded provider to active`() {
        val vm = createViewModel()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals(config, state.config)
        // The default-expanded provider mirrors the desktop panel: the
        // provider actually active in config (is_active / active_provider).
        assertEquals("prov-b", state.expandedProvider)
    }

    @Test
    fun `loadConfig failure surfaces error and keeps config null`() {
        coEvery { mockApi.getToolsetConfig("web") } returns errorResponse(400)

        val vm = createViewModel()

        val state = vm.uiState.value
        assertNull(state.config)
        assertTrue(state.errorMessage?.contains("Failed to load toolset config") == true)
        assertFalse(state.isLoading)
    }

    @Test
    fun `selectProvider success patches active provider and toasts`() {
        coEvery { mockApi.selectToolsetProvider("web", any()) } returns
            Response.success(ToolsetProviderSelectResponse(ok = true, name = "web", provider = "prov-a"))

        val vm = createViewModel()
        vm.selectProvider(providerA)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("prov-a", state.config?.activeProvider)
        assertEquals(listOf(true, false), state.config?.providers?.map { it.isActive })
        assertEquals("Backend set to prov-a", state.toastMessage)
        assertNull(state.selectingProvider)
    }

    @Test
    fun `selectProvider failure toasts and keeps config untouched`() {
        coEvery { mockApi.selectToolsetProvider("web", any()) } returns errorResponse(400)

        val vm = createViewModel()
        vm.selectProvider(providerA)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("prov-b", state.config?.activeProvider)
        assertTrue(state.toastMessage?.contains("Failed to select backend") == true)
        assertNull(state.selectingProvider)
    }

    @Test
    fun `saveEnvVar success marks the key set locally`() {
        coEvery { mockApi.saveToolsetEnv("web", any()) } returns
            Response.success(ToolsetEnvUpdateResponse(ok = true, saved = listOf("KEY_B")))

        val vm = createViewModel()
        vm.saveEnvVar("KEY_B", "secret-value")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(true, state.config?.providers?.get(1)?.envVars?.first { it.key == "KEY_B" }?.isSet)
        assertEquals("Saved KEY_B", state.toastMessage)
        assertNull(state.savingEnvKey)
    }

    @Test
    fun `saveEnvVar with blank value does not call the backend`() {
        val vm = createViewModel()
        vm.saveEnvVar("KEY_B", "   ")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { mockApi.saveToolsetEnv(any(), any()) }
        assertNull(vm.uiState.value.savingEnvKey)
        assertNull(vm.uiState.value.toastMessage)
    }

    @Test
    fun `clearEnvVar success marks key unset and drops revealed value`() {
        coEvery { mockApi.revealEnvVar(any()) } returns
            Response.success(EnvVarRevealResponse(key = "KEY_B", value = "sekret"))
        coEvery { mockApi.deleteEnvVar(any()) } returns Response.success(Unit)

        val vm = createViewModel()
        vm.revealEnvVar("KEY_B")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("sekret", vm.uiState.value.revealedValues["KEY_B"])

        vm.clearEnvVar("KEY_B")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(false, state.config?.providers?.get(1)?.envVars?.first { it.key == "KEY_B" }?.isSet)
        assertFalse(state.revealedValues.containsKey("KEY_B"))
        assertEquals("Cleared KEY_B", state.toastMessage)
        assertNull(state.deletingEnvKey)
    }

    @Test
    fun `reveal then hide toggles revealed value`() {
        coEvery { mockApi.revealEnvVar(any()) } returns
            Response.success(EnvVarRevealResponse(key = "KEY_B", value = "sekret"))

        val vm = createViewModel()
        vm.revealEnvVar("KEY_B")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("sekret", vm.uiState.value.revealedValues["KEY_B"])

        vm.hideEnvVar("KEY_B")
        assertFalse(vm.uiState.value.revealedValues.containsKey("KEY_B"))
    }

    @Test
    fun `setToolset switches config to the selected toolset and drops prior state`() {
        // Regression for the on-device bug (2026-08-06): the VM is shared
        // across all toolset detail nav entries, so the name must be driven
        // per entry — every toolset must load ITS OWN config, and state from
        // the previously viewed toolset (revealed secrets included) must not
        // bleed into the next one.
        val ttsConfig =
            ToolsetConfigResponse(
                name = "tts",
                hasCategory = true,
                providers = listOf(providerA.copy(isActive = false)),
            )
        coEvery { mockApi.getToolsetConfig("tts") } returns Response.success(ttsConfig)
        coEvery { mockApi.revealEnvVar(any()) } returns
            Response.success(EnvVarRevealResponse(key = "KEY_B", value = "sekret"))

        val vm = createViewModel()
        vm.revealEnvVar("KEY_B")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("sekret", vm.uiState.value.revealedValues["KEY_B"])

        vm.setToolset("tts")
        vm.loadConfig()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("tts", state.config?.name)
        assertTrue(state.revealedValues.isEmpty())
        // Default-expand re-claims for the NEW toolset (its first provider),
        // not the previous toolset's active one.
        assertEquals("prov-a", state.expandedProvider)
        coVerify { mockApi.getToolsetConfig("tts") }
    }

    @Test
    fun `runPostSetup polls action log to completion then refreshes config`() {
        coEvery { mockApi.runToolsetPostSetup("web", any()) } returns
            Response.success(
                ToolsetPostSetupResponse(
                    ok = true,
                    pid = 42,
                    name = "tools-post-setup",
                    key = "camofox",
                ),
            )
        var polls = 0
        coEvery { mockApi.getActionStatus("tools-post-setup") } answers {
            polls++
            if (polls == 1) {
                Response.success(
                    ActionStatusResponse(
                        name = "tools-post-setup",
                        running = true,
                        lines = listOf("installing…"),
                    ),
                )
            } else {
                Response.success(
                    ActionStatusResponse(
                        name = "tools-post-setup",
                        running = false,
                        exit_code = 0,
                        lines = listOf("installing…", "done"),
                    ),
                )
            }
        }
        coEvery { mockApi.getToolsetConfig("web") } returns Response.success(config)

        val vm = createViewModel()
        vm.runPostSetup("camofox")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(0, state.postSetup?.exitCode)
        assertFalse(state.postSetup?.running == true)
        assertEquals(listOf("installing…", "done"), state.postSetup?.lines)
        // The config is refreshed once the install finishes so readiness
        // pills reflect the new install state (initial load + refresh).
        coVerify(exactly = 2) { mockApi.getToolsetConfig("web") }
    }

    @Test
    fun `runPostSetup with rejected spawn does not poll`() {
        coEvery { mockApi.runToolsetPostSetup("web", any()) } returns
            Response.success(
                ToolsetPostSetupResponse(
                    ok = false,
                    pid = null,
                    name = "tools-post-setup",
                    key = "camofox",
                ),
            )

        val vm = createViewModel()
        vm.runPostSetup("camofox")
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.postSetup)
        assertTrue(vm.uiState.value.toastMessage?.contains("spawn rejected") == true)
        coVerify(exactly = 0) { mockApi.getActionStatus(any()) }
    }
}
