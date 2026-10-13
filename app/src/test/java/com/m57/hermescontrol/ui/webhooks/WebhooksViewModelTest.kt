package com.m57.hermescontrol.ui.webhooks

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.model.MessagingPlatformUpdate
import com.m57.hermescontrol.data.model.MessagingPlatformUpdateResponse
import com.m57.hermescontrol.data.model.WebhooksResponse
import com.m57.hermescontrol.data.model.WebhooksToggleRequest
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
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Blind tests for R1550 webhook toggle in WebhooksViewModel:
 * - Uses configurePlatform("webhook", MessagingPlatformUpdate(enabled = targetEnabled, profile = capturedProfile))
 *   for BOTH true and false (no enable-only endpoint).
 * - Honest guarded UI outcomes:
 *   - Response ok == true shows success toast and reloads webhooks.
 *   - Response ok == false surfaces failure, restores original state, and avoids success toast.
 *   - HTTP / Network failure rolls back enabled state and shows failure toast.
 *   - Double-tap ignored / serialized (isActionRunning guard blocks overlapping calls).
 *   - Captured profile and scope replacement fences late async results.
 *   - Action running flag cleared on completion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebhooksViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockApi = mockk<HermesApiService>(relaxed = true)

    private val defaultDataScope =
        DataScope(
            connectionProfileId = "test-conn",
            baseUrl = "http://localhost:8080",
            activeProfileId = "work",
            inMemoryAuthGeneration = 1L,
        )

    private val activeProfileFlow = MutableStateFlow("work")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        mockkObject(ApiClient)
        every { ApiClient.hermesApi } returns mockApi

        mockkObject(AuthManager)
        every { AuthManager.currentDataScope() } returns defaultDataScope
        every { AuthManager.activeProfileId } returns activeProfileFlow

        // Authoritative backend state: the post-toggle reload must see what was actually configured.
        coEvery { mockApi.getWebhooks() } answers {
            Response.success(
                WebhooksResponse(
                    enabled = serverEnabled,
                    base_url = "http://localhost:8080",
                    subscriptions = emptyList(),
                ),
            )
        }
    }

    private var serverEnabled = false

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(): WebhooksViewModel = WebhooksViewModel(ioDispatcher = testDispatcher)

    @Test
    fun `toggleWebhooks true calls configurePlatform webhook with enabled true and captured profile`() {
        val configSlot = slot<MessagingPlatformUpdate>()
        coEvery {
            mockApi.configurePlatform(
                platformId = "webhook",
                config = capture(configSlot),
            )
        } answers {
            serverEnabled = configSlot.captured.enabled == true
            Response.success(
                MessagingPlatformUpdateResponse(
                    ok = true,
                    platform = "webhook",
                ),
            )
        }

        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.toggleWebhooks(targetEnabled = true)

        // Optimistically set while action is running
        assertTrue(vm.uiState.value.isActionRunning)
        assertTrue(vm.uiState.value.enabled)

        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.isActionRunning)
        assertTrue(vm.uiState.value.enabled)
        assertEquals("Webhooks global status enabled", vm.uiState.value.toastMessage)

        coVerify(exactly = 1) {
            mockApi.configurePlatform(
                platformId = "webhook",
                config = any(),
            )
        }
        assertEquals(true, configSlot.captured.enabled)
        assertEquals("work", configSlot.captured.profile)

        // Ensure enable-only endpoint is never called
        coVerify(exactly = 0) { mockApi.toggleWebhooks(any<WebhooksToggleRequest>()) }
        // Verify webhooks reloaded upon success
        coVerify(atLeast = 1) { mockApi.getWebhooks() }
    }

    @Test
    fun `toggleWebhooks false calls configurePlatform webhook with enabled false and captured profile`() {
        serverEnabled = true

        val configSlot = slot<MessagingPlatformUpdate>()
        coEvery {
            mockApi.configurePlatform(
                platformId = "webhook",
                config = capture(configSlot),
            )
        } answers {
            serverEnabled = configSlot.captured.enabled == true
            Response.success(
                MessagingPlatformUpdateResponse(
                    ok = true,
                    platform = "webhook",
                ),
            )
        }

        val vm = createViewModel()
        vm.loadWebhooks()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.enabled)

        vm.toggleWebhooks(targetEnabled = false)

        assertTrue(vm.uiState.value.isActionRunning)
        assertFalse(vm.uiState.value.enabled)

        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.isActionRunning)
        assertFalse(vm.uiState.value.enabled)
        assertEquals("Webhooks global status disabled", vm.uiState.value.toastMessage)

        coVerify(exactly = 1) {
            mockApi.configurePlatform(
                platformId = "webhook",
                config = any(),
            )
        }
        assertEquals(false, configSlot.captured.enabled)
        assertEquals("work", configSlot.captured.profile)

        // Ensure enable-only endpoint is never called
        coVerify(exactly = 0) { mockApi.toggleWebhooks(any<WebhooksToggleRequest>()) }
    }

    @Test
    fun `toggleWebhooks when response ok is false rolls back state and shows failure toast`() {
        coEvery { mockApi.getWebhooks() } returns
            Response.success(
                WebhooksResponse(
                    enabled = false,
                    base_url = "http://localhost:8080",
                    subscriptions = emptyList(),
                ),
            )

        coEvery {
            mockApi.configurePlatform(
                platformId = "webhook",
                config = any(),
            )
        } returns
            Response.success(
                MessagingPlatformUpdateResponse(
                    ok = false,
                    platform = "webhook",
                ),
            )

        val vm = createViewModel()
        vm.loadWebhooks()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.enabled)

        vm.toggleWebhooks(targetEnabled = true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.isActionRunning)
        // Rolled back to original state
        assertFalse(vm.uiState.value.enabled)
        assertEquals("Failed to toggle webhooks: Gateway refused update", vm.uiState.value.toastMessage)
    }

    @Test
    fun `toggleWebhooks when api returns http error rolls back state and shows failure toast`() {
        coEvery { mockApi.getWebhooks() } returns
            Response.success(
                WebhooksResponse(
                    enabled = true,
                    base_url = "http://localhost:8080",
                    subscriptions = emptyList(),
                ),
            )

        coEvery {
            mockApi.configurePlatform(
                platformId = "webhook",
                config = any(),
            )
        } returns Response.error(500, "{\"detail\":\"Internal server error\"}".toResponseBody(null))

        val vm = createViewModel()
        vm.loadWebhooks()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.enabled)

        vm.toggleWebhooks(targetEnabled = false)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.isActionRunning)
        // Rolled back to original state
        assertTrue(vm.uiState.value.enabled)
        assertTrue(
            vm.uiState.value.toastMessage
                ?.startsWith("Failed to toggle webhooks:") == true,
        )
    }

    @Test
    fun `toggleWebhooks ignores double-tap while action is running`() {
        coEvery {
            mockApi.configurePlatform(
                platformId = "webhook",
                config = any(),
            )
        } returns
            Response.success(
                MessagingPlatformUpdateResponse(
                    ok = true,
                    platform = "webhook",
                ),
            )

        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        // First tap initiates action
        vm.toggleWebhooks(targetEnabled = true)
        assertTrue(vm.uiState.value.isActionRunning)

        // Second tap while action running should be ignored
        vm.toggleWebhooks(targetEnabled = true)
        vm.toggleWebhooks(targetEnabled = false)

        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            mockApi.configurePlatform(
                platformId = "webhook",
                config = any(),
            )
        }
    }

    @Test
    fun `toggleWebhooks discards result if data scope changes during request`() {
        coEvery {
            mockApi.configurePlatform(
                platformId = "webhook",
                config = any(),
            )
        } answers {
            // Simulate scope change mid-request
            val switchedScope =
                DataScope(
                    connectionProfileId = "switched-conn",
                    baseUrl = "http://otherhost:8080",
                    activeProfileId = "personal",
                    inMemoryAuthGeneration = 2L,
                )
            every { AuthManager.currentDataScope() } returns switchedScope
            every { AuthManager.activeProfileId } returns MutableStateFlow("personal")

            Response.success(
                MessagingPlatformUpdateResponse(
                    ok = true,
                    platform = "webhook",
                ),
            )
        }

        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.toggleWebhooks(targetEnabled = true)
        testDispatcher.scheduler.advanceUntilIdle()

        // Result publication is fenced: success toast must NOT be published into switched scope
        assertNull(vm.uiState.value.toastMessage)
    }

    @Test
    fun `toggleWebhooks discards result if active profile changes during request`() {
        coEvery {
            mockApi.configurePlatform(
                platformId = "webhook",
                config = any(),
            )
        } answers {
            // Scope activeProfileId changes
            every { AuthManager.activeProfileId } returns MutableStateFlow("personal")

            Response.success(
                MessagingPlatformUpdateResponse(
                    ok = true,
                    platform = "webhook",
                ),
            )
        }

        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.toggleWebhooks(targetEnabled = true)
        testDispatcher.scheduler.advanceUntilIdle()

        // Publication fenced due to profile mismatch
        assertNull(vm.uiState.value.toastMessage)
    }
}
