package com.m57.hermescontrol.ui.channels

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.EnvVarField
import com.m57.hermescontrol.data.model.MessagingCatalogInstallRequest
import com.m57.hermescontrol.data.model.MessagingCatalogInstallResponse
import com.m57.hermescontrol.data.model.MessagingCatalogPlatform
import com.m57.hermescontrol.data.model.MessagingLiveApply
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
        every { ApiClient.messagingCatalogApi() } returns mockApi
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

    @Test
    fun `configurePlatform with env map overload sets exact restart toast and retains platform`() {
        val platform =
            MessagingPlatform(
                id = "telegram",
                name = "Telegram",
                enabled = false,
                configured = false,
                envVars = emptyList(),
            )
        coEvery { mockApi.getMessagingPlatforms() } returns
            Response.success(
                MessagingPlatformResponse(
                    envPath = "/tmp/.env",
                    gatewayStartCommand = "hermes gateway start",
                    platforms = listOf(platform),
                ),
            )
        coEvery { mockApi.configurePlatform("telegram", any()) } returns
            Response.success(MessagingPlatformUpdateResponse(ok = true))

        val viewModel = ChannelsViewModel()
        viewModel.loadPlatforms()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.platforms.size)

        viewModel.configurePlatform("telegram", mapOf("bot_token" to "xyz"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "telegram configured successfully — restart the gateway for changes to take effect",
            viewModel.uiState.value.toastMessage,
        )
        assertEquals(setOf("telegram"), viewModel.uiState.value.restartPendingPlatforms)
        coVerify {
            mockApi.configurePlatform(
                "telegram",
                MessagingPlatformUpdate(env = mapOf("bot_token" to "xyz")),
            )
        }
    }

    @Test
    fun `live applied true clears that platform from restart pending and shows connecting`() {
        coEvery { mockApi.configurePlatform("telegram", any()) } returns
            Response.success(
                MessagingPlatformUpdateResponse(
                    ok = true,
                    live = MessagingLiveApply(applied = true, state = "connecting"),
                    hotServed = false,
                ),
            )
        val viewModel = ChannelsViewModel()
        viewModel.loadPlatforms()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.togglePlatform("telegram", enabled = true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(
            viewModel.uiState.value.restartPendingPlatforms
                .isEmpty(),
        )
        assertFalse(viewModel.uiState.value.restartNeeded)
    }

    @Test
    fun `not configured live apply does not ask for a restart`() {
        coEvery { mockApi.configurePlatform("telegram", any()) } returns
            Response.success(
                MessagingPlatformUpdateResponse(
                    ok = true,
                    live = MessagingLiveApply(applied = true, state = "not_configured", detail = "needs TOKEN"),
                ),
            )
        val viewModel = ChannelsViewModel()
        viewModel.loadPlatforms()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.togglePlatform("telegram", enabled = true)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(
            viewModel.uiState.value.restartPendingPlatforms
                .isEmpty(),
        )
        assertFalse(
            viewModel.uiState.value.toastMessage
                .orEmpty()
                .contains("restart"),
        )
    }

    @Test
    fun `live applied false keeps restart pending and includes detail`() {
        coEvery { mockApi.configurePlatform("telegram", any()) } returns
            Response.success(
                MessagingPlatformUpdateResponse(
                    ok = true,
                    hotServed = true,
                    live =
                        MessagingLiveApply(
                            applied = false,
                            state = "pending_restart",
                            detail = "adapter build failed",
                        ),
                ),
            )
        val viewModel = ChannelsViewModel()
        viewModel.loadPlatforms()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.configurePlatform("telegram", MessagingPlatformUpdate(enabled = true))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(setOf("telegram"), viewModel.uiState.value.restartPendingPlatforms)
        assertTrue(viewModel.uiState.value.restartNeeded)
        assertTrue(
            viewModel.uiState.value.toastMessage
                .orEmpty()
                .contains("adapter build failed"),
        )
    }

    @Test
    fun `a later live save for one platform does not clear another pending restart`() {
        coEvery { mockApi.configurePlatform("telegram", any()) } returns
            Response.success(MessagingPlatformUpdateResponse(ok = true, hotServed = false))
        coEvery { mockApi.configurePlatform("discord", any()) } returns
            Response.success(
                MessagingPlatformUpdateResponse(
                    ok = true,
                    live = MessagingLiveApply(applied = true, state = "connecting"),
                ),
            )
        val viewModel = ChannelsViewModel()
        viewModel.configurePlatform("telegram", MessagingPlatformUpdate(enabled = true))
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.configurePlatform("discord", MessagingPlatformUpdate(enabled = true))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(setOf("telegram"), viewModel.uiState.value.restartPendingPlatforms)
    }

    @Test
    fun `load stores available catalog platforms`() {
        coEvery { mockApi.getMessagingPlatforms() } returns
            Response.success(
                platformsResponse().copy(
                    available =
                        listOf(
                            MessagingCatalogPlatform(name = "discord", title = "Discord", badge = "official"),
                        ),
                ),
            )
        val viewModel = ChannelsViewModel()
        viewModel.loadPlatforms()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "discord",
            viewModel.uiState.value.available
                .single()
                .name,
        )
    }

    @Test
    fun `catalog install success refreshes platforms and highlights the new id`() {
        coEvery { mockApi.installMessagingCatalogPlatform("discord", any()) } returns
            Response.success(
                MessagingCatalogInstallResponse(
                    ok = true,
                    pluginName = "discord",
                    platforms = listOf("discord"),
                    warnings = listOf("restart recommended"),
                ),
            )
        val viewModel = ChannelsViewModel()
        viewModel.installCatalogPlatform("discord")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("discord", viewModel.uiState.value.highlightedPlatformId)
        assertEquals(null, viewModel.uiState.value.installingName)
        assertTrue(
            viewModel.uiState.value.toastMessage
                .orEmpty()
                .contains("restart recommended"),
        )
        coVerify {
            mockApi.installMessagingCatalogPlatform("discord", any<MessagingCatalogInstallRequest>())
        }
        coVerify(atLeast = 1) { mockApi.getMessagingPlatforms() }
    }

    @Test
    fun `catalog install refusal surfaces error and command`() {
        coEvery { mockApi.installMessagingCatalogPlatform("discord", any()) } returns
            Response.success(
                MessagingCatalogInstallResponse(
                    ok = false,
                    error = "lazy installs are off",
                    installCommand = "hermes plugins install discord",
                ),
            )
        val viewModel = ChannelsViewModel()
        viewModel.installCatalogPlatform("discord")
        testDispatcher.scheduler.advanceUntilIdle()

        val toast =
            viewModel.uiState.value.toastMessage
                .orEmpty()
        assertTrue(toast.contains("lazy installs are off"))
        assertTrue(toast.contains("hermes plugins install discord"))
        assertEquals(null, viewModel.uiState.value.installingName)
        coVerify(exactly = 0) { mockApi.getMessagingPlatforms() }
    }
}
