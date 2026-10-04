package com.m57.hermescontrol.ui.channels

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.EnvVarField
import com.m57.hermescontrol.data.model.MessagingPlatform
import com.m57.hermescontrol.data.model.MessagingPlatformResponse
import com.m57.hermescontrol.data.model.MessagingPlatformUpdate
import com.m57.hermescontrol.data.model.MessagingPlatformUpdateResponse
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApi: HermesApiService

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ApiClient)
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi
        mockkObject(AuthManager)
        every { AuthManager.currentDataScope() } returns null
        every { AuthManager.activeProfileId } returns MutableStateFlow("work")
        coEvery { mockApi.getMessagingPlatforms() } returns Response.success(platformsResponse())
        coEvery { mockApi.getMessagingPlatforms("work") } returns Response.success(platformsResponse())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `hot served update clears restart requirement and refreshes platforms`() {
        coEvery { mockApi.configurePlatform("telegram", any()) } returns
            Response.success(
                MessagingPlatformUpdateResponse(
                    ok = true,
                    platform = "telegram",
                    hotServed = true,
                ),
            )

        val viewModel = ChannelsViewModel()
        viewModel.loadPlatforms()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.configurePlatform("telegram", MessagingPlatformUpdate(env = mapOf("token" to "redacted")))
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.restartNeeded)
        assertTrue(
            viewModel.uiState.value.toastMessage
                ?.contains("reloaded live") == true,
        )
        coVerify { mockApi.configurePlatform("telegram", any()) }
        coVerify(atLeast = 2) { mockApi.getMessagingPlatforms() }
    }

    @Test
    fun `update without hot served preserves restart required behavior`() {
        coEvery { mockApi.configurePlatform("telegram", any()) } returns
            Response.success(MessagingPlatformUpdateResponse(ok = true, platform = "telegram"))

        val viewModel = ChannelsViewModel()
        viewModel.loadPlatforms()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.configurePlatform("telegram", MessagingPlatformUpdate(enabled = true))
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.restartNeeded)
        assertTrue(
            viewModel.uiState.value.toastMessage
                ?.contains("restart the gateway") == true,
        )
    }

    private fun platformsResponse(): MessagingPlatformResponse =
        MessagingPlatformResponse(
            envPath = "/tmp/.env",
            gatewayStartCommand = "hermes gateway start",
            platforms =
                listOf(
                    MessagingPlatform(
                        id = "telegram",
                        name = "Telegram",
                        enabled = true,
                        configured = true,
                        envVars =
                            listOf(
                                EnvVarField("TELEGRAM_BOT_TOKEN", true, true, isPassword = true),
                                EnvVarField("TELEGRAM_ALLOWED_USERS", false, true, isPassword = false),
                            ),
                    ),
                ),
        )

    @Test
    fun `disconnect clears catalog keys in selected profile and retains platform`() {
        coEvery { mockApi.configurePlatform("telegram", any()) } returns
            Response.success(MessagingPlatformUpdateResponse(ok = true, hotServed = true))
        val viewModel = ChannelsViewModel()

        viewModel.removePlatform("telegram")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            mockApi.configurePlatform(
                "telegram",
                MessagingPlatformUpdate(
                    enabled = false,
                    clearEnv = listOf("TELEGRAM_BOT_TOKEN", "TELEGRAM_ALLOWED_USERS"),
                    profile = "work",
                ),
            )
        }
        assertEquals(
            "telegram",
            viewModel.uiState.value.platforms
                .single()
                .id,
        )
        assertFalse(viewModel.uiState.value.restartNeeded)
        assertEquals(null, viewModel.uiState.value.removingId)
    }

    @Test
    fun `disconnect stops when profile changes during catalog read`() {
        val profile = MutableStateFlow<String?>("work")
        every { AuthManager.activeProfileId } returns profile
        coEvery { mockApi.getMessagingPlatforms("work") } answers {
            profile.value = "other"
            Response.success(platformsResponse())
        }
        val viewModel = ChannelsViewModel()

        viewModel.removePlatform("telegram")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { mockApi.configurePlatform(any(), any()) }
        assertEquals(null, viewModel.uiState.value.removingId)
        assertTrue(
            viewModel.uiState.value.toastMessage
                .orEmpty()
                .contains("profile changed"),
        )
    }

    @Test
    fun `disconnect read failure does not write and exposes failure`() {
        coEvery { mockApi.getMessagingPlatforms("work") } returns
            Response.error(403, "Forbidden".toResponseBody())
        val viewModel = ChannelsViewModel()

        viewModel.removePlatform("telegram")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { mockApi.configurePlatform(any(), any()) }
        assertEquals(null, viewModel.uiState.value.removingId)
        assertTrue(
            viewModel.uiState.value.toastMessage
                .orEmpty()
                .contains("Disconnect failed"),
        )
    }

    @Test
    fun `disconnect requires restart when gateway has not hot served changes`() {
        coEvery { mockApi.configurePlatform("telegram", any()) } returns
            Response.success(MessagingPlatformUpdateResponse(ok = true))
        val viewModel = ChannelsViewModel()

        viewModel.removePlatform("telegram")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.restartNeeded)
    }

    @Test
    fun `disconnect refuses missing credential metadata`() {
        coEvery { mockApi.getMessagingPlatforms("work") } returns
            Response.success(
                platformsResponse().copy(
                    platforms = listOf(platformsResponse().platforms.single().copy(envVars = null)),
                ),
            )
        val viewModel = ChannelsViewModel()

        viewModel.removePlatform("telegram")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { mockApi.configurePlatform(any(), any()) }
        assertTrue(
            viewModel.uiState.value.toastMessage
                .orEmpty()
                .contains("metadata is unavailable"),
        )
    }

    @Test
    fun `disconnect write failure keeps platform and clears progress`() {
        coEvery { mockApi.configurePlatform("telegram", any()) } returns
            Response.error(409, "Conflict".toResponseBody())
        val viewModel = ChannelsViewModel()
        viewModel.loadPlatforms()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.removePlatform("telegram")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "telegram",
            viewModel.uiState.value.platforms
                .single()
                .id,
        )
        assertEquals(null, viewModel.uiState.value.removingId)
        assertTrue(
            viewModel.uiState.value.toastMessage
                .orEmpty()
                .contains("Disconnect failed"),
        )
        assertFalse(viewModel.uiState.value.restartNeeded)
    }
}
