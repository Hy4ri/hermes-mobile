package com.m57.hermescontrol.ui.channels

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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
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
        coEvery { mockApi.getMessagingPlatforms() } returns Response.success(platformsResponse())
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
                    ),
                ),
        )
}
