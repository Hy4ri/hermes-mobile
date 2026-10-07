package com.m57.hermescontrol.ui.authlogin

import android.app.Application
import com.m57.hermescontrol.data.config.ConnectionProfile
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionLoginSwitchTest {
    private val dispatcher = StandardTestDispatcher()
    private val app = mockk<Application>(relaxed = true)
    private var selectedConnectionId = "connection-a"
    private var selectedConnectionUrl = "https://gateway-a.example/"
    private var savedProfiles = emptyList<ConnectionProfile>()
    private lateinit var viewModel: AuthLoginViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(AuthManager)
        mockkObject(ProfileSwitchCoordinator)
        every { AuthManager.getBaseUrl() } answers { selectedConnectionUrl }
        every { AuthManager.getSelectedProfileId() } answers { selectedConnectionId }
        every { AuthManager.getConnectionProfiles() } answers { savedProfiles }
        every { AuthManager.getProfileToken(any()) } returns null
        coEvery { ProfileSwitchCoordinator.switchConnectionProfile(any()) } returns Unit
        viewModel = AuthLoginViewModel(app)
    }

    @After
    fun tearDown() {
        viewModel.clearConnectionState()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `useExistingProfile rehomes through coordinator asynchronously`() =
        runTest {
            viewModel.useExistingProfile("prof-2")

            assertTrue(!viewModel.uiState.value.connectionSuccess)
            coVerify(exactly = 0) { ProfileSwitchCoordinator.switchConnectionProfile(any()) }

            advanceUntilIdle()

            coVerify(exactly = 1) { ProfileSwitchCoordinator.switchConnectionProfile("prof-2") }
            assertTrue(viewModel.uiState.value.connectionSuccess)
        }

    @Test
    fun `refreshForSelectedConnection clears stale login form when connection changes`() {
        viewModel.onBaseUrlChange("https://typed.example/")
        viewModel.onTokenChange("old-token")
        viewModel.onUsernameChange("old-user")
        viewModel.onPasswordChange("old-password")

        selectedConnectionId = "connection-b"
        selectedConnectionUrl = "https://gateway-b.example/"
        val profileB = ConnectionProfile(id = "connection-b", name = "Gateway B", baseUrl = selectedConnectionUrl)
        savedProfiles = listOf(profileB)
        every { AuthManager.getProfileToken("connection-b") } returns "saved-token"

        viewModel.refreshForSelectedConnection()

        val state = viewModel.uiState.value
        assertEquals(selectedConnectionUrl, state.baseUrl)
        assertEquals("", state.token)
        assertEquals("", state.username)
        assertEquals("", state.password)
        assertEquals(null, state.authMode)
        assertEquals(listOf(profileB), state.loggedInProfiles)
    }

    @Test
    fun `refreshForSelectedConnection preserves typed login form for same connection`() {
        // The screen refreshes on entry, before the user starts editing.
        viewModel.refreshForSelectedConnection()
        viewModel.onBaseUrlChange("https://typed.example/")
        viewModel.onUsernameChange("typed-user")
        viewModel.onPasswordChange("typed-password")

        viewModel.refreshForSelectedConnection()

        val state = viewModel.uiState.value
        assertEquals("https://typed.example/", state.baseUrl)
        assertEquals("typed-user", state.username)
        assertEquals("typed-password", state.password)
    }
}
