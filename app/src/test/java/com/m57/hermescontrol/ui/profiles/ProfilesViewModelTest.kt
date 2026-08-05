package com.m57.hermescontrol.ui.profiles

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.ActiveProfileResponse
import com.m57.hermescontrol.data.model.ModelOptionsResponse
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.data.model.ProfileDescribeAutoRequest
import com.m57.hermescontrol.data.model.ProfileDescribeAutoResponse
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.model.ProfileSetupCommandResponse
import com.m57.hermescontrol.data.model.ProfilesResponse
import com.m57.hermescontrol.data.model.RenameProfileRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
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
 * Issue #781 — Profiles screen delete/rename/auto-describe/setup-command.
 * Contracts verified against hermes-agent web_routers/profiles.py:
 * - PATCH  /api/profiles/{name} {new_name} -> {ok, name, path}
 * - DELETE /api/profiles/{name} -> {ok, path}
 * - POST   /api/profiles/{name}/describe-auto {overwrite} -> {ok, reason, description, description_auto}
 *   (generation failures come back as ok:false + reason, NOT an HTTP error)
 * - GET    /api/profiles/{name}/setup-command -> {command}
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfilesViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApi: HermesApiService
    private var storedPinnedModels: MutableList<PinnedModel> = mutableListOf()

    private fun stubProfilesLoad() {
        coEvery { mockApi.getProfiles() } returns
            Response.success(ProfilesResponse(listOf(ProfileInfo(name = "default", is_default = true))))
        coEvery { mockApi.getActiveProfile() } returns Response.success(ActiveProfileResponse(active = "default"))
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val testMainDispatcher = Dispatchers.Main

        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns testDispatcher
        every { Dispatchers.Main } returns testMainDispatcher

        mockkObject(AuthManager)
        storedPinnedModels = mutableListOf()
        every { AuthManager.getPinnedModels() } answers { storedPinnedModels.toList() }
        every { AuthManager.savePinnedModels(any()) } answers {
            storedPinnedModels = firstArg<List<PinnedModel>>().toMutableList()
        }

        mockkObject(ApiClient)
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi

        stubProfilesLoad()
    }

    private fun <T> errorResponse(code: Int): Response<T> = Response.error(code, "{}".toResponseBody(null))

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(): ProfilesViewModel {
        val vm = ProfilesViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    @Test
    fun `renameProfile success calls backend and reloads`() {
        coEvery { mockApi.renameProfile(any(), any()) } returns Response.success(Unit)

        val vm = createViewModel()
        vm.renameProfile("work", "play")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { mockApi.renameProfile("work", RenameProfileRequest("play")) }
        assertTrue(vm.uiState.value.toastMessage!!.contains("renamed"))
        // reload happened
        coVerify { mockApi.getProfiles() }
    }

    @Test
    fun `renameProfile to same name is a no-op`() {
        // If the VM ever fired the API with an unchanged name, this stub throws
        // and the test fails — no fragile exactly=0 verification needed.
        coEvery { mockApi.renameProfile(any(), any()) } throws IllegalStateException("should not be called")

        val vm = createViewModel()
        vm.renameProfile("work", "work")
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.toastMessage)
    }

    @Test
    fun `renameProfile failure surfaces toast and stops loading`() {
        coEvery { mockApi.renameProfile(any(), any()) } returns errorResponse(404)

        val vm = createViewModel()
        vm.renameProfile("work", "play")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.toastMessage!!.contains("Failed to rename"))
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `deleteProfile success calls backend and reloads`() {
        coEvery { mockApi.deleteProfile(any()) } returns Response.success(Unit)

        val vm = createViewModel()
        vm.deleteProfile("work")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { mockApi.deleteProfile("work") }
        assertTrue(vm.uiState.value.toastMessage!!.contains("deleted"))
        coVerify { mockApi.getProfiles() }
    }

    @Test
    fun `deleteProfile failure surfaces toast`() {
        coEvery { mockApi.deleteProfile(any()) } returns errorResponse(404)

        val vm = createViewModel()
        vm.deleteProfile("work")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.toastMessage!!.contains("Failed to delete"))
    }

    @Test
    fun `autoDescribeProfile success with ok true reloads`() {
        coEvery { mockApi.describeProfileAuto(any(), any()) } returns
            Response.success(ProfileDescribeAutoResponse(ok = true, description = "desc", description_auto = true))

        val vm = createViewModel()
        vm.autoDescribeProfile("work")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { mockApi.describeProfileAuto("work", ProfileDescribeAutoRequest(overwrite = true)) }
        assertTrue(vm.uiState.value.toastMessage!!.contains("Auto-described"))
        assertFalse(vm.uiState.value.isAutoDescribing)
        coVerify { mockApi.getProfiles() }
    }

    @Test
    fun `autoDescribeProfile ok false surfaces backend reason`() {
        coEvery { mockApi.describeProfileAuto(any(), any()) } returns
            Response.success(ProfileDescribeAutoResponse(ok = false, reason = "no aux client configured"))

        val vm = createViewModel()
        vm.autoDescribeProfile("work")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.toastMessage!!.contains("no aux client configured"))
        assertFalse(vm.uiState.value.isAutoDescribing)
    }

    @Test
    fun `fetchSetupCommand success stores command`() {
        coEvery { mockApi.getProfileSetupCommand(any()) } returns
            Response.success(ProfileSetupCommandResponse(command = "work setup"))

        val vm = createViewModel()
        vm.fetchSetupCommand("work")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { mockApi.getProfileSetupCommand("work") }
        assertEquals("work setup", vm.uiState.value.setupCommand)
        assertFalse(vm.uiState.value.isLoadingSetupCommand)
    }

    @Test
    fun `fetchSetupCommand failure leaves command null and toasts`() {
        coEvery { mockApi.getProfileSetupCommand(any()) } returns errorResponse(404)

        val vm = createViewModel()
        vm.fetchSetupCommand("work")
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.setupCommand)
        assertTrue(vm.uiState.value.toastMessage!!.contains("Failed to fetch setup command"))
    }

    @Test
    fun `loadModelOptions success populates providers and pins`() {
        storedPinnedModels.add(PinnedModel("openai", "gpt-4"))
        coEvery { mockApi.getModelOptions() } returns
            Response.success(
                ModelOptionsResponse(
                    listOf(
                        ModelProvider(
                            slug = "openai",
                            name = "OpenAI",
                            models = listOf("gpt-4o", "gpt-4o-mini"),
                        ),
                    ),
                ),
            )

        val vm = createViewModel()
        vm.loadModelOptions()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, vm.uiState.value.modelProviders.size)
        assertEquals("gpt-4o", vm.uiState.value.modelProviders[0].models!![0])
        assertEquals(listOf(PinnedModel("openai", "gpt-4")), vm.uiState.value.modelPickerPinned)
        assertFalse(vm.uiState.value.isLoadingBuilderData)
    }

    @Test
    fun `loadModelOptions failure toasts without blanking errorMessage`() {
        coEvery { mockApi.getModelOptions() } returns errorResponse(500)

        val vm = createViewModel()
        vm.loadModelOptions()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.toastMessage!!.contains("Failed to load models"))
        assertNull(vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isLoadingBuilderData)
    }

    @Test
    fun `togglePinModel adds then removes pinned model`() {
        val vm = createViewModel()

        vm.togglePinModel("openai", "gpt-4")
        assertEquals(listOf(PinnedModel("openai", "gpt-4")), vm.uiState.value.modelPickerPinned)
        assertEquals(listOf(PinnedModel("openai", "gpt-4")), storedPinnedModels)

        vm.togglePinModel("openai", "gpt-4")
        assertTrue(vm.uiState.value.modelPickerPinned.isEmpty())
        assertTrue(storedPinnedModels.isEmpty())
    }
}
